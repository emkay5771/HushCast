package app.podiumpodcasts.podium.background.work

/** What one feed refresh did (all subscriptions). */
data class RefreshSummary(
    val totalFeeds: Int = 0,
    val updatedFeeds: Int = 0,
    val unchangedFeeds: Int = 0,
    val failedFeeds: Int = 0,
    val newEpisodes: Int = 0,
    /** The first feed failure, for the error message. */
    val firstError: Throwable? = null
) {
    /**
     * A short reason when the refresh as a whole failed (every feed failed), else null.
     * Some feeds failing is still a successful refresh; see [failedFeeds].
     */
    fun errorMessage(): String? {
        if (totalFeeds == 0 || failedFeeds < totalFeeds) return null
        return shortReason(firstError)
    }

    companion object {
        fun shortReason(error: Throwable?): String {
            var cause: Throwable? = error
            var depth = 0
            while (cause != null && depth < 5) {
                if (isNetworkError(cause)) return "Couldn't reach the feeds. Check your connection."
                cause = cause.cause
                depth++
            }
            val message = error?.message?.lineSequence()?.firstOrNull { it.isNotBlank() }?.trim()
            return when {
                message.isNullOrBlank() -> "Couldn't update your podcasts"
                message.startsWith("UNHANDLED STATUS CODE") ->
                    "The feeds answered with an error (${message.removePrefix("UNHANDLED STATUS CODE").trim()})"
                else -> "Couldn't update your podcasts: ${message.take(80)}"
            }
        }

        private fun isNetworkError(t: Throwable): Boolean =
            t is java.net.UnknownHostException ||
                t is java.net.ConnectException ||
                t is java.net.SocketTimeoutException ||
                t is java.net.NoRouteToHostException ||
                t is javax.net.ssl.SSLException ||
                t.javaClass.name.endsWith("HttpRequestTimeoutException") ||
                t.javaClass.name.endsWith("ConnectTimeoutException")
    }
}

/** How many stale results a refresh re-runs, and which (item 3). */
object RerunPlanner {
    const val DEFAULT_PER_REFRESH = 3
    const val CHARGING_WIFI_PER_REFRESH = 10

    /** 3 per refresh; up to 10 while charging on unmetered Wi-Fi. */
    fun capFor(isCharging: Boolean, isUnmeteredWifi: Boolean): Int =
        if (isCharging && isUnmeteredWifi) CHARGING_WIFI_PER_REFRESH else DEFAULT_PER_REFRESH

    /**
     * The first [cap] candidates [accept] lets through.  The caller rejects episodes whose re-run
     * is still waiting or running (so a refresh never replaces work in progress and the cap goes
     * to episodes that still need it) and episodes without a local file.  [accept] is only asked
     * until the cap is reached.
     */
    inline fun <T> pick(candidates: List<T>, cap: Int, accept: (T) -> Boolean): List<T> {
        if (cap <= 0) return emptyList()
        val out = ArrayList<T>(minOf(cap, candidates.size))
        for (c in candidates) {
            if (out.size >= cap) break
            if (accept(c)) out += c
        }
        return out
    }
}
