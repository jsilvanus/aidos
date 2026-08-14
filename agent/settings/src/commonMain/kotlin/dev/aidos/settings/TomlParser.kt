package dev.aidos.settings

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/**
 * Minimal TOML parser for `aidos.toml` (RFC-0036).
 *
 * Scope: the flat key = value form used by `aidos.toml`. Tables ([section]) produce
 * dotted keys: `[routing]` + `remote_egress = "ask"` → key `routing.remote_egress`.
 * Arrays of strings are supported. Multi-line strings are not needed by the MVP settings set.
 *
 * Per-line error reporting: every parse error includes the line number and continues parsing,
 * so one bad line does not hide three others (RFC-0036).
 */
object TomlParser {

    data class ParseResult(
        /** Key → raw JSON text. Unknown keys are preserved, not dropped (RFC-0039). */
        val values: Map<String, JsonElement>,
        val errors: List<SettingError>,
    )

    fun parse(content: String, sourcePath: String = "aidos.toml"): ParseResult {
        val values = mutableMapOf<String, JsonElement>()
        val errors = mutableListOf<SettingError>()
        var currentSection = ""

        content.lines().forEachIndexed { i, raw ->
            val lineNum = i + 1
            val line = raw.trim()
            when {
                line.isEmpty() || line.startsWith('#') -> { /* skip */ }

                line.startsWith('[') -> {
                    // Section header: [routing] or [routing.remote]
                    val end = line.indexOf(']')
                    if (end < 0) {
                        errors.add(SettingError(
                            key = "", originPath = sourcePath, line = lineNum,
                            message = "unclosed '[' in section header",
                            errorClass = SettingErrorClass.VALIDATION_FAILED,
                        ))
                    } else {
                        currentSection = line.substring(1, end).trim()
                    }
                }

                line.contains('=') -> {
                    val eqIdx = line.indexOf('=')
                    val rawKey = line.substring(0, eqIdx).trim()
                    val rawValue = line.substring(eqIdx + 1).trim()
                    val fullKey = if (currentSection.isEmpty()) rawKey else "$currentSection.$rawKey"

                    val element = parseValue(rawValue, fullKey, sourcePath, lineNum, errors)
                    if (element != null) {
                        values[fullKey] = element
                    }
                }

                else -> {
                    errors.add(SettingError(
                        key = "", originPath = sourcePath, line = lineNum,
                        message = "unrecognised line: $line",
                        errorClass = SettingErrorClass.VALIDATION_FAILED,
                    ))
                }
            }
        }

        return ParseResult(values, errors)
    }

    private fun parseValue(
        raw: String,
        key: String,
        sourcePath: String,
        lineNum: Int,
        errors: MutableList<SettingError>,
    ): JsonElement? {
        return when {
            // Quoted string
            raw.startsWith('"') && raw.endsWith('"') && raw.length >= 2 ->
                JsonPrimitive(raw.substring(1, raw.length - 1).unescape())

            // Boolean
            raw == "true" -> JsonPrimitive(true)
            raw == "false" -> JsonPrimitive(false)

            // Integer
            raw.toLongOrNull() != null -> JsonPrimitive(raw.toLong())

            // Float
            raw.toDoubleOrNull() != null -> JsonPrimitive(raw.toDouble())

            // Inline array: ["a", "b"]
            raw.startsWith('[') && raw.endsWith(']') -> {
                val inner = raw.substring(1, raw.length - 1)
                val elements = mutableListOf<JsonElement>()
                var ok = true
                for (item in splitArrayItems(inner)) {
                    val el = parseValue(item.trim(), key, sourcePath, lineNum, errors)
                    if (el == null) { ok = false; break }
                    elements.add(el)
                }
                if (ok) JsonArray(elements) else null
            }

            else -> {
                errors.add(SettingError(
                    key = key, originPath = sourcePath, line = lineNum,
                    message = "cannot parse value for '$key': $raw",
                    errorClass = SettingErrorClass.VALIDATION_FAILED,
                ))
                null
            }
        }
    }

    /** Split comma-separated array items, respecting quoted strings. */
    private fun splitArrayItems(inner: String): List<String> {
        val items = mutableListOf<String>()
        var depth = 0
        var inString = false
        var start = 0
        for (i in inner.indices) {
            val c = inner[i]
            when {
                c == '"' -> inString = !inString
                !inString && c == '[' -> depth++
                !inString && c == ']' -> depth--
                !inString && depth == 0 && c == ',' -> {
                    items.add(inner.substring(start, i))
                    start = i + 1
                }
            }
        }
        if (start <= inner.length) items.add(inner.substring(start))
        return items.filter { it.isNotBlank() }
    }

    private fun String.unescape(): String =
        this.replace("\\n", "\n").replace("\\t", "\t").replace("\\\\", "\\").replace("\\\"", "\"")
}
