package fi.italeino.aidos.engine.observability

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertNotNull

/**
 * Unit tests for CrashHandler (RFC-0037).
 *
 * Verifies that crashes are properly recorded to the database and detail files.
 */
class CrashHandlerTest {

    @Test
    fun testCrashRecordingWritesDetailFile() {
        val tempDir = Files.createTempDirectory("crash_handler_test").toFile()
        try {
            val crashDir = File(tempDir, "crashes")
            val dbDriver = JdbcSqliteDriver(
                url = "jdbc:sqlite:${File(tempDir, "test.db").absolutePath}",
            )

            // Initialize crash_records table (this would be done by migration in real code)
            dbDriver.execute(
                null,
                """
                CREATE TABLE IF NOT EXISTS crash_records (
                    id TEXT PRIMARY KEY,
                    occurred_at TEXT NOT NULL,
                    runtime_version TEXT NOT NULL,
                    platform_profile TEXT NOT NULL,
                    error_code TEXT,
                    stack_hash TEXT NOT NULL,
                    detail_path TEXT NOT NULL,
                    in_flight_run_ids TEXT NOT NULL DEFAULT '[]',
                    reported INTEGER NOT NULL DEFAULT 0
                )
                """,
                0,
                {}
            )

            val handler = CrashHandler(
                driver = dbDriver,
                runtimeVersion = "0.1.0-test",
                platformProfile = "DESKTOP",
                crashDir = crashDir,
                inFlightRunIds = { listOf("run-1", "run-2") }
            )

            val exception = RuntimeException("Test exception")
            val thread = Thread.currentThread()

            handler.uncaughtException(thread, exception)

            // Verify detail file was created
            val detailFiles = crashDir.listFiles()
            assertNotNull(detailFiles)
            assertTrue(detailFiles.size > 0, "No detail file created")

            val detailFile = detailFiles[0]
            val content = detailFile.readText()
            assertTrue(content.contains("Test exception"), "Detail file missing exception message")
            assertTrue(content.contains("stack_hash"), "Detail file missing stack hash reference")
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testCrashRecordingToDatabase() {
        val tempDir = Files.createTempDirectory("crash_db_test").toFile()
        try {
            val crashDir = File(tempDir, "crashes")
            val dbDriver = JdbcSqliteDriver(
                url = "jdbc:sqlite:${File(tempDir, "test.db").absolutePath}",
            )

            // Initialize crash_records table
            dbDriver.execute(
                null,
                """
                CREATE TABLE IF NOT EXISTS crash_records (
                    id TEXT PRIMARY KEY,
                    occurred_at TEXT NOT NULL,
                    runtime_version TEXT NOT NULL,
                    platform_profile TEXT NOT NULL,
                    error_code TEXT,
                    stack_hash TEXT NOT NULL,
                    detail_path TEXT NOT NULL,
                    in_flight_run_ids TEXT NOT NULL DEFAULT '[]',
                    reported INTEGER NOT NULL DEFAULT 0
                )
                """,
                0,
                {}
            )

            val handler = CrashHandler(
                driver = dbDriver,
                runtimeVersion = "0.1.0-test",
                platformProfile = "MOBILE",
                crashDir = crashDir,
            )

            val exception = IllegalStateException("State is invalid")
            handler.uncaughtException(Thread.currentThread(), exception)

            // Verify record was written to database
            var recordCount = 0
            dbDriver.execute(
                null,
                "SELECT COUNT(*) as cnt FROM crash_records",
                0,
                { recordCount = getInt(0) }
            )
            assertTrue(recordCount > 0, "No crash record written to database")
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testStackHashDeduplication() {
        val tempDir = Files.createTempDirectory("stack_hash_test").toFile()
        try {
            val crashDir = File(tempDir, "crashes")
            val dbDriver = JdbcSqliteDriver(
                url = "jdbc:sqlite:${File(tempDir, "test.db").absolutePath}",
            )

            // Initialize crash_records table
            dbDriver.execute(
                null,
                """
                CREATE TABLE IF NOT EXISTS crash_records (
                    id TEXT PRIMARY KEY,
                    occurred_at TEXT NOT NULL,
                    runtime_version TEXT NOT NULL,
                    platform_profile TEXT NOT NULL,
                    error_code TEXT,
                    stack_hash TEXT NOT NULL,
                    detail_path TEXT NOT NULL,
                    in_flight_run_ids TEXT NOT NULL DEFAULT '[]',
                    reported INTEGER NOT NULL DEFAULT 0
                )
                """,
                0,
                {}
            )

            val handler = CrashHandler(
                driver = dbDriver,
                runtimeVersion = "0.1.0-test",
                platformProfile = "DESKTOP",
                crashDir = crashDir,
            )

            // Record the same exception twice (simulating repeated crashes with same stack)
            val exception = NullPointerException("Null value")
            handler.uncaughtException(Thread.currentThread(), exception)
            handler.uncaughtException(Thread.currentThread(), exception)

            // Both should have the same stack hash (but different IDs)
            var stackHashes = mutableListOf<String>()
            dbDriver.execute(
                null,
                "SELECT stack_hash FROM crash_records",
                0,
                { stackHashes.add(getString(0)) }
            )
            assertTrue(stackHashes.size >= 2, "Not enough crash records")
            assertTrue(stackHashes[0] == stackHashes[1], "Stack hashes should match for same exception")
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
