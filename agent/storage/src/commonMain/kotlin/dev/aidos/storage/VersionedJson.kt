package dev.aidos.storage

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * Thrown before parsing when serialized input exceeds RFC-0039 size or nesting depth limits.
 */
class DeserializationLimitException(message: String) : IllegalArgumentException(message)

/**
 * Wrapper pairing a typed deserialized record with any unknown JSON fields carried forward from
 * a newer runtime (RFC-0039: "unknown fields are preserved, not dropped").
 */
data class VersionedEnvelope<T>(
    val value: T,
    val unknownFields: JsonObject = JsonObject(emptyMap()),
)

/**
 * RFC-0039 serialization and deserialization safety utilities:
 * - Size and nesting-depth bounds checked before parsing.
 * - Explicit `ignoreUnknownKeys = true` paired with [decodePreservingUnknown] /
 *   [encodePreservingUnknown] so unknown fields survive round-trips untouched.
 * - Safe enum decoding that maps unrecognized wire strings to an explicit `UNKNOWN` variant
 *   rather than silently defaulting to the first enum case.
 */
object VersionedJson {

    const val DEFAULT_MAX_BYTES: Int = 1_048_576 // 1 MiB
    const val DEFAULT_MAX_DEPTH: Int = 64

    val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    /**
     * Checks UTF-8 byte length and JSON nesting depth before invoking the parser (RFC-0039).
     * Rejects oversized or excessively nested input without allocating an AST.
     */
    fun validateLimits(
        raw: String,
        maxBytes: Int = DEFAULT_MAX_BYTES,
        maxDepth: Int = DEFAULT_MAX_DEPTH,
    ) {
        val byteLen = raw.encodeToByteArray().size
        if (byteLen > maxBytes) {
            throw DeserializationLimitException(
                "Serialized payload size $byteLen bytes exceeds limit of $maxBytes bytes"
            )
        }
        var depth = 0
        var inString = false
        var escaped = false
        for (ch in raw) {
            if (inString) {
                if (escaped) {
                    escaped = false
                } else if (ch == '\\') {
                    escaped = true
                } else if (ch == '"') {
                    inString = false
                }
            } else {
                when (ch) {
                    '"' -> inString = true
                    '{', '[' -> {
                        depth++
                        if (depth > maxDepth) {
                            throw DeserializationLimitException(
                                "Serialized JSON nesting depth exceeds limit of $maxDepth"
                            )
                        }
                    }
                    '}', ']' -> if (depth > 0) depth--
                }
            }
        }
    }

    /**
     * Deserializes [raw] into [VersionedEnvelope], enforcing [maxBytes] and [maxDepth] before
     * parsing and retaining any top-level keys not in [knownKeys] in [VersionedEnvelope.unknownFields].
     */
    fun <T> decodePreservingUnknown(
        raw: String,
        deserializer: DeserializationStrategy<T>,
        knownKeys: Set<String>,
        maxBytes: Int = DEFAULT_MAX_BYTES,
        maxDepth: Int = DEFAULT_MAX_DEPTH,
    ): VersionedEnvelope<T> {
        validateLimits(raw, maxBytes = maxBytes, maxDepth = maxDepth)
        val obj = json.parseToJsonElement(raw).jsonObject
        val unknown = JsonObject(obj.filterKeys { it !in knownKeys })
        val value = json.decodeFromJsonElement(deserializer, obj)
        return VersionedEnvelope(value = value, unknownFields = unknown)
    }

    /**
     * Serializes [envelope] back to JSON, preserving [VersionedEnvelope.unknownFields] unchanged.
     */
    fun <T> encodePreservingUnknown(
        envelope: VersionedEnvelope<T>,
        serializer: SerializationStrategy<T>,
    ): String {
        val encoded = json.encodeToJsonElement(serializer, envelope.value).jsonObject
        if (envelope.unknownFields.isEmpty()) {
            return json.encodeToString(JsonObject.serializer(), encoded)
        }
        val merged = LinkedHashMap<String, kotlinx.serialization.json.JsonElement>(
            encoded.size + envelope.unknownFields.size
        )
        merged.putAll(encoded)
        for ((k, v) in envelope.unknownFields) {
            if (k !in merged) {
                merged[k] = v
            }
        }
        return json.encodeToString(JsonObject.serializer(), JsonObject(merged))
    }

    /**
     * Maps [wireValue] to an enum constant of [E], returning [unknownFallback] if [wireValue] is
     * unrecognized (RFC-0039: "No default-on-unknown for enums").
     */
    inline fun <reified E : Enum<E>> decodeEnumOrUnknown(
        wireValue: String,
        unknownFallback: E,
    ): E = enumValues<E>().firstOrNull { it.name == wireValue } ?: unknownFallback
}
