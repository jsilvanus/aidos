package fi.italeino.aidos.engine.desktop

import fi.italeino.aidos.engine.approval.AppApprovalStatus
import fi.italeino.aidos.engine.control.EngineControlException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** RFC-0115 apps group: the same decisions in-process and through the loopback admin API of a running Engine. */
class AppsControlTest {
    private val root = Files.createTempDirectory("aidos-apps-control").toFile()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val host = DesktopEngineHost(
        modelsDir = root.resolve("models").apply { mkdirs() },
        stateDir = root,
        serverPort = { 0 },
        discoveryFile = root.resolve("engine.json"),
    )

    @AfterTest
    fun tearDown() {
        runBlocking { host.close() }
        scope.cancel()
        root.deleteRecursively()
    }

    private fun eventually(what: String, check: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) { if (check()) return; Thread.sleep(20) }
        throw AssertionError("timed out waiting for: $what")
    }

    @Test
    fun `an app is approved, limited, allowed to run agents and revoked through the admin API`(): Unit = runBlocking {
        host.start()
        host.approvalStore.recordFirstHandshake("dev.test.app", "Test app")
        val running = assertNotNull(EngineAttach.runningEngine(root.resolve("engine.json"), selfPid = -1))
        val apps = EngineAttach.appsControl(host, running)

        assertEquals(AppApprovalStatus.PENDING, apps.list().single { it.packageName == "dev.test.app" }.status)
        assertTrue(apps.approve("dev.test.app"))
        assertEquals(AppApprovalStatus.APPROVED, host.approvalStore.getApproval("dev.test.app")!!.status, "the real store changed")
        assertTrue(apps.setAgentAllowed("dev.test.app", true))
        assertTrue(apps.setLocalityLimit("dev.test.app", "local_only"))
        val rec = apps.list().single { it.packageName == "dev.test.app" }
        assertTrue(rec.agentAllowed)
        assertEquals("local_only", rec.localityLimit)

        assertFailsWith<EngineControlException> { apps.setLocalityLimit("dev.test.app", "anywhere") }
        assertFalse(apps.approve("no.such.app"), "an unknown app changes nothing")

        assertTrue(apps.revoke("dev.test.app"))
        assertFalse(apps.list().single { it.packageName == "dev.test.app" }.agentAllowed, "revoking clears agent use")
        assertTrue(apps.deny("dev.test.app"))
        assertTrue(apps.undoDeny("dev.test.app"))
        assertEquals(AppApprovalStatus.PENDING, apps.list().single { it.packageName == "dev.test.app" }.status)
    }

    @Test
    fun `the Connected Apps presenter works over the admin API`(): Unit = runBlocking {
        host.start()
        val running = assertNotNull(EngineAttach.runningEngine(root.resolve("engine.json"), selfPid = -1))
        val model = DesktopAppsModel(host, scope, pollMillis = 50, control = { EngineAttach.appsControl(host, running) })
        model.simulateHandshake("Kitchen tablet")
        eventually("app listed") { model.approvals.value.any { it.displayName == "Kitchen tablet" } }
        val id = model.approvals.value.single { it.displayName == "Kitchen tablet" }.packageName
        model.approve(id)
        eventually("approved") { model.approvals.value.single { it.packageName == id }.status == AppApprovalStatus.APPROVED }
        model.setAgentAllowed(id, true)
        eventually("agent allowed") { model.approvals.value.single { it.packageName == id }.agentAllowed }
    }

    @Test
    fun `the app does not start its own Engine while a service already runs`() {
        val live = RunningEngine("http://127.0.0.1:1", "t", 7)
        assertFalse(EngineAttach.shouldStartOwnEngine(autoStart = true, running = live))
        assertTrue(EngineAttach.shouldStartOwnEngine(autoStart = true, running = null))
        assertFalse(EngineAttach.shouldStartOwnEngine(autoStart = false, running = null))
    }
}
