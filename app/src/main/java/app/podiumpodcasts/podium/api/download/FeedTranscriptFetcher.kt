package app.podiumpodcasts.podium.api.download

import app.podiumpodcasts.podium.api.db.model.PodcastEpisodeModel
import app.podiumpodcasts.podium.api.db.model.transcriptCandidates
import app.podiumpodcasts.podium.api.rss.TranscriptRanking
import app.podiumpodcasts.podium.api.rss.TranscriptRef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Downloads a publisher's transcript (the feed's `<podcast:transcript>`) for on-device analysis.
 * Shared by the download work and the stale-result re-runs, so both hand the analyzer the same
 * transcript instead of falling back to a full Whisper pass.
 */
object FeedTranscriptFetcher {
    private const val MAX_BYTES = 5 * 1024 * 1024

    /** The transcript text, or null when it can't be fetched (or is over 5 MB). */
    suspend fun fetchFeedTranscriptRaw(url: String): String? {
        if (url.isBlank()) return null
        return withContext(Dispatchers.IO) {
            runCatching {
                val conn = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
                    connectTimeout = 10_000
                    readTimeout = 15_000
                    instanceFollowRedirects = true
                }
                try {
                    if (conn.responseCode !in 200..299) return@runCatching null
                    val bytes = conn.inputStream.use { input ->
                        val out = java.io.ByteArrayOutputStream()
                        val buf = ByteArray(8192)
                        var total = 0
                        while (true) {
                            val n = input.read(buf)
                            if (n <= 0) break
                            total += n
                            if (total > MAX_BYTES) return@runCatching null
                            out.write(buf, 0, n)
                        }
                        out.toByteArray()
                    }
                    bytes.decodeToString().takeIf { it.isNotBlank() }
                } finally {
                    conn.disconnect()
                }
            }.getOrNull()
        }
    }

    /** A fetched transcript and the MIME hint to parse it with. */
    data class FeedTranscript(val raw: String, val mime: String, val url: String)

    /**
     * Tries the episode's transcripts in preference order (primary, then alternates) and returns
     * the first that downloads.  HTML is skipped: the analyzer only reads JSON, VTT and SRT.
     */
    suspend fun fetchBestForAnalysis(episode: PodcastEpisodeModel, maxTries: Int = 3): FeedTranscript? {
        val usable = analysisCandidates(episode.transcriptCandidates())
        for ((url, mime) in usable.take(maxTries)) {
            val raw = fetchFeedTranscriptRaw(url) ?: continue
            return FeedTranscript(raw = raw, mime = mime, url = url)
        }
        return null
    }

    /** Candidates the on-device analyzer can read, with a MIME filled in from the extension. */
    fun analysisCandidates(candidates: List<Pair<String, String>>): List<Pair<String, String>> =
        candidates.mapNotNull { (url, type) ->
            val ref = TranscriptRef(url, type)
            if (TranscriptRanking.rank(type, url) == TranscriptRanking.RANK_HTML) null
            else url to TranscriptRanking.effectiveType(ref)
        }
}
