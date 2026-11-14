package dev.aidos.daemon

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import dev.aidos.identity.UuidV7Generator
import kotlinx.datetime.Clock
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Aggregates metrics to metric_samples table (RFC-0037).
 *
 * Collects and persists:
 * - Run outcomes by state/error class
 * - Step duration distribution
 * - Model load time
 * - Storage per project
 * - Tokens/cost by model/session
 * - Local vs remote call ratio
 *
 * Wired into RuntimeCompositionRoot initialization and SqliteExecutor events.
 * Runs asynchronously to avoid blocking the execution path.
 */
class MetricsCollector(
    private val driver: SqlDriver,
) {

    private val idGenerator = UuidV7Generator()

    /**
     * Collects metrics across all registered projects in the opened project database.
     * Skips cleanly when no project rows exist (respecting metric_samples.project_id FK).
     */
    fun collect() {
        try {
            val projectIds = loadProjectIds()
            for (projectId in projectIds) {
                collect(projectId)
            }
        } catch (e: Exception) {
            System.err.println("Failed to collect metrics: ${e.message}")
        }
    }

    /**
     * Collects all measured metrics for [projectId] and writes them to `metric_samples`.
     * Verifies [projectId] exists in `projects` before emitting samples to prevent FK violations.
     */
    fun collect(projectId: String) {
        try {
            if (!projectExists(projectId)) return
            val bucket = Clock.System.now().toString().substringBefore("T") + "T00:00:00Z"

            collectRunOutcomes(bucket, projectId)
            collectStepDuration(bucket, projectId)
            collectModelLoadTime(bucket, projectId)
            collectStoragePerProject(bucket, projectId)
            collectTokensAndCost(bucket, projectId)
            collectCallRatio(bucket, projectId)
        } catch (e: Exception) {
            System.err.println("Failed to collect metrics for project $projectId: ${e.message}")
        }
    }

    private fun loadProjectIds(): List<String> {
        val ids = mutableListOf<String>()
        driver.executeQuery(
            identifier = null,
            sql = "SELECT id FROM projects",
            mapper = { cursor ->
                while (cursor.next().value) {
                    cursor.getString(0)?.let(ids::add)
                }
                QueryResult.Value(Unit)
            },
            parameters = 0,
            binders = null,
        )
        return ids
    }

    private fun projectExists(projectId: String): Boolean =
        driver.executeQuery(
            identifier = null,
            sql = "SELECT 1 FROM projects WHERE id = ?",
            mapper = { cursor -> QueryResult.Value(cursor.next().value) },
            parameters = 1,
        ) {
            bindString(0, projectId)
        }.value

    private fun collectRunOutcomes(bucket: String, projectId: String) {
        try {
            val results = mutableListOf<RunOutcomeSample>()
            driver.executeQuery(
                identifier = null,
                sql = """
                    SELECT 
                        runs.state,
                        COALESCE(runs.error_code, 'none') as error_code,
                        COUNT(*) as count
                    FROM runs
                    WHERE runs.project_id = ?
                    GROUP BY runs.state, runs.error_code
                """,
                mapper = { cursor ->
                    while (cursor.next().value) {
                        val state = cursor.getString(0) ?: "unknown"
                        val errorCode = cursor.getString(1) ?: "none"
                        val count = cursor.getLong(2) ?: 0L
                        if (count > 0L) {
                            results.add(RunOutcomeSample(state, errorCode, count))
                        }
                    }
                    QueryResult.Value(Unit)
                },
                parameters = 1,
            ) {
                bindString(0, projectId)
            }
            for (row in results) {
                writeSample(
                    name = "run.outcomes",
                    value = row.count.toDouble(),
                    unit = "count",
                    projectId = projectId,
                    labels = mapOf("state" to row.state, "error_code" to row.errorCode),
                    bucket = bucket,
                )
            }
        } catch (e: Exception) {
            System.err.println("Failed to collect run outcomes: ${e.message}")
        }
    }

    private fun collectStepDuration(bucket: String, projectId: String) {
        try {
            var sample: StepDurationSample? = null
            driver.executeQuery(
                identifier = null,
                sql = """
                    SELECT 
                        AVG(CAST((julianday(tasks.ended_at) - julianday(tasks.started_at)) * 86400000 AS REAL)) as avg_duration,
                        MIN(CAST((julianday(tasks.ended_at) - julianday(tasks.started_at)) * 86400000 AS REAL)) as min_duration,
                        MAX(CAST((julianday(tasks.ended_at) - julianday(tasks.started_at)) * 86400000 AS REAL)) as max_duration,
                        COUNT(*) as count
                    FROM tasks
                    WHERE tasks.project_id = ?
                      AND tasks.started_at IS NOT NULL
                      AND tasks.ended_at IS NOT NULL
                """,
                mapper = { cursor ->
                    if (cursor.next().value) {
                        val avg = cursor.getDouble(0)
                        val min = cursor.getDouble(1)
                        val max = cursor.getDouble(2)
                        val count = cursor.getLong(3) ?: 0L
                        if (count > 0L && avg != null && min != null && max != null) {
                            sample = StepDurationSample(
                                avgMs = maxOf(0.0, avg),
                                minMs = maxOf(0.0, min),
                                maxMs = maxOf(0.0, max),
                            )
                        }
                    }
                    QueryResult.Value(Unit)
                },
                parameters = 1,
            ) {
                bindString(0, projectId)
            }
            sample?.let { s ->
                writeSample("step.duration.avg", s.avgMs, "ms", projectId, emptyMap(), bucket)
                writeSample("step.duration.min", s.minMs, "ms", projectId, emptyMap(), bucket)
                writeSample("step.duration.max", s.maxMs, "ms", projectId, emptyMap(), bucket)
            }
        } catch (e: Exception) {
            System.err.println("Failed to collect step duration: ${e.message}")
        }
    }

    private fun collectModelLoadTime(bucket: String, projectId: String) {
        try {
            val results = mutableListOf<ModelLoadSample>()
            driver.executeQuery(
                identifier = null,
                sql = """
                    SELECT 
                        AVG(CAST(json_extract(detail_json, '$.duration_ms') AS REAL)) as duration,
                        COALESCE(json_extract(detail_json, '$.model_id'), 'unknown') as model_id,
                        COUNT(*) as count
                    FROM audit_log
                    WHERE audit_log.project_id = ?
                      AND audit_log.kind = 'ModelLoaded'
                      AND json_extract(detail_json, '$.duration_ms') IS NOT NULL
                    GROUP BY json_extract(detail_json, '$.model_id')
                """,
                mapper = { cursor ->
                    while (cursor.next().value) {
                        val duration = cursor.getDouble(0)
                        val modelId = cursor.getString(1) ?: "unknown"
                        val count = cursor.getLong(2) ?: 0L
                        if (count > 0L && duration != null) {
                            results.add(ModelLoadSample(durationMs = duration, modelId = modelId))
                        }
                    }
                    QueryResult.Value(Unit)
                },
                parameters = 1,
            ) {
                bindString(0, projectId)
            }
            for (row in results) {
                writeSample(
                    name = "model.load_time",
                    value = row.durationMs,
                    unit = "ms",
                    projectId = projectId,
                    labels = mapOf("model_id" to row.modelId),
                    bucket = bucket,
                )
            }
        } catch (e: Exception) {
            System.err.println("Failed to collect model load time: ${e.message}")
        }
    }

    private fun collectStoragePerProject(bucket: String, projectId: String) {
        try {
            var projectBytes: Long? = null
            driver.executeQuery(
                identifier = null,
                sql = """
                    SELECT SUM(content_nodes.size_bytes), COUNT(*)
                    FROM content_nodes
                    WHERE content_nodes.project_id = ?
                      AND content_nodes.state != 'DELETED'
                """,
                mapper = { cursor ->
                    if (cursor.next().value) {
                        val sum = cursor.getLong(0)
                        val count = cursor.getLong(1) ?: 0L
                        if (count > 0L && sum != null) {
                            projectBytes = sum
                        }
                    }
                    QueryResult.Value(Unit)
                },
                parameters = 1,
            ) {
                bindString(0, projectId)
            }

            if (projectBytes == null) {
                driver.executeQuery(
                    identifier = null,
                    sql = "SELECT SUM(blob_refs.size_bytes), COUNT(*) FROM blob_refs",
                    mapper = { cursor ->
                        if (cursor.next().value) {
                            val sum = cursor.getLong(0)
                            val count = cursor.getLong(1) ?: 0L
                            if (count > 0L && sum != null) {
                                projectBytes = sum
                            }
                        }
                        QueryResult.Value(Unit)
                    },
                    parameters = 0,
                    binders = null,
                )
            }

            projectBytes?.let { size ->
                writeSample("storage.total", size.toDouble(), "bytes", projectId, emptyMap(), bucket)
            }
        } catch (e: Exception) {
            System.err.println("Failed to collect storage: ${e.message}")
        }
    }

    private fun collectTokensAndCost(bucket: String, projectId: String) {
        try {
            val results = mutableListOf<TokenCostSample>()
            driver.executeQuery(
                identifier = null,
                sql = """
                    SELECT 
                        attempts.model_provider,
                        SUM(COALESCE(attempts.tokens_input, 0) + COALESCE(attempts.tokens_output, 0)) as total_tokens,
                        SUM(COALESCE(attempts.cost_units, 0)) as total_cost,
                        COUNT(attempts.tokens_input) + COUNT(attempts.tokens_output) as token_samples,
                        COUNT(attempts.cost_units) as cost_samples
                    FROM attempts
                    INNER JOIN tasks ON attempts.task_id = tasks.id
                    WHERE tasks.project_id = ?
                      AND attempts.model_provider IS NOT NULL
                    GROUP BY attempts.model_provider
                """,
                mapper = { cursor ->
                    while (cursor.next().value) {
                        val model = cursor.getString(0) ?: continue
                        val tokens = cursor.getLong(1) ?: 0L
                        val cost = cursor.getLong(2) ?: 0L
                        val tokenSamples = cursor.getLong(3) ?: 0L
                        val costSamples = cursor.getLong(4) ?: 0L
                        results.add(
                            TokenCostSample(
                                model = model,
                                tokens = tokens,
                                cost = cost,
                                hasTokenSamples = tokenSamples > 0L,
                                hasCostSamples = costSamples > 0L,
                            ),
                        )
                    }
                    QueryResult.Value(Unit)
                },
                parameters = 1,
            ) {
                bindString(0, projectId)
            }
            for (row in results) {
                if (row.hasTokenSamples) {
                    writeSample(
                        name = "tokens.used",
                        value = row.tokens.toDouble(),
                        unit = "tokens",
                        projectId = projectId,
                        labels = mapOf("model" to row.model),
                        bucket = bucket,
                    )
                }
                if (row.hasCostSamples) {
                    writeSample(
                        name = "cost.units",
                        value = row.cost.toDouble(),
                        unit = "cost_units",
                        projectId = projectId,
                        labels = mapOf("model" to row.model),
                        bucket = bucket,
                    )
                }
            }
        } catch (e: Exception) {
            System.err.println("Failed to collect tokens and cost: ${e.message}")
        }
    }

    private fun collectCallRatio(bucket: String, projectId: String) {
        try {
            var totalToolCalls = 0L
            driver.executeQuery(
                identifier = null,
                sql = """
                    SELECT COUNT(*)
                    FROM tool_calls
                    INNER JOIN runs ON tool_calls.run_id = runs.id
                    WHERE runs.project_id = ?
                """,
                mapper = { cursor ->
                    if (cursor.next().value) {
                        totalToolCalls = cursor.getLong(0) ?: 0L
                    }
                    QueryResult.Value(Unit)
                },
                parameters = 1,
            ) {
                bindString(0, projectId)
            }
            if (totalToolCalls > 0L) {
                writeSample("calls.total", totalToolCalls.toDouble(), "count", projectId, emptyMap(), bucket)
            }

            var totalModelCalls = 0L
            var localModelCalls = 0L
            driver.executeQuery(
                identifier = null,
                sql = """
                    SELECT
                        COUNT(*),
                        SUM(
                            CASE
                                WHEN LOWER(attempts.model_provider) IN ('local', 'llama', 'llama.cpp', 'llamacpp', 'ollama', 'embedded', 'on_device', 'on-device')
                                THEN 1 ELSE 0
                            END
                        )
                    FROM attempts
                    INNER JOIN tasks ON attempts.task_id = tasks.id
                    WHERE tasks.project_id = ?
                      AND attempts.model_provider IS NOT NULL
                """,
                mapper = { cursor ->
                    if (cursor.next().value) {
                        totalModelCalls = cursor.getLong(0) ?: 0L
                        localModelCalls = cursor.getLong(1) ?: 0L
                    }
                    QueryResult.Value(Unit)
                },
                parameters = 1,
            ) {
                bindString(0, projectId)
            }

            var totalEgressCalls = 0L
            var localEgressCalls = 0L
            driver.executeQuery(
                identifier = null,
                sql = """
                    SELECT
                        COUNT(*),
                        SUM(CASE WHEN resolved_address_class IN ('LOOPBACK', 'PRIVATE') THEN 1 ELSE 0 END)
                    FROM egress_records
                    WHERE project_id = ?
                """,
                mapper = { cursor ->
                    if (cursor.next().value) {
                        totalEgressCalls = cursor.getLong(0) ?: 0L
                        localEgressCalls = cursor.getLong(1) ?: 0L
                    }
                    QueryResult.Value(Unit)
                },
                parameters = 1,
            ) {
                bindString(0, projectId)
            }

            val totalMeasuredCalls = if (totalModelCalls > 0L) totalModelCalls else totalEgressCalls
            val localMeasuredCalls = if (totalModelCalls > 0L) localModelCalls else localEgressCalls
            if (totalMeasuredCalls > 0L) {
                val ratio = localMeasuredCalls.toDouble() / totalMeasuredCalls.toDouble()
                writeSample("calls.local_ratio", ratio, "ratio", projectId, emptyMap(), bucket)
            }
        } catch (e: Exception) {
            System.err.println("Failed to collect call ratio: ${e.message}")
        }
    }

    private fun writeSample(
        name: String,
        value: Double,
        unit: String,
        projectId: String?,
        labels: Map<String, String>,
        bucket: String,
    ) {
        try {
            if (projectId != null && !projectExists(projectId)) return

            val labelsJson = buildJsonObject {
                for ((k, v) in labels) {
                    put(k, v)
                }
            }.toString()

            driver.execute(
                identifier = null,
                sql = """
                    INSERT INTO metric_samples 
                    (id, project_id, name, value, unit, labels_json, bucket_start)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                parameters = 7,
            ) {
                bindString(0, idGenerator.next())
                bindString(1, projectId)
                bindString(2, name)
                bindDouble(3, value)
                bindString(4, unit)
                bindString(5, labelsJson)
                bindString(6, bucket)
            }
        } catch (e: Exception) {
            System.err.println("Failed to write sample $name: ${e.message}")
        }
    }

    private data class RunOutcomeSample(
        val state: String,
        val errorCode: String,
        val count: Long,
    )

    private data class StepDurationSample(
        val avgMs: Double,
        val minMs: Double,
        val maxMs: Double,
    )

    private data class ModelLoadSample(
        val durationMs: Double,
        val modelId: String,
    )

    private data class TokenCostSample(
        val model: String,
        val tokens: Long,
        val cost: Long,
        val hasTokenSamples: Boolean,
        val hasCostSamples: Boolean,
    )
}
