package app.podiumpodcasts.podium.player

import androidx.media3.session.MediaConstants
import app.podiumpodcasts.podium.api.db.model.PodcastEpisodeDownloadState
import app.podiumpodcasts.podium.background.car.CarActions
import app.podiumpodcasts.podium.background.car.CarAd
import app.podiumpodcasts.podium.background.car.CarChapter
import app.podiumpodcasts.podium.background.car.CarIds
import app.podiumpodcasts.podium.background.car.CarNowPlayingRules
import app.podiumpodcasts.podium.background.car.CarRowActions
import app.podiumpodcasts.podium.background.car.CarRowState
import app.podiumpodcasts.podium.background.car.CarSections
import app.podiumpodcasts.podium.background.car.CarStrings
import app.podiumpodcasts.podium.background.car.CarText
import app.podiumpodcasts.podium.background.car.CarVoice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1.0.48: Android Auto. */
class Release1048Test {

    private val s = CarStrings(
        episodeNumber = "EP. %1\$d",
        timeLeft = "%1\$s left",
        played = "Played",
        adUntil = "%1\$s · until %2\$s",
        adSkipped = "Skipped %1\$s (%2\$s)"
    )

    // ---- F4: row text and progress ----

    @Test
    fun `short durations read like the phone`() {
        assertEquals("1h 3m", CarText.shortDuration(3780))
        assertEquals("42m", CarText.shortDuration(2520))
        assertEquals("1m", CarText.shortDuration(20))
        assertEquals("1h", CarText.shortDuration(3599))
        assertEquals("2h", CarText.shortDuration(7200))
        assertEquals("", CarText.shortDuration(0))
    }

    @Test
    fun `clock shows hours only past an hour`() {
        assertEquals("34:10", CarText.clock(2_050_000L))
        assertEquals("1:02:05", CarText.clock(3_725_000L))
        assertEquals("0:00", CarText.clock(-5L))
    }

    @Test
    fun `episode subtitles inside a podcast`() {
        assertEquals("EP. 12 · 31m left", CarText.episodeSubtitle(s, null, 12, 2520, 660, false))
        assertEquals("EP. 12 · 42m", CarText.episodeSubtitle(s, null, 12, 2520, 0, false))
        assertEquals("EP. 12 · Played", CarText.episodeSubtitle(s, null, 12, 2520, 2500, true))
        assertEquals("31m left", CarText.episodeSubtitle(s, null, null, 2520, 660, false))
        assertEquals("", CarText.episodeSubtitle(s, null, null, 0, 0, false))
    }

    @Test
    fun `episode subtitles in mixed lists lead with the podcast`() {
        assertEquals("Serial · 31m left", CarText.episodeSubtitle(s, "Serial", 12, 2520, 660, false))
        assertEquals("Serial · Played", CarText.episodeSubtitle(s, "Serial", 12, 2520, 0, true))
    }

    @Test
    fun `progress status and percentage`() {
        val notPlayed = CarText.completion(played = false, positionSec = 0, durationSec = 100)
        assertEquals(MediaConstants.EXTRAS_VALUE_COMPLETION_STATUS_NOT_PLAYED, notPlayed.status)
        assertNull(notPlayed.percentage)

        val half = CarText.completion(played = false, positionSec = 50, durationSec = 100)
        assertEquals(MediaConstants.EXTRAS_VALUE_COMPLETION_STATUS_PARTIALLY_PLAYED, half.status)
        assertEquals(0.5, half.percentage!!, 0.0001)

        val unknownLength = CarText.completion(played = false, positionSec = 50, durationSec = 0)
        assertEquals(MediaConstants.EXTRAS_VALUE_COMPLETION_STATUS_PARTIALLY_PLAYED, unknownLength.status)
        assertNull(unknownLength.percentage)

        val done = CarText.completion(played = true, positionSec = 0, durationSec = 100)
        assertEquals(MediaConstants.EXTRAS_VALUE_COMPLETION_STATUS_FULLY_PLAYED, done.status)
        assertEquals(1.0, done.percentage!!, 0.0001)
    }

    // ---- F8–F11: Now Playing ----

    private val sponsor = CarAd(startMs = 600_000L, endMs = 680_000L, label = "Sponsor", willAutoSkip = false)
    private val chapters = listOf(
        CarChapter(0L, "Intro"),
        CarChapter(300_000L, "Interview"),
        CarChapter(900_000L, "Outro")
    )

    private fun rules(
        pos: Long,
        ads: List<CarAd> = listOf(sponsor),
        on: Boolean = true,
        undo: Boolean = false,
        skipped: Set<Long> = emptySet()
    ) = CarNowPlayingRules.compute(s, pos, on, ads, undo, if (undo) "Sponsor" else null, 80_000L, chapters, skipped)

    @Test
    fun `inside an ad that won't auto-skip shows Skip and the ad's end`() {
        val r = rules(pos = 620_000L)
        assertEquals(680_000L, r.skipAdToMs)
        assertFalse(r.showUndo)
        assertEquals("Sponsor · until 11:20", r.subtitle)
    }

    @Test
    fun `an ad that will auto-skip shows no Skip`() {
        val r = rules(pos = 620_000L, ads = listOf(sponsor.copy(willAutoSkip = true)))
        assertNull(r.skipAdToMs)
        assertEquals("Interview", r.subtitle)
    }

    @Test
    fun `sponsor skipping off shows no Skip and keeps the chapter`() {
        val r = rules(pos = 620_000L, on = false)
        assertNull(r.skipAdToMs)
        assertEquals("Interview", r.subtitle)
    }

    @Test
    fun `undo window shows Undo, never with Skip`() {
        val r = rules(pos = 681_000L, undo = true)
        assertTrue(r.showUndo)
        assertNull(r.skipAdToMs)
        assertEquals("Skipped Sponsor (1:20)", r.subtitle)

        val inAd = rules(pos = 620_000L, undo = true)
        assertTrue(inAd.showUndo)
        assertNull(inAd.skipAdToMs)
    }

    @Test
    fun `next chapter only when a later, unskipped chapter exists`() {
        assertTrue(rules(pos = 100_000L, ads = emptyList()).hasNextChapter)
        assertFalse(rules(pos = 950_000L, ads = emptyList()).hasNextChapter)
        assertFalse(rules(pos = 400_000L, ads = emptyList(), skipped = setOf(900_000L)).hasNextChapter)
        // Within half a second of a chapter's start counts as being in it.
        assertFalse(rules(pos = 899_700L, ads = emptyList()).hasNextChapter)
    }

    @Test
    fun `subtitle is the chapter, or null without chapters`() {
        assertEquals("Intro", rules(pos = 10_000L, ads = emptyList()).subtitle)
        val none = CarNowPlayingRules.compute(s, 10_000L, true, emptyList(), false, null, 0L, emptyList(), emptySet())
        assertNull(none.subtitle)
        assertNull(none.skipAdToMs)
        assertFalse(none.hasNextChapter)
    }

    // ---- F1–F3: tabs and sections ----

    @Test
    fun `tabs drop Downloads then New under the host's limit`() {
        val all = listOf(CarIds.TAB_UP_NEXT, CarIds.TAB_NEW, CarIds.TAB_PODCASTS, CarIds.TAB_DOWNLOADS)
        assertEquals(all, CarSections.tabsWithin(all, 4) { it })
        assertEquals(all, CarSections.tabsWithin(all, 0) { it })
        assertEquals(listOf(CarIds.TAB_UP_NEXT, CarIds.TAB_NEW, CarIds.TAB_PODCASTS), CarSections.tabsWithin(all, 3) { it })
        assertEquals(listOf(CarIds.TAB_UP_NEXT, CarIds.TAB_PODCASTS), CarSections.tabsWithin(all, 2) { it })
        assertEquals(listOf(CarIds.TAB_UP_NEXT), CarSections.tabsWithin(all, 1) { it })
    }

    @Test
    fun `Up Next tab shows currently playing episode first without duplicating it`() {
        val withCurrentAndQueue = CarSections.upNext(
            current = "a",
            queued = listOf("a", "b", "c"),
            id = { it },
            nowPlayingTitle = "Now playing",
            upNextTitle = "Up Next"
        )
        assertEquals(listOf("a", "b", "c"), withCurrentAndQueue.map { it.item })
        assertEquals(listOf("Now playing", "Up Next", "Up Next"), withCurrentAndQueue.map { it.group })

        val withCurrentOnly = CarSections.upNext(
            current = "a",
            queued = emptyList(),
            id = { it },
            nowPlayingTitle = "Now playing",
            upNextTitle = "Up Next"
        )
        assertEquals(listOf("a"), withCurrentOnly.map { it.item })
        assertEquals(listOf("Now playing"), withCurrentOnly.map { it.group })

        val withoutCurrent = CarSections.upNext(
            current = null,
            queued = listOf("b", "c"),
            id = { it },
            nowPlayingTitle = "Now playing",
            upNextTitle = "Up Next"
        )
        assertEquals(listOf("b", "c"), withoutCurrent.map { it.item })
        assertEquals(listOf(null, null), withoutCurrent.map { it.group })
    }

    @Test
    fun `New tab shows continuing first and never repeats an episode`() {
        val rows = CarSections.newTab(
            continuing = listOf("a", "b"),
            newEpisodes = listOf("b", "c", "d"),
            id = { it },
            continueTitle = "Continue listening",
            newTitle = "New episodes"
        )
        assertEquals(listOf("a", "b", "c", "d"), rows.map { it.item })
        assertEquals(listOf("Continue listening", "Continue listening", "New episodes", "New episodes"), rows.map { it.group })
    }

    @Test
    fun `New tab caps both sections`() {
        val rows = CarSections.newTab(
            continuing = (1..9).map { "c$it" },
            newEpisodes = (1..80).map { "n$it" },
            id = { it },
            continueTitle = "C",
            newTitle = "N"
        )
        assertEquals(CarSections.MAX_CONTINUE, rows.count { it.group == "C" })
        assertEquals(CarSections.MAX_NEW, rows.count { it.group == "N" })
    }

    @Test
    fun `serial shows get season headings with other episodes last`() {
        data class E(val id: String, val season: Int?)
        val rows = CarSections.serial(
            episodes = listOf(E("1", 1), E("2", 1), E("3", 2), E("x", null)),
            season = { it.season },
            seasonTitle = { "Season $it" },
            otherTitle = "Other episodes"
        )
        assertEquals(listOf("Season 1", "Season 1", "Season 2", "Other episodes"), rows.map { it.group })
    }

    @Test
    fun `serial shows without seasons get no headings`() {
        val rows = CarSections.serial(listOf("a", "b"), season = { null }, seasonTitle = { "S$it" }, otherTitle = "O")
        assertEquals(listOf(null, null), rows.map { it.group })
    }

    @Test
    fun `paging handles the car's unpaged requests`() {
        val list = (1..10).toList()
        assertEquals(list, CarSections.page(list, 0, Int.MAX_VALUE))
        assertEquals(listOf(4, 5, 6), CarSections.page(list, 1, 3))
        assertEquals(emptyList<Int>(), CarSections.page(list, 1, Int.MAX_VALUE))
        assertEquals(emptyList<Int>(), CarSections.page(list, 5, 3))
    }

    // ---- F7: row actions ----

    @Test
    fun `row actions only offer what would change something`() {
        val fresh = CarRowState(inUpNext = false, isCurrent = false, downloadState = null, played = false)
        assertEquals(listOf(CarActions.ADD_UP_NEXT, CarActions.DOWNLOAD, CarActions.MARK_PLAYED), CarRowActions.forEpisode(fresh))

        val queuedDownloadedPlayed = CarRowState(
            inUpNext = true, isCurrent = false,
            downloadState = PodcastEpisodeDownloadState.DOWNLOADED.value, played = true
        )
        assertEquals(emptyList<String>(), CarRowActions.forEpisode(queuedDownloadedPlayed))

        val playingNow = fresh.copy(isCurrent = true, downloadState = PodcastEpisodeDownloadState.DOWNLOADING.value)
        assertEquals(listOf(CarActions.MARK_PLAYED), CarRowActions.forEpisode(playingNow))

        val failed = fresh.copy(downloadState = PodcastEpisodeDownloadState.FAILED.value)
        assertTrue(CarActions.DOWNLOAD in CarRowActions.forEpisode(failed))
    }

    // ---- F14: voice ----

    @Test
    fun `voice query normalising`() {
        assertEquals("daily", CarVoice.normalize("The Daily"))
        assertEquals("serial", CarVoice.normalize("Serial podcast"))
        assertEquals("this american life", CarVoice.normalize("This American Life!"))
    }

    @Test
    fun `voice podcast match prefers exact, then starts-with, then contains`() {
        val podcasts = listOf(
            "a" to "Serial Killers",
            "b" to "Serial",
            "c" to "The Daily",
            "d" to "Buzzcast"
        )
        assertEquals("b", CarVoice.matchPodcast("serial", podcasts))
        assertEquals("a", CarVoice.matchPodcast("serial kill", podcasts))
        assertEquals("c", CarVoice.matchPodcast("the daily", podcasts))
        assertEquals("d", CarVoice.matchPodcast("zzca", podcasts))
        assertEquals("d", CarVoice.matchPodcast("latest buzzcast episode", podcasts))
        assertNull(CarVoice.matchPodcast("radiolab", podcasts))
        assertNull(CarVoice.matchPodcast("", podcasts))
    }

    @Test
    fun `voice picks the episode in progress first`() {
        val eps = listOf(
            CarVoice.Candidate("old", pubDate = 1, positionSec = 300, played = false, lastUpdate = 50),
            CarVoice.Candidate("recent", pubDate = 2, positionSec = 100, played = false, lastUpdate = 90),
            CarVoice.Candidate("new", pubDate = 3, positionSec = 0, played = false, lastUpdate = 0)
        )
        assertEquals("recent", CarVoice.pickEpisode(eps))
    }

    @Test
    fun `voice picks the newest unplayed, or a serial's next one, else the newest`() {
        val eps = listOf(
            CarVoice.Candidate("e1", pubDate = 1, positionSec = 0, played = true, lastUpdate = 0),
            CarVoice.Candidate("e2", pubDate = 2, positionSec = 0, played = false, lastUpdate = 0),
            CarVoice.Candidate("e3", pubDate = 3, positionSec = 0, played = false, lastUpdate = 0)
        )
        assertEquals("e3", CarVoice.pickEpisode(eps))
        assertEquals("e2", CarVoice.pickEpisode(eps, serialOrder = listOf("e1", "e2", "e3")))

        val allPlayed = eps.map { it.copy(played = true) }
        assertEquals("e3", CarVoice.pickEpisode(allPlayed))
        assertNull(CarVoice.pickEpisode(emptyList()))
    }
}
