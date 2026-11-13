package dev.aidos.daemon

import dev.aidos.kernel.PlatformProfile
import dev.aidos.vault.Redactor
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for StructuredLogger (RFC-0037).
 */
class StructuredLoggerTest {

    lateinit var tempDir: File
    
    private lateinit var logger: StructuredLogger
    private lateinit var redactor: Redactor

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("structured-logger-test").toFile()
        redactor = Redactor()
        logger = StructuredLogger(
            redactor = redactor,
            platformProfile = PlatformProfile.DESKTOP,
            runtimeVersion = "0.1.0-alpha",
            homeDir = tempDir.absolutePath,
        )
    }

    @Test
    fun testErrorLogging() {
        logger.error("test.error") {
            field("run_id", "run-123")
            field("message", "Something went wrong")
        }

        val logs = logger.getRecentLogs()
        assertEquals(1, logs.size)
        val log = logs[0]
        assertEquals("ERROR", log.level.name)
        assertEquals("test.error", log.key)
        assertTrue(log.fields.containsKey("run_id"))
        assertTrue(log.fields.containsKey("message"))
    }

    @Test
    fun testWarnLogging() {
        logger.warn("test.warn") {
            field("task_id", "task-456")
            field("issue", "Degraded performance")
        }

        val logs = logger.getRecentLogs()
        assertEquals(1, logs.size)
        assertEquals("WARN", logs[0].level.name)
    }

    @Test
    fun testInfoLogging() {
        logger.info("test.info") {
            field("event", "session.opened")
            field("user_id", "user-789")
        }

        val logs = logger.getRecentLogs()
        assertEquals(1, logs.size)
        assertEquals("INFO", logs[0].level.name)
    }

    @Test
    fun testDebugLoggingOnDesktop() {
        val desktopLogger = StructuredLogger(
            redactor = redactor,
            platformProfile = PlatformProfile.DESKTOP,
            runtimeVersion = "0.1.0-alpha",
            homeDir = tempDir.absolutePath,
        )

        desktopLogger.debug("test.debug") {
            field("step", "1")
            field("decision", "routing")
        }

        val logs = desktopLogger.getRecentLogs()
        assertEquals(1, logs.size)
        assertEquals("DEBUG", logs[0].level.name)
    }

    @Test
    fun testDebugLoggingDisabledOnMobile() {
        val mobileLogger = StructuredLogger(
            redactor = redactor,
            platformProfile = PlatformProfile.MOBILE,
            runtimeVersion = "0.1.0-alpha",
            homeDir = tempDir.absolutePath,
        )

        mobileLogger.debug("test.debug") {
            field("step", "1")
        }

        val logs = mobileLogger.getRecentLogs()
        assertTrue(logs.isEmpty())
    }

    @Test
    fun testTraceLoggingDisabledByDefault() {
        logger.trace("test.trace") {
            field("payload", "sensitive data")
        }

        val logs = logger.getRecentLogs()
        assertTrue(logs.isEmpty())
    }

    @Test
    fun testTraceLoggingWhenEnabled() {
        logger.enableTrace()
        logger.trace("test.trace") {
            field("payload", "sensitive data")
        }

        val logs = logger.getRecentLogs()
        assertEquals(1, logs.size)
        assertEquals("TRACE", logs[0].level.name)
    }

    @Test
    fun testRingBufferBoundsMobile() {
        val mobileLogger = StructuredLogger(
            redactor = redactor,
            platformProfile = PlatformProfile.MOBILE,
            runtimeVersion = "0.1.0-alpha",
            homeDir = tempDir.absolutePath,
        )

        // Log more than 200 records
        for (i in 0..250) {
            mobileLogger.info("test.info") {
                field("index", i.toString())
            }
        }

        val logs = mobileLogger.getRecentLogs()
        assertTrue(logs.size <= 200)
    }

    @Test
    fun testRingBufferBoundsDesktop() {
        // Desktop allows up to 1000 records
        for (i in 0..1050) {
            logger.info("test.info") {
                field("index", i.toString())
            }
        }

        val logs = logger.getRecentLogs()
        assertTrue(logs.size <= 1000)
    }

    @Test
    fun testLogFileCreation() {
        logger.error("test.error") {
            field("test", "data")
        }

        val logsDir = Paths.get(tempDir.absolutePath, ".aidos", "logs")
        assertTrue(Files.exists(logsDir))
        val files = Files.list(logsDir).toList()
        assertTrue(files.isNotEmpty())
    }

    @Test
    fun testCorrelationIds() {
        logger.info("test.correlation") {
            field("run_id", "run-123")
            field("task_id", "task-456")
            field("attempt_id", "attempt-789")
        }

        val logs = logger.getRecentLogs()
        assertEquals(1, logs.size)
        val log = logs[0]
        assertEquals("run-123", log.fields["run_id"])
        assertEquals("task-456", log.fields["task_id"])
        assertEquals("attempt-789", log.fields["attempt_id"])
    }

    @Test
    fun `testLogJson Format`() {
        logger.error("test.json") {
            field("custom_key", "value")
            field("number", "42")
        }

        val logsDir = Paths.get(tempDir.absolutePath, ".aidos", "logs")
        val logFile = Files.list(logsDir).findFirst().orElse(null)
        assertNotNull(logFile)

        val content = Files.readAllLines(logFile)
        assertTrue(content.isNotEmpty())
        val line = content.last()
        val parsed = Json.parseToJsonElement(line).jsonObject
        assertEquals("ERROR", parsed["level"]?.jsonPrimitive?.content)
        assertEquals("test.json", parsed["key"]?.jsonPrimitive?.content)
    }
}
