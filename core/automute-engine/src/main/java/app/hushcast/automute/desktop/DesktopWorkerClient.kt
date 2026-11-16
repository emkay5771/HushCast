package app.hushcast.automute.desktop

import app.hushcast.automute.dsp.MuteActionMode
import app.hushcast.automute.dsp.ReadinessBadge
import app.hushcast.automute.matcher.WordMatcher
import app.hushcast.automute.storage.AdSegmentEntity
import app.hushcast.automute.storage.AutoMuteDatabase
import app.hushcast.automute.storage.BlobCodec
import app.hushcast.automute.storage.EpisodeAnalysisStatusEntity
import app.hushcast.automute.storage.EpisodeInsightsEntity
import app.hushcast.automute.storage.EpisodeWordIndexEntity
import app.hushcast.automute.storage.MuteSegmentEntity
import app.hushcast.automute.storage.toDomain
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * LAN client for the HushCast Desktop Companion Worker.
 * Downloads pre-analyzed audio + WordIndexBlob + AdSegments keyed by fileSha256.
 */
object DesktopWorkerClient {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    suspend fun healthCheck(baseUrl: String, token: String = ""): DesktopHealthResponse =
        withContext(Dispatchers.IO) {
            val body = getText("${normalizeUrl(baseUrl)}/v1/health", token, timeoutMs = 4000)
            json.decodeFromString<DesktopHealthResponse>(body)
        }

    suspend fun submitJob(
        baseUrl: String,
        token: String,
        request: DesktopJobRequest
    ): DesktopJobResponse = withContext(Dispatchers.IO) {
        val payload = json.encodeToString(request)
        val body = postJson("${normalizeUrl(baseUrl)}/v1/jobs", token, payload)
        json.decodeFromString<DesktopJobResponse>(body)
    }

    suspend fun getJob(baseUrl: String, token: String, jobId: String): DesktopJobResponse =
        withContext(Dispatchers.IO) {
            val body = getText("${normalizeUrl(baseUrl)}/v1/jobs/$jobId", token)
            json.decodeFromString<DesktopJobResponse>(body)
        }

    suspend fun pollJobUntilReady(
        baseUrl: String,
        token: String,
        jobId: String,
        pollIntervalMs: Long = 1000L,
        maxWaitMs: Long = 30 * 60 * 1000L,
        onProgress: (DesktopJobResponse) -> Unit = {}
    ): DesktopJobResponse {
        val deadline = System.currentTimeMillis() + maxWaitMs
        while (true) {
            val status = getJob(baseUrl, token, jobId)
            onProgress(status)
            when (status.state) {
                "READY" -> return status
                "FAILED" -> throw IllegalStateException(
                    "Desktop worker failed: ${status.errorMessage ?: status.stageLabel}"
                )
            }
            if (System.currentTimeMillis() >= deadline) {
                throw IllegalStateException("Timed out waiting for desktop worker job $jobId")
            }
            delay(pollIntervalMs)
        }
    }

    suspend fun fetchBundle(
        baseUrl: String,
        token: String,
        sha256: String
    ): DesktopAnalysisBundle = withContext(Dispatchers.IO) {
        val body = getText("${normalizeUrl(baseUrl)}/v1/episodes/$sha256/bundle", token)
        json.decodeFromString<DesktopAnalysisBundle>(body)
    }

    suspend fun listReadyEpisodes(
        baseUrl: String,
        token: String
    ): List<DesktopReadyEpisode> = withContext(Dispatchers.IO) {
        val body = getText("${normalizeUrl(baseUrl)}/v1/episodes", token)
        json.decodeFromString<List<DesktopReadyEpisode>>(body)
    }

    suspend fun semanticSearch(
        baseUrl: String,
        token: String,
        query: String,
        topK: Int = 15
    ): DesktopSearchResponse = withContext(Dispatchers.IO) {
        val payload = json.encodeToString(DesktopSearchRequest(query = query, topK = topK))
        val body = postJson("${normalizeUrl(baseUrl)}/v1/search", token, payload)
        json.decodeFromString<DesktopSearchResponse>(body)
    }

    /**
     * Streams the exact audio file from the desktop worker to [destFile] and verifies
     * that its SHA-256 matches [expectedSha256].
     */
    suspend fun downloadAudioAndVerifySha256(
        baseUrl: String,
        token: String,
        expectedSha256: String,
        destFile: File,
        onProgress: (receivedBytes: Long, totalBytes: Long) -> Unit = { _, _ -> }
    ): Unit = withContext(Dispatchers.IO) {
        destFile.parentFile?.mkdirs()
        val tmpFile = File(destFile.parentFile, "${destFile.name}.part")
        val conn = openConnection("${normalizeUrl(baseUrl)}/v1/episodes/$expectedSha256/audio", token, 30_000)
        try {
            val code = conn.responseCode
            if (code !in 200..299) {
                throw IllegalStateException("Desktop audio download failed: HTTP $code")
            }
            val total = conn.contentLengthLong.coerceAtLeast(0L)
            val digest = MessageDigest.getInstance("SHA-256")
            var received = 0L
            conn.inputStream.use { input ->
                tmpFile.outputStream().use { output ->
                    val buf = ByteArray(65536)
                    while (true) {
                        val read = input.read(buf)
                        if (read < 0) break
                        output.write(buf, 0, read)
                        digest.update(buf, 0, read)
                        received += read
                        onProgress(received, total)
                    }
                }
            }
            val actualSha = digest.digest().joinToString("") { "%02x".format(it) }
            if (!actualSha.equals(expectedSha256, ignoreCase = true)) {
                tmpFile.delete()
                throw IllegalStateException(
                    "SHA-256 mismatch from desktop worker: expected=$expectedSha256 actual=$actualSha"
                )
            }
            if (destFile.exists()) destFile.delete()
            if (!tmpFile.renameTo(destFile)) {
                tmpFile.copyTo(destFile, overwrite = true)
                tmpFile.delete()
            }
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Persists the desktop-computed WordIndexBlob + AdSegments into Room, and runs
     * WordMatcher locally against the user's mute_rules so MuteSegments are ready immediately.
     */
    suspend fun ingestBundleIntoDb(
        db: AutoMuteDatabase,
        episodeId: String,
        bundle: DesktopAnalysisBundle,
        prePadMs: Long = 80L,
        postPadMs: Long = 100L,
        defaultActionMode: MuteActionMode = MuteActionMode.HARD_SILENCE
    ) {
        val dao = db.dao()
        val sha = bundle.fileSha256

        dao.upsertWordIndex(
            EpisodeWordIndexEntity(
                fileSha256 = sha,
                episodeId = episodeId,
                durationSec = bundle.durationSec,
                wordCount = bundle.wordIndexBlob.words.size,
                compressedBlob = BlobCodec.compress(bundle.wordIndexBlob)
            )
        )

        val rules = dao.getAllRules().map { it.toDomain() }
        val windows = WordMatcher.matchWords(
            words = bundle.wordIndexBlob.words,
            rules = rules,
            prePadMs = prePadMs,
            postPadMs = postPadMs,
            defaultActionMode = defaultActionMode
        )
        dao.deleteMuteSegments(sha)
        if (windows.isNotEmpty()) {
            dao.insertMuteSegments(
                windows.map { w ->
                    MuteSegmentEntity(
                        fileSha256 = sha,
                        episodeId = episodeId,
                        startMs = w.startMs,
                        endMs = w.endMs,
                        word = w.word,
                        ruleId = w.ruleId,
                        actionMode = w.actionMode.name,
                        enabled = w.enabled
                    )
                }
            )
        }

        dao.deleteAdSegments(sha)
        if (bundle.adSegments.isNotEmpty()) {
            dao.insertAdSegments(
                bundle.adSegments.map { ad ->
                    AdSegmentEntity(
                        fileSha256 = sha,
                        episodeId = episodeId,
                        startMs = ad.startMs,
                        endMs = ad.endMs,
                        confidence = ad.confidence,
                        isHighConfidence = ad.isHighConfidence,
                        label = ad.label
                    )
                }
            )
        }

        if (!bundle.summary.isNullOrBlank() || bundle.chapters.isNotEmpty()) {
            dao.upsertInsights(
                EpisodeInsightsEntity(
                    episodeId = episodeId,
                    fileSha256 = sha,
                    summary = bundle.summary,
                    chaptersJson = json.encodeToString(bundle.chapters)
                )
            )
        }

        dao.upsertStatus(
            EpisodeAnalysisStatusEntity(
                episodeId = episodeId,
                fileSha256 = sha,
                badge = ReadinessBadge.READY.name,
                progressPercent = 100,
                stageLabel = "Ready (Desktop ${bundle.workerAccelerator.uppercase()})",
                peakRssMb = 0,
                analysisDurationMs = bundle.analysisDurationMs
            )
        )
    }

    private fun normalizeUrl(baseUrl: String): String = baseUrl.trim().trimEnd('/')

    private fun openConnection(url: String, token: String, timeoutMs: Int): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = timeoutMs
        conn.readTimeout = timeoutMs
        if (token.isNotBlank()) {
            conn.setRequestProperty("Authorization", "Bearer $token")
        }
        return conn
    }

    private fun getText(url: String, token: String, timeoutMs: Int = 15_000): String {
        val conn = openConnection(url, token, timeoutMs)
        try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                throw IllegalStateException("HTTP $code from $url: $text")
            }
            return text
        } finally {
            conn.disconnect()
        }
    }

    private fun postJson(url: String, token: String, jsonBody: String, timeoutMs: Int = 15_000): String {
        val conn = openConnection(url, token, timeoutMs)
        try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.outputStream.use { it.write(jsonBody.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                throw IllegalStateException("HTTP $code from $url: $text")
            }
            return text
        } finally {
            conn.disconnect()
        }
    }
}
