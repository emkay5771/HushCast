package app.hushcast.automute.desktop

import app.hushcast.automute.dsp.MuteActionMode
import app.hushcast.automute.dsp.ReadinessBadge
import app.hushcast.automute.matcher.WordMatcher
import app.hushcast.automute.storage.AdSegmentEntity
import app.hushcast.automute.storage.effectiveRules
import app.hushcast.automute.storage.AutoMuteDatabase
import app.hushcast.automute.storage.BlobCodec
import app.hushcast.automute.storage.EpisodeAnalysisStatusEntity
import app.hushcast.automute.storage.EpisodeInsightsEntity
import app.hushcast.automute.storage.EpisodeWordIndexEntity
import app.hushcast.automute.storage.MuteSegmentEntity
import app.hushcast.automute.storage.toDomain
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.util.concurrent.CancellationException
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * Client for the HushCast Desktop / Modal Companion Worker.
 * Downloads pre-analyzed audio + WordIndexBlob + AdSegments keyed by fileSha256.
 */
object DesktopWorkerClient {
    const val MIN_WORKER_API_VERSION = 2

    internal val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        coerceInputValues = true
        explicitNulls = false
    }

    private fun defaultTimeoutMs(normalizedUrl: String): Int =
        if (normalizedUrl.startsWith("https://", ignoreCase = true)) 60_000 else 15_000

    suspend fun healthCheck(
        baseUrl: String,
        token: String = "",
        certFingerprint: String = "",
        timeoutMs: Int? = null
    ): DesktopHealthResponse = withContext(Dispatchers.IO) {
        val normalized = normalizeUrl(baseUrl)
        val timeout = timeoutMs ?: if (normalized.startsWith("https://", ignoreCase = true)) 60_000 else 4_000
        val body = getText("$normalized/v1/health", token, certFingerprint, timeoutMs = timeout)
        json.decodeFromString<DesktopHealthResponse>(body)
    }

    private class CachedHealth(val health: DesktopHealthResponse?, val atMs: Long)

    private val healthCache = java.util.concurrent.ConcurrentHashMap<String, CachedHealth>()

    const val HEALTH_CACHE_MS = 10 * 60 * 1000L
    private const val HEALTH_FAILURE_CACHE_MS = 60 * 1000L

    /**
     * The worker's /v1/health, cached for [maxAgeMs] (a failed read for a minute).  Null when the
     * worker didn't answer; never throws.
     */
    suspend fun cachedHealth(
        baseUrl: String,
        token: String = "",
        certFingerprint: String = "",
        maxAgeMs: Long = HEALTH_CACHE_MS,
        timeoutMs: Int = 15_000
    ): DesktopHealthResponse? {
        val key = normalizeUrl(baseUrl)
        val now = System.currentTimeMillis()
        healthCache[key]?.let { cached ->
            val ttl = if (cached.health != null) maxAgeMs else minOf(maxAgeMs, HEALTH_FAILURE_CACHE_MS)
            if (now - cached.atMs in 0 until ttl) return cached.health
        }
        val fresh = try {
            healthCheck(baseUrl, token, certFingerprint, timeoutMs = timeoutMs)
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            null
        }
        healthCache[key] = CachedHealth(fresh, System.currentTimeMillis())
        return fresh
    }

    /**
     * Tells the worker the phone has its own verified copy of the audio, so the worker can drop
     * its copy (V17).  204 and 404 both count as done.  Best-effort: never throws.
     */
    suspend fun deleteWorkerAudio(
        baseUrl: String,
        token: String,
        sha256: String,
        certFingerprint: String = ""
    ): Boolean = withContext(Dispatchers.IO) {
        if (sha256.isBlank()) return@withContext false
        try {
            val normalized = normalizeUrl(baseUrl)
            val conn = openConnection(
                "$normalized/v1/episodes/$sha256/audio",
                token,
                minOf(defaultTimeoutMs(normalized), 20_000),
                certFingerprint
            )
            try {
                conn.requestMethod = "DELETE"
                val code = conn.responseCode
                code in 200..299 || code == 404
            } finally {
                conn.disconnect()
            }
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            false
        }
    }

    private data class ActiveJobRef(
        val baseUrl: String,
        val token: String,
        val jobId: String,
        val certFingerprint: String
    )

    private val activeJobs = java.util.concurrent.ConcurrentHashMap<String, ActiveJobRef>()

    fun trackActiveJob(
        episodeId: String,
        baseUrl: String,
        token: String,
        jobId: String,
        certFingerprint: String = ""
    ) {
        activeJobs[episodeId] = ActiveJobRef(baseUrl, token, jobId, certFingerprint)
    }

    fun clearActiveJob(episodeId: String, jobId: String? = null) {
        if (jobId == null) {
            activeJobs.remove(episodeId)
        } else {
            activeJobs.remove(episodeId, activeJobs[episodeId]?.takeIf { it.jobId == jobId })
        }
    }

    suspend fun cancelActiveJobForEpisode(episodeId: String) {
        val ref = activeJobs.remove(episodeId) ?: return
        runCatching {
            cancelJob(ref.baseUrl, ref.token, ref.jobId, ref.certFingerprint)
        }
    }

    suspend fun submitJob(
        baseUrl: String,
        token: String,
        request: DesktopJobRequest,
        certFingerprint: String = ""
    ): DesktopJobResponse = withContext(Dispatchers.IO) {
        val normalized = normalizeUrl(baseUrl)
        val payload = json.encodeToString(request)
        val body = postJson("$normalized/v1/jobs", token, payload, certFingerprint, timeoutMs = defaultTimeoutMs(normalized))
        json.decodeFromString<DesktopJobResponse>(body)
    }

    suspend fun getJob(
        baseUrl: String,
        token: String,
        jobId: String,
        certFingerprint: String = "",
        waitMs: Long = 0L,
        sinceRev: Long = 0L
    ): DesktopJobResponse = withContext(Dispatchers.IO) {
        val normalized = normalizeUrl(baseUrl)
        val qs = if (waitMs > 0L) "?waitMs=$waitMs&sinceRev=$sinceRev" else ""
        val timeout = maxOf(defaultTimeoutMs(normalized), (waitMs + 10_000L).toInt())
        val body = getText("$normalized/v1/jobs/$jobId$qs", token, certFingerprint, timeoutMs = timeout)
        json.decodeFromString<DesktopJobResponse>(body)
    }

    suspend fun cancelJob(
        baseUrl: String,
        token: String,
        jobId: String,
        certFingerprint: String = ""
    ) = withContext(Dispatchers.IO) {
        val normalized = normalizeUrl(baseUrl)
        val conn = openConnection("$normalized/v1/jobs/$jobId", token, defaultTimeoutMs(normalized), certFingerprint)
        try {
            conn.requestMethod = "DELETE"
            conn.responseCode
        } finally {
            conn.disconnect()
        }
    }

    class DesktopWorkerJobException(
        message: String,
        val errorCode: String? = null,
        val retryable: Boolean = true,
        /** The worker's own words (errorMessage, else stageLabel), for the episode row (V10). */
        val reason: String? = null
    ) : IllegalStateException(message)

    /**
     * Polls job status with long-polling (?waitMs=&sinceRev=), HTTP 404 auto-resubmit (V2),
     * dynamic ETA*2 deadline (V9), and exponential backoff on transient network errors (E18).
     */
    suspend fun pollJobUntilReady(
        baseUrl: String,
        token: String,
        jobId: String,
        pollIntervalMs: Long = 2000L,
        maxWaitMs: Long = 30 * 60 * 1000L,
        certFingerprint: String = "",
        resubmitRequest: DesktopJobRequest? = null,
        onResubmitted: (String) -> Unit = {},
        onProgress: suspend (DesktopJobResponse) -> Unit = {}
    ): DesktopJobResponse {
        var deadline = System.currentTimeMillis() + maxWaitMs
        val maxQueuedWaitMs = 25 * 60 * 1000L
        var currentJobId = jobId
        var lastRev = 0L
        var resubmitted = false
        var queuedSinceMs: Long? = null
        var consecutiveErrors = 0
        var highestReportedPct = 0

        while (true) {
            currentCoroutineContext().ensureActive()
            val rawStatus = try {
                val res = getJob(
                    baseUrl = baseUrl,
                    token = token,
                    jobId = currentJobId,
                    certFingerprint = certFingerprint,
                    waitMs = 25_000L,
                    sinceRev = lastRev
                )
                consecutiveErrors = 0
                lastRev = res.rev
                res
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                // V2: If job disappeared (HTTP 404, e.g. container restart), resubmit once if available
                if (t.message?.contains("HTTP 404") == true && resubmitRequest != null && !resubmitted) {
                    resubmitted = true
                    val newJob = submitJob(baseUrl, token, resubmitRequest, certFingerprint)
                    currentJobId = newJob.jobId
                    lastRev = newJob.rev
                    trackActiveJob(resubmitRequest.episodeId, baseUrl, token, currentJobId, certFingerprint)
                    onResubmitted(currentJobId)
                    continue
                }
                consecutiveErrors++
                if (consecutiveErrors >= 6 || System.currentTimeMillis() >= deadline) {
                    throw t
                }
                val backoffMs = (pollIntervalMs * (1L shl (consecutiveErrors - 1))).coerceAtMost(15_000L)
                delay(backoffMs)
                continue
            }

            if (rawStatus.progressPercent > highestReportedPct) {
                highestReportedPct = rawStatus.progressPercent
                // Progressing job: extend deadline by at least 15 min
                deadline = maxOf(deadline, System.currentTimeMillis() + 15 * 60 * 1000L)
            }
            // V9: Refresh deadline from ETA * 2 when worker reports etaSec
            val eta = rawStatus.etaSec
            if (eta != null && eta > 0) {
                deadline = maxOf(deadline, System.currentTimeMillis() + maxOf(10 * 60 * 1000L, eta.toLong() * 2000L))
            }

            val status = if (rawStatus.progressPercent < highestReportedPct && rawStatus.state !in setOf("READY", "FAILED", "CANCELLED")) {
                rawStatus.copy(progressPercent = highestReportedPct)
            } else {
                rawStatus
            }

            onProgress(status)
            when (status.state) {
                "READY" -> return status
                "CANCELLED" -> throw CancellationException("Desktop worker job cancelled: $currentJobId")
                "FAILED" -> throw DesktopWorkerJobException(
                    message = "Desktop worker failed: ${status.errorMessage ?: status.stageLabel}",
                    errorCode = status.errorCode,
                    retryable = status.retryable ?: true,
                    reason = status.errorMessage?.takeIf { it.isNotBlank() } ?: status.stageLabel.takeIf { it.isNotBlank() }
                )
                "QUEUED" -> {
                    val now = System.currentTimeMillis()
                    if (status.progressPercent <= 0) {
                        val firstQueued = queuedSinceMs ?: now.also { queuedSinceMs = it }
                        if (now - firstQueued >= maxQueuedWaitMs) {
                            throw IllegalStateException("Timed out waiting in worker queue for job $currentJobId")
                        }
                    } else {
                        queuedSinceMs = null
                    }
                }
                else -> queuedSinceMs = null
            }
            if (System.currentTimeMillis() >= deadline) {
                throw IllegalStateException("Timed out waiting for desktop worker job $currentJobId")
            }
        }
    }


    suspend fun fetchBundle(
        baseUrl: String,
        token: String,
        sha256: String,
        certFingerprint: String = ""
    ): DesktopAnalysisBundle = withContext(Dispatchers.IO) {
        val normalized = normalizeUrl(baseUrl)
        val body = getText(
            "$normalized/v1/episodes/$sha256/bundle",
            token,
            certFingerprint,
            timeoutMs = maxOf(defaultTimeoutMs(normalized), 30_000)
        )
        json.decodeFromString<DesktopAnalysisBundle>(body)
    }

    suspend fun listReadyEpisodes(
        baseUrl: String,
        token: String,
        certFingerprint: String = ""
    ): List<DesktopReadyEpisode> = withContext(Dispatchers.IO) {
        val normalized = normalizeUrl(baseUrl)
        val body = getText("$normalized/v1/episodes", token, certFingerprint, timeoutMs = defaultTimeoutMs(normalized))
        json.decodeFromString<List<DesktopReadyEpisode>>(body)
    }

    suspend fun semanticSearch(
        baseUrl: String,
        token: String,
        query: String,
        topK: Int = 15,
        certFingerprint: String = ""
    ): DesktopSearchResponse = withContext(Dispatchers.IO) {
        val normalized = normalizeUrl(baseUrl)
        val payload = json.encodeToString(DesktopSearchRequest(query = query, topK = topK))
        val body = postJson("$normalized/v1/search", token, payload, certFingerprint, timeoutMs = defaultTimeoutMs(normalized))
        json.decodeFromString<DesktopSearchResponse>(body)
    }

    /**
     * Streams the exact audio file from the desktop worker to [destFile] with Range/If-Range resume (V5)
     * and verifies that its SHA-256 matches [expectedSha256].
     */
    suspend fun downloadAudioAndVerifySha256(
        baseUrl: String,
        token: String,
        expectedSha256: String,
        destFile: File,
        certFingerprint: String = "",
        onProgress: (receivedBytes: Long, totalBytes: Long) -> Unit = { _, _ -> }
    ): Unit = withContext(Dispatchers.IO) {
        destFile.parentFile?.mkdirs()
        val tmpFile = File(destFile.parentFile, "${destFile.name}.part")
        val existingLen = if (tmpFile.exists()) tmpFile.length() else 0L
        val conn = openConnection("${normalizeUrl(baseUrl)}/v1/episodes/$expectedSha256/audio", token, 30_000, certFingerprint)
        if (existingLen > 0L) {
            conn.setRequestProperty("Range", "bytes=$existingLen-")
            conn.setRequestProperty("If-Range", "\"$expectedSha256\"")
        }
        try {
            val code = conn.responseCode
            if (code == 416 && existingLen > 0L) {
                // Already complete on disk; verify SHA below
            } else if (code !in 200..299) {
                throw IllegalStateException("Desktop audio download failed: HTTP $code")
            }
            val isPartial = code == 206
            val baseOffset = if (isPartial) existingLen else 0L
            val contentLen = conn.contentLengthLong.coerceAtLeast(0L)
            val total = if (contentLen > 0L) baseOffset + contentLen else 0L

            if (code != 416) {
                var received = baseOffset
                conn.inputStream.use { input ->
                    java.io.FileOutputStream(tmpFile, isPartial).use { output ->
                        val buf = ByteArray(65536)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = input.read(buf)
                            if (read < 0) break
                            output.write(buf, 0, read)
                            received += read
                            onProgress(received, total)
                        }
                    }
                }
            }
            val actualSha = app.hushcast.automute.hash.StreamingSha256.hashFile(tmpFile)
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
        context: android.content.Context,
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

        val rules = dao.effectiveRules(context, episodeId)
        val windows = WordMatcher.matchWords(
            words = bundle.wordIndexBlob.words,
            rules = rules,
            prePadMs = prePadMs,
            postPadMs = postPadMs,
            defaultActionMode = defaultActionMode
        )
        dao.deleteMuteSegmentsByEpisode(episodeId)
        dao.replaceMuteSegments(
            sha,
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

        dao.deleteAdSegmentsByEpisode(episodeId)
        dao.replaceAdSegments(
            sha,
            bundle.adSegments.map { ad ->
                AdSegmentEntity(
                    fileSha256 = sha,
                    episodeId = episodeId,
                    startMs = ad.startMs,
                    endMs = ad.endMs,
                    confidence = ad.confidence,
                    isHighConfidence = ad.isHighConfidence && ad.adClass == "sponsor_ad",
                    label = ad.label,
                    adClass = ad.adClass
                )
            }
        )
        dao.clearAnalysisForOtherHashes(episodeId, sha)

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
                analysisDurationMs = bundle.analysisDurationMs,
                pipelineVersion = app.hushcast.automute.pipeline.AutoMuteAnalyzer.CURRENT_PIPELINE_VERSION
            )
        )
    }

    private fun normalizeUrl(baseUrl: String): String = baseUrl.trim().trimEnd('/')

    private fun openConnection(
        url: String,
        token: String,
        timeoutMs: Int,
        certFingerprint: String = ""
    ): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        if (conn is HttpsURLConnection && certFingerprint.isNotBlank()) {
            val expected = certFingerprint.replace(":", "").lowercase()
            val trustManager = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
                override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                    val leaf = chain.firstOrNull()
                        ?: throw java.security.cert.CertificateException("Empty certificate chain")
                    val digest = MessageDigest.getInstance("SHA-256").digest(leaf.encoded)
                    val got = digest.joinToString("") { "%02x".format(it) }
                    if (got != expected) {
                        throw java.security.cert.CertificateException("Pinned certificate fingerprint mismatch")
                    }
                }
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            }
            val sslCtx = SSLContext.getInstance("TLS").apply {
                init(null, arrayOf(trustManager), null)
            }
            conn.sslSocketFactory = sslCtx.socketFactory
            conn.hostnameVerifier = javax.net.ssl.HostnameVerifier { _, _ -> true }
        }
        conn.connectTimeout = timeoutMs.coerceAtMost(20_000)
        conn.readTimeout = timeoutMs
        if (token.isNotBlank()) {
            conn.setRequestProperty("Authorization", "Bearer $token")
        }
        return conn
    }

    private fun getText(
        url: String,
        token: String,
        certFingerprint: String = "",
        timeoutMs: Int = 15_000
    ): String {
        val conn = openConnection(url, token, timeoutMs, certFingerprint)
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

    private fun postJson(
        url: String,
        token: String,
        jsonBody: String,
        certFingerprint: String = "",
        timeoutMs: Int = 15_000
    ): String {
        val conn = openConnection(url, token, timeoutMs, certFingerprint)
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
