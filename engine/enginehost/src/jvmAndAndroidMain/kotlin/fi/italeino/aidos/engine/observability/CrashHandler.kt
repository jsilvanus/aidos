package fi.italeino.aidos.engine.observability

import app.cash.sqldelight.db.SqlDriver
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

/**
 * Catches uncaught exceptions and writes crash records to the database (RFC-0037).
 *
 * Each crash record includes:
 * - timestamp
 * - runtime version
 * - platform profile (MOBILE|DESKTOP|HEADLESS_SERVER)
 * - stack trace hash (for deduplication)
 * - detail file path with full stack trace
 * - in-flight run IDs (if available)
 *
 * Nothing is transmitted. All data stays on the device.
 */
class CrashHandler(
    private val driver: SqlDriver,
    private val runtimeVersion: String,
    private val platformProfile: String,
    private val crashDir: File,
    private val inFlightRunIds: () -> List<String> = { emptyList() },
) : Thread.UncaughtExceptionHandler {

    private val previousHandler = Thread.getDefaultUncaughtExceptionHandler()

    init {
        crashDir.mkdirs()
    }

    override fun uncaughtException(thread: Thread, exception: Throwable) {
        try {
            writeCrashRecord(thread, exception)
        } catch (e: Exception) {
            // Even crash recording failed; log to stderr as last resort.
            e.printStackTrace()
        }

        // Forward to the previous handler if one was registered (e.g., platform crash reporting).
        previousHandler?.uncaughtException(thread, exception)
    }

    private fun writeCrashRecord(thread: Thread, exception: Throwable) {
        val id = UUID.randomUUID().toString()
        val occurredAt = Instant.now().toString()
        val stackTrace = getFullStackTrace(exception)
        val stackHash = hashStackTrace(stackTrace)
        val errorCode = exception::class.simpleName
        val runIds = inFlightRunIds()

        // Write full stack trace to a detail file.
        val detailPath = File(crashDir, "$id.txt").absolutePath
        File(detailPath).writeText(
            """
            |Crash Report: $id
            |Occurred: $occurredAt
            |Thread: ${thread.name} (${thread.id})
            |Error: $errorCode
            |Stack Hash: $stackHash
            |
            |$stackTrace
        """.trimMargin()
        )

        // Write to crash_records table (user scope database).
        insertCrashRecord(
            id = id,
            occurredAt = occurredAt,
            runtimeVersion = runtimeVersion,
            platformProfile = platformProfile,
            errorCode = errorCode,
            stackHash = stackHash,
            detailPath = detailPath,
            inFlightRunIds = runIds,
        )
    }

    private fun insertCrashRecord(
        id: String,
        occurredAt: String,
        runtimeVersion: String,
        platformProfile: String,
        errorCode: String?,
        stackHash: String,
        detailPath: String,
        inFlightRunIds: List<String>,
    ) {
        val inFlightJson = if (inFlightRunIds.isEmpty()) "[]" else inFlightRunIds.joinToString(",", "[\"", "\"]") { "\"$it\"" }

        // Raw SQL since the schema is defined in schema/user.sql and managed separately.
        // This matches the crash_records table schema from RFC-0037.
        driver.execute(
            null,
            """
            INSERT INTO crash_records (
                id, occurred_at, runtime_version, platform_profile, error_code,
                stack_hash, detail_path, in_flight_run_ids, reported
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            5,
            { bindString(1, id); bindString(2, occurredAt); bindString(3, runtimeVersion); bindString(4, platformProfile); bindString(5, errorCode ?: "unknown"); bindString(6, stackHash); bindString(7, detailPath); bindString(8, inFlightJson); bindLong(9, 0) }
        )
    }

    private fun getFullStackTrace(exception: Throwable): String {
        val sw = java.io.StringWriter()
        val pw = java.io.PrintWriter(sw)
        exception.printStackTrace(pw)
        pw.close()
        return sw.toString()
    }

    private fun hashStackTrace(stackTrace: String): String {
        // Hash only the first few lines (class and method names) to deduplicate repeated crashes,
        // ignoring line numbers which change with code updates.
        val lines = stackTrace.split("\n")
            .take(5)
            .map { it.replace(Regex(":\\d+\\)"), ")") } // Remove line numbers
            .joinToString("\n")

        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(lines.toByteArray())
        return hash.joinToString("") { "%02x".format(it) }
    }
}
