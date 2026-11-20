package app.podiumpodcasts.podium.background.car

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaConstants
import java.util.Locale

/**
 * Text and state rules for the Android Auto screens, kept free of Android so they can be tested.
 * [CarStrings] are resolved from resources once; tests pass plain English.
 */
data class CarStrings(
    /** "EP. %1$d" */
    val episodeNumber: String,
    /** "%1$s left" */
    val timeLeft: String,
    /** "Played" */
    val played: String,
    /** "%1$s · until %2$s" (ad label, end time) */
    val adUntil: String,
    /** "Skipped %1$s (%2$s)" (ad label, length) */
    val adSkipped: String,
    val separator: String = " · "
)

object CarText {

    /** The phone's list style: "1h 3m", "42m", "1m". Empty when the length isn't known. */
    fun shortDuration(seconds: Int): String {
        if (seconds <= 0) return ""
        var hours = seconds / 3600
        var minutes = ((seconds % 3600) + 30) / 60
        if (minutes == 60) {
            hours += 1
            minutes = 0
        }
        return when {
            hours > 0 && minutes > 0 -> "${hours}h ${minutes}m"
            hours > 0 -> "${hours}h"
            else -> "${minutes.coerceAtLeast(1)}m"
        }
    }

    /** A position in the episode: "34:10", or "1:02:05" past an hour. */
    fun clock(ms: Long): String {
        val total = (ms.coerceAtLeast(0L) / 1000L)
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        else String.format(Locale.US, "%d:%02d", m, s)
    }

    /**
     * An episode row's subtitle.
     * - Inside a podcast ([podcastName] null): "EP. 12 · 31m left", "EP. 12 · 42m", "Played".
     * - In mixed lists (Up Next, New, Downloads, search) the podcast's name leads: "Serial · 31m left".
     */
    fun episodeSubtitle(
        s: CarStrings,
        podcastName: String?,
        episodeNumber: Int?,
        durationSec: Int,
        positionSec: Int,
        played: Boolean
    ): String {
        val parts = ArrayList<String>(2)
        if (!podcastName.isNullOrBlank()) {
            parts += podcastName
        } else if (episodeNumber != null && episodeNumber > 0) {
            parts += String.format(s.episodeNumber, episodeNumber)
        }
        val time = when {
            played -> s.played
            positionSec > 0 && durationSec > positionSec ->
                String.format(s.timeLeft, shortDuration(durationSec - positionSec))
            durationSec > 0 -> shortDuration(durationSec)
            else -> ""
        }
        if (time.isNotEmpty()) parts += time
        return parts.joinToString(s.separator)
    }

    data class Completion(val status: Int, val percentage: Double?)

    /** The progress bar Android Auto draws under an episode row. */
    @OptIn(UnstableApi::class)
    fun completion(played: Boolean, positionSec: Int, durationSec: Int): Completion = when {
        played -> Completion(MediaConstants.EXTRAS_VALUE_COMPLETION_STATUS_FULLY_PLAYED, 1.0)
        positionSec > 0 -> Completion(
            MediaConstants.EXTRAS_VALUE_COMPLETION_STATUS_PARTIALLY_PLAYED,
            if (durationSec > 0) (positionSec.toDouble() / durationSec).coerceIn(0.0, 1.0) else null
        )
        else -> Completion(MediaConstants.EXTRAS_VALUE_COMPLETION_STATUS_NOT_PLAYED, null)
    }
}

/** An ad window as the car rules see it. */
data class CarAd(val startMs: Long, val endMs: Long, val label: String, val willAutoSkip: Boolean)

data class CarChapter(val startMs: Long, val title: String)

/** What the car's Now Playing screen shows beyond the standard controls. */
data class CarNowPlaying(
    /** Non-null: show "Skip sponsor", which jumps to this position. */
    val skipAdToMs: Long?,
    val showUndo: Boolean,
    val hasNextChapter: Boolean,
    /** Replaces the podcast name under the title; null keeps the podcast name. */
    val subtitle: String?
) {
    companion object {
        val NONE = CarNowPlaying(skipAdToMs = null, showUndo = false, hasNextChapter = false, subtitle = null)
    }
}

object CarNowPlayingRules {

    /**
     * - Skip sponsor: inside an ad that won't be skipped automatically (sponsor skipping on).
     * - Undo: during the same window the phone offers it after an automatic skip. Never with Skip.
     * - Next chapter: when a later, not-skipped chapter exists (as `jumpToNextChapter` finds it).
     * - Subtitle: "Sponsor · until 34:10" in an ad, "Skipped Sponsor (1:20)" during Undo, else the
     *   chapter's title, else the podcast's name (null). Fixed text per state, so it never ticks.
     */
    fun compute(
        s: CarStrings,
        positionMs: Long,
        sponsorSkippingOn: Boolean,
        ads: List<CarAd>,
        undoValid: Boolean,
        undoAdLabel: String?,
        undoSkippedMs: Long,
        chapters: List<CarChapter>,
        skippedChapterStarts: Set<Long>
    ): CarNowPlaying {
        val activeAd = if (sponsorSkippingOn) {
            ads.firstOrNull { positionMs >= it.startMs && positionMs < it.endMs && !it.willAutoSkip }
        } else null
        val skipTo = if (activeAd != null && !undoValid) activeAd.endMs else null
        val hasNext = chapters.any { it.startMs > positionMs + 500L && it.startMs !in skippedChapterStarts }
        val subtitle = when {
            activeAd != null -> String.format(s.adUntil, activeAd.label, CarText.clock(activeAd.endMs))
            undoValid && !undoAdLabel.isNullOrBlank() ->
                String.format(s.adSkipped, undoAdLabel, CarText.clock(undoSkippedMs))
            else -> chapters.lastOrNull { it.startMs <= positionMs && it.title.isNotBlank() }?.title
        }
        return CarNowPlaying(skipTo, undoValid, hasNext, subtitle)
    }
}
