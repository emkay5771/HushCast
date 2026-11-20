package app.hushcast.automute.matcher

import app.hushcast.automute.engine.Word

/**
 * Forms of the word-list words that exact matching misses:
 * - the word inside a longer word ("enshittified", "motherfucking", "fuckin", "bitchy");
 * - compounds of words that can't be matched inside other words ("dumbass", "goddamn");
 * - asterisk-masked transcriptions beyond the fixed list ("f*ck", "motherf***er", "bulls**t").
 *
 * Each form applies only while its base word is in the user's list, with that word's rule, so
 * removing a word removes its forms too.
 */
internal object WordForms {

    class EmbeddedStem(
        val stem: String,
        /** The list words that switch this stem on. */
        val activatedBy: Set<String>,
        exceptions: String? = null
    ) {
        /** Innocent words that contain the stem, matched against the normalized word. */
        val exceptions: Regex? = exceptions?.let { Regex(it, RegexOption.IGNORE_CASE) }
    }

    /**
     * Stems matched anywhere inside a word.  Only stems that almost never occur inside innocent
     * words qualify (checked against Webster's 235,000 words plus proper names); the innocent
     * ones that do exist are exceptions.  `shit`'s exceptions cover Torah and Hebrew terms
     * (Bereshit, shita, shittim, shituf), Bible names (Girgashite, Cushite, Elkoshite), names
     * ending in -shita (Yamashita, Matsushita), mishit, Washita and a few misspellings.
     */
    val EMBEDDED_STEMS = listOf(
        EmbeddedStem("fuck", setOf("fuck")),
        EmbeddedStem(
            "shit", setOf("shit"),
            "rei?shit|shitah?s?(?![a-z])|shito[ts]|shitt(im|ah)|shituf|girgashit|elkoshit|[ck]ushit|" +
                "mishit|washit|shitak|shitzu|shital|shitij|shitepoke|brushit|marshit|kaneshit|" +
                "koreishit|mackintoshit"
        ),
        EmbeddedStem("bitch", setOf("bitch")),
        EmbeddedStem("cunt", setOf("cunt"), "scunthorp|placunt"),
        EmbeddedStem("porn", setOf("porn", "pornography"), "agaporn|eporn"),
        EmbeddedStem("masturbat", setOf("masturbate", "masturbation")),
        EmbeddedStem("orgasm", setOf("orgasm")),
        EmbeddedStem("blowjob", setOf("blowjob")),
        EmbeddedStem("handjob", setOf("handjob"))
    )

    /**
     * Compounds and inflections of words that are never matched inside other words, because too
     * many innocent words contain them: class/pass, cocktail/peacock, Dickens/dicker,
     * damning/damnation, scrap/skyscraper, pussycat, congenital, arteriosclerotic, invagination.
     */
    val DERIVED_FORMS: Map<String, List<String>> = mapOf(
        "ass" to listOf(
            "assed", "dumbass", "jackass", "smartass", "badass", "asshat", "asswipe", "kickass",
            "hardass", "fatass", "lardass"
        ),
        "damn" to listOf("goddamn", "goddamned", "goddamnit", "goddammit", "dammit", "damnit", "damnedest", "damndest"),
        "dick" to listOf("dickhead", "dickish", "dickwad", "dicking"),
        "cock" to listOf("cocksucker", "cocksucking"),
        // Never inside other words: scrap, scrapbook, skyscraper, crappie (a fish).
        "crap" to listOf(
            "crappy", "crappier", "crappiest", "crapped", "crapping", "crapper", "crapload",
            "crapshoot", "bullcrap"
        ),
        "piss" to listOf("pissed", "pissing", "pissy"),
        "genitals" to listOf("genitalia"),
        "vagina" to listOf("vaginal"),
        "clitoris" to listOf("clitoral"),
        "erotic" to listOf("erotica", "eroticism")
    )

    /** Letters, then asterisks standing for letters: "f*ck", "f***ing", "a**hole", "motherf***er". */
    private val MASKED = Regex("^[a-z]+(\\*{2,}[a-z]*|\\*+[a-z][a-z*]*)$")

    fun isMasked(norm: String): Boolean = MASKED.matches(norm)

    /** The masked word as a pattern in which each asterisk is one letter. */
    fun maskPattern(norm: String): Regex =
        Regex(norm.split('*').joinToString("[a-z]") { if (it.isEmpty()) "" else Regex.escape(it) })

    private val NAME_GUARDED = setOf("dick", "dicks")
    private val SENTENCE_END = setOf('.', '!', '?', ',', ';', ':')
    private val INITIAL = Regex("^[A-Z]\\.$")
    private val PRONOUN_I = setOf("I", "I'm", "I've", "I'll", "I'd")

    /**
     * A name, not an insult: "Dick Cheney", "Philip K. Dick", "Moby Dick", "Dick's Sporting Goods".
     * Transcripts capitalize names; the insult is lowercase mid-sentence.  Only a capitalized
     * "Dick" next to another capitalized word (not across a sentence break) counts as a name.
     */
    fun isNameUse(norm: String, words: List<Word>, i: Int): Boolean {
        if (norm !in NAME_GUARDED) return false
        val raw = words[i].text.trim().trimStart { !it.isLetter() }
        if (raw.firstOrNull()?.isUpperCase() != true) return false
        fun capitalized(text: String?) = text?.trim()?.trimStart { !it.isLetter() }?.firstOrNull()?.isUpperCase() == true

        val next = words.getOrNull(i + 1)?.text?.trim()
        val nameByNext = raw.lastOrNull() !in SENTENCE_END && capitalized(next) && next !in PRONOUN_I

        val prev = words.getOrNull(i - 1)?.text?.trim()
        val prevEndsSentence = prev != null && prev.lastOrNull() in SENTENCE_END && !INITIAL.matches(prev)
        val nameByPrev = capitalized(prev) && !prevEndsSentence

        return nameByNext || nameByPrev
    }
}
