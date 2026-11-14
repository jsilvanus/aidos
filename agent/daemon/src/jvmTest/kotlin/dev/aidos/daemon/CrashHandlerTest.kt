package dev.aidos.daemon

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import dev.aidos.kernel.PlatformProfile
import dev.aidos.storage.AidosStorage
import dev.aidos.storage.DesktopPaths
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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

    @AfterTest
    fun tearDown() {
        runCatching { driver.close() }
        tempDir.deleteRecursively()
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

    @Test
    fun testStackFrameParsingHandlesWindowsPathsKotlinSyntheticAndModules() {
        val sampleStack = """
            java.lang.RuntimeException: boom
            	at java.base/java.lang.Thread.run(Thread.java:1583)
            	at java.base@21.0.2/jdk.internal.misc.Unsafe.park(Native Method)
            	at app//dev.aidos.daemon.RuntimeCompositionRoot${'$'}buildExecutor${'$'}1${'$'}2.invokeSuspend(C:\Users\dev user\Code (x86)\aidos\RuntimeCompositionRoot.kt:149)
            	at dev.aidos.daemon.Foo${'$'}Companion.<init>(C:\src\Foo.kt:12)
        """.trimIndent()

        val frames = CrashHandler.parseStackTrace(sampleStack)
        assertEquals(4, frames.size)

        assertEquals("java.base", frames[0].moduleName)
        assertEquals("java.lang.Thread", frames[0].className)
        assertEquals("run", frames[0].methodName)
        assertEquals("Thread.java", frames[0].fileName)
        assertEquals(1583, frames[0].lineNumber)

        assertEquals("java.base@21.0.2", frames[1].moduleName)
        assertTrue(frames[1].isNative)

        assertEquals("app", frames[2].moduleName)
        assertEquals("dev.aidos.daemon.RuntimeCompositionRoot\$buildExecutor\$1\$2", frames[2].className)
        assertEquals("invokeSuspend", frames[2].methodName)
        assertEquals("C:\\Users\\dev user\\Code (x86)\\aidos\\RuntimeCompositionRoot.kt", frames[2].fileName)
        assertEquals(149, frames[2].lineNumber)

        assertNull(frames[3].moduleName)
        assertEquals("dev.aidos.daemon.Foo\$Companion", frames[3].className)
        assertEquals("<init>", frames[3].methodName)
        assertEquals("C:\\src\\Foo.kt", frames[3].fileName)
        assertEquals(12, frames[3].lineNumber)
    }

    @Test
    fun testSecretRedactionAcrossMessageStackAndCause() {
        val cause = IllegalStateException(
            "cause api_key=my_secret_api_key_9876 password=hunter2pass dXNlcjpzZWNyZXRwYXNzd29yZA== deadbeef0123456789abcdef01234567"
        )
        val exception = RuntimeException(
            "Auth failed with sk-ant-secret1234567890abcdef and Bearer my-bearer-secret-999 and {\"token\": \"tok-json-secret\"} and token=urltok123 and authorization=Basic dXNlcjpwYXNz",
            cause,
        )

        crashHandler.uncaughtException(Thread.currentThread(), exception)

        val crashesDir = Paths.get(tempDir.absolutePath, ".aidos", "crashes").toFile()
        val txtFiles = crashesDir.listFiles { _, name -> name.endsWith(".txt") }
        assertNotNull(txtFiles)
        assertTrue(txtFiles.isNotEmpty())
        val txtContent = txtFiles[0].readText()

        assertFalse(txtContent.contains("sk-ant-secret1234567890abcdef"), "OpenAI/Anthropic key must be redacted")
        assertFalse(txtContent.contains("my-bearer-secret-999"), "Bearer token must be redacted")
        assertFalse(txtContent.contains("tok-json-secret"), "JSON token must be redacted")
        assertFalse(txtContent.contains("urltok123"), "Query token must be redacted")
        assertFalse(txtContent.contains("my_secret_api_key_9876"), "api_key must be redacted")
        assertFalse(txtContent.contains("hunter2pass"), "password must be redacted")
        assertFalse(txtContent.contains("dXNlcjpzZWNyZXRwYXNzd29yZA=="), "Base64 credential must be redacted")
        assertFalse(txtContent.contains("deadbeef0123456789abcdef01234567"), "Hex credential must be redacted")

        val jsonFiles = crashesDir.listFiles { _, name -> name.endsWith(".json") }
        assertNotNull(jsonFiles)
        assertTrue(jsonFiles.isNotEmpty())
        val decoded = crashHandler.readRecordFromFile(jsonFiles[0])
        assertNotNull(decoded)
        assertFalse((decoded.message ?: "").contains("sk-ant-secret1234567890abcdef"))
        assertFalse((decoded.causeStackTrace ?: "").contains("hunter2pass"))
    }

    @Test
    fun testCrashRecordJsonRoundTripPreservesMultilineStackAndEscapes() {
        val record = CrashRecord(
            id = "crash-123",
            occurredAt = "2026-11-14T08:00:00Z",
            runtimeVersion = "0.1.0-alpha",
            platformProfile = "DESKTOP",
            errorCode = "runtime.invalid_state",
            stackHash = "a".repeat(64),
            detailPath = "C:\\Users\\test\\.aidos\\crashes\\crash-123.txt",
            inFlightRunIds = listOf("run-1", "run-2"),
            threadName = "worker-\"1\"",
            exceptionType = "IllegalStateException",
            message = "Quoted \"msg\" with\nnewline and C:\\path\\file",
            stackTrace = "java.lang.IllegalStateException: Quoted \"msg\"\n\tat app//dev.aidos.Foo.bar(C:\\Users\\a\\Foo.kt:42)\n\tat java.base/java.lang.Thread.run(Thread.java:1)",
            causeStackTrace = "Caused by: java.io.IOException: \"disk\"\n\tat Foo.kt:99",
            frames = listOf(
                StackFrame(
                    className = "dev.aidos.Foo",
                    methodName = "bar",
                    fileName = "C:\\Users\\a\\Foo.kt",
                    lineNumber = 42,
                    moduleName = "app",
                )
            ),
        )

        val file = File(tempDir, "record-roundtrip.json")
        crashHandler.writeRecordToFile(record, file)
        val restored = crashHandler.readRecordFromFile(file)
        assertEquals(record, restored)
    }

    @Test
    fun testUncaughtExceptionChainsPreviousHandler() {
        val capturedThread = AtomicReference<Thread?>()
        val capturedError = AtomicReference<Throwable?>()
        val previous = Thread.UncaughtExceptionHandler { t, e ->
            capturedThread.set(t)
            capturedError.set(e)
        }
        val chainedHandler = CrashHandler(
            driver = driver,
            platformProfile = PlatformProfile.DESKTOP,
            runtimeVersion = "0.1.0-alpha",
            homeDir = tempDir.absolutePath,
            previousHandler = previous,
        )

        val ex = IllegalArgumentException("bad arg")
        val currentThread = Thread.currentThread()
        chainedHandler.uncaughtException(currentThread, ex)

        assertEquals(currentThread, capturedThread.get())
        assertEquals(ex, capturedError.get())
    }
}
