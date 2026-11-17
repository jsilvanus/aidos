package dev.aidos.tts

/**
 * Rule-based Finnish grapheme-to-phoneme conversion, written in Kotlin so no phonemizer library or GPL code ships
 * with the Engine (RFC-0117 amendment). Finnish spelling is close to phonemic, so a rule table is enough for a voice
 * that was trained on espeak-style IPA. Output uses the same symbols as that vocabulary:
 *
 * - vowels a ɑ, e e, i i, o o, u u, y y, ä æ, ö ø; a doubled vowel is the vowel plus `ː`
 * - consonants are one symbol each, a doubled consonant is that symbol twice (no length mark)
 * - ng ŋ, sh ʃ, nk ŋk, ch tʃ (loanwords), c is k or s before e, i, y, ä, ö
 * - stress: a primary stress mark `ˈ` at the start of every word
 *
 * Known limits (documented in RFC-0117): no loanword handling beyond these digraphs, no compound-boundary stress,
 * digits are dropped. Tests pin the output for common words.
 */
class FinnishPhonemizer : Phonemizer {
    override fun phonemize(text: String): List<String> {
        val out = ArrayList<String>()
        for (word in words(text.lowercase())) {
            if (out.isNotEmpty()) out += " "
            out += "ˈ"
            out += wordPhonemes(word)
        }
        return out
    }

    private fun words(s: String): List<String> {
        val result = ArrayList<String>()
        val current = StringBuilder()
        for (c in s) {
            val folded = fold(c)
            if (folded != null) current.append(folded) else if (current.isNotEmpty()) {
                result += current.toString(); current.clear()
            }
        }
        if (current.isNotEmpty()) result += current.toString()
        return result
    }

    /** Lowercase letter to the Finnish letter it is spoken as; null for anything not spoken. */
    private fun fold(c: Char): Char? = when {
        c in 'a'..'z' || c == 'ä' || c == 'ö' -> c
        c == 'å' -> 'o'
        c == 'é' || c == 'è' -> 'e'
        c == 'ü' -> 'y'
        else -> null
    }

    private fun wordPhonemes(w: String): List<String> {
        val out = ArrayList<String>()
        var i = 0
        while (i < w.length) {
            val c = w[i]
            val next = w.getOrNull(i + 1)
            when {
                c == 'n' && next == 'g' -> { out += "ŋ"; i += 2; continue }
                c == 'n' && next == 'k' -> { out += listOf("ŋ", "k"); i += 2; continue }
                c == 's' && next == 'h' -> { out += "ʃ"; i += 2; continue }
                c == 'c' && next == 'h' -> { out += listOf("t", "ʃ"); i += 2; continue }
                c == 't' && next == 'h' -> { out += "t"; i += 2; continue }
                isVowel(c) -> {
                    out += vowel(c)
                    if (next == c) { out += "ː"; i += 2 } else i++
                    continue
                }
                else -> {
                    // A geminate consonant (pp, kk) is two occurrences, so it is emitted twice without a length mark.
                    out += consonant(c, next)
                    i++
                }
            }
        }
        return out
    }

    private fun isVowel(c: Char) = c in "aeiouyäö"

    private fun vowel(c: Char): String = when (c) {
        'a' -> "ɑ"
        'ä' -> "æ"
        'ö' -> "ø"
        else -> c.toString()
    }

    private fun consonant(c: Char, next: Char?): List<String> = when (c) {
        'b' -> listOf("b")
        'c' -> listOf(if (next != null && next in "eiyäö") "s" else "k")
        'd' -> listOf("d")
        'f' -> listOf("f")
        'g' -> listOf("ɡ")
        'h' -> listOf("h")
        'j' -> listOf("j")
        'k' -> listOf("k")
        'l' -> listOf("l")
        'm' -> listOf("m")
        'n' -> listOf("n")
        'p' -> listOf("p")
        'q' -> listOf("k")
        'r' -> listOf("r")
        's' -> listOf("s")
        't' -> listOf("t")
        'v', 'w' -> listOf("v")
        'x' -> listOf("k", "s")
        'z' -> listOf("t", "s")
        else -> emptyList()
    }
}
