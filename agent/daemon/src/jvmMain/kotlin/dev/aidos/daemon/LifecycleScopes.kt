package dev.aidos.daemon

import app.cash.sqldelight.db.SqlDriver
import dev.aidos.kernel.ProjectId
import dev.aidos.kernel.RunId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * Process-lifetime runtime scope (RFC-0048 §Lifecycles).
 *
 * Holds process-wide composition root and platform adapter.
 */
class RuntimeScope(
    val platform: PlatformAdapter,
    val compositionRoot: RuntimeCompositionRoot,
) : AutoCloseable {
    val coroutineScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    var isDisposed: Boolean = false
        private set

    override fun close() {
        if (isDisposed) return
        isDisposed = true
        coroutineScope.cancel()
    }
}

/**
 * Project-lifetime scope created on project open and deterministically disposed on close (RFC-0048 §Lifecycles).
 *
 * Closes the project SQLite driver, cancels project-scoped coroutines, and invokes [onDispose]
 * (e.g. lock release per RFC-0055).
 */
class ProjectScope(
    val projectId: ProjectId,
    val projectDriver: SqlDriver,
    private val onDispose: () -> Unit = {},
) : AutoCloseable {
    val coroutineScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    var isDisposed: Boolean = false
        private set

    override fun close() {
        if (isDisposed) return
        isDisposed = true
        coroutineScope.cancel()
        runCatching { onDispose() }
        runCatching { projectDriver.close() }
    }
}

/**
 * Per-Run scope created when a Run starts and disposed at terminal state (RFC-0048 §Lifecycles).
 */
class RunScope(
    val runId: RunId,
    private val onDispose: () -> Unit = {},
) : AutoCloseable {
    val coroutineScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    var isDisposed: Boolean = false
        private set

    override fun close() {
        if (isDisposed) return
        isDisposed = true
        coroutineScope.cancel()
        runCatching { onDispose() }
    }
}
