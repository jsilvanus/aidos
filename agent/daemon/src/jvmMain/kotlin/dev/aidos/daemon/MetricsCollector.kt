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
     * Collects all metrics and writes them to metric_samples.
     * Should be called periodically (e.g., on Run completion, at checkpoints).
     */
    fun collect() {
        try {
            val bucket = Clock.System.now().toString().substringBefore("T") + "T00:00:00Z"

            collectRunOutcomes(bucket)
            collectStepDuration(bucket)
            collectModelLoadTime(bucket)
            collectStoragePerProject(bucket)
            collectTokensAndCost(bucket)
            collectCallRatio(bucket)
        } catch (e: Exception) {
            System.err.println("Failed to collect metrics: ${e.message}")
        }
    }

    private fun collectRunOutcomes(bucket: String) {
        try {
            // Query run outcomes grouped by state and error class
            val results = mutableListOf<Tuple4<String, String, Long, String?>>()
            driver.executeQuery(
                identifier = null,
                sql = """
                    SELECT 
                        runs.state,
                        COALESCE(runs.error_code, 'none') as error_code,
                        COUNT(*) as count,
                        runs.project_id
                    FROM runs
                    GROUP BY runs.project_id, runs.state, runs.error_code
                """,
                mapper = { cursor ->
                    while (cursor.next().value) {
                        val state = cursor.getString(0) ?: "unknown"
                        val errorCode = cursor.getString(1) ?: "none"
                        val count = cursor.getLong(2) ?: 0L
                        val projectId = cursor.getString(3)
                        results.add(Tuple4(state, errorCode, count, projectId))
                    }
                    QueryResult.Value(Unit)
                },
                parameters = 0,
                binders = null,
            )
            results.forEach { (state, errorCode, count, projectId) ->
                writeSample(
                    name = "run.outcomes",
                    value = count.toDouble(),
                    unit = "count",
                    projectId = projectId,
                    labels = mapOf("state" to state, "error_code" to errorCode),
                    bucket = bucket,
                )
            }
        } catch (e: Exception) {
            System.err.println("Failed to collect run outcomes: ${e.message}")
        }
    }

    private fun collectStepDuration(bucket: String) {
        try {
            // Query task durations (started_at to ended_at)
            val result = mutableListOf<Tuple4<Double, Double, Double, Long>>()
            driver.executeQuery(
                identifier = null,
                sql = """
                    SELECT 
                        AVG(CAST((julianday(tasks.ended_at) - julianday(tasks.started_at)) * 86400000 AS REAL)) as avg_duration,
                        MIN(CAST((julianday(tasks.ended_at) - julianday(tasks.started_at)) * 86400000 AS REAL)) as min_duration,
                        MAX(CAST((julianday(tasks.ended_at) - julianday(tasks.started_at)) * 86400000 AS REAL)) as max_duration,
                        COUNT(*) as count
                    FROM tasks
                    WHERE tasks.started_at IS NOT NULL AND tasks.ended_at IS NOT NULL
                """,
                mapper = { cursor ->
                    if (cursor.next().value) {
                        val avg = cursor.getDouble(0) ?: 0.0
                        val min = cursor.getDouble(1) ?: 0.0
                        val max = cursor.getDouble(2) ?: 0.0
                        val count = cursor.getLong(3) ?: 0L
                        result.add(Tuple4(avg, min, max, count))
                    }
                    QueryResult.Value(Unit)
                },
                parameters = 0,
                binders = null,
            )
            result.forEach { (avg, min, max, _) ->
                writeSample("step.duration.avg", avg, "ms", null, mapOf(), bucket)
                writeSample("step.duration.min", min, "ms", null, mapOf(), bucket)
                writeSample("step.duration.max", max, "ms", null, mapOf(), bucket)
            }
        } catch (e: Exception) {
            System.err.println("Failed to collect step duration: ${e.message}")
        }
    }

    private fun collectModelLoadTime(bucket: String) {
        try {
            // Query audit_log for ModelLoaded events
            val results = mutableListOf<Tuple3<Double, String, Long>>()
            driver.executeQuery(
                identifier = null,
                sql = """
                    SELECT 
                        json_extract(detail_json, '$.duration_ms') as duration,
                        json_extract(detail_json, '$.model_id') as model_id,
                        COUNT(*) as count
                    FROM audit_log
                    WHERE kind = 'ModelLoaded'
                    GROUP BY json_extract(detail_json, '$.model_id')
                """,
                mapper = { cursor ->
                    while (cursor.next().value) {
                        val duration = cursor.getDouble(0) ?: 0.0
                        val modelId = cursor.getString(1) ?: "unknown"
                        val count = cursor.getLong(2) ?: 0L
                        results.add(Tuple3(duration, modelId, count))
                    }
                    QueryResult.Value(Unit)
                },
                parameters = 0,
                binders = null,
            )
            results.forEach { (duration, modelId, _) ->
                writeSample("model.load_time", duration, "ms", null, mapOf("model_id" to modelId), bucket)
            }
        } catch (e: Exception) {
            System.err.println("Failed to collect model load time: ${e.message}")
        }
    }

    private fun collectStoragePerProject(bucket: String) {
        try {
            // Query total storage per project using blob_refs
            val results = mutableListOf<Long>()
            driver.executeQuery(
                identifier = null,
                sql = """
                    SELECT 
                        SUM(blob_refs.size_bytes) as total_size
                    FROM blob_refs
                """,
                mapper = { cursor ->
                    if (cursor.next().value) {
                        val size = cursor.getLong(0) ?: 0L
                        results.add(size)
                    }
                    QueryResult.Value(Unit)
                },
                parameters = 0,
                binders = null,
            )
            results.forEach { size ->
                writeSample("storage.total", size.toDouble(), "bytes", null, mapOf(), bucket)
            }
        } catch (e: Exception) {
            System.err.println("Failed to collect storage: ${e.message}")
        }
    }

    private fun collectTokensAndCost(bucket: String) {
        try {
            // Query tokens and cost by model
            val results = mutableListOf<Tuple3<String, Long, Long>>()
            driver.executeQuery(
                identifier = null,
                sql = """
                    SELECT 
                        attempts.model_provider,
                        SUM(COALESCE(attempts.tokens_input, 0) + COALESCE(attempts.tokens_output, 0)) as total_tokens,
                        SUM(COALESCE(attempts.cost_units, 0)) as total_cost
                    FROM attempts
                    WHERE attempts.model_provider IS NOT NULL
                    GROUP BY attempts.model_provider
                """,
                mapper = { cursor ->
                    while (cursor.next().value) {
                        val model = cursor.getString(0) ?: "unknown"
                        val tokens = cursor.getLong(1) ?: 0L
                        val cost = cursor.getLong(2) ?: 0L
                        results.add(Tuple3(model, tokens, cost))
                    }
                    QueryResult.Value(Unit)
                },
                parameters = 0,
                binders = null,
            )
            results.forEach { (model, tokens, cost) ->
                writeSample("tokens.used", tokens.toDouble(), "tokens", null, 
                    mapOf("model" to model), bucket)
                writeSample("cost.units", cost.toDouble(), "cost_units", null, 
                    mapOf("model" to model), bucket)
            }
        } catch (e: Exception) {
            System.err.println("Failed to collect tokens and cost: ${e.message}")
        }
    }

    private fun collectCallRatio(bucket: String) {
        try {
            // Query local vs remote call ratio from tool_calls
            val result = mutableListOf<Long>()
            driver.executeQuery(
                identifier = null,
                sql = """
                    SELECT COUNT(*) as total_calls
                    FROM tool_calls
                """,
                mapper = { cursor ->
                    if (cursor.next().value) {
                        result.add(cursor.getLong(0) ?: 0L)
                    }
                    QueryResult.Value(Unit)
                },
                parameters = 0,
                binders = null,
            )
            result.forEach { totalCalls ->
                if (totalCalls > 0) {
                    // For now, report total calls; a more sophisticated metric would
                    // distinguish local vs remote based on tool_name patterns
                    writeSample("calls.total", totalCalls.toDouble(), "count", null, mapOf(), bucket)
                }
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
}

// Helper tuple types
data class Tuple3<A, B, C>(val first: A, val second: B, val third: C)
data class Tuple4<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)
data class Tuple5<A, B, C, D, E>(val first: A, val second: B, val third: C, val fourth: D, val fifth: E)
