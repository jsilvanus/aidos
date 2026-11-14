package fi.italeino.aidos.engine.observability

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicReference
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

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
                identifier = null,
                sql = """
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
                parameters = 0,
            ) {}

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
                identifier = null,
                sql = """
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
                parameters = 0,
            ) {}

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
            dbDriver.executeQuery(
                identifier = null,
                sql = "SELECT COUNT(*) as cnt FROM crash_records",
                mapper = { c ->
                    if (c.next().value) recordCount = c.getLong(0)!!.toInt()
                    app.cash.sqldelight.db.QueryResult.Value(Unit)
                },
                parameters = 0,
            ) {}
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
                identifier = null,
                sql = """
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
                parameters = 0,
            ) {}

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
            val stackHashes = mutableListOf<String>()
            dbDriver.executeQuery(
                identifier = null,
                sql = "SELECT stack_hash FROM crash_records",
                mapper = { c ->
                    while (c.next().value) {
                        stackHashes.add(c.getString(0)!!)
                    }
                    app.cash.sqldelight.db.QueryResult.Value(Unit)
                },
                parameters = 0,
            ) {}
            assertTrue(stackHashes.size >= 2, "Not enough crash records")
            assertTrue(stackHashes[0] == stackHashes[1], "Stack hashes should match for same exception")
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testInFlightRunIdsSerializedAsValidJsonAndSecretsRedacted() {
        val tempDir = Files.createTempDirectory("crash_inflight_json").toFile()
        val dbDriver = JdbcSqliteDriver(url = "jdbc:sqlite:${File(tempDir, "test.db").absolutePath}")
        try {
            val crashDir = File(tempDir, "crashes")
            createCrashRecordsTable(dbDriver)

            val captured = AtomicReference<Throwable?>()
            val prev = Thread.UncaughtExceptionHandler { _, e -> captured.set(e) }
            val handler = CrashHandler(
                driver = dbDriver,
                runtimeVersion = "0.1.0-test",
                platformProfile = "DESKTOP",
                crashDir = crashDir,
                inFlightRunIds = { listOf("run-1", "run-\"2\"", "run\\3") },
                previousHandler = prev,
            )

            val ex = RuntimeException("Crash with Bearer secret-bearer-tok and sk-ant-secret1234567890abcd and token=urltok")
            handler.uncaughtException(Thread.currentThread(), ex)
            assertEquals(ex, captured.get())

            var inFlightJson = ""
            dbDriver.executeQuery(
                identifier = null,
                sql = "SELECT in_flight_run_ids FROM crash_records LIMIT 1",
                mapper = { c ->
                    if (c.next().value) inFlightJson = c.getString(0)!!
                    app.cash.sqldelight.db.QueryResult.Value(Unit)
                },
                parameters = 0,
            ) {}
            val parsed = Json.parseToJsonElement(inFlightJson).jsonArray.map { it.jsonPrimitive.content }
            assertEquals(listOf("run-1", "run-\"2\"", "run\\3"), parsed)

            val txtFiles = crashDir.listFiles { _, name -> name.endsWith(".txt") }
            assertNotNull(txtFiles)
            val txt = txtFiles.single().readText()
            assertFalse(txt.contains("secret-bearer-tok"))
            assertFalse(txt.contains("sk-ant-secret1234567890abcd"))
            assertFalse(txt.contains("urltok"))
        } finally {
            runCatching { dbDriver.close() }
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testReadRecordFromFileRoundTripsMultilineAndOrphanRecovery() {
        val tempDir = Files.createTempDirectory("crash_roundtrip_test").toFile()
        val dbDriver = JdbcSqliteDriver(url = "jdbc:sqlite:${File(tempDir, "test.db").absolutePath}")
        try {
            val crashDir = File(tempDir, "crashes").apply { mkdirs() }
            createCrashRecordsTable(dbDriver)

            val record = CrashRecord(
                id = "orphan-crash-1",
                occurredAt = "2026-11-14T08:00:00Z",
                runtimeVersion = "0.1.0-test",
                platformProfile = "DESKTOP",
                errorCode = "IllegalStateException",
                stackHash = "f".repeat(64),
                detailPath = "C:\\Users\\test\\crashes\\orphan-crash-1.txt",
                inFlightRunIds = listOf("run-1", "run-\"quoted\""),
                reported = false,
                stackTrace = "java.lang.IllegalStateException: \"quoted\" line\n\tat Foo.bar(C:\\app\\Foo.kt:12)\n\tat Baz.qux(Baz.kt:34)",
            )

            val orphanFile = File(crashDir, "orphan-crash-1.json")
            val handler = CrashHandler(
                driver = dbDriver,
                runtimeVersion = "0.1.0-test",
                platformProfile = "DESKTOP",
                crashDir = crashDir,
            )
            handler.writeRecordToFile(record, orphanFile)

            val restored = handler.readRecordFromFile(orphanFile)
            assertEquals(record, restored)
            assertFalse(restored?.runtimeVersion?.startsWith("\"") == true)

            val firstRecovered = handler.recoverOrphanedRecords(crashDir)
            assertEquals(1, firstRecovered)
            val secondRecovered = handler.recoverOrphanedRecords(crashDir)
            assertEquals(0, secondRecovered)

            var dbInFlight = ""
            dbDriver.executeQuery(
                identifier = null,
                sql = "SELECT in_flight_run_ids FROM crash_records WHERE id = 'orphan-crash-1'",
                mapper = { c ->
                    if (c.next().value) dbInFlight = c.getString(0)!!
                    app.cash.sqldelight.db.QueryResult.Value(Unit)
                },
                parameters = 0,
            ) {}
            assertEquals(
                listOf("run-1", "run-\"quoted\""),
                Json.parseToJsonElement(dbInFlight).jsonArray.map { it.jsonPrimitive.content },
            )
        } finally {
            runCatching { dbDriver.close() }
            tempDir.deleteRecursively()
        }
    }

    private fun createCrashRecordsTable(dbDriver: JdbcSqliteDriver) {
        dbDriver.execute(
            identifier = null,
            sql = """
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
            parameters = 0,
        ) {}
    }
}
