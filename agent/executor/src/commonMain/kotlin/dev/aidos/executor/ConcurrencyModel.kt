package dev.aidos.executor

import dev.aidos.kernel.PlatformProfile
import dev.aidos.kernel.ProjectId
import dev.aidos.kernel.RunId
import dev.aidos.kernel.SessionId
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Core dispatcher bundle injected throughout the runtime (RFC-0007).
 *
 * - [main]: dedicated single-threaded dispatcher for inbound commands, outbound events, and state machines.
 * - [session]: bounded pool (`min(4, cores)` on DESKTOP/HEADLESS_SERVER, single-threaded on MOBILE).
 * - [io]: I/O dispatcher for SQLite, filesystem, Git, and network adapters.
 * - [background]: low-priority bounded pool for indexing, compaction, and background maintenance.
 */
class RuntimeDispatchers(
    val main: CoroutineDispatcher,
    val session: CoroutineDispatcher,
    val io: CoroutineDispatcher,
    val background: CoroutineDispatcher,
) {
    companion object {
        @OptIn(ExperimentalCoroutinesApi::class)
        fun forProfile(
            profile: PlatformProfile,
            availableProcessors: Int = 4,
        ): RuntimeDispatchers {
            val sessionParallelism = when (profile) {
                PlatformProfile.MOBILE -> 1
                PlatformProfile.DESKTOP, PlatformProfile.HEADLESS_SERVER ->
                    minOf(4, availableProcessors.coerceAtLeast(1))
            }
            return RuntimeDispatchers(
                main = Dispatchers.Default.limitedParallelism(1),
                session = Dispatchers.Default.limitedParallelism(sessionParallelism),
                io = Dispatchers.IO,
                background = Dispatchers.Default.limitedParallelism(2),
            )
        }
    }
}

/**
 * Single-writer SQLite discipline per project (RFC-0007).
 *
 * All writes to a project's database serialize through [writeMutex] on [ioDispatcher],
 * while WAL mode allows concurrent reads.
 */
class ProjectWriteContext(
    val projectId: ProjectId,
    private val ioDispatcher: CoroutineDispatcher,
) {
    private val writeMutex = Mutex()

    suspend fun <T> write(block: suspend () -> T): T =
        writeMutex.withLock {
            withContext(ioDispatcher) {
                block()
            }
        }
}

/**
 * Per-project execution context (RFC-0007).
 *
 * [worktreeMutex] guards the working tree and `.git/index` only — treeless workers
 * (`refs/aidos/workers/<id>`) do not acquire it and run genuinely in parallel.
 */
class ProjectExecutionContext(
    val projectId: ProjectId,
    val worktreeMutex: Mutex = Mutex(),
    val writeContext: ProjectWriteContext,
    val scope: CoroutineScope,
) {
    suspend fun <T> withWorktreeLock(block: suspend () -> T): T =
        worktreeMutex.withLock { block() }
}

/**
 * Structured coroutine scope hierarchy (RFC-0007 §Cancellation Propagation):
 * `RuntimeScope -> ProjectScope(projectId) -> SessionScope(sessionId) -> RunScope(runId)`.
 */
class ConcurrencyRuntimeScope(
    val dispatchers: RuntimeDispatchers,
    parentJob: Job? = null,
) {
    private val job = SupervisorJob(parentJob)
    val coroutineScope: CoroutineScope = CoroutineScope(job + dispatchers.main)

    fun openProject(projectId: ProjectId): ConcurrencyProjectScope =
        ConcurrencyProjectScope(projectId, dispatchers, job)

    fun cancel() {
        coroutineScope.cancel()
    }
}

class ConcurrencyProjectScope(
    val projectId: ProjectId,
    val dispatchers: RuntimeDispatchers,
    parentJob: Job,
) {
    private val job = SupervisorJob(parentJob)
    val coroutineScope: CoroutineScope = CoroutineScope(job + dispatchers.session)
    val executionContext: ProjectExecutionContext = ProjectExecutionContext(
        projectId = projectId,
        writeContext = ProjectWriteContext(projectId, dispatchers.io),
        scope = coroutineScope,
    )

    fun openSession(sessionId: SessionId): ConcurrencySessionScope =
        ConcurrencySessionScope(sessionId, dispatchers, job)

    fun cancel() {
        coroutineScope.cancel()
    }
}

class ConcurrencySessionScope(
    val sessionId: SessionId,
    val dispatchers: RuntimeDispatchers,
    parentJob: Job,
) {
    private val job = SupervisorJob(parentJob)
    val coroutineScope: CoroutineScope = CoroutineScope(job + dispatchers.session)

    fun openRun(runId: RunId): ConcurrencyRunScope =
        ConcurrencyRunScope(runId, dispatchers, job)

    fun cancel() {
        coroutineScope.cancel()
    }
}

class ConcurrencyRunScope(
    val runId: RunId,
    val dispatchers: RuntimeDispatchers,
    parentJob: Job,
) {
    private val job = SupervisorJob(parentJob)
    val coroutineScope: CoroutineScope = CoroutineScope(job + dispatchers.session)

    fun cancel() {
        coroutineScope.cancel()
    }
}

/**
 * Per-project FIFO Event Bus processor on [RuntimeDispatchers.main] (RFC-0007 §Event Bus Ordering).
 */
class ProjectEventBus(
    val projectId: ProjectId,
    dispatchers: RuntimeDispatchers,
    scope: CoroutineScope,
) {
    private val channel = Channel<suspend () -> Unit>(Channel.UNLIMITED)

    init {
        scope.launch(dispatchers.main) {
            for (delivery in channel) {
                delivery()
            }
        }
    }

    suspend fun publish(deliver: suspend () -> Unit) {
        channel.send(deliver)
    }

    fun close() {
        channel.close()
    }
}
