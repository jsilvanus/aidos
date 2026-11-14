package dev.aidos.daemon

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import dev.aidos.identity.UuidV7Generator
import dev.aidos.kernel.PlatformProfile
import dev.aidos.vault.RedactionBoundary
import dev.aidos.vault.Redactor
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import kotlinx.datetime.Clock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray

@Serializable
data class StackFrame(
    val className: String,
    val methodName: String,
    val fileName: String? = null,
    val lineNumber: Int? = null,
    val moduleName: String? = null,
    val isNative: Boolean = false,
)

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
    @SerialName("thread_name") val threadName: String? = null,
    @SerialName("exception_type") val exceptionType: String? = null,
    val message: String? = null,
    @SerialName("stack_trace") val stackTrace: String? = null,
    @SerialName("cause_stack_trace") val causeStackTrace: String? = null,
    val frames: List<StackFrame> = emptyList(),
)

/**
 * Captures and records uncaught exceptions (RFC-0037).
 *
 * On an uncaught exception:
 * 1. Writes a crash_records row with stack_hash = sha256(stackTrace)
 * 2. Captures in_flight_run_ids from sessions with state IN ('RUNNING', 'SLEEPING')
 * 3. Records runtime_version, platform_profile, error_code (if parseable)
 * 4. Writes detail files to ~/.aidos/crashes/<id>.txt and <id>.json (redacted stack trace + context)
 *
 * Wire into main.kt via Thread.setDefaultUncaughtExceptionHandler.
 * Does NOT transmit data anywhere.
 */
class CrashHandler(
    private val driver: SqlDriver,
    private val platformProfile: PlatformProfile,
    private val runtimeVersion: String,
    private val homeDir: String = System.getProperty("user.home"),
    private val previousHandler: Thread.UncaughtExceptionHandler? = Thread.getDefaultUncaughtExceptionHandler(),
    private val redactor: Redactor = Redactor(),
) : Thread.UncaughtExceptionHandler {

    override fun uncaughtException(thread: Thread, exception: Throwable) {
        try {
            val crashId = UuidV7Generator().next()
            val nowIso = Clock.System.now().toString()
            val redactedMessage = exception.message?.let { redactSensitive(it, redactor) }
            val redactedStackTrace = redactSensitive(exception.stackTraceToString(), redactor)
            val redactedCauseTrace = exception.cause?.stackTraceToString()?.let { redactSensitive(it, redactor) }
            val frames = parseStackTrace(redactedStackTrace)
            val stackHash = computeStackHash(redactedStackTrace, frames)
            val crashesDir = Paths.get(homeDir, ".aidos", "crashes")
            Files.createDirectories(crashesDir)

            // Write detail file
            val detailFile = crashesDir.resolve("$crashId.txt").toFile()
            detailFile.writeText(
                """
                Crash Report
                ============
                Time: $nowIso
                Platform: $platformProfile
                Runtime Version: $runtimeVersion
                Thread: ${thread.name}
                Exception Type: ${exception.javaClass.simpleName}
                Message: ${redactedMessage ?: "None"}

                Stack Trace:
                $redactedStackTrace

                Cause:
                ${redactedCauseTrace ?: "None"}
                """.trimIndent(),
                Charsets.UTF_8,
            )

            // Capture in-flight run IDs
            val inflightRunIds = captureInflightRunIds()
            val errorCode = parseErrorCode(exception)

            val recordFile = crashesDir.resolve("$crashId.json").toFile()
            val record = CrashRecord(
                id = crashId,
                occurredAt = nowIso,
                runtimeVersion = runtimeVersion,
                platformProfile = platformProfile.name,
                errorCode = errorCode,
                stackHash = stackHash,
                detailPath = detailFile.absolutePath,
                inFlightRunIds = inflightRunIds,
                threadName = thread.name,
                exceptionType = exception.javaClass.simpleName,
                message = redactedMessage,
                stackTrace = redactedStackTrace,
                causeStackTrace = redactedCauseTrace,
                frames = frames,
            )
            writeRecordToFile(record, recordFile)

            val inflightJson = buildJsonArray {
                for (id in inflightRunIds) {
                    add(JsonPrimitive(id))
                }
            }.toString()

            // Write crash record
            writeCrashRecord(
                id = crashId,
                occurredAt = nowIso,
                runtimeVersion = runtimeVersion,
                platformProfile = platformProfile.name,
                errorCode = errorCode,
                stackHash = stackHash,
                detailPath = detailFile.absolutePath,
                inflightRunIds = inflightJson,
            )

            // Print to stderr for immediate visibility
            System.err.println("CRASH: $crashId - ${exception.javaClass.simpleName}: $redactedMessage")
            System.err.println("Detail file: $detailFile")

        } catch (e: Exception) {
            System.err.println("Failed to record crash: ${redactSensitive(e.message ?: "unknown", redactor)}")
            e.printStackTrace(System.err)
        }

        // Forward to the previously installed handler (if any, guarding against self-recursion).
        if (previousHandler != null && previousHandler !== this) {
            previousHandler.uncaughtException(thread, exception)
        }
    }

    fun writeRecordToFile(record: CrashRecord, file: File) =
        Companion.writeRecordToFile(record, file)

    fun readRecordFromFile(file: File): CrashRecord? =
        Companion.readRecordFromFile(file)

    private fun computeStackHash(stackTrace: String, frames: List<StackFrame> = emptyList()): String {
        val normalized = if (frames.isNotEmpty()) {
            frames.joinToString("\n") { frame ->
                val mod = frame.moduleName?.let { "$it/" } ?: ""
                val loc = frame.fileName ?: ""
                val line = frame.lineNumber?.toString() ?: ""
                "$mod${frame.className}.${frame.methodName}($loc:$line)"
            }
        } else {
            stackTrace
        }
        return try {
            val md = java.security.MessageDigest.getInstance("SHA-256")
            val hash = md.digest(normalized.toByteArray(Charsets.UTF_8))
            hash.joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            "unknown"
        }
    }

    private fun captureInflightRunIds(): List<String> {
        return try {
            val runIds = mutableListOf<String>()

            // Query sessions with state IN ('RUNNING', 'SLEEPING')
            driver.executeQuery(
                identifier = null,
                sql = """
                    SELECT DISTINCT runs.id 
                    FROM runs 
                    WHERE runs.state IN ('RUNNING', 'SLEEPING')
                    LIMIT 100
                """,
                mapper = { cursor ->
                    while (cursor.next().value) {
                        runIds.add(cursor.getString(0) ?: "")
                    }
                    QueryResult.Value(Unit)
                },
                parameters = 0,
                binders = null,
            )

            runIds
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun writeCrashRecord(
        id: String,
        occurredAt: String,
        runtimeVersion: String,
        platformProfile: String,
        errorCode: String?,
        stackHash: String,
        detailPath: String,
        inflightRunIds: String,
    ) {
        try {
            driver.execute(
                identifier = null,
                sql = """
                    INSERT INTO crash_records 
                    (id, occurred_at, runtime_version, platform_profile, error_code, 
                     stack_hash, detail_path, in_flight_run_ids, reported)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0)
                """,
                parameters = 8,
            ) {
                bindString(0, id)
                bindString(1, occurredAt)
                bindString(2, runtimeVersion)
                bindString(3, platformProfile)
                bindString(4, errorCode)
                bindString(5, stackHash)
                bindString(6, detailPath)
                bindString(7, inflightRunIds)
            }
        } catch (e: Exception) {
            System.err.println("Failed to write crash record: ${e.message}")
        }
    }

    private fun parseErrorCode(exception: Throwable): String? {
        return when {
            exception.message?.contains("OutOfMemory") == true -> "runtime.out_of_memory"
            exception.message?.contains("StackOverflow") == true -> "runtime.stack_overflow"
            exception.message?.contains("Timeout") == true -> "runtime.timeout"
            exception is NullPointerException -> "runtime.null_pointer"
            exception is IllegalArgumentException -> "runtime.invalid_argument"
            exception is IllegalStateException -> "runtime.invalid_state"
            else -> null
        }
    }

    companion object {
        private const val REDACTED_MARKER = "[REDACTED]"
        private val json = Json {
            prettyPrint = true
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        private val MODULE_PREFIX_REGEX = Regex("^([A-Za-z0-9_.\$@+-]+(?:/[A-Za-z0-9_.\$@+-]*)?)/(.+)$")
        private val LOCATION_LINE_REGEX = Regex("^(.+):(\\d+)$")
        private val TRAILING_JAR_SUFFIX_REGEX = Regex("\\s+~?\\[[^\\]]+\\]\\s*$")

        private val SK_KEY_REGEX = Regex("\\bsk-(?:ant-|proj-|svcacct-)?[A-Za-z0-9_-]{8,}")
        private val BEARER_REGEX = Regex("(?i)\\bBearer\\s+[A-Za-z0-9._~+/=-]{4,}")
        private val BASIC_AUTH_REGEX = Regex("(?i)\\bBasic\\s+[A-Za-z0-9+/=]{4,}")
        private val JSON_SECRET_FIELD_REGEX = Regex(
            "(?i)(\"?(?:[A-Za-z0-9_.-]*(?:api[_-]?key|apikey|secret(?:_?key)?|password|passwd|authorization|auth[_-]?token|access[_-]?token|refresh[_-]?token|id[_-]?token|bearer[_-]?token|private[_-]?key)|token|credential|credentials|x-api-key)\"?\\s*:\\s*)\"([^\"\\\\]*(?:\\\\.[^\"\\\\]*)*)\""
        )
        private val KV_SECRET_REGEX = Regex(
            "(?i)\\b([A-Za-z0-9_.-]*(?:api[_-]?key|apikey|secret(?:_?key)?|password|passwd|authorization|auth[_-]?token|access[_-]?token|refresh[_-]?token|id[_-]?token|bearer[_-]?token|private[_-]?key)|token|credential|credentials|x-api-key)\\b\\s*([=:])\\s*(?:'([^']*)'|\"([^\"]*)\"|([^\\s,;'\")}\\]]{3,}))"
        )
        private val LABELED_CREDENTIAL_HEX_B64_REGEX = Regex(
            "(?i)\\b([A-Za-z0-9_.-]*(?:cred|auth|key|hmac|sig|signature|session|cookie|salt))\\b\\s*([=:])\\s*(?:\"([A-Za-z0-9+/=_-]{16,})\"|'([A-Za-z0-9+/=_-]{16,})'|([0-9a-fA-F]{32,}|[A-Za-z0-9+/]{16,}={0,2}))"
        )
        private val PADDED_BASE64_CREDENTIAL_REGEX = Regex("(?<![A-Za-z0-9+/=])[A-Za-z0-9+/]{16,}={1,2}(?![A-Za-z0-9+/=])")
        private val HEX_CREDENTIAL_REGEX = Regex("(?<![A-Za-z0-9])[0-9a-fA-F]{32,}(?![A-Za-z0-9])")

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

        fun parseStackTrace(stackTrace: String): List<StackFrame> =
            stackTrace.lineSequence()
                .filter { it.trimStart().startsWith("at ") }
                .mapNotNull { parseStackFrame(it) }
                .toList()

        fun parseStackFrame(line: String): StackFrame? {
            var trimmed = line.trim()
            if (trimmed.isEmpty()) return null
            if (trimmed.startsWith("at ")) {
                trimmed = trimmed.removePrefix("at ").trimStart()
            }
            trimmed = TRAILING_JAR_SUFFIX_REGEX.replace(trimmed, "").trimEnd()
            if (!trimmed.endsWith(")")) return null

            // Match balanced outer parentheses for the source location so Windows paths like
            // `C:\Program Files (x86)\App\Main.kt:42` are preserved intact.
            var depth = 0
            var openParenIdx = -1
            for (i in trimmed.length - 1 downTo 0) {
                when (trimmed[i]) {
                    ')' -> depth++
                    '(' -> {
                        depth--
                        if (depth == 0) {
                            openParenIdx = i
                            break
                        }
                    }
                }
            }
            if (openParenIdx <= 0) return null

            val prefixAndTarget = trimmed.substring(0, openParenIdx).trim()
            val location = trimmed.substring(openParenIdx + 1, trimmed.length - 1).trim()
            if (prefixAndTarget.isEmpty() || location.isEmpty()) return null

            val moduleMatch = MODULE_PREFIX_REGEX.matchEntire(prefixAndTarget)
            val moduleName = moduleMatch?.groupValues?.get(1)?.trimEnd('/')?.ifEmpty { null }
            val target = moduleMatch?.groupValues?.get(2) ?: prefixAndTarget

            val dotIdx = findClassMethodSeparator(target)
            if (dotIdx <= 0 || dotIdx >= target.length - 1) return null

            val className = target.substring(0, dotIdx)
            val methodName = target.substring(dotIdx + 1)

            val isNative = location == "Native Method"
            val fileName: String?
            val lineNumber: Int?
            if (isNative || location == "Unknown Source") {
                fileName = location
                lineNumber = null
            } else {
                val locMatch = LOCATION_LINE_REGEX.matchEntire(location)
                if (locMatch != null) {
                    fileName = locMatch.groupValues[1]
                    lineNumber = locMatch.groupValues[2].toIntOrNull()
                } else {
                    fileName = location
                    lineNumber = null
                }
            }

            return StackFrame(
                className = className,
                methodName = methodName,
                fileName = fileName,
                lineNumber = lineNumber,
                moduleName = moduleName,
                isNative = isNative,
            )
        }

        private fun findClassMethodSeparator(target: String): Int {
            // If method name is backtick-quoted (e.g. Kotlin test method names), skip past backticks.
            if (target.endsWith("`")) {
                val openTick = target.lastIndexOf('`', startIndex = target.length - 2)
                if (openTick > 0) {
                    return target.lastIndexOf('.', startIndex = openTick - 1)
                }
            }
            return target.lastIndexOf('.')
        }

        fun redactSensitive(input: String, redactor: Redactor = Redactor()): String {
            var result = redactor.redact(input, RedactionBoundary.LOG)
            result = SK_KEY_REGEX.replace(result, REDACTED_MARKER)
            result = BEARER_REGEX.replace(result, "Bearer $REDACTED_MARKER")
            result = BASIC_AUTH_REGEX.replace(result, "Basic $REDACTED_MARKER")
            result = JSON_SECRET_FIELD_REGEX.replace(result) { match ->
                val value = match.groupValues[2]
                if (value == REDACTED_MARKER || value.startsWith("«redacted")) match.value
                else "${match.groupValues[1]}\"$REDACTED_MARKER\""
            }
            result = KV_SECRET_REGEX.replace(result) { match ->
                val key = match.groupValues[1]
                val sep = match.groupValues[2]
                val rawVal = match.groupValues[3].ifEmpty { match.groupValues[4] }.ifEmpty { match.groupValues[5] }
                if (rawVal == REDACTED_MARKER || rawVal.startsWith("«redacted")) match.value
                else "$key$sep$REDACTED_MARKER"
            }
            result = LABELED_CREDENTIAL_HEX_B64_REGEX.replace(result) { match ->
                val key = match.groupValues[1]
                val sep = match.groupValues[2]
                if (key.equals("stack_hash", ignoreCase = true)) match.value
                else "$key$sep$REDACTED_MARKER"
            }
            result = PADDED_BASE64_CREDENTIAL_REGEX.replace(result, REDACTED_MARKER)
            result = HEX_CREDENTIAL_REGEX.replace(result, REDACTED_MARKER)
            return result
        }
    }
}
