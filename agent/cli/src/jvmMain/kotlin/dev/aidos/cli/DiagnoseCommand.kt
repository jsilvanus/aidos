package dev.aidos.cli

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import dev.aidos.api.RuntimeClient
import dev.aidos.identity.ProjectRegistry
import dev.aidos.identity.UuidV7Generator
import dev.aidos.storage.AidosStorage
import dev.aidos.storage.DesktopPaths
import dev.aidos.vault.RedactionBoundary
import dev.aidos.vault.Redactor
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.BasicFileAttributes
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Diagnostic command for Aidos (RFC-0037).
 *
 * Usage: `aidos diagnose --output bundle.zip [--include-trace] [--runs RUN_ID,...]`
 *
 * Produces a zip file with:
 * - manifest.json: runtime version, profile, OS, enabled features
 * - logs/: recent diagnostic logs (redacted, excluding TRACE by default)
 * - crashes/: crash records from the past 30 days (redacted)
 * - metrics.json: aggregated metric_samples and run/audit counts from project SQLite DBs
 * - execution/: Execution Graph skeletons for selected runs (or recent failures)
 * - schema.json: schema version and migrations applied from SQLite DBs
 * - redaction-report.txt: what was redacted and why
 *
 * User selects which Runs to include (default: recent failures in past 7 days).
 * Prompts and file contents excluded by default.
 */
class DiagnoseCommand(
    private val runtimeClient: RuntimeClient,
    private val homeDir: String = System.getProperty("user.home"),
    private val redactor: Redactor = Redactor(),
    private val projectDbPaths: List<String> = emptyList(),
    private val maxEntryBytes: Long = DEFAULT_MAX_ENTRY_BYTES,
    private val maxBundleBytes: Long = DEFAULT_MAX_BUNDLE_BYTES,
) {

    private class BundleWriteState(
        var totalBytesWritten: Long = 0L,
        var secretRedactions: Long = 0L,
        var piiRedactions: Long = 0L,
        var skippedOversizedEntries: Int = 0,
    )

    private data class OpenedDatabases(
        val userDb: SqlDriver?,
        val projectDbs: List<Pair<String, SqlDriver>>,
    ) : AutoCloseable {
        override fun close() {
            projectDbs.forEach { (_, driver) -> runCatching { driver.close() } }
            userDb?.let { driver -> runCatching { driver.close() } }
        }
    }

    suspend fun diagnose(
        outputPath: String,
        includeTrace: Boolean = false,
        selectedRuns: List<String> = emptyList(),
        includeSensitive: Boolean = false,
    ) {
        try {
            val outputFile = File(outputPath)
            outputFile.parentFile?.mkdirs()

            println("Creating diagnostic bundle...")

            val state = BundleWriteState()
            openExistingDatabases().use { dbs ->
                ZipOutputStream(outputFile.outputStream()).use { zip ->
                    // 1. Manifest
                    writeManifest(zip, dbs, state)

                    // 2. Logs (redacted)
                    writeLogs(zip, homeDir, includeTrace, includeSensitive, state)

                    // 3. Crashes (files + crash_records table, redacted)
                    writeCrashes(zip, homeDir, dbs.userDb, includeSensitive, state)

                    // 4. Metrics (real SQLite tables)
                    writeMetrics(zip, dbs, includeSensitive, state)

                    // 5. Execution skeletons (selected runs or recent failures)
                    writeExecutionSkeletons(zip, dbs, selectedRuns, includeSensitive, state)

                    // 6. Schema (real SQLite schema_versions + migration_history)
                    writeSchema(zip, dbs, state)

                    // 7. Redaction report
                    writeRedactionReport(zip, state)
                }
            }

            println("✓ Diagnostic bundle: $outputPath")
            println("  Size: ${outputFile.length() / 1024}K")
            println("  Review contents before sharing.")

        } catch (e: Exception) {
            System.err.println("Failed to create diagnostic bundle: ${e.message}")
            e.printStackTrace(System.err)
        }
    }

    private suspend fun openExistingDatabases(): OpenedDatabases {
        val nowIso = { Clock.System.now().toString() }
        val userDbFile = File(DesktopPaths.userDb(homeDir))
        val userDriver: SqlDriver? = if (userDbFile.isFile) {
            runCatching {
                AidosStorage.openUser(userDbFile.absolutePath, "cli", nowIso).driver
            }.getOrNull()
        } else {
            null
        }

        val candidatePaths = linkedSetOf<String>()
        for (explicit in projectDbPaths) {
            val file = File(explicit)
            if (file.isFile) {
                candidatePaths.add(file.canonicalPath)
            }
        }
        if (userDriver != null) {
            runCatching {
                ProjectRegistry(userDriver, UuidV7Generator()).listAll()
            }.getOrDefault(emptyList()).forEach { (_, rootPath) ->
                val stateFile = File(DesktopPaths.stateDb(rootPath))
                if (stateFile.isFile) {
                    candidatePaths.add(stateFile.canonicalPath)
                }
            }
        }
        runCatching { runtimeClient.projects.list() }
            .getOrDefault(emptyList())
            .forEach { summary ->
                val stateFile = File(DesktopPaths.stateDb(summary.projectPath))
                if (stateFile.isFile) {
                    candidatePaths.add(stateFile.canonicalPath)
                }
            }

        val openedProjects = mutableListOf<Pair<String, SqlDriver>>()
        for (path in candidatePaths) {
            val driver = runCatching {
                AidosStorage.openProject(path, "cli", nowIso).driver
            }.getOrNull()
            if (driver != null) {
                openedProjects.add(path to driver)
            }
        }
        return OpenedDatabases(userDriver, openedProjects)
    }

    private suspend fun writeManifest(
        zip: ZipOutputStream,
        dbs: OpenedDatabases,
        state: BundleWriteState,
    ) {
        try {
            val runtimeVersion = runCatching { runtimeClient.runtime.version() }.getOrNull()
            val timestamp = Clock.System.now().toString()

            val json = buildJsonObject {
                put("timestamp", timestamp)
                put("runtime_version", runtimeVersion?.version ?: "unknown")
                put("api_version", runtimeVersion?.apiVersion ?: 0)
                put("platform_profile", runtimeVersion?.profile ?: "unknown")
                put("os_name", System.getProperty("os.name"))
                put("os_version", System.getProperty("os.version"))
                put("user_db_present", dbs.userDb != null)
                put("project_dbs_inspected", dbs.projectDbs.size)
                putJsonObject("enabled_features") {
                    put("git", true)
                    put("mcp", true)
                    put("local_inference", false)
                    put("remote_inference", true)
                }
            }

            writeZipEntry(zip, "manifest.json", json.toString().toByteArray(Charsets.UTF_8), state)
        } catch (e: Exception) {
            System.err.println("Failed to write manifest: ${e.message}")
        }
    }

    private fun writeLogs(
        zip: ZipOutputStream,
        homeDir: String,
        includeTrace: Boolean,
        includeSensitive: Boolean,
        state: BundleWriteState,
    ) {
        try {
            val logsDir = Paths.get(homeDir, ".aidos", "logs")
            if (!Files.exists(logsDir)) return

            Files.list(logsDir).use { stream ->
                val paths = stream.filter { Files.isRegularFile(it) }.toList().sortedBy { it.fileName.toString() }
                for (logPath in paths) {
                    val fileName = logPath.fileName.toString()

                    // Skip TRACE logs unless explicitly included
                    if (!includeTrace && fileName.endsWith("-trace.log")) continue

                    // Skip diagnostic logs older than 30 days
                    if (!isRecentFile(logPath, 30)) continue

                    try {
                        val sizeBytes = Files.size(logPath)
                        if (sizeBytes > maxEntryBytes) {
                            state.skippedOversizedEntries++
                            System.err.println("Skipping oversized log $fileName ($sizeBytes bytes)")
                            continue
                        }
                        val raw = Files.readString(logPath, Charsets.UTF_8)
                        val redacted = redactDiagnosticText(raw, includeSensitive, state)
                        writeZipEntry(zip, "logs/$fileName", redacted.toByteArray(Charsets.UTF_8), state)
                    } catch (e: Exception) {
                        System.err.println("Failed to add log $fileName: ${e.message}")
                    }
                }
            }
        } catch (e: Exception) {
            System.err.println("Failed to write logs: ${e.message}")
        }
    }

    private fun writeCrashes(
        zip: ZipOutputStream,
        homeDir: String,
        userDb: SqlDriver?,
        includeSensitive: Boolean,
        state: BundleWriteState,
    ) {
        try {
            val crashesDir = Paths.get(homeDir, ".aidos", "crashes")
            if (Files.exists(crashesDir)) {
                Files.list(crashesDir).use { stream ->
                    val paths = stream.filter { Files.isRegularFile(it) }.toList().sortedBy { it.fileName.toString() }
                    for (crashPath in paths) {
                        // Keep crashes from past 30 days
                        if (!isRecentFile(crashPath, 30)) continue

                        val fileName = crashPath.fileName.toString()
                        try {
                            val sizeBytes = Files.size(crashPath)
                            if (sizeBytes > maxEntryBytes) {
                                state.skippedOversizedEntries++
                                continue
                            }
                            val raw = Files.readString(crashPath, Charsets.UTF_8)
                            val redacted = redactDiagnosticText(raw, includeSensitive, state)
                            writeZipEntry(zip, "crashes/$fileName", redacted.toByteArray(Charsets.UTF_8), state)
                        } catch (e: Exception) {
                            System.err.println("Failed to add crash file $fileName: ${e.message}")
                        }
                    }
                }
            }

            if (userDb != null) {
                val cutoffIso = Instant.fromEpochMilliseconds(
                    System.currentTimeMillis() - (30L * 86_400_000L),
                ).toString()
                val crashRows = mutableListOf<JsonObject>()
                userDb.executeQuery(
                    identifier = null,
                    sql = """
                        SELECT id, occurred_at, runtime_version, platform_profile, error_code, stack_hash, detail_path, in_flight_run_ids, reported
                        FROM crash_records
                        WHERE occurred_at >= ?
                        ORDER BY occurred_at DESC
                        LIMIT 200
                    """,
                    mapper = { cursor ->
                        while (cursor.next().value) {
                            val rawErrorCode = cursor.getString(4)
                            val rawDetailPath = cursor.getString(6) ?: ""
                            val rawInFlight = cursor.getString(7) ?: "[]"
                            crashRows.add(
                                buildJsonObject {
                                    put("id", cursor.getString(0) ?: "")
                                    put("occurred_at", cursor.getString(1) ?: "")
                                    put("runtime_version", cursor.getString(2) ?: "")
                                    put("platform_profile", cursor.getString(3) ?: "")
                                    if (rawErrorCode != null) {
                                        put("error_code", redactDiagnosticText(rawErrorCode, includeSensitive, state))
                                    } else {
                                        put("error_code", JsonNull)
                                    }
                                    put("stack_hash", cursor.getString(5) ?: "")
                                    put("detail_path", redactDiagnosticText(rawDetailPath, includeSensitive, state))
                                    val inFlightElement = runCatching { Json.parseToJsonElement(rawInFlight) }.getOrNull()
                                    if (inFlightElement != null) {
                                        put("in_flight_run_ids", inFlightElement)
                                    } else {
                                        put("in_flight_run_ids", rawInFlight)
                                    }
                                    put("reported", (cursor.getLong(8) ?: 0L) != 0L)
                                },
                            )
                        }
                        QueryResult.Value(Unit)
                    },
                    parameters = 1,
                ) {
                    bindString(0, cutoffIso)
                }

                val recordsJson = buildJsonObject {
                    put("records", buildJsonArray { crashRows.forEach(::add) })
                }
                writeZipEntry(
                    zip,
                    "crashes/crash_records.json",
                    recordsJson.toString().toByteArray(Charsets.UTF_8),
                    state,
                )
            }
        } catch (e: Exception) {
            System.err.println("Failed to write crashes: ${e.message}")
        }
    }

    private fun writeMetrics(
        zip: ZipOutputStream,
        dbs: OpenedDatabases,
        includeSensitive: Boolean,
        state: BundleWriteState,
    ) {
        try {
            val samples = mutableListOf<JsonObject>()
            val runOutcomes = mutableListOf<JsonObject>()
            val auditCounts = mutableListOf<JsonObject>()
            val redactionCounts = mutableListOf<JsonObject>()
            var totalProjects = 0L

            for ((_, driver) in dbs.projectDbs) {
                runCatching {
                    driver.executeQuery(
                        identifier = null,
                        sql = "SELECT COUNT(*) FROM projects",
                        mapper = { c ->
                            if (c.next().value) totalProjects += (c.getLong(0) ?: 0L)
                            QueryResult.Value(Unit)
                        },
                        parameters = 0,
                    )
                }

                runCatching {
                    driver.executeQuery(
                        identifier = null,
                        sql = """
                            SELECT id, project_id, name, value, unit, labels_json, bucket_start
                            FROM metric_samples
                            ORDER BY bucket_start DESC, name ASC
                            LIMIT 1000
                        """,
                        mapper = { c ->
                            while (c.next().value) {
                                val labelsRaw = c.getString(5) ?: "{}"
                                val labelsRedacted = redactDiagnosticText(labelsRaw, includeSensitive, state)
                                val labelsJson = runCatching { Json.parseToJsonElement(labelsRedacted) }.getOrNull()
                                samples.add(
                                    buildJsonObject {
                                        put("id", c.getString(0) ?: "")
                                        put("project_id", c.getString(1)?.let(::JsonPrimitive) ?: JsonNull)
                                        put("name", c.getString(2) ?: "")
                                        put("value", c.getDouble(3) ?: 0.0)
                                        put("unit", c.getString(4) ?: "")
                                        if (labelsJson != null) put("labels", labelsJson) else put("labels", labelsRedacted)
                                        put("bucket_start", c.getString(6) ?: "")
                                    },
                                )
                            }
                            QueryResult.Value(Unit)
                        },
                        parameters = 0,
                    )
                }

                runCatching {
                    driver.executeQuery(
                        identifier = null,
                        sql = """
                            SELECT project_id, state, COALESCE(error_code, 'none'), COUNT(*)
                            FROM runs
                            GROUP BY project_id, state, error_code
                        """,
                        mapper = { c ->
                            while (c.next().value) {
                                runOutcomes.add(
                                    buildJsonObject {
                                        put("project_id", c.getString(0) ?: "")
                                        put("state", c.getString(1) ?: "")
                                        put("error_code", c.getString(2) ?: "none")
                                        put("count", c.getLong(3) ?: 0L)
                                    },
                                )
                            }
                            QueryResult.Value(Unit)
                        },
                        parameters = 0,
                    )
                }

                runCatching {
                    driver.executeQuery(
                        identifier = null,
                        sql = "SELECT kind, COUNT(*) FROM audit_log GROUP BY kind",
                        mapper = { c ->
                            while (c.next().value) {
                                auditCounts.add(
                                    buildJsonObject {
                                        put("kind", c.getString(0) ?: "")
                                        put("count", c.getLong(1) ?: 0L)
                                    },
                                )
                            }
                            QueryResult.Value(Unit)
                        },
                        parameters = 0,
                    )
                }

                runCatching {
                    driver.executeQuery(
                        identifier = null,
                        sql = "SELECT boundary, detection_kind, COUNT(*) FROM redaction_events GROUP BY boundary, detection_kind",
                        mapper = { c ->
                            while (c.next().value) {
                                redactionCounts.add(
                                    buildJsonObject {
                                        put("boundary", c.getString(0) ?: "")
                                        put("detection_kind", c.getString(1) ?: "")
                                        put("count", c.getLong(2) ?: 0L)
                                    },
                                )
                            }
                            QueryResult.Value(Unit)
                        },
                        parameters = 0,
                    )
                }
            }

            val json = buildJsonObject {
                put("query_time", Clock.System.now().toString())
                put("note", "Aggregated metrics queried from local SQLite project databases")
                put("project_count", totalProjects)
                put("samples", buildJsonArray { samples.forEach(::add) })
                put("run_outcomes", buildJsonArray { runOutcomes.forEach(::add) })
                put("audit_log_summary", buildJsonArray { auditCounts.forEach(::add) })
                put("redaction_events_summary", buildJsonArray { redactionCounts.forEach(::add) })
            }

            writeZipEntry(zip, "metrics.json", json.toString().toByteArray(Charsets.UTF_8), state)
        } catch (e: Exception) {
            System.err.println("Failed to write metrics: ${e.message}")
        }
    }

    private fun writeExecutionSkeletons(
        zip: ZipOutputStream,
        dbs: OpenedDatabases,
        selectedRuns: List<String>,
        includeSensitive: Boolean,
        state: BundleWriteState,
    ) {
        try {
            val runIds = if (selectedRuns.isNotEmpty()) {
                selectedRuns
            } else {
                findRecentFailedRunIds(dbs)
            }
            if (runIds.isEmpty()) return

            for (runId in runIds) {
                try {
                    val skeleton = loadRunSkeleton(dbs, runId, includeSensitive, state)
                    val safeName = sanitizeZipSegment(runId)
                    writeZipEntry(
                        zip,
                        "execution/$safeName.json",
                        skeleton.toString().toByteArray(Charsets.UTF_8),
                        state,
                    )
                } catch (e: Exception) {
                    System.err.println("Failed to write skeleton for run $runId: ${e.message}")
                }
            }
        } catch (e: Exception) {
            System.err.println("Failed to write execution skeletons: ${e.message}")
        }
    }

    private fun findRecentFailedRunIds(dbs: OpenedDatabases): List<String> {
        val cutoffIso = Instant.fromEpochMilliseconds(
            System.currentTimeMillis() - (7L * 86_400_000L),
        ).toString()
        val found = linkedSetOf<String>()
        for ((_, driver) in dbs.projectDbs) {
            runCatching {
                driver.executeQuery(
                    identifier = null,
                    sql = """
                        SELECT id FROM runs
                        WHERE state IN ('FAILED', 'INTERRUPTED')
                          AND started_at >= ?
                        ORDER BY started_at DESC
                        LIMIT 25
                    """,
                    mapper = { c ->
                        while (c.next().value) {
                            c.getString(0)?.let(found::add)
                        }
                        QueryResult.Value(Unit)
                    },
                    parameters = 1,
                ) {
                    bindString(0, cutoffIso)
                }
            }
        }
        return found.toList()
    }

    private fun loadRunSkeleton(
        dbs: OpenedDatabases,
        runId: String,
        includeSensitive: Boolean,
        state: BundleWriteState,
    ): JsonObject {
        data class RunHeader(
            val id: String,
            val projectId: String,
            val sessionId: String,
            val startedAt: String?,
            val endedAt: String?,
            val runState: String,
            val errorCode: String?,
            val errorClass: String?,
            val errorDetailRaw: String?,
            val stepIndex: Long,
            val maxSteps: Long,
            val taintLevel: String,
            val platformProfile: String,
        )

        for ((_, driver) in dbs.projectDbs) {
            var header: RunHeader? = null
            driver.executeQuery(
                identifier = null,
                sql = """
                    SELECT id, project_id, session_id, started_at, ended_at, state, error_code, error_class,
                           error_detail_json, step_index, max_steps, taint_level, platform_profile
                    FROM runs
                    WHERE id = ?
                """,
                mapper = { c ->
                    if (c.next().value) {
                        header = RunHeader(
                            id = c.getString(0) ?: runId,
                            projectId = c.getString(1) ?: "",
                            sessionId = c.getString(2) ?: "",
                            startedAt = c.getString(3),
                            endedAt = c.getString(4),
                            runState = c.getString(5) ?: "UNKNOWN",
                            errorCode = c.getString(6),
                            errorClass = c.getString(7),
                            errorDetailRaw = c.getString(8),
                            stepIndex = c.getLong(9) ?: 0L,
                            maxSteps = c.getLong(10) ?: 0L,
                            taintLevel = c.getString(11) ?: "UNKNOWN",
                            platformProfile = c.getString(12) ?: "UNKNOWN",
                        )
                    }
                    QueryResult.Value(Unit)
                },
                parameters = 1,
            ) {
                bindString(0, runId)
            }

            header?.let { h ->
                val tasks = loadTaskSkeletons(driver, runId)
                val attempts = loadAttemptSkeletons(driver, runId)
                val toolCalls = loadToolCallSkeletons(driver, runId)

                return buildJsonObject {
                    put("run_id", h.id)
                    put("found", true)
                    put("project_id", h.projectId)
                    put("session_id", h.sessionId)
                    put("started_at", h.startedAt?.let(::JsonPrimitive) ?: JsonNull)
                    put("ended_at", h.endedAt?.let(::JsonPrimitive) ?: JsonNull)
                    put("state", h.runState)
                    put("error_code", h.errorCode?.let(::JsonPrimitive) ?: JsonNull)
                    put("error_class", h.errorClass?.let(::JsonPrimitive) ?: JsonNull)
                    if (h.errorDetailRaw != null) {
                        put("error_detail", redactDiagnosticText(h.errorDetailRaw, includeSensitive, state))
                    } else {
                        put("error_detail", JsonNull)
                    }
                    put("step_index", h.stepIndex)
                    put("max_steps", h.maxSteps)
                    put("taint_level", h.taintLevel)
                    put("platform_profile", h.platformProfile)
                    put("duration_ms", computeDurationMs(h.startedAt, h.endedAt))
                    put("tasks", buildJsonArray { tasks.forEach(::add) })
                    put("attempts", buildJsonArray { attempts.forEach(::add) })
                    put("tool_calls", buildJsonArray { toolCalls.forEach(::add) })
                    put("note", "Skeleton only — prompts, arguments, and file contents excluded")
                }
            }
        }

        return buildJsonObject {
            put("run_id", runId)
            put("found", false)
            put("tasks", buildJsonArray { })
            put("attempts", buildJsonArray { })
            put("tool_calls", buildJsonArray { })
            put("note", "Run ID not found in inspected local project databases")
        }
    }

    private fun loadTaskSkeletons(driver: SqlDriver, runId: String): List<JsonObject> {
        val list = mutableListOf<JsonObject>()
        driver.executeQuery(
            identifier = null,
            sql = """
                SELECT id, ordinal, kind, tool_name, model_capability, state, started_at, ended_at
                FROM tasks
                WHERE run_id = ?
                ORDER BY ordinal ASC
            """,
            mapper = { c ->
                while (c.next().value) {
                    list.add(
                        buildJsonObject {
                            put("id", c.getString(0) ?: "")
                            put("ordinal", c.getLong(1) ?: 0L)
                            put("kind", c.getString(2) ?: "")
                            put("tool_name", c.getString(3)?.let(::JsonPrimitive) ?: JsonNull)
                            put("model_capability", c.getString(4)?.let(::JsonPrimitive) ?: JsonNull)
                            put("state", c.getString(5) ?: "")
                            put("started_at", c.getString(6)?.let(::JsonPrimitive) ?: JsonNull)
                            put("ended_at", c.getString(7)?.let(::JsonPrimitive) ?: JsonNull)
                        },
                    )
                }
                QueryResult.Value(Unit)
            },
            parameters = 1,
        ) {
            bindString(0, runId)
        }
        return list
    }

    private fun loadAttemptSkeletons(driver: SqlDriver, runId: String): List<JsonObject> {
        val list = mutableListOf<JsonObject>()
        driver.executeQuery(
            identifier = null,
            sql = """
                SELECT a.id, a.task_id, a.attempt_number, a.started_at, a.ended_at, a.state,
                       a.error_code, a.error_class, a.model_provider, a.model_version,
                       a.tokens_input, a.tokens_output, a.cost_units
                FROM attempts a
                INNER JOIN tasks t ON a.task_id = t.id
                WHERE t.run_id = ?
                ORDER BY t.ordinal ASC, a.attempt_number ASC
            """,
            mapper = { c ->
                while (c.next().value) {
                    list.add(
                        buildJsonObject {
                            put("id", c.getString(0) ?: "")
                            put("task_id", c.getString(1) ?: "")
                            put("attempt_number", c.getLong(2) ?: 0L)
                            put("started_at", c.getString(3)?.let(::JsonPrimitive) ?: JsonNull)
                            put("ended_at", c.getString(4)?.let(::JsonPrimitive) ?: JsonNull)
                            put("state", c.getString(5) ?: "")
                            put("error_code", c.getString(6)?.let(::JsonPrimitive) ?: JsonNull)
                            put("error_class", c.getString(7)?.let(::JsonPrimitive) ?: JsonNull)
                            put("model_provider", c.getString(8)?.let(::JsonPrimitive) ?: JsonNull)
                            put("model_version", c.getString(9)?.let(::JsonPrimitive) ?: JsonNull)
                            put("tokens_input", c.getLong(10)?.let(::JsonPrimitive) ?: JsonNull)
                            put("tokens_output", c.getLong(11)?.let(::JsonPrimitive) ?: JsonNull)
                            put("cost_units", c.getLong(12)?.let(::JsonPrimitive) ?: JsonNull)
                        },
                    )
                }
                QueryResult.Value(Unit)
            },
            parameters = 1,
        ) {
            bindString(0, runId)
        }
        return list
    }

    private fun loadToolCallSkeletons(driver: SqlDriver, runId: String): List<JsonObject> {
        val list = mutableListOf<JsonObject>()
        driver.executeQuery(
            identifier = null,
            sql = """
                SELECT call_id, tool_name, schema_valid, outcome, step_index
                FROM tool_calls
                WHERE run_id = ?
                ORDER BY step_index ASC
            """,
            mapper = { c ->
                while (c.next().value) {
                    list.add(
                        buildJsonObject {
                            put("call_id", c.getString(0) ?: "")
                            put("tool_name", c.getString(1) ?: "")
                            put("schema_valid", (c.getLong(2) ?: 0L) != 0L)
                            put("outcome", c.getString(3) ?: "")
                            put("step_index", c.getLong(4) ?: 0L)
                        },
                    )
                }
                QueryResult.Value(Unit)
            },
            parameters = 1,
        ) {
            bindString(0, runId)
        }
        return list
    }

    private fun writeSchema(
        zip: ZipOutputStream,
        dbs: OpenedDatabases,
        state: BundleWriteState,
    ) {
        try {
            val userSchema = dbs.userDb?.let { readDbSchemaInfo("user", it) }
            val projectSchemas = dbs.projectDbs.mapIndexed { idx, (_, driver) ->
                readDbSchemaInfo("project_${idx + 1}", driver)
            }

            val allMigrations = mutableListOf<JsonObject>()
            userSchema?.second?.let(allMigrations::addAll)
            projectSchemas.forEach { allMigrations.addAll(it.second) }

            val primaryVersion = projectSchemas.firstOrNull()?.first
                ?: userSchema?.first
                ?: 0L

            val json = buildJsonObject {
                put("schema_version", primaryVersion)
                put("timestamp", Clock.System.now().toString())
                if (userSchema != null) {
                    putJsonObject("user_db") {
                        put("schema_version", userSchema.first)
                        put("migrations", buildJsonArray { userSchema.second.forEach(::add) })
                    }
                } else {
                    put("user_db", JsonNull)
                }
                put(
                    "project_dbs",
                    buildJsonArray {
                        for ((idx, pair) in projectSchemas.withIndex()) {
                            add(
                                buildJsonObject {
                                    put("index", idx)
                                    put("schema_version", pair.first)
                                    put("migrations", buildJsonArray { pair.second.forEach(::add) })
                                },
                            )
                        }
                    },
                )
                putJsonObject("migrations_applied") {
                    put("total", allMigrations.size)
                    val last = allMigrations.lastOrNull()
                    if (last != null) put("last_migration", last) else put("last_migration", JsonNull)
                }
            }

            writeZipEntry(zip, "schema.json", json.toString().toByteArray(Charsets.UTF_8), state)
        } catch (e: Exception) {
            System.err.println("Failed to write schema: ${e.message}")
        }
    }

    private fun readDbSchemaInfo(scope: String, driver: SqlDriver): Pair<Long, List<JsonObject>> {
        var version = 0L
        runCatching {
            driver.executeQuery(
                identifier = null,
                sql = "SELECT version FROM schema_versions WHERE id = 1",
                mapper = { c ->
                    if (c.next().value) version = c.getLong(0) ?: 0L
                    QueryResult.Value(Unit)
                },
                parameters = 0,
            )
        }
        val migrations = mutableListOf<JsonObject>()
        runCatching {
            driver.executeQuery(
                identifier = null,
                sql = "SELECT version, applied_at, runtime_version, duration_ms FROM migration_history ORDER BY version ASC",
                mapper = { c ->
                    while (c.next().value) {
                        migrations.add(
                            buildJsonObject {
                                put("scope", scope)
                                put("version", c.getLong(0) ?: 0L)
                                put("applied_at", c.getString(1) ?: "")
                                put("runtime_version", c.getString(2) ?: "")
                                put("duration_ms", c.getLong(3) ?: 0L)
                            },
                        )
                    }
                    QueryResult.Value(Unit)
                },
                parameters = 0,
            )
        }
        return version to migrations
    }

    private fun writeRedactionReport(zip: ZipOutputStream, state: BundleWriteState) {
        try {
            val report = """
                Redaction Report
                ================

                Bundle export summary:
                - Secret/credential redactions applied: ${state.secretRedactions}
                - PII redactions applied: ${state.piiRedactions}
                - Total bundle bytes written: ${state.totalBytesWritten}
                - Skipped oversized entries: ${state.skippedOversizedEntries}

                The following content was excluded from this diagnostic bundle:

                1. Prompts and LLM interactions (excluded by default)
                   - Use --include-sensitive to include these

                2. File contents and local paths (excluded by default)
                   - Use --include-sensitive to include these

                3. API keys and credentials (always excluded)
                   - Detected and redacted using pattern matching
                   - Detection recorded in redaction_events table

                4. TRACE logs (excluded by default)
                   - Use --include-trace to include detailed payload logs

                5. PII and known secrets (always excluded)
                   - Vault-registered secrets redacted with «redacted:NAME»
                   - Emails and user home paths sanitized

                Why redaction?
                - Your diagnostic bundle may contain sensitive information
                - You should review it before sending to maintainers
                - Redaction ensures you can safely share only what's needed

                Questions?
                - Read docs/rfcs/0037-observability.md (RFC-0037 Security section)
                - Report an issue: https://github.com/aidos/aidos/issues
            """.trimIndent()

            writeZipEntry(zip, "redaction-report.txt", report.toByteArray(Charsets.UTF_8), state)
        } catch (e: Exception) {
            System.err.println("Failed to write redaction report: ${e.message}")
        }
    }

    private fun writeZipEntry(
        zip: ZipOutputStream,
        name: String,
        bytes: ByteArray,
        state: BundleWriteState,
    ): Boolean {
        val entrySize = bytes.size.toLong()
        if (!isWithinSizeLimits(entrySize, state.totalBytesWritten, maxEntryBytes, maxBundleBytes)) {
            state.skippedOversizedEntries++
            System.err.println("Skipping entry $name: size $entrySize exceeds configured limits")
            return false
        }
        zip.putNextEntry(ZipEntry(name))
        zip.write(bytes)
        zip.closeEntry()
        state.totalBytesWritten += entrySize
        return true
    }

    private fun redactDiagnosticText(
        input: String,
        includeSensitive: Boolean,
        state: BundleWriteState,
    ): String {
        var result = redactor.redact(input, RedactionBoundary.EXPORT)
        if (result != input) state.secretRedactions++

        val beforeSecrets = result
        result = SK_KEY_REGEX.replace(result, "[REDACTED]")
        result = BEARER_REGEX.replace(result, "Bearer [REDACTED]")
        result = BASIC_AUTH_REGEX.replace(result, "Basic [REDACTED]")
        result = JSON_SECRET_FIELD_REGEX.replace(result) { m ->
            val v = m.groupValues[2]
            if (v == "[REDACTED]" || v.startsWith("«redacted")) m.value
            else "${m.groupValues[1]}\"[REDACTED]\""
        }
        result = KV_SECRET_REGEX.replace(result) { m ->
            val key = m.groupValues[1]
            val sep = m.groupValues[2]
            val rawVal = m.groupValues[3].ifEmpty { m.groupValues[4] }.ifEmpty { m.groupValues[5] }
            if (rawVal == "[REDACTED]" || rawVal.startsWith("«redacted")) m.value
            else "$key$sep[REDACTED]"
        }
        result = PADDED_BASE64_CREDENTIAL_REGEX.replace(result, "[REDACTED]")
        if (result != beforeSecrets) state.secretRedactions++

        if (!includeSensitive) {
            val beforePii = result
            result = EMAIL_REGEX.replace(result, "[REDACTED:EMAIL]")
            if (homeDir.length > 3 && result.contains(homeDir)) {
                result = result.replace(homeDir, "<HOME>")
            }
            val escapedWinHome = homeDir.replace("\\", "\\\\")
            if (escapedWinHome.length > 3 && result.contains(escapedWinHome)) {
                result = result.replace(escapedWinHome, "<HOME>")
            }
            val unixHomeNormalized = homeDir.replace('\\', '/')
            if (unixHomeNormalized.length > 3 && result.contains(unixHomeNormalized)) {
                result = result.replace(unixHomeNormalized, "<HOME>")
            }
            result = WINDOWS_USER_DIR_REGEX.replace(result, "$1<USER>")
            result = UNIX_USER_DIR_REGEX.replace(result, "$1<USER>")
            if (result != beforePii) state.piiRedactions++
        }
        return result
    }

    private fun computeDurationMs(startedAt: String?, endedAt: String?): Long {
        if (startedAt == null || endedAt == null) return 0L
        return runCatching {
            val start = Instant.parse(startedAt)
            val end = Instant.parse(endedAt)
            maxOf(0L, (end - start).inWholeMilliseconds)
        }.getOrDefault(0L)
    }

    private fun sanitizeZipSegment(raw: String): String =
        raw.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "unknown" }

    internal fun isRecentFile(
        path: Path,
        maxAgeDays: Int,
        nowMillis: Long = System.currentTimeMillis(),
    ): Boolean {
        return try {
            val attrs = Files.readAttributes(path, BasicFileAttributes::class.java)
            val fileTime = attrs.lastModifiedTime().toMillis()
            val ageMillis = maxAgeDays.toLong() * 86_400_000L
            val cutoffTime = nowMillis - ageMillis
            fileTime >= cutoffTime
        } catch (e: Exception) {
            false
        }
    }

    companion object {
        const val DEFAULT_MAX_ENTRY_BYTES: Long = 16L * 1024L * 1024L
        const val DEFAULT_MAX_BUNDLE_BYTES: Long = 128L * 1024L * 1024L

        private val SK_KEY_REGEX = Regex("\\bsk-(?:ant-|proj-|svcacct-)?[A-Za-z0-9_-]{8,}")
        private val BEARER_REGEX = Regex("(?i)\\bBearer\\s+[A-Za-z0-9._~+/=-]{4,}")
        private val BASIC_AUTH_REGEX = Regex("(?i)\\bBasic\\s+[A-Za-z0-9+/=]{4,}")
        private val JSON_SECRET_FIELD_REGEX = Regex(
            "(?i)(\"?(?:[A-Za-z0-9_.-]*(?:api[_-]?key|apikey|secret(?:_?key)?|password|passwd|authorization|auth[_-]?token|access[_-]?token|refresh[_-]?token|id[_-]?token|bearer[_-]?token|private[_-]?key)|token|credential|credentials|x-api-key)\"?\\s*:\\s*)\"([^\"\\\\]*(?:\\\\.[^\"\\\\]*)*)\""
        )
        private val KV_SECRET_REGEX = Regex(
            "(?i)\\b([A-Za-z0-9_.-]*(?:api[_-]?key|apikey|secret(?:_?key)?|password|passwd|authorization|auth[_-]?token|access[_-]?token|refresh[_-]?token|id[_-]?token|bearer[_-]?token|private[_-]?key)|token|credential|credentials|x-api-key)\\b\\s*([=:])\\s*(?:'([^']*)'|\"([^\"]*)\"|([^\\s,;'\")}\\]]{3,}))"
        )
        private val PADDED_BASE64_CREDENTIAL_REGEX = Regex("(?<![A-Za-z0-9+/=])[A-Za-z0-9+/]{16,}={1,2}(?![A-Za-z0-9+/=])")
        private val EMAIL_REGEX = Regex("\\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}\\b")
        private val WINDOWS_USER_DIR_REGEX = Regex("(?i)([A-Za-z]:(?:\\\\+|/)Users(?:\\\\+|/))[^\\\\/\\s\"']+")
        private val UNIX_USER_DIR_REGEX = Regex("(/(?:Users|home)/)[^/\\s\"']+")

        internal fun isWithinSizeLimits(
            entrySize: Long,
            currentTotal: Long,
            maxEntryBytes: Long,
            maxBundleBytes: Long,
        ): Boolean {
            if (entrySize < 0L || currentTotal < 0L) return false
            if (entrySize > maxEntryBytes) return false
            if (currentTotal > maxBundleBytes - entrySize) return false
            return true
        }
    }
}
