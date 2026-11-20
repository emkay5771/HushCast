package app.hushcast.automute

import app.hushcast.automute.dsp.MuteActionMode
import app.hushcast.automute.engine.Word
import app.hushcast.automute.matcher.BuiltInFilters
import app.hushcast.automute.matcher.FilterSettings
import app.hushcast.automute.matcher.MuteRule
import app.hushcast.automute.matcher.ProfanityLevel
import app.hushcast.automute.matcher.PresetDictionaries
import app.hushcast.automute.matcher.WordMatcher
import org.junit.Assert.assertEquals
import org.junit.Test

/** Word forms beyond exact matches (1.0.45): inside longer words, compounds, masked, names. */
class WordFormsTest {

    private val profanity = PresetDictionaries.profanityRules(MuteActionMode.HARD_SILENCE)
    private val explicit = PresetDictionaries.explicitRules(MuteActionMode.HARD_SILENCE)
    private val both = profanity + explicit

    /** The words of [text] that get muted, spaced so that no two windows merge. */
    private fun muted(text: String, rules: List<MuteRule> = both): List<String> {
        val words = text.split(" ").filter { it.isNotBlank() }
            .mapIndexed { i, t -> Word(i * 2.0, i * 2.0 + 0.4, " $t") }
        return WordMatcher.matchWords(words, rules).map { it.word }
    }

    private fun assertAllMuted(vararg words: String) {
        for (w in words) assertEquals("\"$w\" should be muted", listOf(w), muted(w))
    }

    private fun assertNoneMuted(vararg words: String) {
        for (w in words) assertEquals("\"$w\" should not be muted", emptyList<String>(), muted(w))
    }

    @Test
    fun `list words inside longer words are muted`() {
        assertAllMuted(
            "enshittified", "enshittification", "shitified", "shitshow", "shithole", "horseshit",
            "batshit", "bullshitting", "shittier", "motherfucking", "fuckin", "fuckin'", "clusterfuck",
            "fuckup", "bitchy", "sonofabitch", "Shit!", "shit's"
        )
    }

    @Test
    fun `innocent words that contain a list word are left alone`() {
        assertNoneMuted(
            // Torah, Hebrew and Bible terms
            "Bereshit", "Bereishit", "B'reishit", "shita", "shitah", "shitos", "shittim", "shituf",
            "Girgashite", "Girgashites", "Cushite", "Kushite", "Elkoshite",
            // names and places
            "Yamashita", "Yamashita's", "Matsushita", "Kinoshita", "Washita", "Shital", "Scunthorpe",
            // other
            "mishit", "mishitting", "shitake", "shiitake"
        )
    }

    @Test
    fun `words that are never matched inside other words stay exact`() {
        assertNoneMuted(
            "class", "passage", "assume", "embassy", "cocktail", "peacock", "Hancock", "cocky",
            "Dickens", "dicker", "damning", "damnation", "condemn", "pussycat", "bastardized",
            "congenital", "invagination", "arteriosclerotic", "Essex", "sextant", "Agapornis"
        )
    }

    @Test
    fun `compounds and plurals of those words are muted`() {
        assertAllMuted(
            "dumbass", "dumbasses", "jackass", "smartass", "badass", "half-assed", "goddamn",
            "goddamned", "dammit", "damnit", "dickhead", "dickheads", "cocksucker", "pussies",
            "bull-shit", "assholes", "bastards"
        )
    }

    @Test
    fun `explicit content forms are muted`() {
        assertAllMuted("pornographic", "porno", "masturbating", "orgasmic", "genitalia", "vaginal", "erotica")
    }

    @Test
    fun `any asterisk-masked word is muted, other asterisks are not`() {
        assertAllMuted("f*ck", "f***ing", "motherf***er", "bulls**t", "sh*t", "a**hole", "c*nt")
        assertNoneMuted("*really*", "footnote*", "5*3")
    }

    @Test
    fun `Dick as a name is not muted, the insult is`() {
        assertEquals(emptyList<String>(), muted("Dick Cheney said"))
        assertEquals(emptyList<String>(), muted("by Philip K. Dick"))
        assertEquals(emptyList<String>(), muted("reading Moby Dick today"))
        assertEquals(listOf("dick."), muted("don't be a dick."))
        assertEquals(listOf("Dick"), muted("Dick move, honestly"))
    }

    @Test
    fun `removing a word from the list removes its forms`() {
        // The list also holds masked spellings ("s***", "a**") that stand for the same word.
        val withoutShit = profanity.filter { it.pattern !in setOf("shit", "s***", "s**t") }
        assertEquals(emptyList<String>(), muted("shitshow", withoutShit))
        val withoutAss = profanity.filter { it.pattern !in setOf("ass", "a**") }
        assertEquals(emptyList<String>(), muted("dumbass", withoutAss))
        // Without the profanity list, masked words have no rule to use.
        assertEquals(emptyList<String>(), muted("f*ck", explicit))
    }

    @Test
    fun `crap and its forms are muted at Strict, scrap and the dice game are not`() {
        val strict = BuiltInFilters.rulesFor(FilterSettings(ProfanityLevel.STRICT))
        for (w in listOf("crap", "Crap!", "crappy", "crappier", "crapload", "crapper", "bullcrap", "crapshoot", "hell", "pissed")) {
            assertEquals("\"$w\" should be muted at Strict", listOf(w), muted(w, strict))
        }
        for (w in listOf("craps", "scrap", "scrapped", "scrapbook", "skyscraper", "crappie", "crappies", "hello", "shell", "Michelle")) {
            assertEquals("\"$w\" should not be muted", emptyList<String>(), muted(w, strict))
        }
    }
}
