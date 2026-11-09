package dev.aidos.executor

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.aidos.broker.AuditLog
import dev.aidos.capability.SqliteCapabilityManager
import dev.aidos.identity.UuidV7Generator
import dev.aidos.kernel.BackoffStrategy
import dev.aidos.kernel.Budget
import dev.aidos.kernel.CapabilityConstraints
import dev.aidos.kernel.CapabilityScope
import dev.aidos.kernel.ErrorClass
import dev.aidos.kernel.EventId
import dev.aidos.kernel.Permission
import dev.aidos.kernel.PlatformProfile
import dev.aidos.kernel.ProjectId
import dev.aidos.kernel.RetryPolicy
import dev.aidos.kernel.RunId
import dev.aidos.kernel.SessionId
import dev.aidos.kernel.SessionRole
import dev.aidos.kernel.SubjectKind
import dev.aidos.kernel.SuspendedOperation
import dev.aidos.kernel.Task
import dev.aidos.kernel.TaskId
import dev.aidos.kernel.TaskKind
import dev.aidos.kernel.TaskState
import dev.aidos.kernel.UserId
import dev.aidos.storage.AidosStorage
import dev.aidos.storage.DesktopPaths
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * RFC-0011 Driver/Worker Orchestration and RFC-0019 Execution Edges tests (PIPELINE.md §4).
 */
class DriverWorkerOrchestrationTest {

    private val nowIso = "2026-08-05T00:00:00Z"
    private fun nextId() = UuidV7Generator().next()

    private fun openDriver(): JdbcSqliteDriver {
        val root = Files.createTempDirectory("orchestration-test").toFile()
        return AidosStorage.openProject(DesktopPaths.stateDb(root.path), "test-1.0") { nowIso }.driver
            as JdbcSqliteDriver
    }

    private fun seedProject(driver: JdbcSqliteDriver, projectId: String) {
        driver.execute(
            null,
            "INSERT OR IGNORE INTO projects (id, name, root_path, project_type, " +
                "created_at, updated_at, state_updated_at) VALUES (?, 'test', '/', 'generic', ?, ?, ?)",
            4,
        ) {
            bindString(0, projectId)
            bindString(1, nowIso)
            bindString(2, nowIso)
            bindString(3, nowIso)
        }
        driver.execute(
            null,
            "INSERT OR IGNORE INTO project_revocation_epoch (project_id, epoch) VALUES (?, 0)",
            1,
        ) { bindString(0, projectId) }
    }

    private fun seedSession(driver: JdbcSqliteDriver, projectId: String, sessionId: String, role: String = "DRIVER") {
        driver.execute(
            null,
            "INSERT OR IGNORE INTO sessions (id, project_id, name, role, state, " +
                "created_at, last_active_at, state_updated_at) VALUES (?, ?, 'test', ?, 'RUNNING', ?, ?, ?)",
            5,
        ) {
            bindString(0, sessionId)
            bindString(1, projectId)
            bindString(2, role)
            bindString(3, nowIso)
            bindString(4, nowIso)
            bindString(5, nowIso)
        }
    }

    private fun seedTriggerEvent(driver: JdbcSqliteDriver, projectId: String, eventId: String) {
        driver.execute(
            null,
            "INSERT OR IGNORE INTO events (id, project_id, sequence, type, schema_version, category, visibility, " +
                "timestamp, source, payload, causal_depth) VALUES (?, ?, 0, 'UserMessage', 1, 'SIGNAL', 'SESSION', ?, 'user', '{}', 0)",
            3,
        ) {
            bindString(0, eventId)
            bindString(1, projectId)
            bindString(2, nowIso)
        }
    }

    private fun insertTask(
        driver: JdbcSqliteDriver,
        taskId: String,
        runId: String,
        sessionId: String,
        projectId: String,
        ordinal: Int,
        kind: TaskKind = TaskKind.TOOL_CALL,
        description: String = "task",
        state: String = "PENDING",
    ) {
        driver.execute(
            null,
            "INSERT INTO tasks (id, run_id, session_id, project_id, ordinal, kind, " +
                "description, tool_name, state, retry_policy_json) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, 'test_tool', ?, '{}')",
            8,
        ) {
            bindString(0, taskId)
            bindString(1, runId)
            bindString(2, sessionId)
            bindString(3, projectId)
            bindLong(4, ordinal.toLong())
            bindString(5, kind.name)
            bindString(6, description)
            bindString(7, state)
        }
    }

    // ─── 1. execution_edges dependency traversal in pendingTasksFor ──────────

    @Test
    fun `tasks with no dependencies are vacuously runnable in ordinal order`() = runBlocking {
        val driver = openDriver()
        val projId = nextId()
        val sessId = nextId()
        val evtId = nextId()
        seedProject(driver, projId)
        seedSession(driver, projId, sessId)
        seedTriggerEvent(driver, projId, evtId)

        val runCreator = RunCreator(driver, ::nextId, { nowIso })
        val runId = runCreator.createForUserMessage(
            sessionId = SessionId(sessId),
            projectId = ProjectId(projId),
            triggerEventId = EventId(evtId),
            userMessageSummary = "test",
            platformProfile = PlatformProfile.DESKTOP,
            deviceId = "runtime",
            networkAvailable = true,
        )
        driver.execute(null, "DELETE FROM tasks WHERE run_id = ?", 1) { bindString(0, runId.value) }

        insertTask(driver, "t1", runId.value, sessId, projId, ordinal = 1)
        insertTask(driver, "t2", runId.value, sessId, projId, ordinal = 2)

        val executor = SqliteExecutor(
            driver = driver,
            audit = AuditLog(driver),
            events = EventStore(driver),
            idGen = ::nextId,
            nowIso = { nowIso },
            taskRunner = object : TaskRunner {
                override suspend fun execute(task: Task) = TaskResult(success = true)
            },
        )

        val runnables = executor.nextRunnableTasks(runId)
        assertEquals(1, runnables.size)
        assertEquals("t1", runnables[0].id.value)
    }

    @Test
    fun `task waits for prerequisite task to complete and non-blocked sibling runs`() = runBlocking {
        val driver = openDriver()
        val projId = nextId()
        val sessId = nextId()
        val evtId = nextId()
        seedProject(driver, projId)
        seedSession(driver, projId, sessId)
        seedTriggerEvent(driver, projId, evtId)

        val runCreator = RunCreator(driver, ::nextId, { nowIso })
        val runId = runCreator.createForUserMessage(
            sessionId = SessionId(sessId),
            projectId = ProjectId(projId),
            triggerEventId = EventId(evtId),
            userMessageSummary = "test",
            platformProfile = PlatformProfile.DESKTOP,
            deviceId = "runtime",
            networkAvailable = true,
        )
        driver.execute(null, "DELETE FROM tasks WHERE run_id = ?", 1) { bindString(0, runId.value) }

        // t1: PENDING (ordinal 1)
        insertTask(driver, "t1", runId.value, sessId, projId, ordinal = 1)
        // t2: PENDING (ordinal 2), DEPENDS_ON t1
        insertTask(driver, "t2", runId.value, sessId, projId, ordinal = 2)
        // t3: PENDING (ordinal 3), no dependencies (independent sibling)
        insertTask(driver, "t3", runId.value, sessId, projId, ordinal = 3)

        val executor = SqliteExecutor(
            driver = driver,
            audit = AuditLog(driver),
            events = EventStore(driver),
            idGen = ::nextId,
            nowIso = { nowIso },
            taskRunner = object : TaskRunner {
                override suspend fun execute(task: Task) = TaskResult(success = true)
            },
        )

        executor.addDependency(ProjectId(projId), TaskId("t2"), TaskId("t1"))

        // When t1 is PENDING, t1 is runnable (it has no deps)
        var next = executor.nextRunnableTasks(runId)
        assertEquals(1, next.size)
        assertEquals("t1", next[0].id.value)

        // Now simulate t1 running: update t1 state to RUNNING
        driver.execute(null, "UPDATE tasks SET state = 'RUNNING' WHERE id = 't1'", 0)

        // When t1 is RUNNING, countEffectfulRunning blocks concurrent effectful tasks.
        // Change t1 kind to MODEL_CALL (read-only) to inspect dependency awareness specifically:
        driver.execute(null, "UPDATE tasks SET kind = 'MODEL_CALL' WHERE id = 't1'", 0)
        // t2 cannot run because t1 is non-terminal (RUNNING). But scan continues to t3!
        next = executor.nextRunnableTasks(runId)
        assertEquals(1, next.size)
        assertEquals("t3", next[0].id.value, "Scan continues past non-terminal blocker to runnable sibling")

        // Now t1 completes:
        driver.execute(null, "UPDATE tasks SET state = 'COMPLETED' WHERE id = 't1'", 0)
        next = executor.nextRunnableTasks(runId)
        assertEquals(1, next.size)
        assertEquals("t2", next[0].id.value, "t2 is runnable once its dependency t1 is COMPLETED")
    }

    @Test
    fun `failed or skipped dependency cascades SKIPPED to downstream tasks`() = runBlocking {
        val driver = openDriver()
        val projId = nextId()
        val sessId = nextId()
        val evtId = nextId()
        seedProject(driver, projId)
        seedSession(driver, projId, sessId)
        seedTriggerEvent(driver, projId, evtId)

        val runCreator = RunCreator(driver, ::nextId, { nowIso })
        val runId = runCreator.createForUserMessage(
            sessionId = SessionId(sessId),
            projectId = ProjectId(projId),
            triggerEventId = EventId(evtId),
            userMessageSummary = "test",
            platformProfile = PlatformProfile.DESKTOP,
            deviceId = "runtime",
            networkAvailable = true,
        )
        driver.execute(null, "DELETE FROM tasks WHERE run_id = ?", 1) { bindString(0, runId.value) }

        insertTask(driver, "t1", runId.value, sessId, projId, ordinal = 1, state = "FAILED")
        insertTask(driver, "t2", runId.value, sessId, projId, ordinal = 2, state = "PENDING")
        insertTask(driver, "t3", runId.value, sessId, projId, ordinal = 3, state = "PENDING")
        insertTask(driver, "t4", runId.value, sessId, projId, ordinal = 4, state = "PENDING")

        val executor = SqliteExecutor(
            driver = driver,
            audit = AuditLog(driver),
            events = EventStore(driver),
            idGen = ::nextId,
            nowIso = { nowIso },
            taskRunner = object : TaskRunner {
                override suspend fun execute(task: Task) = TaskResult(success = true)
            },
        )

        // t2 depends on t1 (which is FAILED)
        executor.addDependency(ProjectId(projId), TaskId("t2"), TaskId("t1"))
        // t3 depends on t2
        executor.addDependency(ProjectId(projId), TaskId("t3"), TaskId("t2"))
        // t4 has no dependencies

        val runnables = executor.nextRunnableTasks(runId)
        assertEquals(1, runnables.size)
        assertEquals("t4", runnables[0].id.value, "t4 is the only runnable task; t2 and t3 cascaded to SKIPPED")

        // Verify t2 and t3 states in database are now SKIPPED
        val t2State = driver.executeQuery(null, "SELECT state FROM tasks WHERE id = 't2'", { c ->
            app.cash.sqldelight.db.QueryResult.Value(if (c.next().value) c.getString(0) else null)
        }, 0).value
        val t3State = driver.executeQuery(null, "SELECT state FROM tasks WHERE id = 't3'", { c ->
            app.cash.sqldelight.db.QueryResult.Value(if (c.next().value) c.getString(0) else null)
        }, 0).value

        assertEquals("SKIPPED", t2State)
        assertEquals("SKIPPED", t3State)
    }

    // ─── 2. WorkerSpawner composite spawning ─────────────────────────────────

    @Test
    fun `WorkerSpawner fails if driver does not hold Permission WORKER_CREATE`() = runBlocking {
        val driver = openDriver()
        val projId = nextId()
        val sessId = nextId()
        val evtId = nextId()
        seedProject(driver, projId)
        seedSession(driver, projId, sessId)
        seedTriggerEvent(driver, projId, evtId)

        val runCreator = RunCreator(driver, ::nextId, { nowIso })
        val capMgr = SqliteCapabilityManager(driver, UuidV7Generator()) { nowIso }
        val spawner = WorkerSpawner(driver, capMgr, runCreator, ::nextId, { nowIso })

        val runId = runCreator.createForUserMessage(
            sessionId = SessionId(sessId),
            projectId = ProjectId(projId),
            triggerEventId = EventId(evtId),
            userMessageSummary = "test",
            platformProfile = PlatformProfile.DESKTOP,
            deviceId = "runtime",
            networkAvailable = true,
        )

        val task = Task(
            id = TaskId("comp-1"),
            runId = runId,
            planId = null,
            sessionId = SessionId(sessId),
            projectId = ProjectId(projId),
            ordinal = 1,
            kind = TaskKind.COMPOSITE,
            description = "Spawn child",
            toolName = null,
            modelKind = null,
            state = TaskState.PENDING,
            startedAt = null,
            endedAt = null,
            awaitingRunId = null,
            retryPolicy = RetryPolicy(1, emptySet(), BackoffStrategy.None),
            approvalChannel = null,
            approvalPhrase = null,
        )

        val result = spawner.spawn(task)
        assertFalse(result.success)
        assertTrue(result.errorMessage?.contains("Permission.WORKER_CREATE") == true)
    }

    @Test
    fun `WorkerSpawner creates worker session, delegates capabilities, and parks on child run`() = runBlocking {
        val driver = openDriver()
        val projId = nextId()
        val sessId = nextId()
        val evtId = nextId()
        seedProject(driver, projId)
        seedSession(driver, projId, sessId)
        seedTriggerEvent(driver, projId, evtId)

        val runCreator = RunCreator(driver, ::nextId, { nowIso })
        val capMgr = SqliteCapabilityManager(driver, UuidV7Generator()) { nowIso }
        val spawner = WorkerSpawner(driver, capMgr, runCreator, ::nextId, { nowIso })

        // Grant WORKER_CREATE and FS_WRITE with allows_delegation = 1
        capMgr.grant(
            subjectId = sessId,
            subjectKind = SubjectKind.SESSION,
            permission = Permission.WORKER_CREATE,
            scope = CapabilityScope.Worker(maxWorkerCount = 5, allowedRoles = setOf(SessionRole.WORKER)),
            constraints = CapabilityConstraints(),
            expiresAt = null,
            grantedBy = UserId("user-1"),
        ).getOrThrow()

        val parentBudget = Budget(inputTokens = 1000L, outputTokens = 1000L)
        val fsCap = capMgr.grant(
            subjectId = sessId,
            subjectKind = SubjectKind.SESSION,
            permission = Permission.FS_WRITE,
            scope = CapabilityScope.Filesystem(ProjectId(projId), "src"),
            constraints = CapabilityConstraints(budget = parentBudget),
            expiresAt = null,
            grantedBy = UserId("user-1"),
        ).getOrThrow()

        // Flip allows_delegation = 1 on fsCap
        driver.execute(null, "UPDATE capabilities SET allows_delegation = 1 WHERE id = ?", 1) {
            bindString(0, fsCap.id.value)
        }

        val runId = runCreator.createForUserMessage(
            sessionId = SessionId(sessId),
            projectId = ProjectId(projId),
            triggerEventId = EventId(evtId),
            userMessageSummary = "driver run",
            platformProfile = PlatformProfile.DESKTOP,
            deviceId = "runtime",
            networkAvailable = true,
        )
        driver.execute(null, "DELETE FROM tasks WHERE run_id = ?", 1) { bindString(0, runId.value) }

        insertTask(driver, "comp-1", runId.value, sessId, projId, ordinal = 1, kind = TaskKind.COMPOSITE, description = "Spawn worker")
        spawner.recordSpawnRequest(
            taskId = TaskId("comp-1"),
            brief = "Child worker brief",
            capabilitiesToDelegate = listOf(fsCap.id.value),
            budgetSplitWays = 2,
        )

        val task = Task(
            id = TaskId("comp-1"),
            runId = runId,
            planId = null,
            sessionId = SessionId(sessId),
            projectId = ProjectId(projId),
            ordinal = 1,
            kind = TaskKind.COMPOSITE,
            description = "Spawn worker",
            toolName = null,
            modelKind = null,
            state = TaskState.PENDING,
            startedAt = null,
            endedAt = null,
            awaitingRunId = null,
            retryPolicy = RetryPolicy(1, emptySet(), BackoffStrategy.None),
            approvalChannel = null,
            approvalPhrase = null,
        )

        val result = spawner.spawn(task)
        assertTrue(result.success)
        val park = assertNotNull(result.park)
        assertEquals(TaskState.AWAITING_INPUT, park.taskState)
        val childOp = assertIs<SuspendedOperation.ChildRun>(park.suspendedOperation)

        // Verify worker session exists
        val workerRole = driver.executeQuery(null, "SELECT role, parent_session_id FROM sessions WHERE id = ?", { c ->
            app.cash.sqldelight.db.QueryResult.Value(if (c.next().value) Pair(c.getString(0), c.getString(1)) else null)
        }, 1) { bindString(0, childOp.childSessionId.value) }.value

        assertNotNull(workerRole)
        assertEquals("WORKER", workerRole.first)
        assertEquals(sessId, workerRole.second)

        // Verify delegated capability with split budget
        val workerCaps = capMgr.loadForSubject(childOp.childSessionId.value)
        assertEquals(1, workerCaps.size)
        assertEquals(Permission.FS_WRITE, workerCaps[0].permission)
        assertEquals(parentBudget.split(2), workerCaps[0].constraints.budget)

        // Verify worker_spawn_requests updated with worker_session_id and child_run_id
        val spawnRow = driver.executeQuery(null, "SELECT worker_session_id, child_run_id FROM worker_spawn_requests WHERE task_id = 'comp-1'", { c ->
            app.cash.sqldelight.db.QueryResult.Value(if (c.next().value) Pair(c.getString(0), c.getString(1)) else null)
        }, 0).value
        assertNotNull(spawnRow)
        assertEquals(childOp.childSessionId.value, spawnRow.first)
        assertEquals(childOp.childRunId.value, spawnRow.second)
    }

    // ─── 3. Recursive Execution & Parent Resumption ──────────────────────────

    @Test
    fun `recursive execution drives child run and resumes parent run to completion`() = runBlocking {
        val driver = openDriver()
        val projId = nextId()
        val sessId = nextId()
        val evtId = nextId()
        seedProject(driver, projId)
        seedSession(driver, projId, sessId)
        seedTriggerEvent(driver, projId, evtId)

        val runCreator = RunCreator(driver, ::nextId, { nowIso })
        val capMgr = SqliteCapabilityManager(driver, UuidV7Generator()) { nowIso }
        val spawner = WorkerSpawner(driver, capMgr, runCreator, ::nextId, { nowIso })

        capMgr.grant(
            subjectId = sessId,
            subjectKind = SubjectKind.SESSION,
            permission = Permission.WORKER_CREATE,
            scope = CapabilityScope.Worker(maxWorkerCount = 5, allowedRoles = setOf(SessionRole.WORKER)),
            constraints = CapabilityConstraints(),
            expiresAt = null,
            grantedBy = UserId("user-1"),
        ).getOrThrow()

        var committedSessionId: SessionId? = null
        var committedRunId: RunId? = null

        val committer = object : WorkerCommitter {
            override suspend fun commitWorkerOutcome(
                projectId: ProjectId,
                workerSessionId: SessionId,
                childRunId: RunId,
                outcomeSummary: String,
            ) {
                committedSessionId = workerSessionId
                committedRunId = childRunId
            }
        }

        val executor = SqliteExecutor(
            driver = driver,
            audit = AuditLog(driver),
            events = EventStore(driver),
            idGen = ::nextId,
            nowIso = { nowIso },
            taskRunner = object : TaskRunner {
                override suspend fun execute(task: Task): TaskResult {
                    // Normal tasks succeed
                    return TaskResult(success = true)
                }
            },
            workerSpawner = spawner,
            workerCommitter = committer,
        )

        val parentRunId = runCreator.createForUserMessage(
            sessionId = SessionId(sessId),
            projectId = ProjectId(projId),
            triggerEventId = EventId(evtId),
            userMessageSummary = "parent run",
            platformProfile = PlatformProfile.DESKTOP,
            deviceId = "runtime",
            networkAvailable = true,
        )
        driver.execute(null, "DELETE FROM tasks WHERE run_id = ?", 1) { bindString(0, parentRunId.value) }

        insertTask(driver, "parent-comp", parentRunId.value, sessId, projId, ordinal = 1, kind = TaskKind.COMPOSITE, description = "Spawn and run child")
        insertTask(driver, "parent-followup", parentRunId.value, sessId, projId, ordinal = 2, kind = TaskKind.TOOL_CALL, description = "Parent follow-on work")

        // Drive parent:
        // 1. Executes parent-comp via workerSpawner
        // 2. Writes continuation row
        // 3. Recursively drives child run
        // 4. Child run has 1 auto-created user message or empty tasks -> completes
        // 5. Child run completion invokes resumeAwaitingParent(childRunId)
        // 6. WorkerCommitter commits outcome
        // 7. parent-comp completes, parent run resumes RUNNING, executes parent-followup, completes!
        executor.drive(parentRunId)

        // Check parent run state
        val parentState = driver.executeQuery(null, "SELECT state FROM runs WHERE id = ?", { c ->
            app.cash.sqldelight.db.QueryResult.Value(if (c.next().value) c.getString(0) else null)
        }, 1) { bindString(0, parentRunId.value) }.value

        assertEquals("COMPLETED", parentState)

        // Check task states
        val compState = driver.executeQuery(null, "SELECT state FROM tasks WHERE id = 'parent-comp'", { c ->
            app.cash.sqldelight.db.QueryResult.Value(if (c.next().value) c.getString(0) else null)
        }, 0).value
        val followupState = driver.executeQuery(null, "SELECT state FROM tasks WHERE id = 'parent-followup'", { c ->
            app.cash.sqldelight.db.QueryResult.Value(if (c.next().value) c.getString(0) else null)
        }, 0).value

        assertEquals("COMPLETED", compState)
        assertEquals("COMPLETED", followupState)

        // Verify committer was invoked
        assertNotNull(committedSessionId)
        assertNotNull(committedRunId)

        // Verify continuation row was deleted
        val continuation = driver.executeQuery(null, "SELECT task_id FROM continuations WHERE run_id = ?", { c ->
            app.cash.sqldelight.db.QueryResult.Value(if (c.next().value) c.getString(0) else null)
        }, 1) { bindString(0, parentRunId.value) }.value
        assertNull(continuation)
    }

    @Test
    fun `child run failure fails parent composite task and parent run`() = runBlocking {
        val driver = openDriver()
        val projId = nextId()
        val sessId = nextId()
        val evtId = nextId()
        seedProject(driver, projId)
        seedSession(driver, projId, sessId)
        seedTriggerEvent(driver, projId, evtId)

        val runCreator = RunCreator(driver, ::nextId, { nowIso })
        val capMgr = SqliteCapabilityManager(driver, UuidV7Generator()) { nowIso }
        val spawner = WorkerSpawner(driver, capMgr, runCreator, ::nextId, { nowIso })

        capMgr.grant(
            subjectId = sessId,
            subjectKind = SubjectKind.SESSION,
            permission = Permission.WORKER_CREATE,
            scope = CapabilityScope.Worker(maxWorkerCount = 5, allowedRoles = setOf(SessionRole.WORKER)),
            constraints = CapabilityConstraints(),
            expiresAt = null,
            grantedBy = UserId("user-1"),
        ).getOrThrow()

        val executor = SqliteExecutor(
            driver = driver,
            audit = AuditLog(driver),
            events = EventStore(driver),
            idGen = ::nextId,
            nowIso = { nowIso },
            taskRunner = object : TaskRunner {
                override suspend fun execute(task: Task): TaskResult {
                    // Fail any task in child run
                    return if (task.description.contains("fail")) {
                        TaskResult(success = false, errorMessage = "Intentional child task failure")
                    } else {
                        TaskResult(success = true)
                    }
                }
            },
            workerSpawner = spawner,
        )

        val parentRunId = runCreator.createForUserMessage(
            sessionId = SessionId(sessId),
            projectId = ProjectId(projId),
            triggerEventId = EventId(evtId),
            userMessageSummary = "parent run",
            platformProfile = PlatformProfile.DESKTOP,
            deviceId = "runtime",
            networkAvailable = true,
        )
        driver.execute(null, "DELETE FROM tasks WHERE run_id = ?", 1) { bindString(0, parentRunId.value) }

        insertTask(driver, "parent-comp", parentRunId.value, sessId, projId, ordinal = 1, kind = TaskKind.COMPOSITE, description = "Spawn child that will fail")

        // Intercept spawn to add a failing task to child run
        val parkResult = spawner.spawn(
            Task(
                id = TaskId("parent-comp"),
                runId = parentRunId,
                planId = null,
                sessionId = SessionId(sessId),
                projectId = ProjectId(projId),
                ordinal = 1,
                kind = TaskKind.COMPOSITE,
                description = "Spawn child that will fail",
                toolName = null,
                modelKind = null,
                state = TaskState.PENDING,
                startedAt = null,
                endedAt = null,
                awaitingRunId = null,
                retryPolicy = RetryPolicy(1, emptySet(), BackoffStrategy.None),
                approvalChannel = null,
                approvalPhrase = null,
            )
        )

        val childOp = assertIs<SuspendedOperation.ChildRun>(parkResult.park?.suspendedOperation)
        // Add a failing task to child run
        insertTask(
            driver,
            "child-task-1",
            childOp.childRunId.value,
            childOp.childSessionId.value,
            projId,
            ordinal = 1,
            description = "will fail",
        )

        // Write parent continuation row manually to simulate executor parking
        driver.execute(
            null,
            "INSERT INTO continuations (run_id, task_id, suspended_operation, operation_detail_json, correlation_id, created_at) " +
                "VALUES (?, 'parent-comp', 'CHILD_RUN', '{}', ?, ?)",
            3,
        ) {
            bindString(0, parentRunId.value)
            bindString(1, childOp.childRunId.value)
            bindString(2, nowIso)
        }
        driver.execute(null, "UPDATE tasks SET state = 'AWAITING_INPUT' WHERE id = 'parent-comp'", 0)
        driver.execute(null, "UPDATE runs SET state = 'YIELDED' WHERE id = ?", 1) { bindString(0, parentRunId.value) }

        // Now drive child run directly
        executor.drive(childOp.childRunId)

        // Child run fails -> triggers resumeAwaitingParent -> parent-comp marked FAILED -> parent run marked FAILED
        val parentState = driver.executeQuery(null, "SELECT state FROM runs WHERE id = ?", { c ->
            app.cash.sqldelight.db.QueryResult.Value(if (c.next().value) c.getString(0) else null)
        }, 1) { bindString(0, parentRunId.value) }.value

        val compState = driver.executeQuery(null, "SELECT state FROM tasks WHERE id = 'parent-comp'", { c ->
            app.cash.sqldelight.db.QueryResult.Value(if (c.next().value) c.getString(0) else null)
        }, 0).value

        assertEquals("FAILED", parentState)
        assertEquals("FAILED", compState)
    }
}
