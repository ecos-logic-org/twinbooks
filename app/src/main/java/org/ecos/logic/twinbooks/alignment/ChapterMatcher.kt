package org.ecos.logic.twinbooks.alignment

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.min

/**
 * Maps each LEFT chapter (EPUB spine item) to its RIGHT counterpart.
 *
 * Editions rarely have identical spines: one has an extra dedication, the translator's
 * note, split front matter... A constant "+1 on both sides" keeps any initial offset
 * forever. This runs a monotonic DP over chapters using:
 *  - text length, normalised by the global right/left length ratio
 *  - numbers in the titles ("Chapter 3" / "Capítulo III" / "Capítulo tres")
 *  - user-forced pairs (manual corrections), which the path must go through
 */
object ChapterMatcher {

    data class ChapterInfo(val textLength: Int, val title: String)

    private const val GAP = -0.1f
    // Scores are centred on this value, so pairing two unrelated spine items (score ~0)
    // costs more than leaving both unmatched (2 * GAP)
    private const val MATCH_THRESHOLD = 0.25
    private const val FORCED_BONUS = 100f

    /** @return array indexed by left chapter; value = right chapter, or -1 if unmatched. */
    fun match(
        left: List<ChapterInfo>,
        right: List<ChapterInfo>,
        forced: Map<Int, Int> = emptyMap()
    ): IntArray {
        val n = left.size
        val m = right.size
        val map = IntArray(n) { -1 }
        if (n == 0 || m == 0) return map

        val ratio = right.sumOf { it.textLength.toLong() }.coerceAtLeast(1).toDouble() /
                left.sumOf { it.textLength.toLong() }.coerceAtLeast(1)
        val leftNums = left.map { titleNumber(it.title) }
        val rightNums = right.map { titleNumber(it.title) }

        fun score(i: Int, j: Int): Float {
            if (forced[i] == j) return FORCED_BONUS
            // Publisher boilerplate (Project Gutenberg license) never has a counterpart
            if (BOILERPLATE.containsMatchIn(left[i].title) || BOILERPLATE.containsMatchIn(right[j].title)) return -1f
            val a = left[i].textLength.coerceAtLeast(1) * ratio
            val b = right[j].textLength.coerceAtLeast(1).toDouble()
            // Tiny spine items (cover, title page) carry little length evidence
            val confidence = min(1.0, min(a, b) / 3000.0)
            val lengthScore = exp(-2 * abs(ln(a / b)))
            var s = (0.3 + 0.7 * confidence) * lengthScore
            val ln = leftNums[i]
            val rn = rightNums[j]
            if (ln != null && rn != null) s += if (ln == rn) 0.6 else -0.4
            return (s - MATCH_THRESHOLD).toFloat()
        }

        val dp = Array(n + 1) { FloatArray(m + 1) }
        val trace = Array(n + 1) { ByteArray(m + 1) }
        for (i in 1..n) { dp[i][0] = i * GAP; trace[i][0] = 1 }
        for (j in 1..m) { dp[0][j] = j * GAP; trace[0][j] = 2 }
        for (i in 1..n) {
            for (j in 1..m) {
                var best = dp[i - 1][j - 1] + score(i - 1, j - 1)
                var dir: Byte = 0
                if (dp[i - 1][j] + GAP > best) { best = dp[i - 1][j] + GAP; dir = 1 }
                if (dp[i][j - 1] + GAP > best) { best = dp[i][j - 1] + GAP; dir = 2 }
                dp[i][j] = best
                trace[i][j] = dir
            }
        }

        var i = n
        var j = m
        while (i > 0 || j > 0) {
            when (trace[i][j].toInt()) {
                0 -> { map[i - 1] = j - 1; i--; j-- }
                1 -> i--
                else -> j--
            }
        }

        // A real chapter paired with something of a very different size is not a pair
        // (typical of 1:N splits, e.g. a whole novella in one EN file vs ES chapters)
        for (l in map.indices) {
            val r = map[l]
            if (r < 0 || forced[l] == r) continue
            val a = left[l].textLength * ratio
            val b = right[r].textLength.toDouble()
            if (maxOf(a, b) >= MIN_CONTENT_LENGTH && abs(ln(a.coerceAtLeast(1.0) / b.coerceAtLeast(1.0))) > ln(MAX_LENGTH_RATIO)) {
                map[l] = -1
            }
        }
        return map
    }

    /** Spine items shorter than this are front/back matter (cover, title page, credits). */
    const val MIN_CONTENT_LENGTH = 3000
    private const val MAX_LENGTH_RATIO = 1.8

    private val NUMBER_WORDS = mapOf(
        "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6, "seven" to 7,
        "eight" to 8, "nine" to 9, "ten" to 10, "eleven" to 11, "twelve" to 12, "thirteen" to 13,
        "fourteen" to 14, "fifteen" to 15, "sixteen" to 16, "seventeen" to 17, "eighteen" to 18,
        "nineteen" to 19, "twenty" to 20,
        "uno" to 1, "dos" to 2, "tres" to 3, "cuatro" to 4, "cinco" to 5, "seis" to 6, "siete" to 7,
        "ocho" to 8, "nueve" to 9, "diez" to 10, "once" to 11, "doce" to 12, "trece" to 13,
        "catorce" to 14, "quince" to 15, "dieciseis" to 16, "diecisiete" to 17, "dieciocho" to 18,
        "diecinueve" to 19, "veinte" to 20
    )

    private val ROMAN = Regex("^[ivxlc]+$")
    private val BOILERPLATE = Regex("gutenberg|licen[cs]e", RegexOption.IGNORE_CASE)

    /** First number found in a chapter title: digits, roman numerals or number words (EN/ES). */
    internal fun titleNumber(title: String): Int? {
        val words = java.text.Normalizer.normalize(title.lowercase(), java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .split(Regex("[^a-z0-9]+"))
            .filter { it.isNotEmpty() }
        for (w in words) {
            w.toIntOrNull()?.let { return it }
            NUMBER_WORDS[w]?.let { return it }
            // "i" alone is too ambiguous in English prose titles unless it's the only word
            if (ROMAN.matches(w) && (w != "i" || words.size <= 2)) romanToInt(w)?.let { return it }
        }
        return null
    }

    private fun romanToInt(s: String): Int? {
        val values = mapOf('i' to 1, 'v' to 5, 'x' to 10, 'l' to 50, 'c' to 100)
        var total = 0
        for (k in s.indices) {
            val v = values[s[k]] ?: return null
            val next = if (k + 1 < s.length) values[s[k + 1]] ?: 0 else 0
            total += if (v < next) -v else v
        }
        // Reject words that merely look roman ("civil", "mix"): must round-trip canonically
        return total.takeIf { it in 1..399 && intToRoman(it) == s }
    }

    private fun intToRoman(value: Int): String {
        val table = listOf(100 to "c", 90 to "xc", 50 to "l", 40 to "xl", 10 to "x", 9 to "ix", 5 to "v", 4 to "iv", 1 to "i")
        var v = value
        val sb = StringBuilder()
        for ((num, sym) in table) while (v >= num) { sb.append(sym); v -= num }
        return sb.toString()
    }
}
