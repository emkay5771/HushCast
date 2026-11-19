package app.hushcast.automute.worker

import androidx.work.ExistingWorkPolicy
import androidx.work.WorkInfo
import app.hushcast.automute.dsp.MuteActionMode
import app.hushcast.automute.engine.AnalysisThrottle
import app.hushcast.automute.pipeline.AutoMuteAnalyzer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AnalyzeNowPolicyTest {
    private val sig = EpisodeAnalysisWorker.requestSignature(
        "/data/ep.mp3", false, MuteActionMode.HARD_SILENCE, EpisodeAnalysisWorker.CONSTRAINTS_NONE
    )
    private val chargingSig = EpisodeAnalysisWorker.requestSignature(
        "/data/ep.mp3", false, MuteActionMode.HARD_SILENCE, EpisodeAnalysisWorker.CONSTRAINTS_CHARGING
    )
    private fun tags(vararg t: String) = setOf("hushcast_analysis_queue", "episode:1", *t)

    @Test
    fun `identical pending or running request is kept`() {
        assertEquals(ExistingWorkPolicy.KEEP, EpisodeAnalysisWorker.analyzeNowPolicy(listOf(WorkInfo.State.RUNNING to tags(sig)), sig, force = false))
        assertEquals(ExistingWorkPolicy.KEEP, EpisodeAnalysisWorker.analyzeNowPolicy(listOf(WorkInfo.State.ENQUEUED to tags(sig)), sig, force = false))
        assertEquals(ExistingWorkPolicy.KEEP, EpisodeAnalysisWorker.analyzeNowPolicy(emptyList(), sig, force = false))
        // Finished work doesn't count.
        assertEquals(ExistingWorkPolicy.KEEP, EpisodeAnalysisWorker.analyzeNowPolicy(listOf(WorkInfo.State.SUCCEEDED to tags(chargingSig)), sig, force = false))
    }

    @Test
    fun `different or unsigned pending request is replaced, and force always replaces`() {
        // Auto-download's charging-only job vs. a tap that can run now.
        assertEquals(ExistingWorkPolicy.REPLACE, EpisodeAnalysisWorker.analyzeNowPolicy(listOf(WorkInfo.State.ENQUEUED to tags(chargingSig)), sig, force = false))
        // Queued by an older version, without a signature.
        assertEquals(ExistingWorkPolicy.REPLACE, EpisodeAnalysisWorker.analyzeNowPolicy(listOf(WorkInfo.State.BLOCKED to tags()), sig, force = false))
        assertEquals(ExistingWorkPolicy.REPLACE, EpisodeAnalysisWorker.analyzeNowPolicy(listOf(WorkInfo.State.RUNNING to tags(sig)), sig, force = true))
    }

    @Test
    fun `signature covers path, quick mode, action mode and constraints`() {
        val base = EpisodeAnalysisWorker.requestSignature("/a.mp3", false, MuteActionMode.BEEP, "none")
        assertNotEquals(base, EpisodeAnalysisWorker.requestSignature("/b.mp3", false, MuteActionMode.BEEP, "none"))
        assertNotEquals(base, EpisodeAnalysisWorker.requestSignature("/a.mp3", true, MuteActionMode.BEEP, "none"))
        assertNotEquals(base, EpisodeAnalysisWorker.requestSignature("/a.mp3", false, MuteActionMode.HARD_SILENCE, "none"))
        assertNotEquals(base, EpisodeAnalysisWorker.requestSignature("/a.mp3", false, MuteActionMode.BEEP, "charging"))
        assertTrue(base.startsWith(EpisodeAnalysisWorker.SIGNATURE_TAG_PREFIX))
    }

    @Test
    fun `thread count halves when warm or playing and never drops below one`() {
        assertEquals(4, AnalysisThrottle.threadsFor(4, AnalysisThrottle.Heat.NORMAL, playing = false))
        assertEquals(2, AnalysisThrottle.threadsFor(4, AnalysisThrottle.Heat.NORMAL, playing = true))
        assertEquals(2, AnalysisThrottle.threadsFor(4, AnalysisThrottle.Heat.WARM, playing = false))
        assertEquals(1, AnalysisThrottle.threadsFor(4, AnalysisThrottle.Heat.WARM, playing = true))
        assertEquals(1, AnalysisThrottle.threadsFor(4, AnalysisThrottle.Heat.HOT, playing = false))
        assertEquals(1, AnalysisThrottle.threadsFor(2, AnalysisThrottle.Heat.HOT, playing = true))
    }

    @Test
    fun `checkpoint keys carry the pipeline version and share the sha prefix`() {
        val key = AutoMuteAnalyzer.checkpointKey("abc", tinyModel = false)
        assertEquals("abc:v${AutoMuteAnalyzer.CURRENT_PIPELINE_VERSION}:base:dtw", key)
        assertTrue(AutoMuteAnalyzer.checkpointKey("abc", tinyModel = true).startsWith("abc:"))
    }
}
