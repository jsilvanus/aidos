package dev.aidos.daemon

import dev.aidos.kernel.PlatformProfile
import dev.aidos.vault.Redactor
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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

    @AfterTest
    fun tearDown() {
        tempDir.deleteRecursively()
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

    @Test
    fun testJsonControlCharsAndQuotesEscapedPerRfc8259() {
        val special = "back\\slash \"quote\" line\nret\rtab\tbs\bform\u000cnull\u0000ctrl\u001f"
        logger.info("test.escaping") {
            field("payload", special)
            field("level", "SPOOFED")
            field("key", "SPOOFED_KEY")
        }

        val logsDir = Paths.get(tempDir.absolutePath, ".aidos", "logs")
        val logFile = Files.list(logsDir).use { it.findFirst().orElse(null) }
        assertNotNull(logFile)

        val lines = Files.readAllLines(logFile, Charsets.UTF_8)
        assertEquals(1, lines.size)
        val parsed = Json.parseToJsonElement(lines.single()).jsonObject
        assertEquals(special, parsed["payload"]?.jsonPrimitive?.content)
        assertEquals("INFO", parsed["level"]?.jsonPrimitive?.content)
        assertEquals("test.escaping", parsed["key"]?.jsonPrimitive?.content)
    }

    @Test
    fun testRecursiveSecretRedactionInMetadataAndRingBuffer() {
        logger.info("test.redaction") {
            field("token", "short-token")
            field("token_count", 150)
            field("max_tokens", 4096)
            field(
                "nested",
                mapOf(
                    "apiKey" to "raw-api-key",
                    "api_key" to "raw-api-key-2",
                    "secret" to "top-secret",
                    "password" to "hunter2",
                    "authorization" to "Bearer secret-jwt",
                    "items" to listOf(
                        mapOf("access_token" to "tok-nested"),
                        "Bearer inner-bearer-secret and sk-proj-abcd12345678",
                    ),
                ),
            )
            field("json_blob", "{\"password\":\"inside-json\",\"token\":\"inside-tok\",\"safe\":\"ok\"}")
        }

        val logsDir = Paths.get(tempDir.absolutePath, ".aidos", "logs")
        val logFile = Files.list(logsDir).use { it.findFirst().orElse(null) }
        assertNotNull(logFile)
        val rawLine = Files.readAllLines(logFile, Charsets.UTF_8).single()

        for (secret in listOf(
            "short-token",
            "raw-api-key",
            "raw-api-key-2",
            "top-secret",
            "hunter2",
            "secret-jwt",
            "tok-nested",
            "inner-bearer-secret",
            "sk-proj-abcd12345678",
            "inside-json",
            "inside-tok",
        )) {
            assertFalse(rawLine.contains(secret), "Log JSONL leaked secret: $secret")
        }

        val parsed = Json.parseToJsonElement(rawLine).jsonObject
        assertEquals("150", parsed["token_count"]?.jsonPrimitive?.content)
        assertEquals("4096", parsed["max_tokens"]?.jsonPrimitive?.content)

        val recent = logger.getRecentLogs().single()
        assertEquals("[REDACTED]", recent.fields["token"])
        assertFalse((recent.fields["nested"] ?: "").contains("top-secret"))
        assertFalse((recent.fields["json_blob"] ?: "").contains("inside-json"))
    }

    @Test
    fun testTraceAndNormalLogFilesStaySeparate() {
        logger.enableTrace()
        logger.trace("trace.event") {
            field("step", "1")
        }
        logger.info("info.event") {
            field("step", "2")
        }

        val logsDir = Paths.get(tempDir.absolutePath, ".aidos", "logs")
        val files = Files.list(logsDir).use { s -> s.map { it.fileName.toString() }.toList() }
        assertTrue(files.contains("diagnostic-trace.log"), "Expected diagnostic-trace.log in $files")
        val datedFiles = files.filter { it.startsWith("diagnostic-") && it != "diagnostic-trace.log" }
        assertTrue(datedFiles.isNotEmpty(), "Expected dated diagnostic log file after TRACE log")

        val traceLines = Files.readAllLines(logsDir.resolve("diagnostic-trace.log"), Charsets.UTF_8)
        assertEquals(1, traceLines.size)
        assertEquals("TRACE", Json.parseToJsonElement(traceLines.single()).jsonObject["level"]?.jsonPrimitive?.content)

        val datedLines = Files.readAllLines(logsDir.resolve(datedFiles.first()), Charsets.UTF_8)
        assertEquals(1, datedLines.size)
        assertEquals("INFO", Json.parseToJsonElement(datedLines.single()).jsonObject["level"]?.jsonPrimitive?.content)
    }

    @Test
    fun testConcurrentLoggingProducesValidJsonLines() {
        val threadCount = 6
        val perThread = 40
        val pool = Executors.newFixedThreadPool(threadCount)
        try {
            val futures = (0 until threadCount).map { t ->
                pool.submit {
                    for (i in 0 until perThread) {
                        logger.info("concurrent.event") {
                            field("thread", t)
                            field("index", i)
                            field("msg", "line-$t-$i\n\"quoted\"")
                        }
                    }
                }
            }
            futures.forEach { it.get(15, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }

        val logsDir = Paths.get(tempDir.absolutePath, ".aidos", "logs")
        val logFile = Files.list(logsDir).use { it.findFirst().orElse(null) }
        assertNotNull(logFile)
        val lines = Files.readAllLines(logFile, Charsets.UTF_8)
        assertEquals(threadCount * perThread, lines.size)
        for (line in lines) {
            val obj = Json.parseToJsonElement(line).jsonObject
            assertEquals("INFO", obj["level"]?.jsonPrimitive?.content)
        }
    }
}
