package dev.aidos.daemon

import app.cash.sqldelight.db.SqlDriver
import dev.aidos.identity.UuidV7Generator
import dev.aidos.kernel.PlatformProfile
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import kotlinx.datetime.Clock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray

/**
 * Captures and records uncaught exceptions (RFC-0037).
 *
 * On an uncaught exception:
 * 1. Writes a crash_records row with stack_hash = sha256(stackTrace)
 * 2. Captures in_flight_run_ids from sessions with state IN ('RUNNING', 'SLEEPING')
 * 3. Records runtime_version, platform_profile, error_code (if parseable)
 * 4. Writes detail file to ~/.aidos/crashes/<id>.txt (stack trace + context)
 *
 * Wire into main.kt via Thread.setDefaultUncaughtExceptionHandler.
 * Does NOT transmit data anywhere.
 */
class CrashHandler(
    private val driver: SqlDriver,
    private val platformProfile: PlatformProfile,
    private val runtimeVersion: String,
    private val homeDir: String = System.getProperty("user.home"),
) : Thread.UncaughtExceptionHandler {

    override fun uncaughtException(thread: Thread, exception: Throwable) {
        try {
            val crashId = UuidV7Generator().generate()
            val nowIso = Clock.System.now().toString()
            val stackTrace = exception.stackTraceToString()
            val stackHash = computeStackHash(stackTrace)
            val crashesDir = Paths.get(homeDir, ".aidos", "crashes")
            Files.createDirectories(crashesDir)

            // Write detail file
            val detailFile = crashesDir.resolve("$crashId.txt").toFile()
            detailFile.writeText("""
                Crash Report
                ============
                Time: $nowIso
                Platform: $platformProfile
                Runtime Version: $runtimeVersion
                Thread: ${thread.name}
                Exception Type: ${exception.javaClass.simpleName}
                
                Stack Trace:
                $stackTrace
                
                Cause:
                ${exception.cause?.stackTraceToString() ?: "None"}
            """.trimIndent())

            // Capture in-flight run IDs
            val inflightRunIds = captureInflightRunIds()

            // Write crash record
            writeCrashRecord(
                id = crashId,
                occurredAt = nowIso,
                runtimeVersion = runtimeVersion,
                platformProfile = platformProfile.name,
                errorCode = parseErrorCode(exception),
                stackHash = stackHash,
                detailPath = detailFile.absolutePath,
                inflightRunIds = inflightRunIds,
            )

            // Print to stderr for immediate visibility
            System.err.println("CRASH: $crashId - ${exception.javaClass.simpleName}: ${exception.message}")
            System.err.println("Detail file: $detailFile")

        } catch (e: Exception) {
            System.err.println("Failed to record crash: ${e.message}")
            e.printStackTrace(System.err)
        }

        // Re-throw to let default handler (system exit) take over
        Thread.getDefaultUncaughtExceptionHandler()?.uncaughtException(thread, exception)
    }

    private fun computeStackHash(stackTrace: String): String {
        return try {
            val md = java.security.MessageDigest.getInstance("SHA-256")
            val hash = md.digest(stackTrace.toByteArray())
            hash.joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            "unknown"
        }
    }

    private fun captureInflightRunIds(): String {
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
                    val result = mutableListOf<String>()
                    while (cursor.next().value) {
                        result.add(cursor.getString(0) ?: "")
                    }
                    result
                },
                parameters = 0,
            ) { }
            
            Json.encodeToString(buildJsonArray {
                for (id in runIds) {
                    add(JsonPrimitive(id))
                }
            })
        } catch (e: Exception) {
            "[]"
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
}
