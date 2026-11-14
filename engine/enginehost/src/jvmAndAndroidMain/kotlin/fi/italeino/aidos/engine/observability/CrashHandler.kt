package fi.italeino.aidos.engine.observability

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class CrashRecord(
    val id: String,
    @SerialName("occurred_at") val occurredAt: String,
    @SerialName("runtime_version") val runtimeVersion: String,
    @SerialName("platform_profile") val platformProfile: String,
    @SerialName("error_code") val errorCode: String? = null,
    @SerialName("stack_hash") val stackHash: String,
    @SerialName("detail_path") val detailPath: String,
    @SerialName("in_flight_run_ids") val inFlightRunIds: List<String> = emptyList(),
    val reported: Boolean = false,
    @SerialName("stack_trace") val stackTrace: String? = null,
)

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
    private val previousHandler: Thread.UncaughtExceptionHandler? = Thread.getDefaultUncaughtExceptionHandler(),
) : Thread.UncaughtExceptionHandler {

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
        if (previousHandler != null && previousHandler !== this) {
            previousHandler.uncaughtException(thread, exception)
        }
    }

    fun writeRecordToFile(record: CrashRecord, file: File) =
        Companion.writeRecordToFile(record, file)

    fun readRecordFromFile(file: File): CrashRecord? =
        Companion.readRecordFromFile(file)

    /**
     * Scans [directory] for JSON crash records written when DB persistence failed or
     * before recovery completed, and inserts any missing records into `crash_records`.
     */
    fun recoverOrphanedRecords(directory: File = crashDir): Int {
        if (!directory.isDirectory) return 0
        val files = directory.listFiles { _, name -> name.endsWith(".json") } ?: return 0
        var recovered = 0
        for (file in files) {
            val record = readRecordFromFile(file) ?: continue
            val exists = driver.executeQuery(
                identifier = null,
                sql = "SELECT 1 FROM crash_records WHERE id = ?",
                mapper = { c -> QueryResult.Value(c.next().value) },
                parameters = 1,
            ) {
                bindString(0, record.id)
            }.value
            if (!exists) {
                insertCrashRecord(
                    id = record.id,
                    occurredAt = record.occurredAt,
                    runtimeVersion = record.runtimeVersion,
                    platformProfile = record.platformProfile,
                    errorCode = record.errorCode,
                    stackHash = record.stackHash,
                    detailPath = record.detailPath,
                    inFlightRunIds = record.inFlightRunIds,
                    reported = record.reported,
                )
                recovered++
            }
        }
        return recovered
    }

    private fun writeCrashRecord(thread: Thread, exception: Throwable) {
        val id = UUID.randomUUID().toString()
        val occurredAt = Instant.now().toString()
        val stackTrace = redactSensitive(getFullStackTrace(exception))
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
            |stack_hash: $stackHash
            |
            |$stackTrace
        """.trimMargin(),
            Charsets.UTF_8,
        )

        val recordFile = File(crashDir, "$id.json")
        writeRecordToFile(
            CrashRecord(
                id = id,
                occurredAt = occurredAt,
                runtimeVersion = runtimeVersion,
                platformProfile = platformProfile,
                errorCode = errorCode ?: "unknown",
                stackHash = stackHash,
                detailPath = detailPath,
                inFlightRunIds = runIds,
                reported = false,
                stackTrace = stackTrace,
            ),
            recordFile,
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
            reported = false,
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
        reported: Boolean = false,
    ) {
        val inFlightJson = json.encodeToString(inFlightRunIds)

        // Raw SQL since the schema is defined in schema/user.sql and managed separately.
        // This matches the crash_records table schema from RFC-0037.
        driver.execute(
            identifier = null,
            sql = """
            INSERT OR IGNORE INTO crash_records (
                id, occurred_at, runtime_version, platform_profile, error_code,
                stack_hash, detail_path, in_flight_run_ids, reported
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            parameters = 9,
        ) {
            bindString(0, id)
            bindString(1, occurredAt)
            bindString(2, runtimeVersion)
            bindString(3, platformProfile)
            bindString(4, errorCode ?: "unknown")
            bindString(5, stackHash)
            bindString(6, detailPath)
            bindString(7, inFlightJson)
            bindLong(8, if (reported) 1L else 0L)
        }
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
        val hash = digest.digest(lines.toByteArray(Charsets.UTF_8))
        return hash.joinToString("") { "%02x".format(it) }
    }

    companion object {
        private val json = Json {
            prettyPrint = true
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        private val SK_KEY_REGEX = Regex("\\bsk-(?:ant-|proj-|svcacct-)?[A-Za-z0-9_-]{8,}")
        private val BEARER_REGEX = Regex("(?i)\\bBearer\\s+[A-Za-z0-9._~+/=-]{4,}")
        private val BASIC_AUTH_REGEX = Regex("(?i)\\bBasic\\s+[A-Za-z0-9+/=]{4,}")
        private val KV_SECRET_REGEX = Regex(
            "(?i)\\b([A-Za-z0-9_.-]*(?:api[_-]?key|apikey|secret(?:_?key)?|password|passwd|authorization|auth[_-]?token|access[_-]?token|refresh[_-]?token|id[_-]?token|bearer[_-]?token)|token|credential|credentials|x-api-key)\\b\\s*([=:])\\s*(?:'([^']*)'|\"([^\"]*)\"|([^\\s,;'\")}\\]]{3,}))"
        )

        fun writeRecordToFile(record: CrashRecord, file: File) {
            file.parentFile?.mkdirs()
            file.writeText(json.encodeToString(record), Charsets.UTF_8)
        }

        fun readRecordFromFile(file: File): CrashRecord? {
            if (!file.isFile) return null
            val raw = file.readText(Charsets.UTF_8)
            if (raw.isBlank()) return null
            return runCatching {
                json.decodeFromString<CrashRecord>(raw)
            }.getOrNull()
        }

        fun redactSensitive(input: String): String {
            var result = SK_KEY_REGEX.replace(input, "[REDACTED]")
            result = BEARER_REGEX.replace(result, "Bearer [REDACTED]")
            result = BASIC_AUTH_REGEX.replace(result, "Basic [REDACTED]")
            result = KV_SECRET_REGEX.replace(result) { match ->
                "${match.groupValues[1]}${match.groupValues[2]}[REDACTED]"
            }
            return result
        }
    }
}
