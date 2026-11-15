package dev.aidos.daemon

import app.cash.sqldelight.db.SqlDriver
import dev.aidos.executor.ProjectExecutionContext
import dev.aidos.executor.ProjectWriteContext
import dev.aidos.executor.RuntimeDispatchers
import dev.aidos.kernel.ProjectId
import dev.aidos.kernel.RunId
import dev.aidos.kernel.SessionId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * Process-lifetime runtime scope (RFC-0048 §Lifecycles, RFC-0007 §Dispatcher Hierarchy).
 *
 * Holds process-wide composition root, platform adapter, and [RuntimeDispatchers].
 */
class RuntimeScope(
    val platform: PlatformAdapter,
    val compositionRoot: RuntimeCompositionRoot,
    val dispatchers: RuntimeDispatchers = RuntimeDispatchers.forProfile(
        platform.profile,
        Runtime.getRuntime().availableProcessors(),
    ),
) : AutoCloseable {
    private val job = SupervisorJob()
    val coroutineScope: CoroutineScope = CoroutineScope(job + dispatchers.main)
    var isDisposed: Boolean = false
        private set

    fun openProject(
        projectId: ProjectId,
        projectDriver: SqlDriver,
        onDispose: () -> Unit = {},
    ): ProjectScope = ProjectScope(
        projectId = projectId,
        projectDriver = projectDriver,
        dispatchers = dispatchers,
        parentJob = job,
        onDispose = onDispose,
    )

    override fun close() {
        if (isDisposed) return
        isDisposed = true
        coroutineScope.cancel()
    }
}

/**
 * Project-lifetime scope created on project open and deterministically disposed on close
 * (RFC-0048 §Lifecycles, RFC-0007 §Working-Tree Lock / SQLite Single-Writer).
 *
 * Closes the project SQLite driver, cancels project-scoped coroutines, and invokes [onDispose]
 * (e.g. lock release per RFC-0055).
 */
class ProjectScope(
    val projectId: ProjectId,
    val projectDriver: SqlDriver,
    val dispatchers: RuntimeDispatchers = RuntimeDispatchers.forProfile(
        dev.aidos.kernel.PlatformProfile.DESKTOP,
        Runtime.getRuntime().availableProcessors(),
    ),
    parentJob: Job? = null,
    private val onDispose: () -> Unit = {},
) : AutoCloseable {
    private val job = SupervisorJob(parentJob)
    val coroutineScope: CoroutineScope = CoroutineScope(job + dispatchers.session)
    val executionContext: ProjectExecutionContext = ProjectExecutionContext(
        projectId = projectId,
        writeContext = ProjectWriteContext(projectId, dispatchers.io),
        scope = coroutineScope,
    )
    var isDisposed: Boolean = false
        private set

    fun openSession(sessionId: SessionId, onDispose: () -> Unit = {}): SessionScope =
        SessionScope(sessionId, dispatchers, job, onDispose)

    override fun close() {
        if (isDisposed) return
        isDisposed = true
        coroutineScope.cancel()
        runCatching { onDispose() }
        runCatching { projectDriver.close() }
    }
}

/**
 * Per-Session scope in the RFC-0007 cancellation hierarchy (`RuntimeScope -> ProjectScope -> SessionScope -> RunScope`).
 */
class SessionScope(
    val sessionId: SessionId,
    val dispatchers: RuntimeDispatchers = RuntimeDispatchers.forProfile(
        dev.aidos.kernel.PlatformProfile.DESKTOP,
        Runtime.getRuntime().availableProcessors(),
    ),
    parentJob: Job? = null,
    private val onDispose: () -> Unit = {},
) : AutoCloseable {
    private val job = SupervisorJob(parentJob)
    val coroutineScope: CoroutineScope = CoroutineScope(job + dispatchers.session)
    var isDisposed: Boolean = false
        private set

    fun openRun(runId: RunId, onDispose: () -> Unit = {}): RunScope =
        RunScope(runId, dispatchers, job, onDispose)

    override fun close() {
        if (isDisposed) return
        isDisposed = true
        coroutineScope.cancel()
        runCatching { onDispose() }
    }
}

/**
 * Per-Run scope created when a Run starts and disposed at terminal state (RFC-0048 §Lifecycles, RFC-0007).
 */
class RunScope(
    val runId: RunId,
    val dispatchers: RuntimeDispatchers = RuntimeDispatchers.forProfile(
        dev.aidos.kernel.PlatformProfile.DESKTOP,
        Runtime.getRuntime().availableProcessors(),
    ),
    parentJob: Job? = null,
    private val onDispose: () -> Unit = {},
) : AutoCloseable {
    private val job = SupervisorJob(parentJob)
    val coroutineScope: CoroutineScope = CoroutineScope(job + dispatchers.session)
    var isDisposed: Boolean = false
        private set

    override fun close() {
        if (isDisposed) return
        isDisposed = true
        coroutineScope.cancel()
        runCatching { onDispose() }
    }
}
