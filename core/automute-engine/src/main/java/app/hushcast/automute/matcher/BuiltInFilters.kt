package app.hushcast.automute.matcher

import app.hushcast.automute.dsp.MuteActionMode

/** How strong a built-in profanity word is; the profanity level decides which ones are muted. */
enum class Severity { STRONG, MODERATE, MILD }

/** The profanity filter: off, or which severities to mute. */
enum class ProfanityLevel {
    OFF, LIGHT, STANDARD, STRICT;

    fun mutes(severity: Severity): Boolean = when (this) {
        OFF -> false
        LIGHT -> severity == Severity.STRONG
        STANDARD -> severity != Severity.MILD
        STRICT -> true
    }
}

/** What the built-in filters mute (the global settings, or one podcast's with its own level). */
data class FilterSettings(
    val profanity: ProfanityLevel = ProfanityLevel.OFF,
    val explicit: Boolean = false,
    val religious: Boolean = false
)

/**
 * The built-in word filters (1.0.45): profanity in three severities chosen by a level, explicit
 * content, and religious exclamations.  They replace the presets older versions copied into the
 * word list; the user's own words stay in the database as before.
 *
 * Built-in words are matched like list words, with the same variations ([WordForms]); a
 * variation counts with its base word.  Each built-in word has a fixed rule id (the base of its
 * list plus its position) far above any database id, so stored mute segments keep pointing at
 * the right word.  Lists are append-only for that reason.
 */
object BuiltInFilters {
    const val CATEGORY_PROFANITY = "Profanity"
    const val CATEGORY_EXPLICIT = "Explicit Content"
    const val CATEGORY_RELIGIOUS = "Religious"

    class Entry(
        val pattern: String,
        val severity: Severity? = null,
        /** Shown in the "what's muted" lists (inflections and masked spellings aren't). */
        val shown: Boolean = true,
        val matchPlurals: Boolean = true
    )

    private const val BUILT_IN_MIN_ID = 9_000_000_000L
    private const val PROFANITY_BASE_ID = 9_100_000_000L
    private const val EXPLICIT_BASE_ID = 9_200_000_000L
    private const val RELIGIOUS_BASE_ID = 9_300_000_000L

    fun isBuiltInId(id: Long): Boolean = id >= BUILT_IN_MIN_ID

    private fun strong(p: String, shown: Boolean = true) = Entry(p, Severity.STRONG, shown)
    private fun moderate(p: String, shown: Boolean = true) = Entry(p, Severity.MODERATE, shown)
    private fun mild(p: String, shown: Boolean = true, plurals: Boolean = true) = Entry(p, Severity.MILD, shown, plurals)

    /** Append only (rule ids follow positions).  Strong words first: masked words fall back to the first. */
    val PROFANITY: List<Entry> = listOf(
        strong("fuck"), strong("fucking", false), strong("fucked", false), strong("fucker", false),
        strong("f***", false), strong("f**k", false), strong("motherfucker"),
        strong("cunt"), strong("c***", false), strong("cock"), strong("pussy"), strong("p****", false),
        moderate("shit"), moderate("shitting", false), moderate("shitty", false), moderate("s***", false),
        moderate("s**t", false), moderate("bullshit"),
        moderate("bitch"), moderate("bitching", false), moderate("b****", false),
        moderate("asshole"), moderate("a**hole", false), moderate("dick"), moderate("d***", false),
        moderate("bastard"),
        mild("ass"), mild("a**", false), mild("damn"), mild("damned", false),
        // "craps" is also the dice game.
        mild("crap", plurals = false), mild("hell"), mild("piss")
    )

    val EXPLICIT: List<Entry> = PresetDictionaries.EXPLICIT_CONTENT_PRESET.map { Entry(it) }

    /** Phrases only, never "God" on its own: it's said with respect far more often.  Append only. */
    val RELIGIOUS: List<Entry> = listOf(
        Entry("oh my god"), Entry("oh god"), Entry("for god's sake"), Entry("swear to god"),
        Entry("jesus christ"), Entry("goddamn"),
        // Spellings of goddamn: muted, not listed separately.
        Entry("goddamned", shown = false), Entry("goddamnit", shown = false), Entry("goddammit", shown = false)
    )

    private fun rule(id: Long, entry: Entry, category: String) = MuteRule(
        id = id,
        pattern = entry.pattern,
        matchPlurals = entry.matchPlurals,
        actionMode = MuteActionMode.HARD_SILENCE,
        category = category,
        useDefaultMode = true
    )

    /** The built-in rules [settings] turn on. */
    fun rulesFor(settings: FilterSettings): List<MuteRule> = buildList {
        if (settings.religious) {
            RELIGIOUS.forEachIndexed { i, e -> add(rule(RELIGIOUS_BASE_ID + i, e, CATEGORY_RELIGIOUS)) }
        }
        PROFANITY.forEachIndexed { i, e ->
            if (settings.profanity.mutes(e.severity!!)) add(rule(PROFANITY_BASE_ID + i, e, CATEGORY_PROFANITY))
        }
        if (settings.explicit) {
            EXPLICIT.forEachIndexed { i, e -> add(rule(EXPLICIT_BASE_ID + i, e, CATEGORY_EXPLICIT)) }
        }
    }

    /** The built-in rule id for a word, or null if it isn't one. */
    fun idFor(pattern: String, category: String): Long? {
        val p = pattern.trim().lowercase()
        return when (category) {
            CATEGORY_PROFANITY -> PROFANITY.indexOfFirst { it.pattern == p }.takeIf { it >= 0 }?.let { PROFANITY_BASE_ID + it }
            CATEGORY_EXPLICIT -> EXPLICIT.indexOfFirst { it.pattern == p }.takeIf { it >= 0 }?.let { EXPLICIT_BASE_ID + it }
            CATEGORY_RELIGIOUS -> RELIGIOUS.indexOfFirst { it.pattern == p }.takeIf { it >= 0 }?.let { RELIGIOUS_BASE_ID + it }
            else -> null
        }
    }

    /** The profanity words shown for one severity (no inflections or masked spellings). */
    fun shownProfanity(severity: Severity): List<String> =
        PROFANITY.filter { it.severity == severity && it.shown }.map { it.pattern }

    /** The global settings with a podcast's own level, if the episode's podcast has one (ids are "feed:guid"). */
    fun effective(
        global: FilterSettings,
        podcastLevels: Map<String, ProfanityLevel>,
        episodeId: String
    ): FilterSettings {
        val own = podcastLevels.entries
            .filter { episodeId.startsWith("${it.key}:") }
            .maxByOrNull { it.key.length }
            ?.value
        return if (own == null) global else global.copy(profanity = own)
    }

    /** What the one-time move from copied presets to built-in filters does with a stored list. */
    data class PresetMigration(
        val level: ProfanityLevel,
        val explicit: Boolean,
        /** Patterns of stored rows the built-in filters now cover (their rows are removed). */
        val absorbedProfanity: Set<String>,
        val absorbedExplicit: Set<String>
    )

    /**
     * Older versions copied the presets into the word list.  The move keeps everything muted that
     * was muted: the level is the strictest one whose earlier preset words were all in the list and
     * on.  Preset words beyond that level stay as the user's own words.
     */
    fun presetMigrationFor(enabledPatterns: Set<String>): PresetMigration {
        val severityOf = PROFANITY.associate { it.pattern to it.severity!! }
        val earlier = PresetDictionaries.PROFANITY_PRESET.toSet()
        val level = listOf(ProfanityLevel.STRICT, ProfanityLevel.STANDARD, ProfanityLevel.LIGHT).firstOrNull { level ->
            earlier.filter { level.mutes(severityOf.getValue(it)) }.all { it in enabledPatterns }
        } ?: ProfanityLevel.OFF
        val absorbedProfanity = earlier.filter { level.mutes(severityOf.getValue(it)) }.toSet()

        val explicitWords = PresetDictionaries.EXPLICIT_CONTENT_PRESET.toSet()
        val explicit = explicitWords.all { it in enabledPatterns }
        return PresetMigration(
            level = level,
            explicit = explicit,
            absorbedProfanity = absorbedProfanity,
            absorbedExplicit = if (explicit) explicitWords else emptySet()
        )
    }
}
