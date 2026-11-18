package app.hushcast.automute.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopWorkerContractTest {

    @Test
    fun `decodes worker job response when optional fields are explicit null`() {
        val raw = """
            {
              "jobId": "job-123",
              "episodeId": "ep-1",
              "state": "QUEUED",
              "progressPercent": 0,
              "stageLabel": "Queued",
              "stageProgress": null,
              "fileSha256": null,
              "errorMessage": null,
              "errorCode": null,
              "retryable": null,
              "etaSec": null,
              "queuePosition": null
            }
        """.trimIndent()

        val decoded = DesktopWorkerClient.json.decodeFromString(DesktopJobResponse.serializer(), raw)
        assertEquals("job-123", decoded.jobId)
        assertEquals("QUEUED", decoded.state)
        assertEquals(0, decoded.progressPercent)
        assertEquals(0f, decoded.stageProgress, 0.001f)
        assertFalse(decoded.retryable)
        assertEquals(0, decoded.queuePosition)
        assertNull(decoded.fileSha256)
        assertNull(decoded.errorMessage)
    }

    @Test
    fun `encodes job request omitting null optional fields`() {
        val req = DesktopJobRequest(
            episodeId = "ep-1",
            audioUrl = "https://example.com/audio.mp3",
            title = "Test Episode",
            feedChaptersUrl = "https://example.com/chapters.json",
            feedChaptersType = null,
            feedTranscriptUrl = null
        )

        val encoded = DesktopWorkerClient.json.encodeToString(DesktopJobRequest.serializer(), req)
        assertFalse("Null fields should be omitted: $encoded", encoded.contains("feedChaptersType"))
        assertFalse("Null fields should be omitted: $encoded", encoded.contains("feedTranscriptUrl"))
        assertTrue(encoded.contains("feedChaptersUrl"))
    }
}
