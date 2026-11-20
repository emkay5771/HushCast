package app.hushcast.automute.matcher

import app.hushcast.automute.dsp.MuteActionMode
import app.hushcast.automute.dsp.MuteWindow
import app.hushcast.automute.engine.Word
import kotlin.math.roundToLong
import kotlinx.serialization.Serializable

@Serializable
data class MuteRule(
    val id: Long = 0L,
    val pattern: String,
    val isRegex: Boolean = false,
    val matchPlurals: Boolean = true,
    val actionMode: MuteActionMode = MuteActionMode.HARD_SILENCE,
    val enabled: Boolean = true,
    val category: String = "Custom",
    val useDefaultMode: Boolean = false
)

/**
 * The preset lists as 1.0.44 and earlier copied them into the word list.  Since 1.0.45 the
 * built-in filters ([BuiltInFilters]) replace them; these stay so the one-time migration can
 * recognize copied presets, and as fixed word sets for tests.
 */
object PresetDictionaries {
    val PROFANITY_PRESET = listOf(
        "fuck", "fucking", "fucked", "fucker", "f***", "f**k",
        "shit", "shitting", "shitty", "s***", "s**t",
        "bitch", "bitching", "b****",
        "asshole", "a**hole", "ass", "a**",
        "damn", "damned", "cunt", "c***",
        "dick", "d***", "cock", "c***",
        "pussy", "p****", "bastard", "bullshit", "motherfucker"
    )

    val EXPLICIT_CONTENT_PRESET = listOf(
        "sex", "sexual", "sexually", "porn", "pornography",
        "orgasm", "masturbate", "masturbation", "erotic",
        "genitals", "penis", "vagina", "clitoris", "intercourse",
        "nsfw", "blowjob", "handjob"
    )

    fun profanityRules(
        actionMode: MuteActionMode = MuteActionMode.HARD_SILENCE,
        useDefaultMode: Boolean = true
    ): List<MuteRule> =
        PROFANITY_PRESET.mapIndexed { idx, w ->
            MuteRule(
                id = 1000L + idx,
                pattern = w,
                isRegex = false,
                matchPlurals = true,
                actionMode = actionMode,
                category = "Profanity",
                useDefaultMode = useDefaultMode
            )
        }

    fun explicitRules(
        actionMode: MuteActionMode = MuteActionMode.HARD_SILENCE,
        useDefaultMode: Boolean = true
    ): List<MuteRule> =
        EXPLICIT_CONTENT_PRESET.mapIndexed { idx, w ->
            MuteRule(
                id = 2000L + idx,
                pattern = w,
                isRegex = false,
                matchPlurals = true,
                actionMode = actionMode,
                category = "Explicit Content",
                useDefaultMode = useDefaultMode
            )
        }
}

/**
 * Track A Word Matcher:
 * Normalizes case, punctuation, contractions, plurals, compound words, regex rules,
 * and Whisper's asterisk-masked profanity (e.g., "f***", "s**t").
 */
object WordMatcher {

    /**
     * Bumped whenever matching changes what it catches, so episodes already analyzed are matched
     * again once after an update (see App).  2: words inside longer words, compounds, any
     * asterisk-masked word, "-ies" plurals, hyphenated words, names guarded ([WordForms]).
     */
    const val MATCHER_VERSION = 3

    private val SAFE_CONTRACTIONS = setOf("he'll", "he\u2019ll")

    private val MASKED_PROFANITY_MAP = mapOf(
        "f***" to "fuck",
        "f**k" to "fuck",
        "f***ing" to "fucking",
        "s***" to "shit",
        "s**t" to "shit",
        "b****" to "bitch",
        "a**" to "ass",
        "a**hole" to "asshole",
        "c***" to "cunt",
        "d***" to "dick",
        "p****" to "pussy"
    )

    fun normalizeToken(raw: String): String {
        val trimmed = raw.trim().lowercase()
            .trimStart { !it.isLetterOrDigit() && it != '*' }
            .trimEnd { !it.isLetterOrDigit() && it != '*' }
        MASKED_PROFANITY_MAP[trimmed]?.let { return it }
        if (trimmed in SAFE_CONTRACTIONS) return "he'll"
        return trimmed.replace("'", "").replace("\u2019", "")
    }

    private fun effectiveMode(rule: MuteRule, defaultActionMode: MuteActionMode): MuteActionMode =
        if (rule.useDefaultMode) defaultActionMode else rule.actionMode

    private fun modeStrength(mode: MuteActionMode): Int = when (mode) {
        MuteActionMode.VOLUME_DUCK -> 1
        MuteActionMode.BEEP -> 2
        MuteActionMode.CUSTOM_SOUND -> 2
        MuteActionMode.HARD_SILENCE -> 3
        MuteActionMode.SAMPLE_DROP_MICRO_SKIP -> 4
    }

    class CompiledRules internal constructor(
        internal val exactSet: Map<String, MuteRule>,
        internal val compoundByFirstToken: Map<String, List<Pair<List<String>, MuteRule>>>,
        internal val combinedRegex: Regex?,
        internal val regexRules: List<Pair<Regex, MuteRule>>,
        internal val defaultActionMode: MuteActionMode,
        /** Stems matched inside longer words, each with the rule of the list word that enabled it. */
        internal val embeddedStems: List<Pair<WordForms.EmbeddedStem, MuteRule>> = emptyList(),
        /** The rule for a masked word that spells none of the list words exactly. */
        internal val maskFallback: MuteRule? = null
    ) {
        val isEmpty: Boolean
            get() = exactSet.isEmpty() && compoundByFirstToken.isEmpty() && regexRules.isEmpty()

        /** For a word with no exact match: its hyphenated parts, a masked spelling, or a stem inside it. */
        internal fun extraFormRule(norm: String): MuteRule? {
            if ('-' in norm) {
                for (part in norm.split('-')) {
                    if (part.isNotEmpty()) exactSet[part]?.let { return it }
                }
            }
            if ('*' in norm) {
                if (!WordForms.isMasked(norm)) return null
                val pattern = WordForms.maskPattern(norm)
                return exactSet.entries.firstOrNull { pattern.matches(it.key) }?.value ?: maskFallback
            }
            for ((stem, rule) in embeddedStems) {
                if (stem.stem in norm && stem.exceptions?.containsMatchIn(norm) != true) return rule
            }
            return null
        }
    }

    /** The word and its plural (-s / -es, and -ies for a consonant + y: "pussy" -> "pussies"). */
    private fun putWithPlurals(
        exactSet: HashMap<String, MuteRule>,
        base: String,
        rule: MuteRule,
        plurals: Boolean,
        iesPlural: Boolean = true
    ) {
        exactSet.putIfAbsent(base, rule)
        if (!plurals) return
        if (base.endsWith("s") || base.endsWith("x") || base.endsWith("z") ||
            base.endsWith("ch") || base.endsWith("sh")
        ) {
            exactSet.putIfAbsent("${base}es", rule)
        } else {
            exactSet.putIfAbsent("${base}s", rule)
            exactSet.putIfAbsent("${base}es", rule)
            if (iesPlural && base.length > 2 && base.endsWith("y") && base[base.length - 2] !in "aeiou") {
                exactSet.putIfAbsent("${base.dropLast(1)}ies", rule)
            }
        }
    }

    fun compileRules(
        rules: List<MuteRule>,
        defaultActionMode: MuteActionMode = MuteActionMode.HARD_SILENCE
    ): CompiledRules {
        val activeRules = rules.filter { it.enabled && it.pattern.isNotBlank() }
        val exactSet = HashMap<String, MuteRule>()
        val compoundByFirst = HashMap<String, MutableList<Pair<List<String>, MuteRule>>>()
        val regexRules = ArrayList<Pair<Regex, MuteRule>>()

        for (rule in activeRules) {
            if (rule.isRegex) {
                runCatching { Regex(rule.pattern, RegexOption.IGNORE_CASE) }
                    .getOrNull()
                    ?.let { regexRules.add(it to rule) }
            } else {
                val tokens = rule.pattern.trim().split(Regex("\\s+"))
                    .map { normalizeToken(it) }
                    .filter { it.isNotEmpty() }
                if (tokens.size == 1) {
                    putWithPlurals(exactSet, tokens[0], rule, rule.matchPlurals)
                } else if (tokens.size > 1) {
                    compoundByFirst.getOrPut(tokens[0]) { ArrayList(2) }.add(tokens to rule)
                }
            }
        }

        val hasBackreference = regexRules.any { (rx, _) ->
            Regex("""\\[1-9]""").containsMatchIn(rx.pattern)
        }
        val combinedRegex = if (regexRules.size > 1 && !hasBackreference) {
            runCatching {
                Regex(regexRules.joinToString("|") { "(?:${it.first.pattern})" }, RegexOption.IGNORE_CASE)
            }.getOrNull()
        } else null

        // Compounds and inflections of list words that can't be matched inside other words.
        for ((base, forms) in WordForms.DERIVED_FORMS) {
            val rule = exactSet[base] ?: continue
            // No -ies plurals of forms: "crappy" -> "crappies" is a fish.
            for (form in forms) putWithPlurals(exactSet, form, rule, plurals = true, iesPlural = false)
        }
        val embedded = WordForms.EMBEDDED_STEMS.mapNotNull { stem ->
            stem.activatedBy.firstNotNullOfOrNull { exactSet[it] }?.let { stem to it }
        }
        // Transcribers mask only profanity, so an unknown masked word uses a profanity-list rule.
        val maskFallback = activeRules.firstOrNull { !it.isRegex && it.category == "Profanity" }

        return CompiledRules(
            exactSet, compoundByFirst, combinedRegex, regexRules, defaultActionMode, embedded, maskFallback
        )
    }

    fun matchWords(
        words: List<Word>,
        rules: List<MuteRule>,
        prePadMs: Long = 80L,
        postPadMs: Long = 100L,
        defaultActionMode: MuteActionMode = MuteActionMode.HARD_SILENCE
    ): List<MuteWindow> {
        if (words.isEmpty() || rules.isEmpty()) return emptyList()
        val compiled = compileRules(rules, defaultActionMode)
        return matchWords(words, compiled, prePadMs, postPadMs)
    }

    fun matchWords(
        words: List<Word>,
        compiled: CompiledRules,
        prePadMs: Long = 80L,
        postPadMs: Long = 100L
    ): List<MuteWindow> {
        if (words.isEmpty() || compiled.isEmpty) return emptyList()

        val exactSet = compiled.exactSet
        val compoundByFirst = compiled.compoundByFirstToken
        val combinedRegex = compiled.combinedRegex
        val regexRules = compiled.regexRules
        val defaultActionMode = compiled.defaultActionMode

        val normalizedWords = words.map { normalizeToken(it.text) }
        val rawWindows = ArrayList<MuteWindow>()

        fun neighborBoundedWindow(
            firstIdx: Int,
            lastIdx: Int,
            wordText: String,
            ruleId: Long,
            mode: MuteActionMode
        ): MuteWindow {
            val first = words[firstIdx]
            val last = words[lastIdx]
            val rawStartMs = (first.start * 1000.0).roundToLong()
            val rawEndMs = (last.end * 1000.0).roundToLong()

            val rawDurMs = (rawEndMs - rawStartMs).coerceAtLeast(0L)
            val minOverlapFloor = if (rawDurMs < 120L) 35L else 15L

            val effectivePre = if (firstIdx > 0) {
                val prev = words[firstIdx - 1]
                val prevEndMs = (prev.end * 1000.0).roundToLong()
                val prevDurMs = ((prev.end - prev.start) * 1000.0).roundToLong().coerceAtLeast(0L)
                val gapMs = (rawStartMs - prevEndMs).coerceAtLeast(0L)
                val maxOverlap = maxOf(minOverlapFloor, prevDurMs / 4L)
                minOf(prePadMs, gapMs + maxOverlap)
            } else {
                prePadMs
            }

            val effectivePost = if (lastIdx + 1 < words.size) {
                val next = words[lastIdx + 1]
                val nextStartMs = (next.start * 1000.0).roundToLong()
                val nextDurMs = ((next.end - next.start) * 1000.0).roundToLong().coerceAtLeast(0L)
                val gapMs = (nextStartMs - rawEndMs).coerceAtLeast(0L)
                val maxOverlap = maxOf(minOverlapFloor, nextDurMs / 4L)
                minOf(postPadMs, gapMs + maxOverlap)
            } else {
                postPadMs
            }

            return MuteWindow(
                startMs = (rawStartMs - effectivePre).coerceAtLeast(0L),
                endMs = (rawEndMs + effectivePost).coerceAtLeast(rawStartMs + 1L),
                word = wordText,
                ruleId = ruleId,
                actionMode = mode
            )
        }

        for (i in words.indices) {
            val w = words[i]
            val norm = normalizedWords[i]
            if (norm.isEmpty()) continue

            val exactRule = if (WordForms.isNameUse(norm, words, i)) {
                null
            } else {
                exactSet[norm] ?: compiled.extraFormRule(norm)
            }
            if (exactRule != null) {
                rawWindows.add(
                    neighborBoundedWindow(
                        firstIdx = i,
                        lastIdx = i,
                        wordText = w.text.trim(),
                        ruleId = exactRule.id,
                        mode = effectiveMode(exactRule, defaultActionMode)
                    )
                )
            } else if (regexRules.isNotEmpty()) {
                val trimmedRaw = w.text.trim()
                val mayMatch = combinedRegex == null ||
                    combinedRegex.containsMatchIn(trimmedRaw) ||
                    combinedRegex.containsMatchIn(norm)
                if (mayMatch) {
                    val matchedRegex = regexRules.firstOrNull { (regex, _) ->
                        regex.containsMatchIn(trimmedRaw) || regex.containsMatchIn(norm)
                    }
                    if (matchedRegex != null) {
                        rawWindows.add(
                            neighborBoundedWindow(
                                firstIdx = i,
                                lastIdx = i,
                                wordText = trimmedRaw,
                                ruleId = matchedRegex.second.id,
                                mode = effectiveMode(matchedRegex.second, defaultActionMode)
                            )
                        )
                    }
                }
            }

            val phrasesStartingHere = compoundByFirst[norm]
            if (phrasesStartingHere != null) {
                for ((phraseTokens, rule) in phrasesStartingHere) {
                    val len = phraseTokens.size
                    if (i + len > words.size) continue
                    var allMatch = true
                    for (j in 1 until len) {
                        if (normalizedWords[i + j] != phraseTokens[j]) {
                            allMatch = false
                            break
                        }
                    }
                    if (allMatch) {
                        val phraseText = words.subList(i, i + len).joinToString(" ") { it.text.trim() }
                        rawWindows.add(
                            neighborBoundedWindow(
                                firstIdx = i,
                                lastIdx = i + len - 1,
                                wordText = phraseText,
                                ruleId = rule.id,
                                mode = effectiveMode(rule, defaultActionMode)
                            )
                        )
                    }
                }
            }
        }

        return mergeOverlappingWindows(rawWindows)
    }

    internal fun mergeOverlappingWindows(windows: List<MuteWindow>): List<MuteWindow> {
        if (windows.isEmpty()) return emptyList()
        val sorted = windows.sortedBy { it.startMs }
        val merged = ArrayList<MuteWindow>(sorted.size)
        var current = sorted[0]
        for (i in 1 until sorted.size) {
            val next = sorted[i]
            if (next.startMs <= current.endMs + 40L) {
                val strongestMode = if (modeStrength(next.actionMode) > modeStrength(current.actionMode)) {
                    next.actionMode
                } else {
                    current.actionMode
                }
                current = current.copy(
                    endMs = maxOf(current.endMs, next.endMs),
                    word = "${current.word} ${next.word}",
                    actionMode = strongestMode
                )
            } else {
                merged.add(current)
                current = next
            }
        }
        merged.add(current)
        return merged
    }
}
