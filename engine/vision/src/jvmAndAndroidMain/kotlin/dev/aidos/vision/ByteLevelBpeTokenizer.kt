package dev.aidos.vision

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The GPT-2 style byte-level BPE that SmolVLM's `tokenizer.json` describes: digits split one by one,
 * then the GPT-2 word regex, then BPE merges over the byte-to-unicode alphabet. Added tokens (the
 * `<image>` / `<end_of_utterance>` family) are matched literally before any of that.
 *
 * Written for this one tokenizer.json shape, not for the format in general; [fromJson] rejects
 * anything else (a Unigram model, a normalizer) instead of encoding it subtly wrong.
 */
class ByteLevelBpeTokenizer private constructor(
    private val vocab: Map<String, Int>,
    private val idToToken: Array<String?>,
    private val mergeRank: Map<Pair<String, String>, Int>,
    private val added: Map<String, Int>,
    private val specialIds: Set<Int>,
    private val printedAdded: Map<Int, ByteArray>,
) {
    private val addedPattern: Regex? = added.keys.takeIf { it.isNotEmpty() }
        ?.sortedByDescending { it.length }
        ?.joinToString("|") { Regex.escape(it) }
        ?.let { Regex(it) }
    private val bpeCache = HashMap<String, List<Int>>()

    /** The id of an added or vocabulary token, or null when it is not one. */
    fun tokenId(token: String): Int? = added[token] ?: vocab[token]

    fun encode(text: String): IntArray {
        val out = ArrayList<Int>()
        var from = 0
        val pattern = addedPattern
        if (pattern != null) {
            for (m in pattern.findAll(text)) {
                if (m.range.first > from) encodePlain(text.substring(from, m.range.first), out)
                out += added.getValue(m.value)
                from = m.range.last + 1
            }
        }
        if (from < text.length) encodePlain(text.substring(from), out)
        return out.toIntArray()
    }

    /** Bytes of one token as the model emitted it; added tokens have none (they are never printed). */
    fun tokenBytes(id: Int): ByteArray {
        if (id in specialIds) return ByteArray(0)
        printedAdded[id]?.let { return it }
        val token = idToToken.getOrNull(id) ?: return ByteArray(0)
        return ByteArray(token.length) { UNICODE_TO_BYTE[token[it]]?.toByte() ?: '?'.code.toByte() }
    }

    fun decode(ids: IntArray): String {
        val bytes = java.io.ByteArrayOutputStream()
        for (id in ids) bytes.write(tokenBytes(id))
        return bytes.toByteArray().decodeToString()
    }

    private fun encodePlain(text: String, out: MutableList<Int>) {
        // Digits are isolated first (pre_tokenizer: Digits{individual_digits}), then each piece goes
        // through the ByteLevel regex.
        val piece = StringBuilder()
        fun flush() {
            if (piece.isEmpty()) return
            for (m in WORD.findAll(piece)) out += bpe(m.value)
            piece.setLength(0)
        }
        for (ch in text) {
            if (ch in '0'..'9') {
                flush()
                out += bpe(ch.toString())
            } else piece.append(ch)
        }
        flush()
    }

    private fun bpe(word: String): List<Int> = bpeCache.getOrPut(word) {
        val mapped = word.toByteArray(Charsets.UTF_8).map { BYTE_TO_UNICODE.getValue(it.toInt() and 0xFF).toString() }
        val parts = mapped.toMutableList()
        while (parts.size > 1) {
            var best = Int.MAX_VALUE
            var bestAt = -1
            for (i in 0 until parts.size - 1) {
                val rank = mergeRank[parts[i] to parts[i + 1]] ?: continue
                if (rank < best) { best = rank; bestAt = i }
            }
            if (bestAt < 0) break
            parts[bestAt] = parts[bestAt] + parts[bestAt + 1]
            parts.removeAt(bestAt + 1)
        }
        parts.map { vocab[it] ?: error("token not in vocabulary: $it") }
    }

    companion object {
        // GPT-2's pre-tokenizer pattern (ByteLevel use_regex).
        private val WORD = Regex("""'s|'t|'re|'ve|'m|'ll|'d| ?\p{L}+| ?\p{N}+| ?[^\s\p{L}\p{N}]+|\s+(?!\S)|\s+""")

        private val BYTE_TO_UNICODE: Map<Int, Char> = buildMap {
            val printable = (33..126) + (161..172) + (174..255)
            var extra = 0
            for (b in 0..255) {
                if (b in printable) put(b, b.toChar()) else put(b, (256 + extra++).toChar())
            }
        }
        private val UNICODE_TO_BYTE: Map<Char, Int> = BYTE_TO_UNICODE.entries.associate { (b, c) -> c to b }

        fun fromJson(json: String): ByteLevelBpeTokenizer {
            val root = Json.parseToJsonElement(json).jsonObject
            val model = root.getValue("model").jsonObject
            require(model["type"]?.jsonPrimitive?.content == "BPE") { "not a BPE tokenizer.json" }
            require(root["normalizer"].let { it == null || it is kotlinx.serialization.json.JsonNull }) {
                "tokenizer.json has a normalizer; this tokenizer implements none"
            }
            val vocab = model.getValue("vocab").jsonObject.mapValues { it.value.jsonPrimitive.int }
            val merges = model.getValue("merges").jsonArray.mapIndexed { rank, m ->
                val pair = when (m) {
                    is JsonArray -> m[0].jsonPrimitive.content to m[1].jsonPrimitive.content
                    is JsonPrimitive -> m.content.split(' ', limit = 2).let { it[0] to it[1] }
                    else -> error("unreadable merge entry")
                }
                pair to rank
            }.toMap()
            val addedList = root["added_tokens"]?.jsonArray.orEmpty().map { it as JsonObject }
            val added = addedList.associate { it.getValue("content").jsonPrimitive.content to it.getValue("id").jsonPrimitive.int }
            val special = addedList.filter { it["special"]?.jsonPrimitive?.content == "true" }
                .map { it.getValue("id").jsonPrimitive.int }.toSet()
            val size = (vocab.values + added.values).max() + 1
            val byId = arrayOfNulls<String>(size)
            vocab.forEach { (t, id) -> byId[id] = t }
            // A non-special added token is ordinary text when decoded; a special one is never printed.
            val printed = added.filterValues { it !in special }.entries.associate { (t, id) -> id to t.encodeToByteArray() }
            return ByteLevelBpeTokenizer(vocab, byId, merges, added, special, printed)
        }
    }
}
