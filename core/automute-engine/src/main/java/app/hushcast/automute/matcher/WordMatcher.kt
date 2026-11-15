package app.hushcast.automute.matcher

import app.hushcast.automute.dsp.MuteActionMode
import app.hushcast.automute.dsp.MuteWindow
import app.hushcast.automute.engine.Word
import kotlinx.serialization.Serializable

@Serializable
data class MuteRule(
    val id: Long = 0L,
    val pattern: String,
    val isRegex: Boolean = false,
    val matchPlurals: Boolean = true,
    val actionMode: MuteActionMode = MuteActionMode.HARD_SILENCE,
    val enabled: Boolean = true,
    val category: String = "Custom"
)

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

    fun profanityRules(actionMode: MuteActionMode = MuteActionMode.HARD_SILENCE): List<MuteRule> =
        PROFANITY_PRESET.mapIndexed { idx, w ->
            MuteRule(
                id = 1000L + idx,
                pattern = w,
                isRegex = false,
                matchPlurals = true,
                actionMode = actionMode,
                category = "Profanity"
            )
        }

    fun explicitRules(actionMode: MuteActionMode = MuteActionMode.HARD_SILENCE): List<MuteRule> =
        EXPLICIT_CONTENT_PRESET.mapIndexed { idx, w ->
            MuteRule(
                id = 2000L + idx,
                pattern = w,
                isRegex = false,
                matchPlurals = true,
                actionMode = actionMode,
                category = "Explicit Content"
            )
        }
}

/**
 * Track A Word Matcher:
 * Normalizes case, punctuation, contractions, plurals, compound words, regex rules,
 * and Whisper's asterisk-masked profanity (e.g., "f***", "s**t").
 */
object WordMatcher {

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
        // Handle partial asterisk masking like f*ck -> fuck? Keep stripped alphanumeric for normal words
        return trimmed.replace("'", "").replace("\u2019", "")
    }

    fun matchWords(
        words: List<Word>,
        rules: List<MuteRule>,
        prePadMs: Long = 80L,
        postPadMs: Long = 100L,
        defaultActionMode: MuteActionMode = MuteActionMode.HARD_SILENCE
    ): List<MuteWindow> {
        val activeRules = rules.filter { it.enabled && it.pattern.isNotBlank() }
        if (words.isEmpty() || activeRules.isEmpty()) return emptyList()

        val exactSet = HashMap<String, MuteRule>()
        val compoundPhrases = ArrayList<Pair<List<String>, MuteRule>>()
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
                    val base = tokens[0]
                    exactSet.putIfAbsent(base, rule)
                    if (rule.matchPlurals && !base.endsWith("s")) {
                        exactSet.putIfAbsent("${base}s", rule)
                        exactSet.putIfAbsent("${base}es", rule)
                    }
                } else if (tokens.size > 1) {
                    compoundPhrases.add(tokens to rule)
                }
            }
        }

        val normalizedWords = words.map { normalizeToken(it.text) }
        val rawWindows = ArrayList<MuteWindow>()

        for (i in words.indices) {
            val w = words[i]
            val norm = normalizedWords[i]
            if (norm.isEmpty()) continue

            // 1. Exact / plural / masked profanity match
            val exactRule = exactSet[norm]
            if (exactRule != null) {
                rawWindows.add(
                    MuteWindow(
                        startMs = ((w.start * 1000.0).toLong() - prePadMs).coerceAtLeast(0L),
                        endMs = (w.end * 1000.0).toLong() + postPadMs,
                        word = w.text.trim(),
                        ruleId = exactRule.id,
                        actionMode = exactRule.actionMode
                    )
                )
                continue
            }

            // 2. Regex match against normalized or raw trimmed word
            val trimmedRaw = w.text.trim()
            val matchedRegex = regexRules.firstOrNull { (regex, _) ->
                regex.containsMatchIn(trimmedRaw) || regex.containsMatchIn(norm)
            }
            if (matchedRegex != null) {
                rawWindows.add(
                    MuteWindow(
                        startMs = ((w.start * 1000.0).toLong() - prePadMs).coerceAtLeast(0L),
                        endMs = (w.end * 1000.0).toLong() + postPadMs,
                        word = trimmedRaw,
                        ruleId = matchedRegex.second.id,
                        actionMode = matchedRegex.second.actionMode
                    )
                )
            }
        }

        // 3. Compound multi-word phrase match
        for ((phraseTokens, rule) in compoundPhrases) {
            val len = phraseTokens.size
            if (words.size < len) continue
            for (i in 0..(words.size - len)) {
                var allMatch = true
                for (j in 0 until len) {
                    if (normalizedWords[i + j] != phraseTokens[j]) {
                        allMatch = false
                        break
                    }
                }
                if (allMatch) {
                    val first = words[i]
                    val last = words[i + len - 1]
                    val phraseText = words.subList(i, i + len).joinToString(" ") { it.text.trim() }
                    rawWindows.add(
                        MuteWindow(
                            startMs = ((first.start * 1000.0).toLong() - prePadMs).coerceAtLeast(0L),
                            endMs = (last.end * 1000.0).toLong() + postPadMs,
                            word = phraseText,
                            ruleId = rule.id,
                            actionMode = rule.actionMode
                        )
                    )
                }
            }
        }

        return mergeOverlappingWindows(rawWindows, defaultActionMode)
    }

    private fun mergeOverlappingWindows(
        windows: List<MuteWindow>,
        defaultActionMode: MuteActionMode
    ): List<MuteWindow> {
        if (windows.isEmpty()) return emptyList()
        val sorted = windows.sortedBy { it.startMs }
        val merged = ArrayList<MuteWindow>(sorted.size)
        var current = sorted[0]
        for (i in 1 until sorted.size) {
            val next = sorted[i]
            if (next.startMs <= current.endMs + 40L && next.actionMode == current.actionMode) {
                current = current.copy(
                    endMs = maxOf(current.endMs, next.endMs),
                    word = "${current.word} ${next.word}"
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
