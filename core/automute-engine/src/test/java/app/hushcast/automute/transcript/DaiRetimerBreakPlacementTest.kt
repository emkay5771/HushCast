package app.hushcast.automute.transcript

import app.hushcast.automute.engine.Segment
import app.hushcast.automute.engine.TranscriptionOutput
import app.hushcast.automute.engine.Word
import app.hushcast.automute.matcher.MuteRule
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

class DaiRetimerBreakPlacementTest {

    private fun anchor(center: Double, shift: Double, idx: Int) =
        DaiRetimer.AnchorMatch(downloadCenterSec = center, shiftSec = shift, matchedCount = 12, firstWordIndex = idx, lastWordIndex = idx)

    @Test
    fun `synthetic 90s ad break places split at last matched word and shifts post-break words within 100ms`() {
        // 200 transcript words, 1 word/sec: 0..200s
        val refWords = (0 until 200).map { idx ->
            Word(start = idx.toDouble(), end = idx + 0.8, text = " w$idx")
        }
        // Pre-ad: transcript 0..100s -> audio 0..100s (shift 0s); 90 s ad at audio 100..190s;
        // post-ad: transcript 100..200s -> audio 190..290s (shift +90s)
        val anchors = listOf(
            anchor(10.0, 0.0, 10),
            anchor(99.0, 0.0, 99),
            anchor(190.0, 90.0, 100),
            anchor(270.0, 90.0, 180)
        )

        val stretches = DaiRetimer.buildStretches(anchors, refWords)
        assertEquals(2, stretches.size)

        // Break must be at the last matched word (99 -> end 99.8s = 99800ms), NOT a midpoint
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

    @Test
    fun `unheard words between the anchors go to the side of the longest pause`() {
        // Words every 0.5 s with a 1.3 s pause after word 50 (where the ad was inserted).
        val refWords = (0 until 100).map { i ->
            val t = i * 0.5 + if (i > 50) 1.2 else 0.0
            Word(t, t + 0.4, " w$i")
        }
        // Word 50 was never heard: the last word heard before the ad is 49, the first after is 51.
        val anchors = listOf(
            DaiRetimer.AnchorMatch(10.0, 0.0, 12, firstWordIndex = 5, lastWordIndex = 49),
            DaiRetimer.AnchorMatch(120.0, 60.0, 12, firstWordIndex = 51, lastWordIndex = 90)
        )
        val stretches = DaiRetimer.buildStretches(anchors, refWords)
        assertEquals(2, stretches.size)
        assertEquals((refWords[50].end * 1000).toLong(), stretches[0].srcEndMs)
        assertEquals((refWords[51].start * 1000).toLong(), stretches[1].srcStartMs)
    }

    /**
     * A synthetic DAI episode: [ref] is the publisher transcript; [ads] are (insert-after-word,
     * length) pairs.  [clips] plays Whisper: it "hears" the transcript words at their download
     * times (+-20 ms), misses every [dropEvery]-th word, and hears ad copy (some of it common
     * transcript words) inside the ads.
     */
    private class FakeEpisode(
        val ref: List<Word>,
        ads: List<Pair<Int, Double>>,
        val dropEvery: Int = 0,
        val dropWords: Set<Int> = emptySet()
    ) {
        /** (insert point in source seconds, length) */
        val inserts: List<Pair<Double, Double>> = ads.map { (afterIdx, len) ->
            ((ref[afterIdx].end + ref[afterIdx + 1].start) / 2.0) to len
        }

        fun srcToDl(t: Double): Double = t + inserts.filter { t >= it.first }.sumOf { it.second }

        val adSpans: List<Pair<Double, Double>> = inserts.mapIndexed { k, (at, len) ->
            val start = at + inserts.take(k).sumOf { it.second }
            start to start + len
        }
        val durationSec = srcToDl(ref.last().end) + 4.0
        val calls = ArrayList<Pair<Double, Double>>()

        private fun jitter(i: Int) = (((i * 7919) % 41) - 20) / 1000.0

        val clips = ClipTranscriber { s, e ->
            synchronized(calls) { calls.add(s to e) }
            val out = ArrayList<Word>()
            ref.forEachIndexed { i, w ->
                if (i in dropWords || (dropEvery > 0 && i % dropEvery == dropEvery - 1)) return@forEachIndexed
                val t = srcToDl(w.start) + jitter(i)
                if (t >= s && t < e) out.add(Word(t - s, t - s + (w.end - w.start), w.text))
            }
            adSpans.forEachIndexed { k, (aStart, aEnd) ->
                var t = aStart + 0.2
                var n = 0
                while (t < aEnd - 0.2) {
                    if (t >= s && t < e) {
                        // Every 5th ad word is a word the transcript also uses (upper case here
                        // only so the test can tell it apart; matching ignores case).
                        val text = if (n % 5 == 4) ref[(n * 31 + k * 7) % ref.size].text.uppercase() else " ad${k}x$n"
                        out.add(Word(t - s, t - s + 0.3, text))
                    }
                    t += 0.45
                    n++
                }
            }
            TranscriptionOutput(emptyList(), out.sortedBy { it.start })
        }

        fun clipInsideAnAd(): Boolean = calls.any { (s, e) -> adSpans.any { s >= it.first && e <= it.second } }
    }

    /** Words every ~0.5 s from a 400-word vocabulary, with a 1.2 s pause after each index in [pausesAfter]. */
    private fun transcript(n: Int, pausesAfter: Set<Int>, seed: Int = 7): List<Word> {
        val rnd = Random(seed)
        var t = 3.0
        return (0 until n).map { i ->
            val w = Word(t, t + 0.4, " v${rnd.nextInt(400)}")
            t += 0.5
            if (i in pausesAfter) t += 1.2
            w
        }
    }

    private fun feedOf(words: List<Word>) = ParsedTranscript(
        format = TranscriptFormat.PODCASTING_20_JSON,
        segments = words.chunked(12).map { c -> Segment(c.first().start, c.last().end, c.joinToString("") { it.text }.trim()) },
        words = words,
        hasWordLevelTimings = true
    )

    private fun assertAllWordsWithin100ms(ep: FakeEpisode, map: OffsetMap) {
        for ((i, w) in ep.ref.withIndex()) {
            val got = map.shiftTimeSec(w.start)
            val want = ep.srcToDl(w.start)
            assertTrue("word $i at ${w.start}s mapped to $got, expected $want", abs(got - want) <= 0.100)
        }
    }

    @Test
    fun `probes that land inside the ad still find both edges and every word is within 100ms`() = runBlocking {
        val ref = transcript(3000, pausesAfter = setOf(900, 2100))
        // 120 s and 60 s mid-rolls; Whisper misses every 17th word, including word 900, the last
        // word before the first ad.
        val ep = FakeEpisode(ref, ads = listOf(900 to 120.0, 2100 to 60.0), dropEvery = 17)
        assertTrue(900 % 17 == 16)

        val result = DaiRetimer.retimeTranscript(ep.durationSec, feedOf(ref), ep.clips, enabledRules = emptyList(), log = ::println)
        assertNotNull("retiming gave up", result)
        result!!

        assertTrue("no probe clip landed inside an ad", ep.clipInsideAnAd())
        println("clips transcribed: ${ep.calls.size} (${"%.0f".format(ep.calls.sumOf { it.second - it.first })} s of audio)")
        assertEquals(3, result.offsetMap.stretches.size)
        assertAllWordsWithin100ms(ep, result.offsetMap)

        // The uncovered gaps are the ads.
        assertEquals(2, result.adCandidateSpansSec.size)
        for ((k, gap) in result.adCandidateSpansSec.withIndex()) {
            val (aStart, aEnd) = ep.adSpans[k]
            assertTrue("gap $gap vs ad ${ep.adSpans[k]}", abs(gap.first - aStart) < 1.0 && abs(gap.second - aEnd) < 1.0)
        }
    }

    @Test
    fun `a long ad that swallows the midpoint and quarter probes is still bracketed`() = runBlocking {
        val ref = transcript(2600, pausesAfter = setOf(1200))
        val ep = FakeEpisode(ref, ads = listOf(1200 to 330.0), dropEvery = 23)
        val (aStart, aEnd) = ep.adSpans[0]
        val result = DaiRetimer.retimeTranscript(ep.durationSec, feedOf(ref), ep.clips, enabledRules = emptyList(), log = ::println)
        assertNotNull("retiming gave up", result)
        result!!
        // Several probes (not just one) landed inside the ad on the way to its edges.
        val inside = ep.calls.count { (s, e) -> s >= aStart && e <= aEnd }
        assertTrue("only $inside clips inside the ad", inside >= 3)
        println("clips transcribed: ${ep.calls.size} (${"%.0f".format(ep.calls.sumOf { it.second - it.first })} s of audio)")
        assertEquals(2, result.offsetMap.stretches.size)
        assertAllWordsWithin100ms(ep, result.offsetMap)
    }

    @Test
    fun `SRT cues get Whisper times only where a rule matches, with no duplicated words`() = runBlocking {
        // Uneven speech, so cue words spread evenly are visibly off.
        val rnd = Random(11)
        var t = 2.0
        val ref = (0 until 2400).map { i ->
            val dur = 0.15 + rnd.nextDouble() * 0.55
            val w = Word(t, t + dur, " v${rnd.nextInt(400)}")
            t += dur + 0.02 + rnd.nextDouble() * 0.28
            if (i == 1100) t += 1.5
            w
        }
        val cues = ArrayList<Segment>()
        var i = 0
        while (i < ref.size) {
            val n = 6 + (i % 5)
            val c = ref.subList(i, minOf(ref.size, i + n))
            cues.add(Segment(c.first().start, c.last().end, c.joinToString("") { it.text }.trim()))
            i += n
        }
        val feed = ParsedTranscript(TranscriptFormat.SRT, cues, emptyList(), hasWordLevelTimings = false)
        val ep = FakeEpisode(ref, ads = listOf(1100 to 90.0))
        val ruleToken = ref[1500].text.trim()
        val rules = listOf(MuteRule(id = 1, pattern = ruleToken, matchPlurals = false))

        val result = DaiRetimer.retimeTranscript(ep.durationSec, feed, ep.clips, enabledRules = rules, log = ::println)
        assertNotNull("retiming gave up", result)
        result!!

        // Every cue word is there exactly once (ad copy is "adNxM" or upper case).
        val transcriptWords = result.words.filter { it.text.trim().startsWith("v") }
        assertEquals("cue words duplicated or lost", ref.size, transcriptWords.size)
        assertEquals(ref.map { it.text }.sorted(), transcriptWords.map { it.text }.sorted())

        // Every occurrence of the rule word was heard: within 100 ms of where it is said.
        val truth = ref.filter { it.text.trim() == ruleToken }.map { ep.srcToDl(it.start) }
        val got = transcriptWords.filter { it.text.trim() == ruleToken }.map { it.start }
        assertEquals(truth.size, got.size)
        for (g in got) assertTrue("rule word at $g, truth $truth", truth.any { abs(it - g) <= 0.100 })

        // Every other cue is reported as approximate (the one the ad cuts in two, as two spans);
        // the rule word's cues are not.
        val ruleCues = cues.count { c -> c.text.split(" ").any { it == ruleToken } }
        assertTrue(ruleCues >= 1)
        val cutCues = cues.count { c -> c.start < ref[1100].end && c.end > ref[1101].start }
        assertEquals(cues.size - ruleCues + cutCues, result.approximateSpans.size)
        assertTrue(result.approximateSpans.none { it.attempted })
        // No cue is stretched across the ad (evenly spread words may overrun its edge a little).
        val (aStart, aEnd) = ep.adSpans[0]
        assertTrue(result.approximateSpans.none { minOf(it.end, aEnd) - maxOf(it.start, aStart) > 2.0 })
    }
}
