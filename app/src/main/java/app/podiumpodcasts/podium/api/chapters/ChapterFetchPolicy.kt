package app.podiumpodcasts.podium.api.chapters

/**
 * When a feed refresh fetches an episode's chapters URL.
 *
 * A refresh only fetches for new episodes, for episodes whose chapters URL changed, and for
 * earlier failures whose backoff has passed.  Older episodes that were never fetched are left to
 * the player, which fetches chapters when the episode is played.
 */
object ChapterFetchPolicy {
    /** After this many failed or empty fetches a refresh stops trying (playback still does). */
    const val MAX_ATTEMPTS = 5

    private const val BASE_BACKOFF_MS = 60L * 60L * 1000L          // 1 hour
    private const val MAX_BACKOFF_MS = 7L * 24L * 60L * 60L * 1000L // 7 days

    /** How many chapter URLs a refresh fetches at once, and how long it may spend in total. */
    const val PARALLELISM = 4
    const val PERIODIC_BUDGET_MS = 45_000L
    const val MANUAL_BUDGET_MS = 20_000L

    fun shouldFetch(
        isNewEpisode: Boolean,
        storedUrl: String,
        feedUrl: String,
        failures: Int,
        retryAfterMs: Long,
        hasPublisherChapters: Boolean,
        nowMs: Long
    ): Boolean {
        if (feedUrl.isBlank()) return false
        // A different URL than a previously stored one: the publisher changed the chapters.
        // Only the path counts, so hosts that rotate signed query tokens don't refetch each time.
        val urlChanged = storedUrl.isNotBlank() && withoutQuery(storedUrl) != withoutQuery(feedUrl)
        if (urlChanged) return true
        if (hasPublisherChapters) return false
        if (isNewEpisode) return true
        return isRetryDue(failures, retryAfterMs, nowMs)
    }

    fun isRetryDue(failures: Int, retryAfterMs: Long, nowMs: Long): Boolean =
        failures in 1 until MAX_ATTEMPTS && nowMs >= retryAfterMs

    /** Backoff after the [failures]-th failed or empty fetch: 1 h, 4 h, 16 h, 64 h, then 7 days. */
    fun backoffMs(failures: Int): Long {
        if (failures <= 0) return 0L
        var delay = BASE_BACKOFF_MS
        repeat(failures - 1) {
            delay *= 4
            if (delay >= MAX_BACKOFF_MS) return MAX_BACKOFF_MS
        }
        return delay.coerceAtMost(MAX_BACKOFF_MS)
    }

    fun nextRetryAt(failures: Int, nowMs: Long): Long = nowMs + backoffMs(failures)

    private fun withoutQuery(url: String): String =
        url.trim().substringBefore('#').substringBefore('?')
}
