package app.hushcast.automute

import app.hushcast.automute.dsp.MuteActionMode
import app.hushcast.automute.engine.Word
import app.hushcast.automute.matcher.MuteRule
import app.hushcast.automute.matcher.PresetDictionaries
import app.hushcast.automute.matcher.WordMatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WordMatcherTest {

    @Test
    fun matchesMaskedProfanityAndPluralsWithPadding() {
        val words = listOf(
            Word(1.0, 1.3, " What"),
            Word(1.3, 1.6, " the"),
            Word(1.6, 2.0, " f***"),
            Word(2.0, 2.3, " is"),
            Word(2.3, 2.7, " going"),
            Word(5.0, 5.4, " fucks")
        )
        val rules = PresetDictionaries.profanityRules(MuteActionMode.HARD_SILENCE)
        val windows = WordMatcher.matchWords(words, rules, prePadMs = 80L, postPadMs = 100L)

        assertEquals(2, windows.size)
        assertEquals(1520L, windows[0].startMs)
        assertEquals(2100L, windows[0].endMs)
        assertEquals(4920L, windows[1].startMs)
        assertEquals(5500L, windows[1].endMs)
    }

    @Test
    fun matchesCompoundPhrasesAndRegex() {
        val words = listOf(
            Word(0.5, 0.8, " This"),
            Word(0.8, 1.1, " is"),
            Word(1.1, 1.5, " brand"),
            Word(1.5, 1.9, " name"),
            Word(3.0, 3.5, " sponsor123")
        )
        val rules = listOf(
            MuteRule(id = 1, pattern = "brand name", actionMode = MuteActionMode.VOLUME_DUCK),
            MuteRule(id = 2, pattern = "^sponsor\\d+$", isRegex = true, actionMode = MuteActionMode.SAMPLE_DROP_MICRO_SKIP)
        )
        val windows = WordMatcher.matchWords(words, rules, prePadMs = 50L, postPadMs = 50L)

        assertEquals(2, windows.size)
        assertTrue(windows.any { it.actionMode == MuteActionMode.VOLUME_DUCK && it.word == "brand name" })
        assertTrue(windows.any { it.actionMode == MuteActionMode.SAMPLE_DROP_MICRO_SKIP && it.word == "sponsor123" })
    }
}
