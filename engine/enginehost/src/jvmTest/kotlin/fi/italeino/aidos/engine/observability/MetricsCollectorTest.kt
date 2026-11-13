package fi.italeino.aidos.engine.observability

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertNotNull

/**
 * Unit tests for MetricsCollector (RFC-0037).
 *
 * Verifies that metrics are properly recorded to the database with correct schema.
 */
class MetricsCollectorTest {

    @Test
    fun testMetricSampleWriting() {
        val tempDir = Files.createTempDirectory("metrics_test").toFile()
        try {
            val dbDriver = JdbcSqliteDriver(
                url = "jdbc:sqlite:${File(tempDir, "metrics.db").absolutePath}",
            )

            // Initialize metric_samples table
            dbDriver.execute(
                null,
                """
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
                0,
                {}
            )

            val collector = MetricsCollector(dbDriver, projectId = "test-project")

            collector.recordMetric(
                name = "test.metric",
                value = 42.5,
                unit = "ms",
                labels = """{"test": "value"}"""
            )

            // Verify record was written
            var recordCount = 0
            dbDriver.execute(
                null,
                "SELECT COUNT(*) FROM metric_samples WHERE name = 'test.metric'",
                0,
                { recordCount = getInt(0) }
            )
            assertTrue(recordCount > 0, "No metric sample recorded")
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testRunCompletionMetrics() {
        val tempDir = Files.createTempDirectory("run_metrics_test").toFile()
        try {
            val dbDriver = JdbcSqliteDriver(
                url = "jdbc:sqlite:${File(tempDir, "metrics.db").absolutePath}",
            )

            // Initialize metric_samples table
            dbDriver.execute(
                null,
                """
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
                0,
                {}
            )

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
            dbDriver.execute(
                null,
                "SELECT COUNT(*) FROM metric_samples WHERE name = 'run.completion'",
                0,
                { completionCount = getInt(0) }
            )
            assertTrue(completionCount > 0, "Run completion metric not recorded")

            var durationCount = 0
            dbDriver.execute(
                null,
                "SELECT COUNT(*) FROM metric_samples WHERE name = 'run.duration'",
                0,
                { durationCount = getInt(0) }
            )
            assertTrue(durationCount > 0, "Run duration metric not recorded")
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testInferenceMetrics() {
        val tempDir = Files.createTempDirectory("inference_metrics_test").toFile()
        try {
            val dbDriver = JdbcSqliteDriver(
                url = "jdbc:sqlite:${File(tempDir, "metrics.db").absolutePath}",
            )

            // Initialize metric_samples table
            dbDriver.execute(
                null,
                """
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
                0,
                {}
            )

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
            dbDriver.execute(
                null,
                "SELECT COUNT(*) FROM metric_samples WHERE name LIKE 'model.inference%'",
                0,
                { inferenceCount = getInt(0) }
            )
            assertTrue(inferenceCount >= 4, "Not all inference metrics recorded (got $inferenceCount)")
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testModelLoadMetrics() {
        val tempDir = Files.createTempDirectory("model_load_test").toFile()
        try {
            val dbDriver = JdbcSqliteDriver(
                url = "jdbc:sqlite:${File(tempDir, "metrics.db").absolutePath}",
            )

            // Initialize metric_samples table
            dbDriver.execute(
                null,
                """
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
                0,
                {}
            )

            val collector = MetricsCollector(dbDriver, projectId = null) // User-scope metric

            collector.recordModelLoad(
                modelId = "llama-2",
                loadTimeMs = 2000,
                sizeBytes = 4 * 1024 * 1024 * 1024L // 4 GB
            )

            // Verify model load metrics were recorded
            var loadCount = 0
            dbDriver.execute(
                null,
                "SELECT COUNT(*) FROM metric_samples WHERE name LIKE 'model.load%'",
                0,
                { loadCount = getInt(0) }
            )
            assertTrue(loadCount >= 2, "Model load metrics not recorded")
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testCapabilityDenialMetrics() {
        val tempDir = Files.createTempDirectory("capability_test").toFile()
        try {
            val dbDriver = JdbcSqliteDriver(
                url = "jdbc:sqlite:${File(tempDir, "metrics.db").absolutePath}",
            )

            // Initialize metric_samples table
            dbDriver.execute(
                null,
                """
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
                0,
                {}
            )

            val collector = MetricsCollector(dbDriver, projectId = "test-project")

            collector.recordCapabilityDenial(
                capabilityId = "file.write",
                operation = "write",
                reason = "capability_not_granted"
            )

            // Verify denial was recorded
            var denialCount = 0
            dbDriver.execute(
                null,
                "SELECT COUNT(*) FROM metric_samples WHERE name = 'capability.denied'",
                0,
                { denialCount = getInt(0) }
            )
            assertTrue(denialCount > 0, "Capability denial metric not recorded")
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testToolInvocationMetrics() {
        val tempDir = Files.createTempDirectory("tool_invocation_test").toFile()
        try {
            val dbDriver = JdbcSqliteDriver(
                url = "jdbc:sqlite:${File(tempDir, "metrics.db").absolutePath}",
            )

            // Initialize metric_samples table
            dbDriver.execute(
                null,
                """
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
                0,
                {}
            )

            val collector = MetricsCollector(dbDriver, projectId = "test-project")

            collector.recordToolInvocation(
                toolId = "git.commit",
                durationMs = 500,
                success = true,
                errorCode = null
            )

            collector.recordToolInvocation(
                toolId = "file.read",
                durationMs = 100,
                success = false,
                errorCode = "permission_denied"
            )

            // Verify tool metrics were recorded
            var toolCount = 0
            dbDriver.execute(
                null,
                "SELECT COUNT(*) FROM metric_samples WHERE name LIKE 'tool.%'",
                0,
                { toolCount = getInt(0) }
            )
            assertTrue(toolCount >= 4, "Not all tool metrics recorded (got $toolCount)")
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
