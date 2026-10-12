package dev.aidos.decisions

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Reads a Hugging Face `tokenizer.json` of the Gemma family (the one Laya multilingual ships):
 * space -> U+2581 normalizer, Metaspace pre-tokenizer, BPE with byte fallback, added special tokens.
 *
 * Not a general tokenizers port: it validates that the file has exactly that shape and refuses
 * anything else, because a silently different tokenization would still "work" and give wrong
 * decisions. Parity with the Hugging Face tokenizer is pinned by golden ids (laya_golden.json).
 *
 * TODO(android): parsing the 34 MB JSON on-device is heavy; a compact binary form belongs in the
 * model package once the Android host is exercised.
 */
class GemmaBpeTokenizer private constructor(
    private val vocab: Map<String, Int>,
    private val mergeRank: Map<String, Int>,
    private val addedTokens: List<AddedToken>,
    private val byteFallback: Boolean,
    val unkId: Int,
    private val specials: Map<String, Int>,
) {
    class AddedToken(val content: String, val id: Int, val lstrip: Boolean)

    val maskId: Int = specials.getValue("<mask>")
    val clsId: Int = specials.getValue("<bos>")
    val sepId: Int = specials.getValue("<eos>")
    val padId: Int = specials.getValue("<pad>")
    val maskToken: String = "<mask>"

    /** Token ids for [text] with no special tokens added (what Laya calls `add_special_tokens=False`). */
    fun encode(text: String): List<Int> {
        if (text.isEmpty()) return emptyList()
        val out = ArrayList<Int>()
        var cursor = 0
        // Added tokens are matched on the raw text first, longest first, as in the HF implementation.
        var i = 0
        while (i < text.length) {
            val hit = addedTokens.firstOrNull { text.startsWith(it.content, i) }
            if (hit == null) { i++; continue }
            var segEnd = i
            if (hit.lstrip) while (segEnd > cursor && text[segEnd - 1].isWhitespace()) segEnd--
            if (segEnd > cursor) out += encodeSegment(text.substring(cursor, segEnd))
            out += hit.id
            i += hit.content.length
            cursor = i
        }
        if (cursor < text.length) out += encodeSegment(text.substring(cursor))
        return out
    }

    private fun encodeSegment(segment: String): List<Int> {
        // Normalizer: Replace ' ' -> U+2581. Metaspace(prepend_scheme=always): prepend U+2581 unless
        // the section already starts with it; split=true: a piece begins at every U+2581.
        var s = segment.replace(' ', META)
        if (!s.startsWith(META)) s = META + s
        val pieces = ArrayList<String>()
        var start = 0
        for (idx in 1 until s.length) {
            if (s[idx] == META) { pieces += s.substring(start, idx); start = idx }
        }
        pieces += s.substring(start)
        val out = ArrayList<Int>()
        for (p in pieces) bpe(p, out)
        return out
    }

    private fun bpe(piece: String, out: MutableList<Int>) {
        // Initial symbols: one per code point; a code point missing from the vocabulary falls back to
        // its UTF-8 byte tokens <0xXX> (byte_fallback) or to unk.
        val symbols = ArrayList<String>()
        var i = 0
        while (i < piece.length) {
            val width = if (piece[i].isHighSurrogate() && i + 1 < piece.length && piece[i + 1].isLowSurrogate()) 2 else 1
            val ch = piece.substring(i, i + width)
            i += width
            if (vocab.containsKey(ch)) symbols += ch
            else if (byteFallback) {
                for (b in ch.encodeToByteArray()) symbols += byteToken(b.toInt() and 0xFF)
            } else symbols += UNK_MARK
        }
        while (symbols.size > 1) {
            var bestRank = Int.MAX_VALUE
            var bestAt = -1
            for (j in 0 until symbols.size - 1) {
                val r = mergeRank[symbols[j] + " " + symbols[j + 1]] ?: continue
                if (r < bestRank) { bestRank = r; bestAt = j }
            }
            if (bestAt < 0) break
            symbols[bestAt] = symbols[bestAt] + symbols[bestAt + 1]
            symbols.removeAt(bestAt + 1)
        }
        var lastWasUnk = false
        for (sym in symbols) {
            val id = vocab[sym]
            if (id == null) {
                // fuse_unk: consecutive unknowns collapse into one.
                if (!lastWasUnk) out += unkId
                lastWasUnk = true
            } else { out += id; lastWasUnk = false }
        }
    }

    companion object {
        private const val META = '▁'
        private const val UNK_MARK = "\u0000unk"

        fun fromJson(json: String): GemmaBpeTokenizer {
            val root = Json.parseToJsonElement(json).jsonObject
            val model = root.getValue("model").jsonObject
            require(model.getValue("type").jsonPrimitive.content == "BPE") { "tokenizer model is not BPE" }
            // Refuse shapes this port does not implement rather than tokenize them subtly wrong.
            val norm = root["normalizer"] as? JsonObject
            require(norm != null && norm["type"]?.jsonPrimitive?.content == "Replace" &&
                norm["pattern"]?.jsonObject?.get("String")?.jsonPrimitive?.content == " " &&
                norm["content"]?.jsonPrimitive?.content == META.toString()) { "unsupported normalizer (expected space -> U+2581)" }
            val pre = root["pre_tokenizer"] as? JsonObject
            require(pre != null && pre["type"]?.jsonPrimitive?.content == "Metaspace" &&
                pre["prepend_scheme"]?.jsonPrimitive?.content == "always" &&
                pre["split"]?.jsonPrimitive?.boolean == true) { "unsupported pre_tokenizer (expected Metaspace always/split)" }
            require(model["dropout"].let { it == null || it is kotlinx.serialization.json.JsonNull }) { "BPE dropout unsupported" }

            val vocab = HashMap<String, Int>(model.getValue("vocab").jsonObject.size * 2)
            for ((k, v) in model.getValue("vocab").jsonObject) vocab[k] = (v as JsonPrimitive).int
            val merges = model.getValue("merges").jsonArray
            val rank = HashMap<String, Int>(merges.size * 2)
            for ((idx, m) in merges.withIndex()) rank[mergeKey(m)] = idx

            val added = ArrayList<AddedToken>()
            val specials = HashMap<String, Int>()
            for (e in root.getValue("added_tokens").jsonArray) {
                val o = e.jsonObject
                val content = o.getValue("content").jsonPrimitive.content
                val id = o.getValue("id").jsonPrimitive.int
                added += AddedToken(content, id, o["lstrip"]?.jsonPrimitive?.boolean ?: false)
                specials[content] = id
            }
            added.sortByDescending { it.content.length }
            val unk = model["unk_token"]?.jsonPrimitive?.contentOrNull
            return GemmaBpeTokenizer(
                vocab, rank, added,
                byteFallback = model["byte_fallback"]?.jsonPrimitive?.boolean ?: false,
                unkId = unk?.let { vocab[it] } ?: specials.getValue("<unk>"),
                specials = specials,
            )
        }

        private fun mergeKey(m: kotlinx.serialization.json.JsonElement): String =
            if (m is JsonArray) m[0].jsonPrimitive.content + " " + m[1].jsonPrimitive.content
            else m.jsonPrimitive.content
    }
}

private fun byteToken(b: Int): String {
    val hex = "0123456789ABCDEF"
    return "<0x" + hex[b shr 4] + hex[b and 0xF] + ">"
}
