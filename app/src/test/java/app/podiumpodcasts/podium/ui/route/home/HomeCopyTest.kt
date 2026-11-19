package app.podiumpodcasts.podium.ui.route.home

import app.podiumpodcasts.podium.ui.component.model.episode.episodeRowStateDescription
import app.podiumpodcasts.podium.ui.dialog.deleteConfirmationBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class HomeCopyTest {

    @Test
    fun `mark all as played names the count`() {
        assertEquals(
            "This marks 3 unplayed episodes of Radiolab as played.",
            markAllPlayedConfirmText("Radiolab", 3)
        )
        assertEquals(
            "This marks 1 unplayed episode of Radiolab as played.",
            markAllPlayedConfirmText("Radiolab", 1)
        )
        assertEquals(
            "This marks every episode of Radiolab as played.",
            markAllPlayedConfirmText("Radiolab", 0)
        )
    }

    @Test
    fun `unsubscribe names the count`() {
        assertEquals(
            "You have 2 unplayed episodes of this podcast. New episodes will stop arriving. Downloads and history are kept.",
            unsubscribeConfirmText(2)
        )
        assertEquals(
            "You have no unplayed episodes of this podcast. New episodes will stop arriving. Downloads and history are kept.",
            unsubscribeConfirmText(0)
        )
    }

    @Test
    fun `copy says played, not listened`() {
        listOf(
            markAllPlayedConfirmText("X", 4),
            unsubscribeConfirmText(4)
        ).forEach { assertFalse(it, it.contains("listened", ignoreCase = true)) }
    }

    @Test
    fun `episode row state description`() {
        assertEquals(
            "Played, downloaded, HushCast ready",
            episodeRowStateDescription(
                isPlayed = true, hasProgress = false, isInQueue = false,
                isDownloaded = true, isDownloading = false, downloadFailed = false,
                isProcessed = true, isAnalyzing = false, isPlayingNow = false
            )
        )
        assertEquals(
            "In progress, in Up Next, downloading",
            episodeRowStateDescription(
                isPlayed = false, hasProgress = true, isInQueue = true,
                isDownloaded = false, isDownloading = true, downloadFailed = false,
                isProcessed = false, isAnalyzing = false, isPlayingNow = false
            )
        )
        assertEquals(
            "Playing, HushCast processing",
            episodeRowStateDescription(
                isPlayed = false, hasProgress = true, isInQueue = false,
                isDownloaded = false, isDownloading = false, downloadFailed = false,
                isProcessed = false, isAnalyzing = true, isPlayingNow = true
            )
        )
        assertEquals(
            "Unplayed",
            episodeRowStateDescription(
                isPlayed = false, hasProgress = false, isInQueue = false,
                isDownloaded = false, isDownloading = false, downloadFailed = false,
                isProcessed = false, isAnalyzing = false, isPlayingNow = false
            )
        )
    }

    @Test
    fun `delete dialog body`() {
        assertEquals("“My list” will be deleted. All data will be lost.", deleteConfirmationBody("My list", "All data will be lost."))
        assertEquals("Extra only.", deleteConfirmationBody(" ", "Extra only."))
        assertEquals("“Ep” will be deleted.", deleteConfirmationBody("Ep", ""))
    }
}
