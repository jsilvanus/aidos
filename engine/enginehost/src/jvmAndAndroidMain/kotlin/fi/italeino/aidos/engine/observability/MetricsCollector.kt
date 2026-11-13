package fi.italeino.aidos.engine.observability

import app.cash.sqldelight.db.SqlDriver
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Collects and writes observability metrics to the database (RFC-0037).
 *
 * Metrics are aggregated in SQLite, sampled rather than streamed. On mobile the observation
 * cost must stay well below the cost of the thing observed.
 *
 * Recorded metrics:
 * - Run outcomes by state and error class
 * - Step duration distribution
 * - Resource usage: storage per project, model load time, inference latency
 * - Cost: tokens and cost units by model
 * - Local versus remote call ratio
 */
class MetricsCollector(
    private val driver: SqlDriver,
    private val projectId: String? = null,
) {

    /**
     * Records a single metric sample.
     *
     * @param name Metric name (e.g., "model.inference.latency_ms", "run.outcome.success")
     * @param value The metric value
     * @param unit Unit of measurement (e.g., "ms", "bytes", "tokens")
     * @param labels JSON object with optional labels for filtering (e.g., {"model_id": "llama-2", "provider": "local"})
     */
    fun recordMetric(
        name: String,
        value: Double,
        unit: String = "",
        labels: String = "{}",
    ) {
        val id = UUID.randomUUID().toString()
        val now = Instant.now()
        // Bucket start: round down to 1-minute granularity for aggregation
        val bucketStart = now.truncatedTo(ChronoUnit.MINUTES).toString()

        insertMetricSample(
            id = id,
            projectId = projectId,
            name = name,
            value = value,
            unit = unit,
            labelsJson = labels,
            bucketStart = bucketStart,
        )
    }

    /**
     * Record run completion metrics (RFC-0037).
     * 
     * @param runId The run ID
     * @param state Final state (COMPLETED, FAILED, CANCELLED, etc.)
     * @param durationMs Total duration in milliseconds
     * @param errorCode Error code if failed (from RFC-0029)
     * @param attemptCount Number of attempts made
     */
    fun recordRunCompletion(
        runId: String,
        state: String,
        durationMs: Long,
        errorCode: String? = null,
        attemptCount: Int = 1,
    ) {
        val labels = buildLabels {
            put("run_id", runId)
            put("state", state)
            if (errorCode != null) put("error_code", errorCode)
        }
        recordMetric("run.completion", 1.0, "count", labels)
        recordMetric("run.duration", durationMs.toDouble(), "ms", labels)
        recordMetric("run.attempts", attemptCount.toDouble(), "count", labels)
    }

    /**
     * Record model inference metrics (RFC-0037).
     *
     * @param modelId Model identifier
     * @param inferenceLatencyMs Time from request to first token
     * @param totalLatencyMs Total time including streaming
     * @param tokensGenerated Number of tokens produced
     * @param peakMemoryMb Peak memory used
     * @param isLocalModel Whether inference ran locally vs remotely
     */
    fun recordInference(
        modelId: String,
        inferenceLatencyMs: Long,
        totalLatencyMs: Long,
        tokensGenerated: Int,
        peakMemoryMb: Int,
        isLocalModel: Boolean,
    ) {
        val labels = buildLabels {
            put("model_id", modelId)
            put("locality", if (isLocalModel) "local" else "remote")
        }
        recordMetric("model.inference.latency_ms", inferenceLatencyMs.toDouble(), "ms", labels)
        recordMetric("model.inference.total_ms", totalLatencyMs.toDouble(), "ms", labels)
        recordMetric("model.tokens.generated", tokensGenerated.toDouble(), "tokens", labels)
        recordMetric("model.memory.peak_mb", peakMemoryMb.toDouble(), "mb", labels)
    }

    /**
     * Record model load metrics (RFC-0037).
     *
     * @param modelId Model identifier
     * @param loadTimeMs Time to load and initialize the model
     * @param sizeBytes Model size in bytes
     */
    fun recordModelLoad(
        modelId: String,
        loadTimeMs: Long,
        sizeBytes: Long,
    ) {
        val labels = buildLabels { put("model_id", modelId) }
        recordMetric("model.load.time_ms", loadTimeMs.toDouble(), "ms", labels)
        recordMetric("model.size.bytes", sizeBytes.toDouble(), "bytes", labels)
    }

    /**
     * Record storage metrics (RFC-0037).
     *
     * @param retentionClass Retention class (PERMANENT, AGED, TEMPORARY)
     * @param sizeBytes Total storage in bytes for this retention class
     */
    fun recordStorageUsage(
        retentionClass: String,
        sizeBytes: Long,
    ) {
        val labels = buildLabels { put("retention_class", retentionClass) }
        recordMetric("storage.project.bytes", sizeBytes.toDouble(), "bytes", labels)
    }

    /**
     * Record capability denial (RFC-0037, Security requirement 5).
     *
     * @param capabilityId The capability ID that was denied
     * @param operation The operation attempted (e.g., "write", "execute")
     * @param reason Why it was denied (e.g., "capability_not_granted")
     */
    fun recordCapabilityDenial(
        capabilityId: String,
        operation: String,
        reason: String,
    ) {
        val labels = buildLabels {
            put("capability_id", capabilityId)
            put("operation", operation)
            put("reason", reason)
        }
        recordMetric("capability.denied", 1.0, "count", labels)
    }

    /**
     * Record tool invocation outcome (RFC-0037).
     *
     * @param toolId Tool identifier
     * @param durationMs Execution time
     * @param success Whether the tool succeeded
     * @param errorCode Error code if failed (RFC-0029)
     */
    fun recordToolInvocation(
        toolId: String,
        durationMs: Long,
        success: Boolean,
        errorCode: String? = null,
    ) {
        val labels = buildLabels {
            put("tool_id", toolId)
            put("success", if (success) "true" else "false")
            if (errorCode != null) put("error_code", errorCode)
        }
        recordMetric("tool.invocation", 1.0, "count", labels)
        recordMetric("tool.duration_ms", durationMs.toDouble(), "ms", labels)
    }

    private fun insertMetricSample(
        id: String,
        projectId: String?,
        name: String,
        value: Double,
        unit: String,
        labelsJson: String,
        bucketStart: String,
    ) {
        // Raw SQL since the schema is defined in schema/project.sql.
        // This matches the metric_samples table schema from RFC-0037.
        val projectIdParam = projectId ?: "NULL"
        driver.execute(
            null,
            """
            INSERT INTO metric_samples (
                id, project_id, name, value, unit, labels_json, bucket_start
            ) VALUES (?, ?, ?, ?, ?, ?, ?)
            """,
            5,
            { 
                bindString(1, id)
                if (projectId != null) bindString(2, projectId) else bindNull(2)
                bindString(3, name)
                bindDouble(4, value)
                bindString(5, unit)
                bindString(6, labelsJson)
                bindString(7, bucketStart)
            }
        )
    }

    private fun buildLabels(block: MutableMap<String, String>.() -> Unit): String {
        val labels = mutableMapOf<String, String>()
        labels.block()
        return labels.entries.joinToString(",", "{", "}") { (k, v) -> "\"$k\":\"$v\"" }
    }
}
