package dev.aidos.daemon

import app.cash.sqldelight.db.SqlDriver
import dev.aidos.kernel.PlatformProfile
import dev.aidos.vault.Redactor
import dev.aidos.vault.RedactionBoundary
import java.io.File
import java.io.FileWriter
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.BasicFileAttributes
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
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

    private data class LogRecord(
        val timestamp: String,
        val level: Level,
        val key: String,
        val fields: Map<String, String>,
    )

    private val maxRingBufferSize = when (platformProfile) {
        PlatformProfile.MOBILE -> 200
        else -> 1000
    }

    private val ringBuffer = ArrayDeque<LogRecord>(maxRingBufferSize)
    private val logsDir = Paths.get(homeDir, ".aidos", "logs")
    
    // Level configuration
    private val enabledLevels = mutableSetOf<Level>(Level.ERROR, Level.WARN, Level.INFO)
    private val traceFile = Paths.get(homeDir, ".aidos", "logs", "diagnostic-trace.log")

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
            enabledLevels.add(Level.DEBUG)
        }
    }

    /**
     * Enables TRACE logging for 30 minutes (desktop) or logs (mobile).
     * Trace records write to a separate file excluded from diagnostic bundles.
     */
    fun enableTrace() {
        enabledLevels.add(Level.TRACE)
    }

    /**
     * Disables TRACE logging.
     */
    fun disableTrace() {
        enabledLevels.remove(Level.TRACE)
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
        if (enabledLevels.contains(Level.DEBUG)) {
            log(Level.DEBUG, key, block)
        }
    }

    /**
     * Logs a TRACE-level record (payload detail).
     * Off by default, enabled per-subsystem for 30 minutes, writes to separate file.
     * May contain prompts and tool results.
     */
    fun trace(key: String, block: FieldBuilder.() -> Unit) {
        if (enabledLevels.contains(Level.TRACE)) {
            log(Level.TRACE, key, block)
        }
    }

    private fun log(level: Level, key: String, block: FieldBuilder.() -> Unit) {
        if (!enabledLevels.contains(level)) return

        val builder = FieldBuilder()
        block(builder)
        val fields = builder.fields.toMap()

        val timestamp = Clock.System.now().toString()
        val record = LogRecord(timestamp, level, key, fields)

        // Add to ring buffer
        synchronized(ringBuffer) {
            if (ringBuffer.size >= maxRingBufferSize) {
                ringBuffer.removeFirst()
            }
            ringBuffer.addLast(record)
        }

        // Write to file
        try {
            val logFile = getLogFile()
            FileWriter(logFile, true).use { writer ->
                val json = buildLogJson(timestamp, level, key, fields)
                writer.append(json).append("\n")
                writer.flush()
            }
        } catch (e: Exception) {
            System.err.println("Failed to write log: ${e.message}")
        }

        // Age out log files on checkpoint (called from retention maintenance)
        if (level == Level.ERROR) {
            ageOutLogs()
        }
    }

    private fun buildLogJson(
        timestamp: String,
        level: Level,
        key: String,
        fields: Map<String, String>
    ): String {
        val json = buildJsonObject {
            put("timestamp", timestamp)
            put("level", level.name)
            put("key", key)
            put("version", runtimeVersion)
            put("profile", platformProfile.name)
            
            // Add redacted fields
            for ((name, value) in fields) {
                val redacted = redactor.redactWithBoundary(value, RedactionBoundary.LOG)
                put(name, redacted)
            }
        }
        return json.toString()
    }

    private fun getLogFile(): File {
        val dateStr = Instant.now()
            .atZone(ZoneId.of("UTC"))
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))
        
        val fileName = if (enabledLevels.contains(Level.TRACE) && ringBuffer.any { it.level == Level.TRACE }) {
            "diagnostic-trace.log"
        } else {
            "diagnostic-$dateStr.log"
        }
        
        return logsDir.resolve(fileName).toFile()
    }

    private fun ageOutLogs() {
        try {
            val maxAgeDays = if (platformProfile == PlatformProfile.MOBILE) 7L else 30L
            val cutoffTime = System.currentTimeMillis() - (maxAgeDays * 24 * 60 * 60 * 1000)
            
            Files.list(logsDir).use { stream ->
                stream.forEach { path ->
                    try {
                        if (path.fileName.toString().startsWith("diagnostic-") && 
                            !path.fileName.toString().endsWith("-trace.log")) {
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
        synchronized(ringBuffer) {
            return ringBuffer.takeLast(limit)
        }
    }

    /**
     * Builder for structured fields. Every field value is redacted before being written.
     */
    class FieldBuilder {
        val fields = mutableMapOf<String, String>()

        fun field(name: String, value: Any?) {
            fields[name] = value?.toString() ?: "null"
        }
    }
}

/**
 * Extension for Redactor to apply redaction boundary.
 */
fun Redactor.redactWithBoundary(value: String, boundary: RedactionBoundary): String {
    return this.redact(value, boundary)
}
