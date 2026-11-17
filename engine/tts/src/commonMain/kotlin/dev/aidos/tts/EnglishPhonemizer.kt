package dev.aidos.tts

/**
 * English text to phonemes from a CMU Pronouncing Dictionary (BSD-2-Clause data, shipped as `cmudict.dict` in the
 * voice package) with a small letter-to-sound fallback for words the dictionary lacks. Pronunciations are ARPAbet,
 * converted to the espeak-style IPA symbols the voice was trained on. Stress digits become `ˈ` (primary) and `ˌ`
 * (secondary) before their vowel.
 *
 * Known limits (RFC-0117): the fallback is approximate and will mispronounce many unseen words; numbers are dropped,
 * not spelled out. The dictionary is a data file fetched with the package, never bundled in the source.
 */
class EnglishPhonemizer(private val dictionary: Map<String, List<String>>) : Phonemizer {
    override fun phonemize(text: String): List<String> {
        val out = ArrayList<String>()
        for (word in words(text)) {
            if (out.isNotEmpty()) out += " "
            val arpabet = dictionary[word]
            if (arpabet != null) out += arpabetToIpa(arpabet) else out += fallback(word)
        }
        return out
    }

    private fun words(s: String): List<String> {
        val result = ArrayList<String>()
        val current = StringBuilder()
        for (raw in s.lowercase()) {
            val c = if (raw == '’') '\'' else raw
            if (c in 'a'..'z' || c == '\'') {
                current.append(c)
            } else if (current.isNotEmpty()) {
                result += current.toString().trim('\''); current.clear()
            }
        }
        if (current.isNotEmpty()) result += current.toString().trim('\'')
        return result.filter { it.isNotEmpty() }
    }

    private fun arpabetToIpa(phones: List<String>): List<String> {
        val out = ArrayList<String>()
        for (p in phones) {
            val stress = p.lastOrNull()?.takeIf { it.isDigit() }
            val base = if (stress != null) p.dropLast(1) else p
            val ipa = IPA[base] ?: continue
            when (stress) {
                '1' -> out += "ˈ"
                '2' -> out += "ˌ"
            }
            out += ipa
        }
        return out
    }

    private fun fallback(word: String): List<String> {
        val out = mutableListOf("ˈ")
        var i = 0
        while (i < word.length) {
            val digraph = DIGRAPHS.entries.firstOrNull { word.startsWith(it.key, i) }
            if (digraph != null) {
                out += digraph.value; i += digraph.key.length; continue
            }
            LETTERS[word[i]]?.let { out += it }
            i++
        }
        return out
    }

    companion object {
        /** Parses a CMU dictionary text (`WORD  P1 P2 ...`, `;;;` comments). Keeps the first pronunciation of each word. */
        fun parseCmudict(text: String): Map<String, List<String>> {
            val map = HashMap<String, List<String>>()
            for (line in text.lineSequence()) {
                if (line.startsWith(";;;") || line.isBlank()) continue
                val parts = line.trim().split(Regex("\\s+"))
                if (parts.size < 2) continue
                val word = parts[0].substringBefore('(').lowercase()
                if (word in map) continue
                map[word] = parts.drop(1)
            }
            return map
        }

        private val IPA: Map<String, List<String>> = mapOf(
            "AA" to listOf("ɑ"), "AE" to listOf("æ"), "AH" to listOf("ʌ"), "AO" to listOf("ɔ"),
            "AW" to listOf("a", "ʊ"), "AY" to listOf("a", "ɪ"), "EH" to listOf("ɛ"), "ER" to listOf("ɝ"),
            "EY" to listOf("e", "ɪ"), "IH" to listOf("ɪ"), "IY" to listOf("i"), "OW" to listOf("o", "ʊ"),
            "OY" to listOf("ɔ", "ɪ"), "UH" to listOf("ʊ"), "UW" to listOf("u"),
            "B" to listOf("b"), "CH" to listOf("t", "ʃ"), "D" to listOf("d"), "DH" to listOf("ð"),
            "F" to listOf("f"), "G" to listOf("ɡ"), "HH" to listOf("h"), "JH" to listOf("d", "ʒ"),
            "K" to listOf("k"), "L" to listOf("l"), "M" to listOf("m"), "N" to listOf("n"), "NG" to listOf("ŋ"),
            "P" to listOf("p"), "R" to listOf("ɹ"), "S" to listOf("s"), "SH" to listOf("ʃ"), "T" to listOf("t"),
            "TH" to listOf("θ"), "V" to listOf("v"), "W" to listOf("w"), "Y" to listOf("j"), "Z" to listOf("z"),
            "ZH" to listOf("ʒ"),
        )

        private val DIGRAPHS: Map<String, List<String>> = mapOf(
            "tch" to listOf("t", "ʃ"), "th" to listOf("θ"), "sh" to listOf("ʃ"), "ch" to listOf("t", "ʃ"),
            "ng" to listOf("ŋ"), "ph" to listOf("f"), "ck" to listOf("k"),
        )

        private val LETTERS: Map<Char, List<String>> = mapOf(
            'a' to listOf("æ"), 'b' to listOf("b"), 'c' to listOf("k"), 'd' to listOf("d"), 'e' to listOf("ɛ"),
            'f' to listOf("f"), 'g' to listOf("ɡ"), 'h' to listOf("h"), 'i' to listOf("ɪ"), 'j' to listOf("d", "ʒ"),
            'k' to listOf("k"), 'l' to listOf("l"), 'm' to listOf("m"), 'n' to listOf("n"), 'o' to listOf("ɒ"),
            'p' to listOf("p"), 'q' to listOf("k"), 'r' to listOf("ɹ"), 's' to listOf("s"), 't' to listOf("t"),
            'u' to listOf("ʌ"), 'v' to listOf("v"), 'w' to listOf("w"), 'x' to listOf("k", "s"), 'y' to listOf("j"),
            'z' to listOf("z"),
        )
    }
}
