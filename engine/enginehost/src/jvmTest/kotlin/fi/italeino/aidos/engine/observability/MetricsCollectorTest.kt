package fi.italeino.aidos.engine.observability

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.io.File
import java.nio.file.Files
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit tests for MetricsCollector (RFC-0037).
 *
 * Verifies that metrics are properly recorded to the database with correct schema.
 */
class MetricsCollectorTest {

    private fun createTestDb(dir: File): JdbcSqliteDriver {
        val driver = JdbcSqliteDriver(
            url = "jdbc:sqlite:${File(dir, "test.db").absolutePath}",
        )
        // Initialize metric_samples table
        driver.execute(
            identifier = null,
            sql = """
            CREATE TABLE IF NOT EXISTS metric_samples (
                id TEXT PRIMARY KEY,
                project_id TEXT,
                name TEXT NOT NULL,
                value REAL NOT NULL,
                unit TEXT NOT NULL,
                labels_json TEXT NOT NULL DEFAULT '{}',
                bucket_start TEXT NOT NULL
            )
            """,
            parameters = 0,
        ) {}
        return driver
    }

    @Test
    fun testMetricSampleWriting() {
        val tempDir = Files.createTempDirectory("metrics_test").toFile()
        try {
            val dbDriver = createTestDb(tempDir)
            val collector = MetricsCollector(dbDriver, projectId = "test-project")

            collector.recordMetric(
                name = "test.metric",
                value = 42.5,
                unit = "ms",
                labels = """{"test": "value"}"""
            )

            // Verify record was written
            var recordCount = 0
            dbDriver.executeQuery(
                identifier = null,
                sql = "SELECT COUNT(*) FROM metric_samples WHERE name = 'test.metric'",
                mapper = { c ->
                    if (c.next().value) recordCount = c.getLong(0)!!.toInt()
                    QueryResult.Value(Unit)
                },
                parameters = 0,
            ) {}
            assertTrue(recordCount > 0, "No metric sample recorded")
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testRunCompletionMetrics() {
        val tempDir = Files.createTempDirectory("run_metrics_test").toFile()
        try {
            val dbDriver = createTestDb(tempDir)
            val collector = MetricsCollector(dbDriver, projectId = "test-project")

            collector.recordRunCompletion(
                runId = "run-123",
                state = "COMPLETED",
                durationMs = 5000,
                errorCode = null,
                attemptCount = 1
            )

            // Verify run completion metrics were recorded
            var completionCount = 0
            dbDriver.executeQuery(
                identifier = null,
                sql = "SELECT COUNT(*) FROM metric_samples WHERE name = 'run.completion'",
                mapper = { c ->
                    if (c.next().value) completionCount = c.getLong(0)!!.toInt()
                    QueryResult.Value(Unit)
                },
                parameters = 0,
            ) {}
            assertTrue(completionCount > 0, "Run completion metric not recorded")
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testInferenceMetrics() {
        val tempDir = Files.createTempDirectory("inference_metrics_test").toFile()
        try {
            val dbDriver = createTestDb(tempDir)
            val collector = MetricsCollector(dbDriver, projectId = "test-project")

            collector.recordInference(
                modelId = "llama-2",
                inferenceLatencyMs = 100,
                totalLatencyMs = 5000,
                tokensGenerated = 256,
                peakMemoryMb = 2048,
                isLocalModel = true
            )

            // Verify all inference metrics were recorded
            var inferenceCount = 0
            dbDriver.executeQuery(
                identifier = null,
                sql = "SELECT COUNT(*) FROM metric_samples WHERE name LIKE 'model.inference%'",
                mapper = { c ->
                    if (c.next().value) inferenceCount = c.getLong(0)!!.toInt()
                    QueryResult.Value(Unit)
                },
                parameters = 0,
            ) {}
            assertTrue(inferenceCount >= 4, "Not all inference metrics recorded (got $inferenceCount)")
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testModelLoadMetrics() {
        val tempDir = Files.createTempDirectory("model_load_test").toFile()
        try {
            val dbDriver = createTestDb(tempDir)
            val collector = MetricsCollector(dbDriver, projectId = null) // User-scope metric

            collector.recordModelLoad(
                modelId = "llama-2",
                loadTimeMs = 2000,
                sizeBytes = 4 * 1024 * 1024 * 1024L // 4 GB
            )

            // Verify model load metrics were recorded
            var loadCount = 0
            dbDriver.executeQuery(
                identifier = null,
                sql = "SELECT COUNT(*) FROM metric_samples WHERE name LIKE 'model.load%'",
                mapper = { c ->
                    if (c.next().value) loadCount = c.getLong(0)!!.toInt()
                    QueryResult.Value(Unit)
                },
                parameters = 0,
            ) {}
            assertTrue(loadCount >= 2, "Model load metrics not recorded")
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testCapabilityDenialMetrics() {
        val tempDir = Files.createTempDirectory("capability_test").toFile()
        try {
            val dbDriver = createTestDb(tempDir)
            val collector = MetricsCollector(dbDriver, projectId = "test-project")

            collector.recordCapabilityDenial(
                capabilityId = "file.write",
                operation = "write",
                reason = "capability_not_granted"
            )

            // Verify denial was recorded
            var denialCount = 0
            dbDriver.executeQuery(
                identifier = null,
                sql = "SELECT COUNT(*) FROM metric_samples WHERE name = 'capability.denied'",
                mapper = { c ->
                    if (c.next().value) denialCount = c.getLong(0)!!.toInt()
                    QueryResult.Value(Unit)
                },
                parameters = 0,
            ) {}
            assertTrue(denialCount > 0, "Capability denial metric not recorded")
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testBuildLabelsEscapesQuotesBackslashesAndControlCharacters() {
        val tempDir = Files.createTempDirectory("metrics_escaping_test").toFile()
        val dbDriver = createTestDb(tempDir)
        try {
            val collector = MetricsCollector(dbDriver, projectId = "proj-escape")
            val dirtyOp = "line1\nline2\r\t\b\u000c\u0000\"quote\"\\slash"
            collector.recordCapabilityDenial(
                capabilityId = "cap\"with\\slash",
                operation = dirtyOp,
                reason = "reason\n\"bad\"",
            )

            var labelsJson = ""
            dbDriver.executeQuery(
                identifier = null,
                sql = "SELECT labels_json FROM metric_samples WHERE name = 'capability.denied' LIMIT 1",
                mapper = { c ->
                    if (c.next().value) labelsJson = c.getString(0)!!
                    QueryResult.Value(Unit)
                },
                parameters = 0,
            ) {}

            val obj = Json.parseToJsonElement(labelsJson).jsonObject
            assertEquals("cap\"with\\slash", obj["capability_id"]?.jsonPrimitive?.content)
            assertEquals(dirtyOp, obj["operation"]?.jsonPrimitive?.content)
            assertEquals("reason\n\"bad\"", obj["reason"]?.jsonPrimitive?.content)
        } finally {
            runCatching { dbDriver.close() }
            tempDir.deleteRecursively()
        }
    }
}
