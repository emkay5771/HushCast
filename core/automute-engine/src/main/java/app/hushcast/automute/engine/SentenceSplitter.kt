package app.hushcast.automute.engine

import kotlinx.serialization.Serializable

/** A recognized word with its times in seconds; [text] keeps the recognizer's leading space. */
@Serializable
data class Word(val start: Double, val end: Double, val text: String)

/**
 * Cuts a recognizer's output into sentence-sized segments, which is what the tagger was trained
 * on.  Call [split] once per recognizer segment, or on a whole transcript whose window edges were
 * already repaired.
 */
object SentenceSplitter {
    private val ABBREVIATIONS = setOf("mr.", "mrs.", "ms.", "dr.", "st.", "jr.", "sr.", "vs.", "etc.", "inc.",
        "co.", "no.", "u.s.", "u.k.", "e.g.", "i.e.", "a.m.", "p.m.", "mt.", "ft.", "approx.")
    private val SENTENCE_END = Regex("[.?!][\"')\\]]*$")
    const val MAX_SEGMENT_S = 15.0

    fun isSentenceEnd(word: String): Boolean {
        val w = word.trim().lowercase()
        return SENTENCE_END.containsMatchIn(w) && w !in ABBREVIATIONS
    }

    fun split(words: List<Word>, maxLen: Double = MAX_SEGMENT_S): List<Segment> {
        val out = ArrayList<Segment>()
        var cur = ArrayList<Word>()
        fun flush(upto: Int = cur.size) {
            val take = cur.subList(0, upto).toList()
            cur = ArrayList(cur.subList(upto, cur.size))
            if (take.isNotEmpty()) out.add(Segment(take.first().start, take.last().end,
                take.joinToString("") { it.text }.trim(), take.size))
        }
        for (w in words) {
            cur.add(w)
            if (isSentenceEnd(w.text)) flush()
            else if (cur.last().end - cur.first().start > maxLen && cur.size > 2) {
                val cut = fallbackCut(cur, maxLen)
                if (cut > 0) flush(cut)
            }
        }
        flush()
        return out
    }

    /** Fallback pieces (no sentence punctuation within [MAX_SEGMENT_S]) must be at least this long. */
    const val MIN_FALLBACK_S = 3.0
    const val MIN_FALLBACK_WORDS = 6
    /** A pause must be at least this long to be preferred as the split point. */
    const val MIN_PAUSE_S = 0.15

    /**
     * How many leading words of [cur] to flush when a stretch with no sentence punctuation runs past
     * [maxLen], or 0 to keep accumulating.  Splits at the longest pause if it's at least
     * [MIN_PAUSE_S] (earliest one on ties); otherwise at the last word boundary within [maxLen].
     * Every piece is at least [MIN_FALLBACK_S] and [MIN_FALLBACK_WORDS] long.  (Recognizers such as
     * faster-whisper report back-to-back word times, so "largest gap" alone would tie at 0 and cut
     * one word at a time.)  Mirrors `split_sentences` in the desktop worker.
     */
    internal fun fallbackCut(cur: List<Word>, maxLen: Double): Int {
        val t0 = cur.first().start
        val valid = (MIN_FALLBACK_WORDS until cur.size).filter { n -> cur[n - 1].end - t0 >= MIN_FALLBACK_S - EPS }
        if (valid.isEmpty()) return 0
        var bestN = valid.first()
        var bestGap = cur[bestN].start - cur[bestN - 1].end
        for (n in valid.drop(1)) {
            val gap = cur[n].start - cur[n - 1].end
            if (gap > bestGap) { bestN = n; bestGap = gap }
        }
        if (bestGap >= MIN_PAUSE_S - EPS) return bestN
        // No usable pause (e.g. back-to-back word times): the last cut that keeps the piece within
        // maxLen, or as close above it as the minimums allow.
        return valid.lastOrNull { n -> cur[n - 1].end - t0 <= maxLen + EPS } ?: valid.first()
    }

    private const val EPS = 1e-6
}
