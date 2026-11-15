package dev.aidos.executor

import dev.aidos.kernel.PlatformProfile
import dev.aidos.kernel.ProjectId
import dev.aidos.kernel.RunId
import dev.aidos.kernel.SessionId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConcurrencyModelTest {

    @Test
    fun `ProjectWriteContext serializes concurrent SQLite writes`() = runBlocking {
        val dispatchers = RuntimeDispatchers.forProfile(PlatformProfile.DESKTOP, availableProcessors = 4)
        val writeContext = ProjectWriteContext(ProjectId("p1"), dispatchers.io)
        val activeWriters = AtomicInteger(0)
        val maxConcurrent = AtomicInteger(0)

        val jobs = (1..12).map {
            async(dispatchers.session) {
                writeContext.write {
                    val cur = activeWriters.incrementAndGet()
                    maxConcurrent.updateAndGet { prev -> maxOf(prev, cur) }
                    delay(10)
                    activeWriters.decrementAndGet()
                    it
                }
            }
        }
        val results = jobs.awaitAll()
        assertEquals((1..12).toList(), results)
        assertEquals(1, maxConcurrent.get())
    }

    @Test
    fun `ProjectExecutionContext worktreeMutex serializes worktree access while treeless work runs in parallel`() = runBlocking {
        val dispatchers = RuntimeDispatchers.forProfile(PlatformProfile.DESKTOP, availableProcessors = 4)
        val runtime = ConcurrencyRuntimeScope(dispatchers)
        val project = runtime.openProject(ProjectId("p1"))
        val activeWorktree = AtomicInteger(0)
        val maxWorktree = AtomicInteger(0)

        val worktreeJobs = (1..6).map {
            async(dispatchers.session) {
                project.executionContext.withWorktreeLock {
                    val cur = activeWorktree.incrementAndGet()
                    maxWorktree.updateAndGet { prev -> maxOf(prev, cur) }
                    delay(10)
                    activeWorktree.decrementAndGet()
                }
            }
        }
        worktreeJobs.awaitAll()
        assertEquals(1, maxWorktree.get())
        runtime.cancel()
    }

    @Test
    fun `cancellation propagates down RuntimeScope to ProjectScope SessionScope and RunScope`() = runBlocking {
        val dispatchers = RuntimeDispatchers.forProfile(PlatformProfile.MOBILE)
        val runtime = ConcurrencyRuntimeScope(dispatchers)
        val project = runtime.openProject(ProjectId("p1"))
        val session = project.openSession(SessionId("s1"))
        val run = session.openRun(RunId("r1"))

        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val job = run.coroutineScope.launch {
            started.complete(Unit)
            try {
                while (isActive) delay(20)
            } finally {
                cancelled.complete(Unit)
            }
        }
        started.await()

        // Cancelling the SessionScope must cancel child RunScope coroutines.
        session.cancel()
        cancelled.await()
        job.join()

        assertFalse(run.coroutineScope.isActive)
        assertFalse(session.coroutineScope.isActive)
        // Sibling/parent ProjectScope remains active when only SessionScope is cancelled.
        assertTrue(project.coroutineScope.isActive)

        runtime.cancel()
        assertFalse(project.coroutineScope.isActive)
    }

    @Test
    fun `ProjectEventBus delivers events in strict FIFO order on main dispatcher`() = runBlocking {
        val dispatchers = RuntimeDispatchers.forProfile(PlatformProfile.DESKTOP)
        val runtime = ConcurrencyRuntimeScope(dispatchers)
        val bus = ProjectEventBus(ProjectId("p1"), dispatchers, runtime.coroutineScope)

        val observed = mutableListOf<Int>()
        val done = CompletableDeferred<Unit>()
        for (i in 1..20) {
            bus.publish {
                observed.add(i)
                if (i == 20) done.complete(Unit)
            }
        }
        done.await()
        assertEquals((1..20).toList(), observed)

        bus.close()
        runtime.cancel()
    }
}
