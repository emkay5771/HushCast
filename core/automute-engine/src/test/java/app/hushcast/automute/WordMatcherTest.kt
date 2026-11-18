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
        // Adjacent 300ms words ("the" and "is") bound overlap to max(15, 300/4) = 75ms
        assertEquals(1525L, windows[0].startMs)
        assertEquals(2075L, windows[0].endMs)
        // Isolated word ("fucks") with >2s gap before and no word after gets full padding
        assertEquals(4920L, windows[1].startMs)
        assertEquals(5500L, windows[1].endMs)
    }

    @Test
    fun boundsPaddingAgainstShortAdjacentWords() {
        val words = listOf(
            Word(1.00, 1.12, " a"),      // 120ms -> maxOverlap = 30ms
            Word(1.12, 1.40, " fuck"),
            Word(1.40, 1.48, " it")      // 80ms  -> maxOverlap = 20ms
        )
        val rules = PresetDictionaries.profanityRules(MuteActionMode.HARD_SILENCE)
        val windows = WordMatcher.matchWords(words, rules, prePadMs = 80L, postPadMs = 100L)

        assertEquals(1, windows.size)
        assertEquals(1090L, windows[0].startMs) // 1120 - 30
        assertEquals(1420L, windows[0].endMs)   // 1400 + 20
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
