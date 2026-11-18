package app.hushcast.automute.worker

import androidx.work.workDataOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class EpisodeAnalysisWorkerInputTest {

    @Test
    fun `readFeedTranscriptFromInput reads KEY_FEED_TRANSCRIPT_PATH and deletes temp file`() {
        val tempFile = File.createTempFile("hushcast_tx_test_", ".srt")
        tempFile.writeText("1\n00:00:01,000 --> 00:00:03,000\nHello world\n")

        val data = workDataOf(
            EpisodeAnalysisWorker.KEY_FEED_TRANSCRIPT_PATH to tempFile.absolutePath
        )

        val read = EpisodeAnalysisWorker.readFeedTranscriptFromInput(data, deleteTempFile = true)
        assertEquals("1\n00:00:01,000 --> 00:00:03,000\nHello world\n", read)
        assertFalse("Temp transcript file should be deleted after reading", tempFile.exists())
    }

    @Test
    fun `readFeedTranscriptFromInput prefers inline KEY_FEED_TRANSCRIPT_RAW when present`() {
        val data = workDataOf(
            EpisodeAnalysisWorker.KEY_FEED_TRANSCRIPT_RAW to "WEBVTT\n\n00:00:01.000 --> 00:00:02.000\nHi\n"
        )
        val read = EpisodeAnalysisWorker.readFeedTranscriptFromInput(data, deleteTempFile = false)
        assertEquals("WEBVTT\n\n00:00:01.000 --> 00:00:02.000\nHi\n", read)
    }

    @Test
    fun `readFeedTranscriptFromInput returns null when path is missing`() {
        val data = workDataOf(
            EpisodeAnalysisWorker.KEY_FEED_TRANSCRIPT_PATH to "/nonexistent/path/transcript.txt"
        )
        assertNull(EpisodeAnalysisWorker.readFeedTranscriptFromInput(data, deleteTempFile = true))
    }
}
