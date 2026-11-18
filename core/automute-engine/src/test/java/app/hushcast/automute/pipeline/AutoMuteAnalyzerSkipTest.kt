package app.hushcast.automute.pipeline

import app.hushcast.automute.dsp.ReadinessBadge
import app.hushcast.automute.storage.EpisodeAnalysisStatusEntity
import app.hushcast.automute.storage.EpisodeWordIndexEntity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoMuteAnalyzerSkipTest {

    @Test
    fun `skips full analysis when READY at current pipeline version`() {
        val sha = "abc123"
        val index = EpisodeWordIndexEntity(
            fileSha256 = sha,
            episodeId = "ep-1",
            durationSec = 120.0,
            wordCount = 100,
            compressedBlob = byteArrayOf(1, 2, 3)
        )
        val status = EpisodeAnalysisStatusEntity(
            episodeId = "ep-1",
            fileSha256 = sha,
            badge = ReadinessBadge.READY.name,
            progressPercent = 100,
            stageLabel = "Ready",
            pipelineVersion = AutoMuteAnalyzer.CURRENT_PIPELINE_VERSION
        )

        assertTrue(
            AutoMuteAnalyzer.shouldSkipFullAnalysis(
                existingIndex = index,
                existingStatus = status,
                sha256 = sha,
                quickWordMuteOnly = false,
                hasAdSegments = true
            )
        )
    }

    @Test
    fun `does not skip when status is ANALYZING or pipelineVersion is older`() {
        val sha = "abc123"
        val index = EpisodeWordIndexEntity(
            fileSha256 = sha,
            episodeId = "ep-1",
            durationSec = 120.0,
            wordCount = 100,
            compressedBlob = byteArrayOf(1, 2, 3)
        )
        val analyzingStatus = EpisodeAnalysisStatusEntity(
            episodeId = "ep-1",
            fileSha256 = sha,
            badge = ReadinessBadge.ANALYZING.name,
            progressPercent = 2,
            stageLabel = "Verifying audio file",
            pipelineVersion = AutoMuteAnalyzer.CURRENT_PIPELINE_VERSION
        )
        val oldVersionStatus = analyzingStatus.copy(
            badge = ReadinessBadge.READY.name,
            progressPercent = 100,
            pipelineVersion = AutoMuteAnalyzer.CURRENT_PIPELINE_VERSION - 1
        )

        assertFalse(
            AutoMuteAnalyzer.shouldSkipFullAnalysis(
                existingIndex = index,
                existingStatus = analyzingStatus,
                sha256 = sha,
                quickWordMuteOnly = false,
                hasAdSegments = true
            )
        )
        assertFalse(
            AutoMuteAnalyzer.shouldSkipFullAnalysis(
                existingIndex = index,
                existingStatus = oldVersionStatus,
                sha256 = sha,
                quickWordMuteOnly = false,
                hasAdSegments = true
            )
        )
    }
}
