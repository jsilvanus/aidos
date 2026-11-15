package fi.italeino.aidos.engine.agent

import dev.aidos.aidin.NodeId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

class ToolApprovalQueueTest {
    private val peer = NodeId("a".repeat(26))

    private suspend fun ToolApprovalQueue.awaitPending(): PendingToolApproval {
        repeat(200) { pending.value.firstOrNull()?.let { return it }; delay(10) }
        error("nothing became pending")
    }

    @Test
    fun `a pending request is shown, answered once, and removed`(): Unit = runBlocking {
        val q = ToolApprovalQueue(timeout = 5_000.milliseconds)
        val answer = CoroutineScope(Dispatchers.Default).async { q.request(peer, "dictator", "write_note") }
        val item = q.awaitPending()
        assertEquals("write_note", item.tool)
        assertEquals("dictator", item.app)
        assertTrue(q.decide(item.id, true))
        assertTrue(answer.await())
        assertTrue(q.pending.value.isEmpty())
        assertFalse(q.decide(item.id, false), "already answered")
    }

    @Test
    fun `a refusal and a silence both mean no`(): Unit = runBlocking {
        val q = ToolApprovalQueue(timeout = 150.milliseconds)
        val refused = CoroutineScope(Dispatchers.Default).async { q.request(peer, "app", "t1") }
        q.decide(q.awaitPending().id, false)
        assertFalse(refused.await())
        // Nobody answers: it times out as a refusal and the prompt disappears.
        assertFalse(q.request(peer, "app", "t2"))
        assertTrue(q.pending.value.isEmpty())
    }

    @Test
    fun `approval is per call, nothing is remembered`(): Unit = runBlocking {
        val q = ToolApprovalQueue(timeout = 150.milliseconds)
        val first = CoroutineScope(Dispatchers.Default).async { q.request(peer, "app", "write") }
        q.decide(q.awaitPending().id, true)
        assertTrue(first.await())
        assertFalse(q.request(peer, "app", "write"), "the next identical call asks again and, unanswered, is refused")
    }
}
