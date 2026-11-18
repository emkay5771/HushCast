package app.hushcast.automute.transcript
import app.hushcast.automute.engine.Word
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class DaiRetimerBreakPlacementTest {

    @Test
    fun `synthetic 90s ad break places split at last matched word and shifts post-break words within 100ms`() {
        // 200 transcript words, 1 word/sec: 0..200s
        val refWords = (0 until 200).map { idx ->
            Word(start = idx.toDouble(), end = idx + 0.8, text = " w$idx")
        }
        // Synthetic anchors:
        // Pre-ad: transcript 0..100s -> audio 0..100s (shift 0s)
        // 90s DAI ad inserted at audio 100..190s (when bisection clip lands inside ad, no match is found,
        // so neighboring anchors remain at transcriptWordIndex=99 and transcriptWordIndex=100)
        // Post-ad: transcript 100..200s -> audio 190..290s (shift +90s)
        val anchors = listOf(
            DaiRetimer.AnchorMatch(downloadCenterSec = 10.0, transcriptCenterSec = 10.0, shiftSec = 0.0, matchedCount = 12, transcriptWordIndex = 10),
            DaiRetimer.AnchorMatch(downloadCenterSec = 99.0, transcriptCenterSec = 99.0, shiftSec = 0.0, matchedCount = 12, transcriptWordIndex = 99),
            DaiRetimer.AnchorMatch(downloadCenterSec = 190.0, transcriptCenterSec = 100.0, shiftSec = 90.0, matchedCount = 12, transcriptWordIndex = 100),
            DaiRetimer.AnchorMatch(downloadCenterSec = 270.0, transcriptCenterSec = 180.0, shiftSec = 90.0, matchedCount = 12, transcriptWordIndex = 180)
        )

        val stretches = DaiRetimer.buildStretches(anchors, refWords)
        assertEquals(2, stretches.size)

        // Break must be at prev.transcriptWordIndex (99 -> end 99.8s = 99800ms), NOT midpoint
        assertEquals(99_800L, stretches[0].srcEndMs)
        assertEquals(100_000L, stretches[1].srcStartMs)

        // Every word after the break (index 100..199) must land within 100ms of true audio time (t + 90s)
        val offsetMap = OffsetMap(stretches)
        for (i in 100 until refWords.size) {
            val shiftedSec = offsetMap.shiftTimeMs((refWords[i].start * 1000.0).toLong()) / 1000.0
            val expectedSec = refWords[i].start + 90.0
            assertTrue(
                "Word $i shifted to $shiftedSec, expected $expectedSec",
                abs(shiftedSec - expectedSec) <= 0.100
            )
        }
    }
}

