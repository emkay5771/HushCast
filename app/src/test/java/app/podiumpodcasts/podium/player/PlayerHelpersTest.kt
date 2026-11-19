package app.podiumpodcasts.podium.player

import app.hushcast.automute.dsp.AdWindow
import app.hushcast.automute.dsp.MuteActionMode
import app.hushcast.automute.engine.Segment
import app.hushcast.automute.engine.Word
import app.hushcast.automute.storage.MuteSegmentEntity
import app.podiumpodcasts.podium.PlayerToolbarAction
import app.podiumpodcasts.podium.api.chapters.ResolvedChapter
import app.podiumpodcasts.podium.ui.dialog.bottomsheet.media.EpisodeScoped
import app.podiumpodcasts.podium.ui.dialog.bottomsheet.media.PlayerToolbarOrder
import app.podiumpodcasts.podium.ui.dialog.bottomsheet.media.TranscriptLineUi
import app.podiumpodcasts.podium.ui.dialog.bottomsheet.media.adForLine
import app.podiumpodcasts.podium.ui.dialog.bottomsheet.media.cleanMuteWord
import app.podiumpodcasts.podium.ui.dialog.bottomsheet.media.currentChapterIndexAt
import app.podiumpodcasts.podium.ui.dialog.bottomsheet.media.groupIntoParagraphs
import app.podiumpodcasts.podium.ui.dialog.bottomsheet.media.mutedSegmentIdsForWord
import app.podiumpodcasts.podium.ui.dialog.bottomsheet.media.splitSegmentsAtAdBoundaries
import app.podiumpodcasts.podium.ui.hushcast.HushCastLabels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlayerHelpersTest {

    // Item 20: "Sound" follows the custom-sound selection.
    @Test
    fun `sound choice is the custom sound only while one is selected`() {
        assertEquals(MuteActionMode.CUSTOM_SOUND, HushCastLabels.resolveUserChoice(MuteActionMode.BEEP, "content://sound"))
        assertEquals(MuteActionMode.BEEP, HushCastLabels.resolveUserChoice(MuteActionMode.BEEP, null))
        assertEquals(MuteActionMode.BEEP, HushCastLabels.resolveUserChoice(MuteActionMode.BEEP, ""))
        assertEquals(MuteActionMode.BEEP, HushCastLabels.resolveUserChoice(MuteActionMode.CUSTOM_SOUND, null))
        // Non-sound modes are saved as picked.
        assertEquals(MuteActionMode.HARD_SILENCE, HushCastLabels.resolveUserChoice(MuteActionMode.HARD_SILENCE, "content://sound"))
        assertEquals(MuteActionMode.VOLUME_DUCK, HushCastLabels.resolveUserChoice(MuteActionMode.VOLUME_DUCK, null))
    }

    // Item 2: the shared episode-page view model's data is ignored for other episodes.
    @Test
    fun `scoped values only answer for their own episode`() {
        val scoped = EpisodeScoped("ep1", listOf(1, 2))
        assertEquals(listOf(1, 2), scoped.valueFor("ep1"))
        assertNull(scoped.valueFor("ep2"))
    }

    // Item 5: "Unmute word" turns off the segments that cover the word.
    @Test
    fun `unmute finds every enabled segment over the word`() {
        val segments = listOf(
            segment(id = 1, start = 9_900, end = 10_400),
            segment(id = 2, start = 10_300, end = 10_700),
            segment(id = 3, start = 10_000, end = 10_500, enabled = false),
            segment(id = 4, start = 20_000, end = 20_400)
        )
        val word = Word(start = 10.0, end = 10.5, text = " darn")
        assertEquals(listOf(1L, 2L), mutedSegmentIdsForWord(word, segments))
        assertEquals(emptyList<Long>(), mutedSegmentIdsForWord(Word(30.0, 30.4, "fine"), segments))
    }

    @Test
    fun `mute words are trimmed of punctuation but keep apostrophes and hyphens`() {
        assertEquals("darn", cleanMuteWord(" darn,"))
        assertEquals("don't", cleanMuteWord("\"don't!\""))
        assertEquals("x-ray", cleanMuteWord("(x-ray)"))
        assertEquals("", cleanMuteWord(" ... "))
    }

    // T3: transcript lines inside a sponsor block.
    @Test
    fun `lines overlapping an ad belong to it`() {
        val ad = AdWindow(startMs = 60_000, endMs = 120_000, confidence = 0.9f)
        assertEquals(ad, adForLine(line(50_000, 65_000), listOf(ad)))
        assertEquals(ad, adForLine(line(70_000, 80_000), listOf(ad)))
        assertNull(adForLine(line(120_000, 130_000), listOf(ad)))
        assertNull(adForLine(line(10_000, 20_000), listOf(ad)))
        // Publisher lines without timings never match.
        assertNull(adForLine(line(0, 0), listOf(ad)))
    }

    @Test
    fun `current chapter is the one playing, else the last started`() {
        val chapters = listOf(chapter(0, 0, 60_000), chapter(1, 60_000, 120_000), chapter(2, 150_000, 200_000))
        assertEquals(0, currentChapterIndexAt(chapters, 0L))
        assertEquals(1, currentChapterIndexAt(chapters, 60_000L))
        assertEquals(1, currentChapterIndexAt(chapters, 130_000L)) // in a gap: last started
        assertEquals(2, currentChapterIndexAt(chapters, 250_000L))
        assertEquals(-1, currentChapterIndexAt(emptyList(), 10L))
    }

    // T1 / I9: toolbar order.
    @Test
    fun `toolbar keeps the user's order and appends missing actions with Transcript first`() {
        val available = listOf(
            PlayerToolbarAction.HUSHCAST, PlayerToolbarAction.SPEED, PlayerToolbarAction.SLEEP,
            PlayerToolbarAction.TRANSCRIPT, PlayerToolbarAction.FAVORITE, PlayerToolbarAction.DOWNLOAD
        )
        val ordered = PlayerToolbarOrder.ordered(
            listOf(PlayerToolbarAction.SLEEP, PlayerToolbarAction.ANALYZE, PlayerToolbarAction.EPISODE_DETAILS),
            available
        )
        assertEquals(
            listOf(
                PlayerToolbarAction.SLEEP, PlayerToolbarAction.HUSHCAST, PlayerToolbarAction.TRANSCRIPT,
                PlayerToolbarAction.SPEED, PlayerToolbarAction.FAVORITE, PlayerToolbarAction.DOWNLOAD
            ),
            ordered
        )
        assertEquals(available.size, ordered.distinct().size)
    }

    @Test
    fun `default toolbar order starts with the defaults`() {
        val available = listOf(
            PlayerToolbarAction.HUSHCAST, PlayerToolbarAction.SPEED, PlayerToolbarAction.SLEEP,
            PlayerToolbarAction.TRANSCRIPT, PlayerToolbarAction.MARK_PLAYED
        )
        val ordered = PlayerToolbarOrder.ordered(PlayerToolbarOrder.DEFAULT_ACTIONS, available)
        assertEquals(PlayerToolbarOrder.DEFAULT_ACTIONS, ordered.take(4))
        assertEquals(PlayerToolbarAction.MARK_PLAYED, ordered.last())
    }

    @Test
    fun `move reorders and clamps`() {
        val list = listOf("a", "b", "c", "d")
        assertEquals(listOf("b", "c", "a", "d"), PlayerToolbarOrder.move(list, 0, 2))
        assertEquals(listOf("d", "a", "b", "c"), PlayerToolbarOrder.move(list, 3, 0))
        assertEquals(listOf("b", "c", "d", "a"), PlayerToolbarOrder.move(list, 0, 99))
        assertEquals(list, PlayerToolbarOrder.move(list, 7, 0))
        assertEquals(list, PlayerToolbarOrder.move(list, 1, 1))
    }

    @Test
    fun `splitSegmentsAtAdBoundaries and groupIntoParagraphs separate dialogue from ads`() {
        val segs = listOf(
            Segment(start = 10.0, end = 12.0, text = "Back to the show. Now a word from our sponsor.", speaker = 0),
            Segment(start = 12.1, end = 14.0, text = "Use code HUSH.", speaker = 0)
        )
        val words = listOf(
            Word(10.0, 10.3, "Back"),
            Word(10.3, 10.5, " to"),
            Word(10.5, 10.7, " the"),
            Word(10.7, 11.0, " show."),
            Word(11.1, 11.3, " Now"),
            Word(11.3, 11.5, " a"),
            Word(11.5, 11.7, " word"),
            Word(11.7, 11.85, " from"),
            Word(11.85, 11.95, " our"),
            Word(11.95, 12.0, " sponsor."),
            Word(12.1, 12.5, "Use"),
            Word(12.5, 13.0, " code"),
            Word(13.0, 14.0, " HUSH.")
        )
        val adRanges = listOf(11.05 to 14.5)
        val split = splitSegmentsAtAdBoundaries(segs, words, adRanges)
        assertEquals(3, split.size)
        assertEquals("Back to the show.", split[0].text)
        assertEquals("Now a word from our sponsor.", split[1].text)

        val paragraphs = groupIntoParagraphs(split, adRanges)
        assertEquals(2, paragraphs.size)
        assertEquals(1, paragraphs[0].size)
        assertEquals("Back to the show.", paragraphs[0][0].text)
        assertEquals(2, paragraphs[1].size)
    }

    @Test
    fun `adForLine ignores tiny edge overlap and matches midpoint`() {
        val ad = AdWindow(startMs = 10_000L, endMs = 20_000L, confidence = 0.9f, adClass = "sponsor_ad")
        assertNull(adForLine(line(8_000L, 10_050L), listOf(ad)))
        assertEquals(ad, adForLine(line(10_000L, 12_000L), listOf(ad)))
    }

    private fun segment(id: Long, start: Long, end: Long, enabled: Boolean = true) = MuteSegmentEntity(
        id = id,
        fileSha256 = "sha",
        episodeId = "ep",
        startMs = start,
        endMs = end,
        word = "darn",
        ruleId = 1L,
        actionMode = "HARD_SILENCE",
        enabled = enabled
    )

    private fun line(start: Long, end: Long) = TranscriptLineUi(
        index = 0,
        startMs = start,
        endMs = end,
        speaker = null,
        text = "text",
        words = emptyList()
    )

    private fun chapter(index: Int, start: Long, end: Long) = ResolvedChapter(
        index = index,
        publishedStartMs = start,
        startMs = start,
        endMs = end,
        title = "Chapter $index",
        source = "json"
    )
}
