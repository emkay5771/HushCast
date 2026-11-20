package app.hushcast.automute

import app.hushcast.automute.engine.Word
import app.hushcast.automute.matcher.BuiltInFilters
import app.hushcast.automute.matcher.FilterSettings
import app.hushcast.automute.matcher.PresetDictionaries
import app.hushcast.automute.matcher.ProfanityLevel
import app.hushcast.automute.matcher.WordMatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Built-in filters (1.0.45): profanity levels, explicit, religious, per-podcast, migration. */
class BuiltInFiltersTest {

    /** The words of [text] muted under [settings], spaced so no two windows merge. */
    private fun muted(text: String, settings: FilterSettings): List<String> {
        val words = text.split(" ").filter { it.isNotBlank() }
            .mapIndexed { i, t -> Word(i * 2.0, i * 2.0 + 0.4, " $t") }
        return WordMatcher.matchWords(words, BuiltInFilters.rulesFor(settings)).map { it.word }
    }

    private val sample = "fuck shit damn crap motherfucking bullshitting dumbass hell"

    @Test
    fun `everything is off by default`() {
        assertEquals(emptyList<String>(), muted(sample, FilterSettings()))
        assertTrue(BuiltInFilters.rulesFor(FilterSettings()).isEmpty())
    }

    @Test
    fun `each level adds the next severity`() {
        assertEquals(listOf("fuck", "motherfucking"), muted(sample, FilterSettings(ProfanityLevel.LIGHT)))
        assertEquals(
            listOf("fuck", "shit", "motherfucking", "bullshitting"),
            muted(sample, FilterSettings(ProfanityLevel.STANDARD))
        )
        assertEquals(sample.split(" "), muted(sample, FilterSettings(ProfanityLevel.STRICT)))
    }

    @Test
    fun `explicit content and religious exclamations are their own switches`() {
        val text = "sex pornographic oh my God !"
        assertEquals(emptyList<String>(), muted(text, FilterSettings(ProfanityLevel.STRICT)))
        assertEquals(listOf("sex", "pornographic"), muted(text, FilterSettings(explicit = true)))
        assertEquals(listOf("oh my God"), muted(text, FilterSettings(religious = true)))
    }

    @Test
    fun `religious exclamations are phrases, never God on its own`() {
        val on = FilterSettings(religious = true)
        assertEquals(emptyList<String>(), muted("God is my shepherd and thank God", on))
        assertEquals(listOf("for God's sake"), muted("for God's sake", on))
        assertEquals(listOf("Jesus Christ!"), muted("Jesus Christ!", on))
        assertEquals(listOf("goddamn"), muted("goddamn", on))
    }

    @Test
    fun `masked words are muted only with profanity on`() {
        assertEquals(listOf("f*ck"), muted("f*ck", FilterSettings(ProfanityLevel.LIGHT)))
        assertEquals(emptyList<String>(), muted("f*ck", FilterSettings(explicit = true, religious = true)))
    }

    @Test
    fun `a podcast's own level applies to its episodes only`() {
        val global = FilterSettings(ProfanityLevel.STANDARD, explicit = true)
        val levels = mapOf("https://feeds.example/torah" to ProfanityLevel.LIGHT)
        assertEquals(
            FilterSettings(ProfanityLevel.LIGHT, explicit = true),
            BuiltInFilters.effective(global, levels, "https://feeds.example/torah:ep-1")
        )
        assertEquals(global, BuiltInFilters.effective(global, levels, "https://feeds.example/other:ep-1"))
        // A feed whose URL starts the same way isn't the same podcast.
        assertEquals(global, BuiltInFilters.effective(global, levels, "https://feeds.example/torah2:ep-1"))
    }

    @Test
    fun `built-in ids are stable and never collide with database ids`() {
        val id = BuiltInFilters.idFor("shit", BuiltInFilters.CATEGORY_PROFANITY)!!
        assertTrue(BuiltInFilters.isBuiltInId(id))
        assertFalse(BuiltInFilters.isBuiltInId(1234L))
        assertNull(BuiltInFilters.idFor("brand name", "Custom"))
        assertEquals(id, BuiltInFilters.rulesFor(FilterSettings(ProfanityLevel.STRICT)).single { it.pattern == "shit" }.id)
    }

    // --- Migration from presets copied into the word list ---

    private val profanityPreset = PresetDictionaries.PROFANITY_PRESET.toSet()
    private val explicitPreset = PresetDictionaries.EXPLICIT_CONTENT_PRESET.toSet()

    @Test
    fun `the whole profanity preset becomes Strict`() {
        val plan = BuiltInFilters.presetMigrationFor(profanityPreset + "my own word")
        assertEquals(ProfanityLevel.STRICT, plan.level)
        assertEquals(profanityPreset, plan.absorbedProfanity)
        assertFalse(plan.explicit)
    }

    @Test
    fun `removed mild words give Standard, and kept mild words stay the user's`() {
        val plan = BuiltInFilters.presetMigrationFor(profanityPreset - "ass")
        assertEquals(ProfanityLevel.STANDARD, plan.level)
        assertTrue("damn" !in plan.absorbedProfanity) // still muted, as the user's own word
        assertTrue("shit" in plan.absorbedProfanity)
    }

    @Test
    fun `no preset means everything stays off`() {
        val plan = BuiltInFilters.presetMigrationFor(setOf("brand name"))
        assertEquals(ProfanityLevel.OFF, plan.level)
        assertTrue(plan.absorbedProfanity.isEmpty())
        assertFalse(plan.explicit)
    }

    @Test
    fun `the whole explicit preset turns explicit content on`() {
        val plan = BuiltInFilters.presetMigrationFor(explicitPreset)
        assertTrue(plan.explicit)
        assertEquals(explicitPreset, plan.absorbedExplicit)
        assertFalse(BuiltInFilters.presetMigrationFor(explicitPreset - "sex").explicit)
    }

    @Test
    fun `contraction he'll is not matched as hell`() {
        assertEquals(
            listOf("hell"),
            muted("he'll he\u2019ll hell", FilterSettings(ProfanityLevel.STRICT))
        )
    }
}
