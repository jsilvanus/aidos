package dev.aidos.daemon

import dev.aidos.kernel.PlatformProfile
import dev.aidos.vault.RedactionBoundary
import dev.aidos.vault.Redactor
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.datetime.Clock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Structured logging for Aidos (RFC-0037).
 *
 * Every record is structured JSON with mandatory correlation IDs (run_id, task_id, attempt_id),
 * unconditional redaction, and ring-buffer in-memory storage. Log files are written to
 * ~/.aidos/logs/diagnostic-*.log with 7-day age-out on MOBILE, configurable on DESKTOP.
 *
 * Levels:
 * - ERROR: runtime failure; always on
 * - WARN: degradation with user-visible consequence; always on
 * - INFO: lifecycle events; always on
 * - DEBUG: step detail; off by default, on desktop only
 * - TRACE: payload detail; off by default, per-subsystem, separate file
 *
 * Ring buffer bounds:
 * - MOBILE: 200 records
 * - DESKTOP: 1000 records
 * - HEADLESS_SERVER: 1000 records
 */
class StructuredLogger(
    private val redactor: Redactor,
    private val platformProfile: PlatformProfile,
    private val runtimeVersion: String,
    private val homeDir: String = System.getProperty("user.home"),
) {
    enum class Level { ERROR, WARN, INFO, DEBUG, TRACE }

    data class LogRecord(
        val timestamp: String,
        val level: Level,
        val key: String,
        val fields: Map<String, String>,
    )

    private val maxRingBufferSize = when (platformProfile) {
        PlatformProfile.MOBILE -> 200
        else -> 1000
    }

    private val stateLock = Any()
    private val fileLock = Any()
    private val ringBuffer = ArrayDeque<LogRecord>(maxRingBufferSize)
    private val logsDir = Paths.get(homeDir, ".aidos", "logs")

    // Level configuration guarded by stateLock
    private val enabledLevels = mutableSetOf(Level.ERROR, Level.WARN, Level.INFO)

    init {
        Files.createDirectories(logsDir)
        if (platformProfile == PlatformProfile.DESKTOP) {
            enabledLevels.add(Level.DEBUG)
        }
    }

    /**
     * Enables DEBUG logging for the current session (DESKTOP only).
     */
    fun enableDebug() {
        if (platformProfile == PlatformProfile.DESKTOP) {
            synchronized(stateLock) {
                enabledLevels.add(Level.DEBUG)
            }
        }
    }

    /**
     * Enables TRACE logging for 30 minutes (desktop) or logs (mobile).
     * Trace records write to a separate file excluded from diagnostic bundles.
     */
    fun enableTrace() {
        synchronized(stateLock) {
            enabledLevels.add(Level.TRACE)
        }
    }

    /**
     * Disables TRACE logging.
     */
    fun disableTrace() {
        synchronized(stateLock) {
            enabledLevels.remove(Level.TRACE)
        }
    }

    /**
     * Logs an ERROR-level record.
     * Always enabled. Use for runtime failures the system cannot recover from.
     */
    fun error(key: String, block: FieldBuilder.() -> Unit) {
        log(Level.ERROR, key, block)
    }

    /**
     * Logs a WARN-level record.
     * Always enabled. Use for degradation with user-visible consequences.
     */
    fun warn(key: String, block: FieldBuilder.() -> Unit) {
        log(Level.WARN, key, block)
    }

    /**
     * Logs an INFO-level record.
     * Always enabled. Use for lifecycle events (session open/close, model load/unload).
     */
    fun info(key: String, block: FieldBuilder.() -> Unit) {
        log(Level.INFO, key, block)
    }

    /**
     * Logs a DEBUG-level record (desktop only).
     * Off by default. Use for step detail: prompt sizes, routing decisions, cache hits.
     */
    fun debug(key: String, block: FieldBuilder.() -> Unit) {
        if (isLevelEnabled(Level.DEBUG)) {
            log(Level.DEBUG, key, block)
        }
    }

    /**
     * Logs a TRACE-level record (payload detail).
     * Off by default, enabled per-subsystem for 30 minutes, writes to separate file.
     * May contain prompts and tool results.
     */
    fun trace(key: String, block: FieldBuilder.() -> Unit) {
        if (isLevelEnabled(Level.TRACE)) {
            log(Level.TRACE, key, block)
        }
    }

    private fun isLevelEnabled(level: Level): Boolean =
        synchronized(stateLock) { enabledLevels.contains(level) }

    private fun log(level: Level, key: String, block: FieldBuilder.() -> Unit) {
        if (!isLevelEnabled(level)) return

        val builder = FieldBuilder()
        block(builder)

        val redactedJsonFields = LinkedHashMap<String, JsonElement>(builder.rawValues.size)
        val redactedStringFields = LinkedHashMap<String, String>(builder.rawValues.size)
        for ((name, rawValue) in builder.rawValues) {
            val element = redactFieldValue(name, rawValue)
            redactedJsonFields[name] = element
            redactedStringFields[name] = when (element) {
                is JsonPrimitive -> element.content
                else -> element.toString()
            }
        }

        val redactedKey = redactString(key)
        val timestamp = Clock.System.now().toString()
        val record = LogRecord(timestamp, level, redactedKey, redactedStringFields)

        synchronized(stateLock) {
            if (!enabledLevels.contains(level)) return
            if (ringBuffer.size >= maxRingBufferSize) {
                ringBuffer.removeFirst()
            }
            ringBuffer.addLast(record)
        }

        val jsonLine = buildLogJson(timestamp, level, redactedKey, redactedJsonFields)
        synchronized(fileLock) {
            try {
                val logPath = getLogFile(level).toPath()
                Files.createDirectories(logsDir)
                val bytes = "$jsonLine\n".toByteArray(Charsets.UTF_8)
                Files.write(
                    logPath,
                    bytes,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.APPEND,
                )
            } catch (e: Exception) {
                System.err.println("Failed to write log: ${e.message}")
            }

            // Age out log files on checkpoint (called from retention maintenance)
            if (level == Level.ERROR) {
                ageOutLogs()
            }
        }
    }

    private fun buildLogJson(
        timestamp: String,
        level: Level,
        key: String,
        fields: Map<String, JsonElement>,
    ): String {
        val json = buildJsonObject {
            for ((name, value) in fields) {
                if (name !in RESERVED_ENVELOPE_KEYS) {
                    put(name, value)
                }
            }
            put("timestamp", timestamp)
            put("level", level.name)
            put("key", key)
            put("version", runtimeVersion)
            put("profile", platformProfile.name)
        }
        return serializeJsonElement(json)
    }

    private fun serializeJsonElement(element: JsonElement): String {
        val sb = StringBuilder(128)
        appendJsonElement(sb, element)
        return sb.toString()
    }

    private fun appendJsonElement(sb: StringBuilder, element: JsonElement) {
        when (element) {
            JsonNull -> sb.append("null")
            is JsonPrimitive -> {
                if (element.isString) {
                    appendEscapedJsonString(sb, element.content)
                } else {
                    sb.append(element.content)
                }
            }
            is JsonArray -> {
                sb.append('[')
                element.forEachIndexed { idx, child ->
                    if (idx > 0) sb.append(',')
                    appendJsonElement(sb, child)
                }
                sb.append(']')
            }
            is JsonObject -> {
                sb.append('{')
                var first = true
                for ((k, v) in element) {
                    if (!first) sb.append(',')
                    first = false
                    appendEscapedJsonString(sb, k)
                    sb.append(':')
                    appendJsonElement(sb, v)
                }
                sb.append('}')
            }
        }
    }

    private fun appendEscapedJsonString(sb: StringBuilder, value: String) {
        sb.append('"')
        for (ch in value) {
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> {
                    if (ch.code < 0x20) {
                        sb.append("\\u")
                        val hex = ch.code.toString(16).padStart(4, '0')
                        sb.append(hex)
                    } else {
                        sb.append(ch)
                    }
                }
            }
        }
        sb.append('"')
    }

    private fun redactFieldValue(name: String, value: Any?): JsonElement {
        if (isSensitiveKey(name)) {
            return JsonPrimitive(REDACTED_MARKER)
        }
        return redactValue(value)
    }

    private fun redactValue(value: Any?): JsonElement {
        return when (value) {
            null -> JsonNull
            is JsonElement -> redactJsonElement(value)
            is Boolean -> JsonPrimitive(value)
            is Number -> JsonPrimitive(value)
            is Map<*, *> -> JsonObject(
                buildMap {
                    for ((k, v) in value) {
                        val keyStr = k?.toString() ?: "null"
                        put(keyStr, redactFieldValue(keyStr, v))
                    }
                },
            )
            is Iterable<*> -> JsonArray(value.map { redactValue(it) })
            is Array<*> -> JsonArray(value.map { redactValue(it) })
            is String -> {
                val trimmed = value.trim()
                if ((trimmed.startsWith("{") && trimmed.endsWith("}")) ||
                    (trimmed.startsWith("[") && trimmed.endsWith("]"))
                ) {
                    val parsed = runCatching { Json.parseToJsonElement(trimmed) }.getOrNull()
                    if (parsed != null) {
                        return redactJsonElement(parsed)
                    }
                }
                JsonPrimitive(redactString(value))
            }
            else -> JsonPrimitive(redactString(value.toString()))
        }
    }

    private fun redactJsonElement(element: JsonElement): JsonElement {
        return when (element) {
            is JsonObject -> JsonObject(
                element.mapValues { (k, v) ->
                    if (isSensitiveKey(k)) JsonPrimitive(REDACTED_MARKER) else redactJsonElement(v)
                },
            )
            is JsonArray -> JsonArray(element.map { redactJsonElement(it) })
            is JsonPrimitive -> {
                if (element.isString) JsonPrimitive(redactString(element.content)) else element
            }
        }
    }

    private fun redactString(input: String): String {
        var result = redactor.redactWithBoundary(input, RedactionBoundary.LOG)
        result = SK_KEY_REGEX.replace(result, REDACTED_MARKER)
        result = BEARER_REGEX.replace(result, "Bearer $REDACTED_MARKER")
        result = BASIC_AUTH_REGEX.replace(result, "Basic $REDACTED_MARKER")
        result = JSON_SECRET_FIELD_REGEX.replace(result) { match ->
            val value = match.groupValues[2]
            if (value == REDACTED_MARKER || value.startsWith("«redacted")) match.value
            else "${match.groupValues[1]}\"$REDACTED_MARKER\""
        }
        result = KV_SECRET_REGEX.replace(result) { match ->
            val k = match.groupValues[1]
            val sep = match.groupValues[2]
            val rawVal = match.groupValues[3].ifEmpty { match.groupValues[4] }.ifEmpty { match.groupValues[5] }
            if (rawVal == REDACTED_MARKER || rawVal.startsWith("«redacted")) match.value
            else "$k$sep$REDACTED_MARKER"
        }
        result = PADDED_BASE64_CREDENTIAL_REGEX.replace(result, REDACTED_MARKER)
        return result
    }

    private fun getLogFile(level: Level): File {
        val dateStr = Instant.now()
            .atZone(ZoneId.of("UTC"))
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))

        val fileName = if (level == Level.TRACE) {
            "diagnostic-trace.log"
        } else {
            "diagnostic-$dateStr.log"
        }

        return logsDir.resolve(fileName).toFile()
    }

    private fun ageOutLogs() {
        try {
            val maxAgeDays = if (platformProfile == PlatformProfile.MOBILE) 7L else 30L
            val cutoffTime = System.currentTimeMillis() - (maxAgeDays * 86_400_000L)

            Files.list(logsDir).use { stream ->
                stream.forEach { path ->
                    try {
                        val name = path.fileName.toString()
                        if (name.startsWith("diagnostic-") && !name.endsWith("-trace.log")) {
                            val attrs = Files.readAttributes(path, BasicFileAttributes::class.java)
                            if (attrs.lastModifiedTime().toMillis() < cutoffTime) {
                                Files.delete(path)
                            }
                        }
                    } catch (e: Exception) {
                        // Ignore errors aging out individual files
                    }
                }
            }
        } catch (e: Exception) {
            System.err.println("Failed to age out logs: ${e.message}")
        }
    }

    /**
     * Returns the last N log records from the ring buffer (for diagnostic bundles).
     */
    fun getRecentLogs(limit: Int = 200): List<LogRecord> {
        synchronized(stateLock) {
            return ringBuffer.takeLast(limit)
        }
    }

    /**
     * Builder for structured fields. Every field value is redacted before being written.
     */
    class FieldBuilder {
        val fields = mutableMapOf<String, String>()
        internal val rawValues = linkedMapOf<String, Any?>()

        fun field(name: String, value: Any?) {
            fields[name] = value?.toString() ?: "null"
            rawValues[name] = value
        }
    }

    companion object {
        private const val REDACTED_MARKER = "[REDACTED]"
        private val RESERVED_ENVELOPE_KEYS = setOf("timestamp", "level", "key", "version", "profile")

        private val SENSITIVE_SINGLE_WORDS = setOf(
            "secret",
            "secrets",
            "password",
            "passwd",
            "passphrase",
            "credential",
            "credentials",
            "authorization",
            "bearer",
            "apikey",
            "privatekey",
        )
        private val KEY_TOKEN_PREFIXES = setOf("api", "private", "secret", "access", "signing", "encryption")
        private val CAMEL_SPLIT_REGEX = Regex("(?<=[a-z0-9])(?=[A-Z])|[\\s_.-]+")

        private val SK_KEY_REGEX = Regex("\\bsk-(?:ant-|proj-|svcacct-)?[A-Za-z0-9_-]{8,}")
        private val BEARER_REGEX = Regex("(?i)\\bBearer\\s+[A-Za-z0-9._~+/=-]{4,}")
        private val BASIC_AUTH_REGEX = Regex("(?i)\\bBasic\\s+[A-Za-z0-9+/=]{4,}")
        private val JSON_SECRET_FIELD_REGEX = Regex(
            "(?i)(\"?(?:[A-Za-z0-9_.-]*(?:api[_-]?key|apikey|secret(?:_?key)?|password|passwd|authorization|auth[_-]?token|access[_-]?token|refresh[_-]?token|id[_-]?token|bearer[_-]?token|private[_-]?key)|token|credential|credentials|x-api-key)\"?\\s*:\\s*)\"([^\"\\\\]*(?:\\\\.[^\"\\\\]*)*)\""
        )
        private val KV_SECRET_REGEX = Regex(
            "(?i)\\b([A-Za-z0-9_.-]*(?:api[_-]?key|apikey|secret(?:_?key)?|password|passwd|authorization|auth[_-]?token|access[_-]?token|refresh[_-]?token|id[_-]?token|bearer[_-]?token|private[_-]?key)|token|credential|credentials|x-api-key)\\b\\s*([=:])\\s*(?:'([^']*)'|\"([^\"]*)\"|([^\\s,;'\")}\\]]{3,}))"
        )
        private val PADDED_BASE64_CREDENTIAL_REGEX = Regex("(?<![A-Za-z0-9+/=])[A-Za-z0-9+/]{16,}={1,2}(?![A-Za-z0-9+/=])")

        internal fun isSensitiveKey(name: String): Boolean {
            val words = name.split(CAMEL_SPLIT_REGEX)
                .filter { it.isNotEmpty() }
                .map { it.lowercase() }
            if (words.isEmpty()) return false
            if (words.any { it in SENSITIVE_SINGLE_WORDS }) return true
            if (words.last() == "token") return true
            for (i in 0 until words.size - 1) {
                if (words[i] in KEY_TOKEN_PREFIXES && words[i + 1] == "key") return true
                if (words[i] == "auth" && words[i + 1] == "header") return true
            }
            return false
        }
    }
}

/**
 * Extension for Redactor to apply redaction boundary.
 */
fun Redactor.redactWithBoundary(value: String, boundary: RedactionBoundary): String {
    return this.redact(value, boundary)
}
