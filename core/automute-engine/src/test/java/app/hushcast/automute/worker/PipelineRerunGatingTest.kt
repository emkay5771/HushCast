package app.hushcast.automute.worker
import app.hushcast.automute.dsp.ReadinessBadge
import app.hushcast.automute.pipeline.AutoMuteAnalyzer
import app.hushcast.automute.storage.EpisodeAnalysisStatusEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PipelineRerunGatingTest {

    @Test
    fun `finds only READY episodes with outdated pipelineVersion`() {
        val current = AutoMuteAnalyzer.CURRENT_PIPELINE_VERSION
        val statuses = listOf(
            EpisodeAnalysisStatusEntity(
                episodeId = "old_ready",
                fileSha256 = "sha_old",
                badge = ReadinessBadge.READY.name,
                pipelineVersion = current - 1
            ),
            EpisodeAnalysisStatusEntity(
                episodeId = "current_ready",
                fileSha256 = "sha_cur",
                badge = ReadinessBadge.READY.name,
                pipelineVersion = current
            ),
            EpisodeAnalysisStatusEntity(
                episodeId = "old_not_processed",
                fileSha256 = "sha_np",
                badge = ReadinessBadge.NOT_PROCESSED.name,
                pipelineVersion = current - 1
            )
        )

        val stale = EpisodeAnalysisWorker.findStaleEpisodesForRerun(statuses, current)
        assertEquals(listOf("old_ready"), stale.map { it.episodeId })
    }

    @Test
    fun `re-run is gated on charging plus unmetered wifi or configured worker`() {
        assertTrue(EpisodeAnalysisWorker.isRerunEligible(isCharging = true, isUnmeteredWifi = true, isWorkerConfigured = false))
        assertTrue(EpisodeAnalysisWorker.isRerunEligible(isCharging = false, isUnmeteredWifi = false, isWorkerConfigured = true))
        assertFalse(EpisodeAnalysisWorker.isRerunEligible(isCharging = false, isUnmeteredWifi = true, isWorkerConfigured = false))
        assertFalse(EpisodeAnalysisWorker.isRerunEligible(isCharging = true, isUnmeteredWifi = false, isWorkerConfigured = false))
        assertFalse(EpisodeAnalysisWorker.isRerunEligible(isCharging = false, isUnmeteredWifi = false, isWorkerConfigured = false))
    }
}

