package app.hushcast.automute.engine

import app.hushcast.automute.desktop.DesktopChapterDto
import app.hushcast.automute.storage.AdSegmentEntity
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sqrt

/**
 * On-device auto-chapter generator using embedding topic-shift boundaries and TF-IDF titles.
 *
 * Mirrors the non-LLM path of `desktop-worker/hushcast_worker/chapters.py` so on-device analysis
 * produces grounded chapters with zero additional ML models or memory overhead, reusing the
 * sentence embeddings already computed for ad detection.
 */
object ChapterGenerator {

    private val TOKEN_REGEX = Regex("[A-Za-z0-9][A-Za-z0-9'-]*")

    private val STOPWORDS = setOf(
        "a", "about", "above", "across", "actually", "after", "again", "against", "all",
        "almost", "already", "also", "always", "am", "an", "and", "any", "are", "aren't",
        "around", "as", "at", "basically", "be", "became", "because", "become", "been",
        "before", "behind", "being", "between", "beyond", "but", "by", "can", "can't",
        "certainly", "comes", "coming", "could", "definitely", "did", "didn't", "do",
        "does", "doesn't", "doing", "don't", "down", "during", "each", "either", "enough",
        "especially", "essentially", "even", "ever", "every", "feel", "for", "from",
        "get", "getting", "go", "goes", "going", "good", "got", "great", "had", "has",
        "have", "having", "he", "her", "here", "hers", "him", "his", "how", "however",
        "i", "i'd", "i'll", "i'm", "i've", "if", "in", "inside", "instead", "into", "is",
        "isn't", "it", "it's", "its", "just", "kind", "know", "like", "literally",
        "little", "looking", "looks", "lot", "made", "make", "many", "maybe", "me",
        "mean", "meanwhile", "more", "most", "much", "my", "need", "neither", "no",
        "not", "now", "obviously", "of", "off", "oh", "ok", "okay", "on", "one",
        "only", "or", "other", "our", "out", "outside", "over", "own", "people",
        "personally", "pretty", "probably", "put", "rather", "really", "right", "said",
        "same", "say", "saying", "see", "seemed", "seems", "she", "should", "so",
        "some", "something", "sort", "still", "stuff", "such", "sure", "take",
        "talking", "tell", "than", "that", "that's", "the", "their", "them", "then",
        "there", "there's", "therefore", "these", "they", "they're", "thing", "things",
        "think", "this", "those", "though", "through", "time", "to", "too", "totally",
        "toward", "towards", "try", "trying", "uh", "um", "under", "up", "us", "use",
        "used", "using", "very", "wait", "want", "was", "wasn't", "way", "we", "we're",
        "well", "were", "what", "what's", "when", "where", "whether", "which", "while",
        "who", "why", "will", "with", "within", "without", "would", "yeah", "yes",
        "you", "you're", "your", "yours"
    )

    private fun isInsideAd(startSec: Double, endSec: Double, adSegments: List<AdSegmentEntity>): Boolean {
        val sMs = (startSec * 1000.0).toLong()
        val eMs = (endSec * 1000.0).toLong()
        for (ad in adSegments) {
            if (sMs < ad.endMs && eMs > ad.startMs) return true
        }
        return false
    }

    internal fun extractChapterTokens(text: String): List<Pair<String, Boolean>> {
        val out = ArrayList<Pair<String, Boolean>>()
        for (match in TOKEN_REGEX.findAll(text)) {
            val raw = match.value
            val low = raw.lowercase().trim('\'', '-')
            if (low.length < 3 || low in STOPWORDS || low.all { it.isDigit() }) continue
            val isCap = raw[0].isUpperCase() && !raw.all { !it.isLetter() || it.isUpperCase() }
            out.add(low to isCap)
        }
        return out
    }

    internal fun nameChapterFromTfIdf(
        chunkTexts: List<String>,
        allChunkTokenSets: List<Set<String>>,
        chapterIdx: Int
    ): String {
        val tf = HashMap<String, Int>()
        val properForms = HashMap<String, String>()
        for (txt in chunkTexts) {
            val toks = extractChapterTokens(txt)
            for (idx in toks.indices) {
                val (low, isCap) = toks[idx]
                tf[low] = (tf[low] ?: 0) + 1
                if (isCap && low !in properForms) {
                    properForms[low] = low.replaceFirstChar { it.uppercase() }
                }
                if (idx + 1 < toks.size) {
                    val bi = "$low ${toks[idx + 1].first}"
                    tf[bi] = (tf[bi] ?: 0) + 1
                }
            }
        }

        if (tf.isEmpty()) return "Part ${chapterIdx + 1}"

        val nChapters = max(1, allChunkTokenSets.size)
        val scored = ArrayList<Pair<Double, String>>(tf.size)
        for ((term, count) in tf) {
            if (count < 2 && ' ' !in term) continue
            var df = 0
            for (s in allChunkTokenSets) {
                if (term in s) df++
            }
            val idf = ln((nChapters + 1.0) / (df + 0.5)) + 0.5
            var boost = if (' ' in term) 1.65 else 1.0
            if (term in properForms) {
                boost *= 1.25
            }
            scored.add((count * idf * boost) to term)
        }

        scored.sortByDescending { it.first }
        val picked = ArrayList<String>(2)
        val usedWords = HashSet<String>()
        for ((_, term) in scored) {
            val parts = term.split(' ')
            if (parts.any { it in usedWords }) continue
            usedWords.addAll(parts)
            val pretty = parts.joinToString(" ") { p ->
                properForms[p] ?: p.replaceFirstChar { it.uppercase() }
            }
            picked.add(pretty)
            if (picked.size >= 2) break
        }

        return if (picked.isEmpty()) "Part ${chapterIdx + 1}" else picked.joinToString(" \u2022 ")
    }

    /**
     * Generates topic-shift chapters from [segments] and their flat row-major [embeddings]
     * (`segments.size * dim`), excluding segments that overlap [adSegments].
     */
    fun generateChapters(
        segments: List<Segment>,
        embeddings: FloatArray,
        dim: Int,
        adSegments: List<AdSegmentEntity>
    ): List<DesktopChapterDto> {
        if (segments.isEmpty() || dim <= 0 || embeddings.size < segments.size * dim) {
            return emptyList()
        }

        val nonAdIndices = ArrayList<Int>(segments.size)
        for (i in segments.indices) {
            val s = segments[i]
            if (!isInsideAd(s.start, s.end, adSegments) && s.text.trim().split(Regex("\\s+")).size >= 3) {
                nonAdIndices.add(i)
            }
        }
        if (nonAdIndices.size < 4) return emptyList()

        val m = nonAdIndices.size
        val totalDurSec = max(0.0, segments[nonAdIndices.last()].end - segments[nonAdIndices.first()].start)

        val boundaries = ArrayList<Int>()
        boundaries.add(nonAdIndices[0])

        val win = min(6, max(2, m / 12))
        if (m > win * 2 + 2) {
            val sims = ArrayList<Pair<Int, Double>>(m - 2 * win)
            val left = DoubleArray(dim)
            val right = DoubleArray(dim)
            for (k in win until (m - win)) {
                left.fill(0.0)
                right.fill(0.0)
                for (j in (k - win) until k) {
                    val base = nonAdIndices[j] * dim
                    for (d in 0 until dim) left[d] += embeddings[base + d].toDouble()
                }
                for (j in k until (k + win)) {
                    val base = nonAdIndices[j] * dim
                    for (d in 0 until dim) right[d] += embeddings[base + d].toDouble()
                }
                var lnSq = 0.0
                var rnSq = 0.0
                var dot = 0.0
                for (d in 0 until dim) {
                    val lv = left[d] / win
                    val rv = right[d] / win
                    lnSq += lv * lv
                    rnSq += rv * rv
                    dot += lv * rv
                }
                val sim = dot / ((sqrt(lnSq) + 1e-12) * (sqrt(rnSq) + 1e-12))
                sims.add(k to sim)
            }

            sims.sortBy { it.second }
            val maxChapters = min(9, max(4, (totalDurSec / 360.0).roundToInt() + 1))
            val minGapSec = max(120.0, min(240.0, totalDurSec / max(1, maxChapters + 2)))
            val selectedTimes = ArrayList<Double>(maxChapters)
            selectedTimes.add(segments[nonAdIndices[0]].start)

            for ((k, _) in sims) {
                val segIdx = nonAdIndices[k]
                val t = segments[segIdx].start
                if (selectedTimes.all { kotlin.math.abs(t - it) >= minGapSec }) {
                    boundaries.add(segIdx)
                    selectedTimes.add(t)
                    if (boundaries.size >= maxChapters) break
                }
            }
            boundaries.sort()
        }

        val chapterTextsList = ArrayList<List<String>>(boundaries.size)
        val allChunkTokenSets = ArrayList<Set<String>>(boundaries.size)
        for (bPos in boundaries.indices) {
            val startIdx = boundaries[bPos]
            val endIdx = if (bPos + 1 < boundaries.size) boundaries[bPos + 1] else segments.size
            val txts = ArrayList<String>()
            val tset = HashSet<String>()
            for (idx in startIdx until endIdx) {
                val seg = segments[idx]
                if (!isInsideAd(seg.start, seg.end, adSegments)) {
                    txts.add(seg.text)
                    val toks = extractChapterTokens(seg.text)
                    for (ti in toks.indices) {
                        val low = toks[ti].first
                        tset.add(low)
                        if (ti + 1 < toks.size) {
                            tset.add("$low ${toks[ti + 1].first}")
                        }
                    }
                }
            }
            chapterTextsList.add(txts)
            allChunkTokenSets.add(tset)
        }

        val chapters = ArrayList<DesktopChapterDto>(boundaries.size)
        for (bPos in boundaries.indices) {
            var rawStart = segments[boundaries[bPos]].start
            if (bPos == 0 && rawStart < 15.0) {
                rawStart = 0.0
            }
            val startMs = max(0L, (rawStart * 1000.0).roundToLong())
            val title = nameChapterFromTfIdf(chapterTextsList[bPos], allChunkTokenSets, bPos)
            chapters.add(
                DesktopChapterDto(
                    startMs = startMs,
                    title = title,
                    source = "auto"
                )
            )
        }
        return chapters
    }
}
