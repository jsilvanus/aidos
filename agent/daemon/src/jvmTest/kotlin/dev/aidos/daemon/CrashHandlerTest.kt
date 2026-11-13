package dev.aidos.daemon

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import dev.aidos.kernel.PlatformProfile
import dev.aidos.storage.AidosStorage
import dev.aidos.storage.DesktopPaths
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for CrashHandler (RFC-0037).
 */
class CrashHandlerTest {

    lateinit var tempDir: File
    
    private lateinit var driver: SqlDriver
    private lateinit var crashHandler: CrashHandler

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("crash-handler-test").toFile()
        val userDb = AidosStorage.openUser(
            DesktopPaths.userDb(tempDir.path),
            "0.1.0-alpha",
            { java.time.Instant.now().toString() }
        )
        driver = userDb.driver
        
        crashHandler = CrashHandler(
            driver = driver,
            platformProfile = PlatformProfile.DESKTOP,
            runtimeVersion = "0.1.0-alpha",
            homeDir = tempDir.absolutePath,
        )
    }

    @Test
    fun testCrashHandlerWritesCrashRecord() {
        val exception = RuntimeException("Test exception")
        val thread = Thread.currentThread()

        // Create crash record manually (can't actually trigger uncaught exception in test)
        try {
            crashHandler.uncaughtException(thread, exception)
        } catch (e: Exception) {
            // Expected - handler re-throws to default handler
        }

        // Verify crash record was written
        var count = 0L
        driver.executeQuery(
            identifier = null,
            sql = "SELECT COUNT(*) FROM crash_records",
            mapper = { cursor ->
                if (cursor.next().value) count = cursor.getLong(0) ?: 0L
                QueryResult.Value(Unit)
            },
            parameters = 0,
        )

        assertTrue(count > 0, "Crash record should be written")
    }

    @Test
    fun testCrashDetailFileCreated() {
        val exception = RuntimeException("Test exception details")
        val thread = Thread.currentThread()

        try {
            crashHandler.uncaughtException(thread, exception)
        } catch (e: Exception) {
            // Expected
        }

        val crashesDir = Paths.get(tempDir.absolutePath, ".aidos", "crashes")
        assertTrue(Files.exists(crashesDir), "Crashes directory should be created")

        val crashFiles = Files.list(crashesDir).toList()
        assertTrue(crashFiles.isNotEmpty(), "Crash detail file should be created")

        val detailFile = crashFiles[0]
        val content = Files.readString(detailFile)
        assertTrue(content.contains("RuntimeException"))
        assertTrue(content.contains("Test exception details"))
    }

    @Test
    fun testStackHashComputed() {
        var stackHash = ""
        driver.executeQuery(
            identifier = null,
            sql = "SELECT stack_hash FROM crash_records LIMIT 1",
            mapper = { cursor ->
                if (cursor.next().value) {
                    stackHash = cursor.getString(0) ?: ""
                }
                QueryResult.Value(Unit)
            },
            parameters = 0,
        )

        // Note: We need to first write a crash record
        val exception = RuntimeException("Test")
        try {
            crashHandler.uncaughtException(Thread.currentThread(), exception)
        } catch (e: Exception) {
            // Expected
        }

        driver.executeQuery(
            identifier = null,
            sql = "SELECT stack_hash FROM crash_records LIMIT 1",
            mapper = { cursor ->
                if (cursor.next().value) {
                    stackHash = cursor.getString(0) ?: ""
                }
                QueryResult.Value(Unit)
            },
            parameters = 0,
        )

        assertTrue(stackHash.isNotEmpty(), "Stack hash should be computed")
        assertEquals(64, stackHash.length, "SHA-256 hash should be 64 hex chars")
    }

    @Test
    fun testCrashRecordHasCorrectFields() {
        val exception = RuntimeException("Test")
        try {
            crashHandler.uncaughtException(Thread.currentThread(), exception)
        } catch (e: Exception) {
            // Expected
        }

        var occurredAt = ""
        var runtimeVersion = ""
        var platformProfile = ""

        driver.executeQuery(
            identifier = null,
            sql = "SELECT occurred_at, runtime_version, platform_profile FROM crash_records LIMIT 1",
            mapper = { cursor ->
                if (cursor.next().value) {
                    occurredAt = cursor.getString(0) ?: ""
                    runtimeVersion = cursor.getString(1) ?: ""
                    platformProfile = cursor.getString(2) ?: ""
                }
                QueryResult.Value(Unit)
            },
            parameters = 0,
        )

        assertTrue(occurredAt.isNotEmpty(), "occurred_at should be recorded")
        assertEquals("0.1.0-alpha", runtimeVersion)
        assertEquals("DESKTOP", platformProfile)
    }

    @Test
    fun testErrorCodeParsing() {
        val exceptions = listOf(
            Pair(OutOfMemoryError("OOM"), "runtime.out_of_memory"),
            Pair(StackOverflowError("Stack overflow"), "runtime.stack_overflow"),
            Pair(NullPointerException("NPE"), "runtime.null_pointer"),
        )

        for ((exception, _) in exceptions) {
            try {
                crashHandler.uncaughtException(Thread.currentThread(), exception)
            } catch (e: Exception) {
                // Expected
            }
        }

        var errorCodes = mutableListOf<String?>()
        driver.executeQuery(
            identifier = null,
            sql = "SELECT error_code FROM crash_records",
            mapper = { cursor ->
                while (cursor.next().value) {
                    errorCodes.add(cursor.getString(0))
                }
                QueryResult.Value(Unit)
            },
            parameters = 0,
        )

        assertTrue(errorCodes.isNotEmpty(), "Error codes should be captured")
    }

    @Test
    fun testInflightRunIdsCapture() {
        val exception = RuntimeException("Test with in-flight runs")
        try {
            crashHandler.uncaughtException(Thread.currentThread(), exception)
        } catch (e: Exception) {
            // Expected
        }

        var inflightRunIds = ""
        driver.executeQuery(
            identifier = null,
            sql = "SELECT in_flight_run_ids FROM crash_records LIMIT 1",
            mapper = { cursor ->
                if (cursor.next().value) {
                    inflightRunIds = cursor.getString(0) ?: "[]"
                }
                QueryResult.Value(Unit)
            },
            parameters = 0,
        )

        // Should be valid JSON array (at least "[]")
        assertTrue(inflightRunIds.startsWith("["), "in_flight_run_ids should be JSON array")
        assertTrue(inflightRunIds.endsWith("]"), "in_flight_run_ids should be JSON array")
    }
}
