package org.ecos.logic.twinbooks.text

/**
 * Rule-based sentence splitter for English and Spanish prose.
 *
 * Returned sentences are exact (trimmed) substrings of the input, so they can be
 * located verbatim in the DOM for highlighting.
 *
 * A boundary is a run of terminal punctuation (. ! ? …), optionally followed by
 * closing quotes/brackets, then whitespace, then the start of a new sentence
 * (uppercase letter, digit, ¿, ¡, opening quote, or a dialogue dash followed by one
 * of those). It is NOT a boundary when:
 *  - the token before a '.' is a known abbreviation (Mr., Sra.) or a single initial (J.)
 *  - the next word starts lowercase ('"Stop!" he cried.', '—¿Qué? —dijo él.')
 */
object SentenceSplitter {

    // Only abbreviations usually followed by a capitalized word. Ones that often end a
    // sentence ("etc.", "no.") are left out on purpose: splitting there is the lesser evil.
    private val ABBREVIATIONS = setOf(
        // English
        "mr", "mrs", "ms", "dr", "prof", "st", "jr", "sr", "mt", "vs", "e.g", "i.e",
        "gen", "col", "capt", "lt", "sgt", "rev", "hon", "gov",
        // Spanish
        "sra", "srta", "dña", "dn", "ud", "uds", "vd", "vds", "lic", "ing", "arq",
        "avda", "p.ej", "ej"
    )

    private val TERMINALS = setOf('.', '!', '?', '…')
    private val CLOSERS = setOf('"', '\'', '”', '’', '»', ')', ']')
    private val OPENERS = setOf('"', '\'', '“', '‘', '«', '(', '[', '¿', '¡')
    private val DASHES = setOf('—', '–', '-')

    fun split(text: String): List<String> {
        val sentences = mutableListOf<String>()
        var start = 0
        var i = 0
        val n = text.length

        while (i < n) {
            if (text[i] !in TERMINALS) { i++; continue }

            val punctStart = i
            while (i < n && text[i] in TERMINALS) i++
            while (i < n && text[i] in CLOSERS) i++
            val end = i

            if (i < n && !text[i].isWhitespace()) continue
            while (i < n && text[i].isWhitespace()) i++
            if (i >= n) break

            if (!startsNewSentence(text, i)) continue
            if (text.substring(punctStart, end).trimEnd(*CLOSERS.toCharArray()) == "." &&
                isAbbreviation(text, punctStart)
            ) continue

            text.substring(start, end).trim().takeIf { it.isNotEmpty() }?.let { sentences.add(it) }
            start = i
        }

        text.substring(start).trim().takeIf { it.isNotEmpty() }?.let { sentences.add(it) }
        return sentences
    }

    private fun startsNewSentence(text: String, pos: Int): Boolean {
        var p = pos
        if (text[p] in DASHES) {
            p++
            while (p < text.length && text[p].isWhitespace()) p++
            if (p >= text.length) return false
        }
        while (p < text.length && text[p] in OPENERS) {
            if (text[p] == '¿' || text[p] == '¡') return true
            p++
        }
        if (p >= text.length) return false
        val c = text[p]
        return c.isUpperCase() || c.isDigit()
    }

    /** True when the word ending at [dotPos] (exclusive of the dot) is an abbreviation or an initial. */
    private fun isAbbreviation(text: String, dotPos: Int): Boolean {
        var s = dotPos
        while (s > 0 && (text[s - 1].isLetter() || text[s - 1] == '.')) s--
        val word = text.substring(s, dotPos)
        if (word.isEmpty()) return false
        // Single capital = initial ("J. K. Rowling"), except the English pronoun "I"
        if (word.length == 1 && word[0].isUpperCase()) return word != "I"
        return word.lowercase() in ABBREVIATIONS
    }
}
