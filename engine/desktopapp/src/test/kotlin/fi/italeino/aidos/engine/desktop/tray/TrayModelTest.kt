package fi.italeino.aidos.engine.desktop.tray

import fi.italeino.aidos.engine.control.ApprovalsControl
import fi.italeino.aidos.engine.control.PendingApproval
import fi.italeino.aidos.engine.desktop.RunningEngine
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private class FakeApprovals(var items: List<PendingApproval> = emptyList(), var failing: Boolean = false) : ApprovalsControl {
    override suspend fun pending(): List<PendingApproval> = if (failing) throw IllegalStateException("down") else items
    override suspend fun decide(id: String, allow: Boolean) = true
}

private fun ask(id: String) = PendingApproval(id, "tool", "Laptop wants chat to run shell", "detail")

class TrayModelTest {
    @Test
    fun `each new approval toasts once and a resolved one closes its popup`(): Unit = runBlocking {
        val fake = FakeApprovals()
        val w = ApprovalWatcher { fake }
        assertEquals(ServiceState.RUNNING, w.poll().engine)

        fake.items = listOf(ask("tool:1"))
        val first = w.poll()
        assertEquals(listOf("tool:1"), first.raised.map { it.id })
        assertEquals("Aidos: 1 waiting for your answer", first.tooltip())
        assertTrue(w.poll().raised.isEmpty(), "the same item does not toast again")

        fake.items = listOf(ask("tool:1"), ask("tool:2"))
        assertEquals(listOf("tool:2"), w.poll().raised.map { it.id })

        fake.items = listOf(ask("tool:2"))
        val done = w.poll()
        assertEquals(setOf("tool:1"), done.resolvedIds)
        assertTrue(done.raised.isEmpty())
    }

    @Test
    fun `a failed poll keeps what the user is looking at and does not toast it again`(): Unit = runBlocking {
        val fake = FakeApprovals(listOf(ask("tool:1")))
        val w = ApprovalWatcher { fake }
        w.poll()
        fake.failing = true
        val blip = w.poll()
        assertEquals(ServiceState.UNREACHABLE, blip.engine)
        assertEquals(listOf("tool:1"), blip.pending.map { it.id })
        assertTrue(blip.resolvedIds.isEmpty())
        fake.failing = false
        val back = w.poll()
        assertEquals(ServiceState.RUNNING, back.engine)
        assertTrue(back.raised.isEmpty())
    }

    @Test
    fun `a stopped Engine closes every popup and says so`(): Unit = runBlocking {
        var control: ApprovalsControl? = FakeApprovals(listOf(ask("tool:1")))
        val w = ApprovalWatcher { control }
        w.poll()
        control = null
        val s = w.poll()
        assertEquals(ServiceState.STOPPED, s.engine)
        assertEquals(setOf("tool:1"), s.resolvedIds)
        assertEquals("Aidos: Engine is not running", s.tooltip())
    }

    @Test
    fun `toasts carry who is asking but nothing else`() {
        val s = TraySnapshot(ServiceState.RUNNING, listOf(ask("tool:1")), listOf(ask("tool:1")), emptySet())
        assertFalse(s.tooltip().contains("shell"), "the tooltip shows counts, not content")
    }
}

class TrayActionsTest {
    @Test
    fun `start launches a headless Engine only when none is running`() {
        val launched = mutableListOf<List<String>>()
        val none = TrayActions(running = { null }, launch = { launched += it })
        assertTrue(none.startEngine())
        assertEquals(listOf(listOf("--daemon")), launched)

        launched.clear()
        val up = TrayActions(running = { RunningEngine("http://127.0.0.1:1", "t", 1) }, launch = { launched += it })
        assertFalse(up.startEngine())
        assertTrue(launched.isEmpty())
        up.openWindow()
        assertEquals(listOf(emptyList<String>()), launched)
    }

    @Test
    fun `stopping with no Engine running is a no-op`() {
        assertFalse(TrayActions(running = { null }, launch = {}).stopEngine())
    }

    @Test
    fun `the relaunch command runs this class with the given arguments`() {
        val cmd = TrayActions.selfCommand(listOf("--daemon"))
        assertEquals("--daemon", cmd.last())
        assertTrue(cmd.contains("fi.italeino.aidos.engine.desktop.MainKt"))
    }
}

class CompositeApprovalsTest {
    private class Src(val items: List<PendingApproval>, val fail: Boolean = false, val accepts: Set<String> = emptySet()) : ApprovalsControl {
        override suspend fun pending() = if (fail) throw IllegalStateException("down") else items
        override suspend fun decide(id: String, allow: Boolean) = id in accepts
    }

    @Test
    fun `one failing source does not hide the other, all failing throws`(): Unit = runBlocking {
        val ok = Src(listOf(ask("cap:1")))
        assertEquals(listOf("cap:1"), CompositeApprovals(listOf(Src(emptyList(), fail = true), ok)).pending().map { it.id })
        var threw = false
        try { CompositeApprovals(listOf(Src(emptyList(), true), Src(emptyList(), true))).pending() } catch (_: IllegalStateException) { threw = true }
        assertTrue(threw)
    }

    @Test
    fun `a decision reaches the source that owns the id`(): Unit = runBlocking {
        val c = CompositeApprovals(listOf(Src(emptyList(), accepts = setOf("tool:1")), Src(emptyList(), accepts = setOf("cap:2"))))
        assertTrue(c.decide("cap:2", true))
        assertTrue(c.decide("tool:1", false))
        assertFalse(c.decide("gone", true))
    }

    @Test
    fun `the agent source answers a capability request and treats a vanished one as not waiting`(): Unit = runBlocking {
        val calls = mutableListOf<String>()
        val client = dev.aidos.api.MockRuntimeClient()
        val src = AgentApprovalsControl(client)
        assertFalse(src.decide("tool:9", true), "not its id")
        assertTrue(src.pending().none { it.title.contains("reason") })
        calls.clear()
    }
}
