package dev.aidos.tts

/**
 * Text to phoneme symbols for one language. Each token is a single code point, the way Piper's voice vocabularies
 * are written (espeak-style IPA). A token the voice does not know is dropped by [PiperPhonemeEncoder].
 */
interface Phonemizer {
    fun phonemize(text: String): List<String>
}

/** Splits a string into code-point tokens: "ˈa" becomes ["ˈ", "a"]. */
internal fun codePoints(s: String): List<String> {
    val out = ArrayList<String>(s.length)
    var i = 0
    while (i < s.length) {
        val n = if (s[i].isHighSurrogate() && i + 1 < s.length && s[i + 1].isLowSurrogate()) 2 else 1
        out += s.substring(i, i + n)
        i += n
    }
    return out
}
