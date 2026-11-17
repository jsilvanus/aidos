package dev.aidos.tts

/**
 * Input rules shared by every voice. The limit is a constant so a caller can plan around it: longer text is refused
 * rather than silently cut, and [split] is what a host uses to speak text in sentence-sized pieces.
 */
object SpeechText {
    const val MAX_INPUT_CHARS = 4000

    /** Trims [input] and refuses blank or over-long text with [TtsRequestException] (HTTP 400 on the route). */
    fun validate(input: String): String {
        val text = input.trim()
        if (text.isEmpty()) throw TtsRequestException("input must not be empty")
        if (text.length > MAX_INPUT_CHARS) {
            throw TtsRequestException("input is ${text.length} characters, the limit is $MAX_INPUT_CHARS")
        }
        return text
    }

    /**
     * Splits [text] into pieces of at most [maxChars], preferring sentence ends ([.!?] followed by whitespace), then
     * the last space before the limit. A single word longer than [maxChars] is cut as a last resort.
     */
    fun split(text: String, maxChars: Int = 400): List<String> {
        require(maxChars > 0) { "maxChars must be positive" }
        val pieces = mutableListOf<String>()
        var rest = text.trim()
        while (rest.length > maxChars) {
            val window = rest.substring(0, maxChars + 1)
            val sentenceEnd = window.indexOfLast { it == '.' || it == '!' || it == '?' }
                .takeIf { it > 0 && it < window.length - 1 && window[it + 1].isWhitespace() }
            val cut = sentenceEnd?.let { it + 1 } ?: window.lastIndexOf(' ').takeIf { it > 0 } ?: maxChars
            pieces += rest.substring(0, cut).trim()
            rest = rest.substring(cut).trim()
        }
        if (rest.isNotEmpty()) pieces += rest
        return pieces
    }
}
