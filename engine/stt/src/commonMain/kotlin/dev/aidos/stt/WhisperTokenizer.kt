package dev.aidos.stt

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Whisper's GPT-2 style byte-level BPE, read from a Hugging Face `tokenizer.json`. Decoding is what
 * transcription needs; encoding exists only so a caller's `prompt` (spelling hints) can be fed back as
 * the decoder's previous-text context.
 */
class WhisperTokenizer private constructor(
    private val idToToken: Array<String?>,
    private val tokenToId: Map<String, Int>,
    private val mergeRank: Map<Pair<String, String>, Int>,
    /** `<|...|>` markers by content. */
    val special: Map<String, Int>,
) {
    val eot: Int = special.getValue("<|endoftext|>")
    val sot: Int = special.getValue("<|startoftranscript|>")
    val transcribe: Int = special.getValue("<|transcribe|>")
    val noTimestamps: Int = special.getValue("<|notimestamps|>")
    val startOfPrev: Int? = special["<|startofprev|>"]
    val noSpeech: Int? = special["<|nospeech|>"] ?: special["<|nocaptions|>"]

    /** First timestamp token; everything from here up is a `<|0.00|>` style marker. */
    val timestampBegin: Int = noTimestamps + 1

    /** Language code to token id, e.g. "fi" to `<|fi|>`. */
    val languages: Map<String, Int> =
        special.filterKeys { LANG.matches(it) }.mapKeys { it.key.substring(2, it.key.length - 2) }

    private val specialIds: Set<Int> = special.values.toSet()

    fun languageToken(code: String): Int =
        languages[code.lowercase().substringBefore('-')]
            ?: throw SttRequestException("unsupported language '$code'")

    /** Text for [ids]; special and timestamp tokens are dropped. */
    fun decode(ids: List<Int>): String {
        val sb = StringBuilder()
        for (id in ids) {
            if (id >= timestampBegin || id in specialIds) continue
            sb.append(idToToken.getOrNull(id) ?: continue)
        }
        val bytes = ByteArray(sb.length) { i ->
            UNICODE_TO_BYTE[sb[i]]?.toByte() ?: '?'.code.toByte()
        }
        return bytes.decodeToString()
    }

    fun encode(text: String): List<Int> {
        val out = ArrayList<Int>()
        for (m in PRETOKEN.findAll(text)) {
            val mapped = m.value.encodeToByteArray().joinToString("") { BYTE_TO_UNICODE[it.toInt() and 0xFF].toString() }
            out += bpe(mapped).mapNotNull { tokenToId[it] }
        }
        return out
    }

    private fun bpe(word: String): List<String> {
        var parts = word.map { it.toString() }
        while (parts.size > 1) {
            var best = Int.MAX_VALUE
            var at = -1
            for (i in 0 until parts.size - 1) {
                val r = mergeRank[parts[i] to parts[i + 1]] ?: continue
                if (r < best) { best = r; at = i }
            }
            if (at < 0) break
            parts = parts.subList(0, at) + (parts[at] + parts[at + 1]) + parts.subList(at + 2, parts.size)
        }
        return parts
    }

    companion object {
        private val LANG = Regex("<\\|[a-z]{2,3}\\|>")
        private val PRETOKEN = Regex("'s|'t|'re|'ve|'m|'ll|'d| ?\\p{L}+| ?\\p{N}+| ?[^\\s\\p{L}\\p{N}]+|\\s+(?!\\S)|\\s+")
        private val BYTE_TO_UNICODE: CharArray
        private val UNICODE_TO_BYTE: Map<Char, Int>

        init {
            val keep = ((0x21..0x7E) + (0xA1..0xAC) + (0xAE..0xFF)).toSet()
            val table = CharArray(256)
            var extra = 0
            for (b in 0..255) table[b] = if (b in keep) b.toChar() else (256 + extra++).toChar()
            BYTE_TO_UNICODE = table
            UNICODE_TO_BYTE = table.withIndex().associate { it.value to it.index }
        }

        fun fromJson(json: String): WhisperTokenizer {
            val root = Json.parseToJsonElement(json).jsonObject
            val model = root.getValue("model").jsonObject
            val vocab = model.getValue("vocab").jsonObject
            val tokenToId = HashMap<String, Int>(vocab.size * 2)
            var maxId = 0
            for ((tok, id) in vocab) { val i = id.jsonPrimitive.int; tokenToId[tok] = i; if (i > maxId) maxId = i }
            val special = HashMap<String, Int>()
            val added = (root["added_tokens"] as? JsonArray).orEmpty().map { it.jsonObject }
            for (a in added) { val id = a.getValue("id").jsonPrimitive.int; if (id > maxId) maxId = id }
            val idToToken = arrayOfNulls<String>(maxId + 1)
            for ((tok, id) in tokenToId) idToToken[id] = tok
            for (a in added) {
                val content = a.getValue("content").jsonPrimitive.content
                val id = a.getValue("id").jsonPrimitive.int
                idToToken[id] = content
                if (content.startsWith("<|") && content.endsWith("|>") ||
                    (a["special"] as? JsonPrimitive)?.boolean == true
                ) special[content] = id
            }
            val ranks = HashMap<Pair<String, String>, Int>()
            (model["merges"] as? JsonArray).orEmpty().forEachIndexed { rank, m ->
                val pair = when (m) {
                    is JsonArray -> m[0].jsonPrimitive.content to m[1].jsonPrimitive.content
                    else -> m.jsonPrimitive.content.split(' ', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
                }
                ranks[pair] = rank
            }
            return WhisperTokenizer(idToToken, tokenToId, ranks, special)
        }
    }
}

private fun JsonArray?.orEmpty(): List<kotlinx.serialization.json.JsonElement> = this ?: emptyList()
