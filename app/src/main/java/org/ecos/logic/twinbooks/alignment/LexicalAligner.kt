package org.ecos.logic.twinbooks.alignment

import java.text.Normalizer
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * Offline sentence aligner for one chapter pair.
 *
 * Input: the LEFT sentences already machine-translated into the right language (on-device
 * EN→ES) and the RIGHT sentences. Both sides are then Spanish, so a lexical similarity
 * works: IDF-weighted Dice over lightly stemmed content words, damped by length ratio.
 *
 * Alignment is a banded, monotonic DP (Gale–Church style) with moves 1:1, 1:0, 0:1,
 * 2:1 and 1:2, so translators merging/splitting sentences don't derail the rest.
 */
object LexicalAligner {

    data class AlignedPair(val left: Int, val right: Int, val score: Float)

    private const val GAP = -0.1f
    private const val MERGE_PENALTY = -0.05f
    private const val NEG = -1e9f

    private val STOPWORDS = setOf(
        "de", "la", "que", "el", "en", "y", "a", "los", "se", "del", "las", "un", "por", "con",
        "no", "una", "su", "para", "es", "al", "lo", "como", "mas", "o", "pero", "sus", "le",
        "ha", "me", "si", "sin", "sobre", "este", "ya", "entre", "cuando", "todo", "esta", "ser",
        "son", "dos", "tambien", "fue", "habia", "era", "muy", "hasta", "desde", "mi", "porque",
        "yo", "tu", "te", "ti", "el", "ella", "ellos", "ellas", "nos", "nosotros", "vosotros",
        "usted", "ustedes", "les", "mis", "tus", "nuestro", "nuestra", "eso", "esto", "ese",
        "esa", "esos", "esas", "estos", "estas", "aquel", "aquella", "otro", "otra", "otros",
        "otras", "algo", "nada", "donde", "quien", "cual", "cuyo", "e", "u", "ni", "sino",
        "aunque", "pues", "asi", "aqui", "alli", "ahi", "entonces", "luego", "tan", "tanto",
        "estaba", "estaban", "estar", "estoy", "esta", "estan", "hay", "he", "has", "han",
        "hemos", "habia", "habian", "sido", "ser", "soy", "eres", "somos", "fueron", "seria",
        "puede", "podia", "hacer", "hizo", "dijo", "unos", "unas", "cada", "solo", "mucho",
        "poco", "bien", "mal", "ahora", "siempre", "nunca", "tambien", "todavia", "aun", "casi"
    )

    fun align(translatedLeft: List<String>, right: List<String>): List<AlignedPair> {
        val n = translatedLeft.size
        val m = right.size
        if (n == 0 || m == 0) return emptyList()

        val leftTokens = translatedLeft.map { tokenize(it) }
        val rightTokens = right.map { tokenize(it) }
        val idf = buildIdf(leftTokens + rightTokens)
        val leftLen = translatedLeft.map { it.length.coerceAtLeast(1) }
        val rightLen = right.map { it.length.coerceAtLeast(1) }

        fun weight(tokens: Set<String>) = tokens.sumOf { (idf[it] ?: 0.0) }

        fun sim(a: Set<String>, lenA: Int, b: Set<String>, lenB: Int): Float {
            val lengthScore = exp(-abs(ln(lenA.toDouble() / lenB))).toFloat()
            if (a.isEmpty() || b.isEmpty()) return 0.2f * lengthScore
            val common = a.intersect(b).sumOf { idf[it] ?: 0.0 }
            val dice = (2 * common / (weight(a) + weight(b))).toFloat()
            return dice * (0.5f + 0.5f * lengthScore)
        }

        // Band around the diagonal: the path cannot drift further than this
        val band = max(40, (max(n, m) * 0.15).toInt()) + abs(n - m)
        val lo = IntArray(n + 1) { i -> max(0, (i.toLong() * m / n).toInt() - band) }
        val hi = IntArray(n + 1) { i -> min(m, (i.toLong() * m / n).toInt() + band) }
        val dp = Array(n + 1) { i -> FloatArray(hi[i] - lo[i] + 1) { NEG } }
        // 0=1:1, 1=skip left, 2=skip right, 3=2:1, 4=1:2
        val trace = Array(n + 1) { i -> ByteArray(hi[i] - lo[i] + 1) }

        fun get(i: Int, j: Int): Float =
            if (i < 0 || j < lo[i] || j > hi[i]) NEG else dp[i][j - lo[i]]

        if (lo[0] == 0) dp[0][0] = 0f
        for (i in 0..n) {
            for (j in lo[i]..hi[i]) {
                if (i == 0 && j == 0) continue
                var best = NEG
                var dir = 0
                fun consider(prev: Float, gain: Float, d: Int) {
                    if (prev <= NEG / 2) return
                    val v = prev + gain
                    if (v > best) { best = v; dir = d }
                }
                if (i > 0 && j > 0) {
                    consider(get(i - 1, j - 1), sim(leftTokens[i - 1], leftLen[i - 1], rightTokens[j - 1], rightLen[j - 1]), 0)
                }
                if (i > 0) consider(get(i - 1, j), GAP, 1)
                if (j > 0) consider(get(i, j - 1), GAP, 2)
                if (i > 1 && j > 0) {
                    val merged = leftTokens[i - 2] + leftTokens[i - 1]
                    consider(get(i - 2, j - 1), sim(merged, leftLen[i - 2] + leftLen[i - 1], rightTokens[j - 1], rightLen[j - 1]) + MERGE_PENALTY, 3)
                }
                if (i > 0 && j > 1) {
                    val merged = rightTokens[j - 2] + rightTokens[j - 1]
                    consider(get(i - 1, j - 2), sim(leftTokens[i - 1], leftLen[i - 1], merged, rightLen[j - 2] + rightLen[j - 1]) + MERGE_PENALTY, 4)
                }
                dp[i][j - lo[i]] = best
                trace[i][j - lo[i]] = dir.toByte()
            }
        }

        if (get(n, m) <= NEG / 2) return emptyList()

        val result = mutableListOf<AlignedPair>()
        var i = n
        var j = m
        while (i > 0 || j > 0) {
            when (trace[i][j - lo[i]].toInt()) {
                0 -> {
                    result.add(AlignedPair(i - 1, j - 1, sim(leftTokens[i - 1], leftLen[i - 1], rightTokens[j - 1], rightLen[j - 1])))
                    i--; j--
                }
                1 -> i--
                2 -> j--
                3 -> {
                    val s = get(i, j) - get(i - 2, j - 1) - MERGE_PENALTY
                    result.add(AlignedPair(i - 1, j - 1, s))
                    result.add(AlignedPair(i - 2, j - 1, s))
                    i -= 2; j--
                }
                4 -> {
                    val s = get(i, j) - get(i - 1, j - 2) - MERGE_PENALTY
                    result.add(AlignedPair(i - 1, j - 1, s))
                    result.add(AlignedPair(i - 1, j - 2, s))
                    i--; j -= 2
                }
            }
        }
        return result.reversed()
    }

    private fun buildIdf(docs: List<Set<String>>): Map<String, Double> {
        val df = HashMap<String, Int>()
        docs.forEach { d -> d.forEach { df[it] = (df[it] ?: 0) + 1 } }
        val total = docs.size.toDouble()
        return df.mapValues { (_, c) -> ln(1.0 + total / c) }
    }

    internal fun tokenize(text: String): Set<String> {
        val normalized = Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
        return normalized.split(Regex("[^\\p{L}\\p{N}]+"))
            .asSequence()
            .filter { it.isNotEmpty() }
            .filter { it.all(Char::isDigit) || (it.length > 1 && it !in STOPWORDS) }
            .map { stem(it) }
            .toSet()
    }

    /** Very light Spanish stemmer: drops plural 's' and a final gender vowel, keeps 6 chars. */
    private fun stem(word: String): String {
        if (word.all(Char::isDigit)) return word
        var w = word
        if (w.length > 3 && w.endsWith("s")) w = w.dropLast(1)
        if (w.length > 3 && w.last() in "aeo") w = w.dropLast(1)
        return w.take(6)
    }
}
