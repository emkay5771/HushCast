package app.podiumpodcasts.podium.background.work

import app.hushcast.automute.desktop.DesktopHealthResponse
import app.hushcast.automute.desktop.DesktopWorkerClient
import app.hushcast.automute.desktop.DesktopWorkerStatusText
import app.hushcast.automute.pipeline.AutoMuteAnalyzer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkerStatusTextTest {

    @Test
    fun `queued jobs get a label only after 10 seconds`() {
        assertNull(DesktopWorkerStatusText.queuedLabel(queuedForMs = 9_999, isCloud = true))
        assertEquals("Starting the cloud GPU…", DesktopWorkerStatusText.queuedLabel(10_000, isCloud = true))
        assertEquals("Waiting for the worker…", DesktopWorkerStatusText.queuedLabel(10_000, isCloud = false))
    }

    @Test
    fun `queue position is shown when known`() {
        assertEquals(
            "Waiting for the worker… (2 ahead)",
            DesktopWorkerStatusText.queuedLabel(15_000, isCloud = false, jobQueuePosition = 2, healthQueuePosition = 5)
        )
        assertEquals(
            "Starting the cloud GPU… (3 in queue)",
            DesktopWorkerStatusText.queuedLabel(15_000, isCloud = true, jobQueuePosition = 0, healthQueuePosition = 3)
        )
        assertEquals(
            "Starting the cloud GPU…",
            DesktopWorkerStatusText.queuedLabel(15_000, isCloud = true, jobQueuePosition = 0, healthQueuePosition = 0)
        )
    }

    @Test
    fun `health tells modal from mac and old workers from current ones`() {
        assertTrue(DesktopHealthResponse(deployment = "modal").isCloud)
        assertFalse(DesktopHealthResponse(deployment = "local").isCloud)
        val current = DesktopHealthResponse(
            apiVersion = DesktopWorkerClient.MIN_WORKER_API_VERSION,
            pipelineVersion = AutoMuteAnalyzer.CURRENT_PIPELINE_VERSION
        )
        assertFalse(current.isOutdated())
        assertTrue(current.copy(pipelineVersion = AutoMuteAnalyzer.CURRENT_PIPELINE_VERSION - 1).isOutdated())
        assertTrue(current.copy(apiVersion = DesktopWorkerClient.MIN_WORKER_API_VERSION - 1).isOutdated())
        assertNull(DesktopHealthResponse().queuePosition)
    }

    @Test
    fun `health without queuePosition still decodes`() {
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val parsed = json.decodeFromString(
            DesktopHealthResponse.serializer(),
            """{"status":"ok","apiVersion":2,"pipelineVersion":6,"deployment":"modal","somethingNew":1}"""
        )
        assertNull(parsed.queuePosition)
        val withQueue = json.decodeFromString(DesktopHealthResponse.serializer(), """{"queuePosition":4}""")
        assertEquals(4, withQueue.queuePosition)
    }

    @Test
    fun `worker failure reasons are shortened for the row`() {
        assertEquals(
            "Could not download audio (HTTP 403)",
            DesktopWorkerStatusText.shortReason("Desktop worker failed: RuntimeError: could not download audio (HTTP 403)\nTraceback ...")
        )
        assertEquals("CUDA out of memory", DesktopWorkerStatusText.shortReason("torch.cuda.OutOfMemoryError: CUDA out of memory"))
        assertNull(DesktopWorkerStatusText.shortReason("   \n  "))
        assertNull(DesktopWorkerStatusText.shortReason(null))
        val long = DesktopWorkerStatusText.shortReason("a".repeat(200), maxLen = 40)!!
        assertEquals(40, long.length)
        assertTrue(long.endsWith("…"))
    }
}
