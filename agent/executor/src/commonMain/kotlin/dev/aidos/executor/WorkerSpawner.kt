package dev.aidos.executor

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import dev.aidos.broker.AuditLog
import dev.aidos.kernel.ActorKind
import dev.aidos.kernel.ActorRef
import dev.aidos.kernel.CapabilityManager
import dev.aidos.kernel.EventId
import dev.aidos.kernel.Permission
import dev.aidos.kernel.PlatformProfile
import dev.aidos.kernel.ProjectId
import dev.aidos.kernel.RunId
import dev.aidos.kernel.SessionId
import dev.aidos.kernel.SubjectKind
import dev.aidos.kernel.SuspendedOperation
import dev.aidos.kernel.Task
import dev.aidos.kernel.TaskId
import dev.aidos.kernel.TaskState
import dev.aidos.settings.SettingDescriptor
import dev.aidos.settings.Settings
import dev.aidos.settings.SettingsWriter
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

data class WorkerSpawnSpec(
    val brief: String,
    val capabilitiesToDelegate: List<String> = emptyList(),
    val budgetSplitWays: Int = 1,
    val modelId: String? = null,
    val locality: String? = null,
    val taintApprovalPolicy: String? = null,
)

/**
 * Composite task spawning (RFC-0011, PIPELINE.md §4).
 *
 * Spawns a worker session (`role = WORKER`, `parent_session_id = the driver's`),
 * validates [Permission.WORKER_CREATE] on the driver session, delegates a caller-selected
 * subset of driver capabilities via [CapabilityManager.delegate] with [dev.aidos.kernel.Budget.split],
 * creates a child Run via [RunCreator.createForUserMessage], and returns
 * [TaskResult.park] with [SuspendedOperation.ChildRun].
 */
class WorkerSpawner(
    private val driver: SqlDriver,
    private val capabilityManager: CapabilityManager,
    private val runCreator: RunCreator,
    private val idGen: () -> String,
    private val nowIso: () -> String,
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun recordSpawnRequest(
        taskId: TaskId,
        brief: String,
        capabilitiesToDelegate: List<String> = emptyList(),
        budgetSplitWays: Int = 1,
        modelId: String? = null,
        locality: String? = null,
        taintApprovalPolicy: String? = null,
    ) {
        val now = nowIso()
        val capsJson = json.encodeToString(capabilitiesToDelegate)
        driver.execute(
            identifier = null,
            sql = "INSERT INTO worker_spawn_requests (task_id, brief, capabilities_json, " +
                "budget_split_ways, worker_session_id, child_run_id, created_at) " +
                "VALUES (?, ?, ?, ?, NULL, NULL, ?) " +
                "ON CONFLICT(task_id) DO UPDATE SET brief = excluded.brief, " +
                "capabilities_json = excluded.capabilities_json, " +
                "budget_split_ways = excluded.budget_split_ways",
            parameters = 5,
        ) {
            bindString(0, taskId.value)
            bindString(1, brief)
            bindString(2, capsJson)
            bindLong(3, maxOf(1, budgetSplitWays).toLong())
            bindString(4, now)
        }

        driver.execute(
            identifier = null,
            sql = "DELETE FROM settings WHERE scope = 'task_spawn' AND scope_id = ?",
            parameters = 1,
        ) {
            bindString(0, taskId.value)
        }

        modelId?.trim()?.takeIf { it.isNotEmpty() }?.let { trimmedModelId ->
            upsertTaskSpawnSetting(taskId.value, Settings.modelDefaultRunLlm, trimmedModelId, now)
        }
        locality?.trim()?.takeIf { it.isNotEmpty() }?.let { rawLocality ->
            val parsedLocality = Settings.routingModelLocality.codec
                .decode(JsonPrimitive(rawLocality))
                .getOrThrow()
            upsertTaskSpawnSetting(taskId.value, Settings.routingModelLocality, parsedLocality, now)
        }
        taintApprovalPolicy?.trim()?.takeIf { it.isNotEmpty() }?.let { rawPolicy ->
            val parsedPolicy = Settings.trustTaintApprovalPolicy.codec
                .decode(JsonPrimitive(rawPolicy.uppercase()))
                .getOrThrow()
            upsertTaskSpawnSetting(taskId.value, Settings.trustTaintApprovalPolicy, parsedPolicy, now)
        }
    }

    private fun <T> upsertTaskSpawnSetting(
        taskId: String,
        descriptor: SettingDescriptor<T>,
        value: T,
        nowIso: String,
    ) {
        val encoded = descriptor.codec.encode(value).toString()
        driver.execute(
            identifier = null,
            sql = "INSERT INTO settings (scope, scope_id, key, value_json, set_at, set_by_kind) " +
                "VALUES ('task_spawn', ?, ?, ?, ?, 'RUNTIME')",
            parameters = 4,
        ) {
            bindString(0, taskId)
            bindString(1, descriptor.key)
            bindString(2, encoded)
            bindString(3, nowIso)
        }
    }

    suspend fun spawn(task: Task): TaskResult {
        // 1. Check Permission.WORKER_CREATE on driver session (RFC-0011, PIPELINE.md §4)
        val driverCaps = capabilityManager.loadForSubject(task.sessionId.value)
        val hasWorkerCreate = driverCaps.any { it.permission == Permission.WORKER_CREATE && it.revokedAt == null }
        if (!hasWorkerCreate) {
            return TaskResult(
                success = false,
                errorMessage = "Driver session ${task.sessionId.value} does not hold Permission.WORKER_CREATE",
            )
        }

        // 2. Load worker spawn spec from worker_spawn_requests if present, else fallback
        val spec = loadSpawnSpec(task.id.value) ?: WorkerSpawnSpec(
            brief = task.description,
            capabilitiesToDelegate = emptyList(),
            budgetSplitWays = 1,
        )

        // 3. Create worker session row
        val workerSessionId = idGen()
        val now = nowIso()
        val workerRef = "refs/aidos/workers/$workerSessionId"
        driver.execute(
            identifier = null,
            sql = "INSERT INTO sessions (id, project_id, name, role, description, state, " +
                "parent_session_id, worker_ref, consecutive_failures, created_at, last_active_at, " +
                "archived_at, state_updated_at, row_version) " +
                "VALUES (?, ?, ?, 'WORKER', ?, 'RUNNING', ?, ?, 0, ?, ?, NULL, ?, 1)",
            parameters = 9,
        ) {
            bindString(0, workerSessionId)
            bindString(1, task.projectId.value)
            bindString(2, "worker-${workerSessionId.take(8)}")
            bindString(3, spec.brief)
            bindString(4, task.sessionId.value)
            bindString(5, workerRef)
            bindString(6, now)
            bindString(7, now)
            bindString(8, now)
        }

        // 4. Delegate capabilities with budget split
        val splitWays = maxOf(1, spec.budgetSplitWays)
        val capsToDelegate = if (spec.capabilitiesToDelegate.isNotEmpty()) {
            driverCaps.filter { it.id.value in spec.capabilitiesToDelegate && it.allowsDelegation && it.revokedAt == null }
        } else {
            driverCaps.filter { it.allowsDelegation && it.permission != Permission.WORKER_CREATE && it.revokedAt == null }
        }

        for (cap in capsToDelegate) {
            val splitBudget = cap.constraints.budget?.split(splitWays)
            val delegated = capabilityManager.delegate(
                parent = cap.id,
                toSubjectId = workerSessionId,
                toSubjectKind = SubjectKind.WORKER,
                attenuatedScope = cap.scope,
                attenuatedConstraints = cap.constraints.copy(budget = splitBudget),
            )
            if (delegated.isFailure) {
                return TaskResult(
                    success = false,
                    errorMessage = "Failed to delegate capability ${cap.id.value}: ${delegated.exceptionOrNull()?.message}",
                )
            }
        }

        // 5. Create child run via runCreator
        val parentRunContext = loadRunContext(task.runId)
            ?: return TaskResult(success = false, errorMessage = "Parent run ${task.runId.value} not found")

        val childRunId = runCreator.createForUserMessage(
            sessionId = SessionId(workerSessionId),
            projectId = task.projectId,
            triggerEventId = parentRunContext.triggerEventId,
            userMessageSummary = spec.brief,
            platformProfile = parentRunContext.platformProfile,
            deviceId = parentRunContext.deviceId,
            networkAvailable = parentRunContext.networkAvailable,
            maxSteps = 24,
        )

        // 5b. Propagate any per-spawn model / locality / guardian overrides to child run scope
        val chosenModelId = spec.modelId?.trim()?.takeIf { it.isNotEmpty() }
        if (chosenModelId != null) {
            SettingsWriter.writeRun(
                driver = driver,
                runId = childRunId.value,
                descriptor = Settings.modelDefaultLlm,
                value = chosenModelId,
                nowIso = now,
                setByKind = "RUNTIME",
            ).getOrThrow()
            SettingsWriter.writeRun(
                driver = driver,
                runId = childRunId.value,
                descriptor = Settings.modelDefaultRunLlm,
                value = chosenModelId,
                nowIso = now,
                setByKind = "RUNTIME",
            ).getOrThrow()
        }
        val chosenLocality = spec.locality?.trim()?.takeIf { it.isNotEmpty() }
        if (chosenLocality != null) {
            val parsedLocality = Settings.routingModelLocality.codec
                .decode(JsonPrimitive(chosenLocality))
                .getOrThrow()
            SettingsWriter.writeRun(
                driver = driver,
                runId = childRunId.value,
                descriptor = Settings.routingModelLocality,
                value = parsedLocality,
                nowIso = now,
                setByKind = "RUNTIME",
            ).getOrThrow()
        }
        val chosenPolicy = spec.taintApprovalPolicy?.trim()?.takeIf { it.isNotEmpty() }
        if (chosenPolicy != null) {
            val parsedPolicy = Settings.trustTaintApprovalPolicy.codec
                .decode(JsonPrimitive(chosenPolicy.uppercase()))
                .getOrThrow()
            SettingsWriter.writeRun(
                driver = driver,
                runId = childRunId.value,
                descriptor = Settings.trustTaintApprovalPolicy,
                value = parsedPolicy,
                nowIso = now,
                setByKind = "RUNTIME",
            ).getOrThrow()
        }

        // 6. Update worker_spawn_requests
        updateOrInsertSpawnRequest(task.id.value, spec, workerSessionId, childRunId.value)

        // 7. Record spawn detail and audit entry, then park parent task
        val operationDetail = buildJsonObject {
            put("worker_session_id", workerSessionId)
            put("child_run_id", childRunId.value)
            put("brief", spec.brief)
            if (chosenModelId != null) {
                put("model_id", chosenModelId)
            }
            put("model_chosen_by_main_model", chosenModelId != null)
            if (chosenLocality != null) {
                put("locality", chosenLocality.lowercase())
            }
            if (chosenPolicy != null) {
                put("taint_approval_policy", chosenPolicy.uppercase())
            }
        }.toString()

        AuditLog(driver, parentRunContext.deviceId).write(
            id = idGen(),
            projectId = task.projectId.value,
            kind = "WorkerSpawned",
            actor = ActorRef(ActorKind.SESSION, task.sessionId.value),
            subjectRef = workerSessionId,
            detailJson = operationDetail,
            nowIso = now,
        )

        return TaskResult(
            success = true,
            park = ParkRequest(
                suspendedOperation = SuspendedOperation.ChildRun(childRunId, SessionId(workerSessionId)),
                operationDetailJson = operationDetail,
                taskState = TaskState.AWAITING_INPUT,
            ),
        )
    }

    private fun loadSpawnSpec(taskId: String): WorkerSpawnSpec? {
        val base = driver.executeQuery(
            identifier = null,
            sql = "SELECT brief, capabilities_json, budget_split_ways FROM worker_spawn_requests WHERE task_id = ?",
            mapper = { c ->
                QueryResult.Value(
                    if (c.next().value) {
                        val brief = c.getString(0)!!
                        val capsJson = c.getString(1)!!
                        val ways = c.getLong(2)?.toInt() ?: 1
                        val caps = try {
                            json.decodeFromString<List<String>>(capsJson)
                        } catch (_: Exception) {
                            emptyList()
                        }
                        WorkerSpawnSpec(brief, caps, ways)
                    } else null
                )
            },
            parameters = 1,
        ) { bindString(0, taskId) }.value ?: return null

        val taskSpawnSettings = driver.executeQuery(
            identifier = null,
            sql = "SELECT key, value_json FROM settings WHERE scope = 'task_spawn' AND scope_id = ?",
            mapper = { c ->
                val map = LinkedHashMap<String, String>()
                while (c.next().value) {
                    val k = c.getString(0)
                    val v = c.getString(1)
                    if (k != null && v != null) map[k] = v
                }
                QueryResult.Value(map)
            },
            parameters = 1,
        ) { bindString(0, taskId) }.value

        val modelId = taskSpawnSettings[Settings.modelDefaultRunLlm.key]?.let { raw ->
            runCatching { json.parseToJsonElement(raw) }
                .getOrNull()
                ?.let { Settings.modelDefaultRunLlm.codec.decode(it).getOrNull() }
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
        }
        val locality = taskSpawnSettings[Settings.routingModelLocality.key]?.let { raw ->
            runCatching { json.parseToJsonElement(raw) }
                .getOrNull()
                ?.let { Settings.routingModelLocality.codec.decode(it).getOrNull() }
                ?.name
                ?.lowercase()
        }
        val taintApprovalPolicy = taskSpawnSettings[Settings.trustTaintApprovalPolicy.key]?.let { raw ->
            runCatching { json.parseToJsonElement(raw) }
                .getOrNull()
                ?.let { Settings.trustTaintApprovalPolicy.codec.decode(it).getOrNull() }
                ?.name
        }

        return base.copy(
            modelId = modelId,
            locality = locality,
            taintApprovalPolicy = taintApprovalPolicy,
        )
    }

    private fun updateOrInsertSpawnRequest(
        taskId: String,
        spec: WorkerSpawnSpec,
        workerSessionId: String,
        childRunId: String,
    ) {
        val capsJson = json.encodeToString(spec.capabilitiesToDelegate)
        driver.execute(
            identifier = null,
            sql = "INSERT INTO worker_spawn_requests (task_id, brief, capabilities_json, " +
                "budget_split_ways, worker_session_id, child_run_id, created_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?) " +
                "ON CONFLICT(task_id) DO UPDATE SET " +
                "worker_session_id = excluded.worker_session_id, " +
                "child_run_id = excluded.child_run_id",
            parameters = 7,
        ) {
            bindString(0, taskId)
            bindString(1, spec.brief)
            bindString(2, capsJson)
            bindLong(3, spec.budgetSplitWays.toLong())
            bindString(4, workerSessionId)
            bindString(5, childRunId)
            bindString(6, nowIso())
        }
    }

    private data class ParentRunContext(
        val triggerEventId: EventId,
        val platformProfile: PlatformProfile,
        val deviceId: String,
        val networkAvailable: Boolean,
    )

    private fun loadRunContext(runId: RunId): ParentRunContext? =
        driver.executeQuery(
            identifier = null,
            sql = "SELECT trigger_event_id, platform_profile, device_id, network_available FROM runs WHERE id = ?",
            mapper = { c ->
                QueryResult.Value(
                    if (c.next().value) {
                        ParentRunContext(
                            triggerEventId = EventId(c.getString(0)!!),
                            platformProfile = PlatformProfile.valueOf(c.getString(1)!!),
                            deviceId = c.getString(2)!!,
                            networkAvailable = (c.getLong(3) ?: 0L) == 1L,
                        )
                    } else null
                )
            },
            parameters = 1,
        ) { bindString(0, runId.value) }.value
}
