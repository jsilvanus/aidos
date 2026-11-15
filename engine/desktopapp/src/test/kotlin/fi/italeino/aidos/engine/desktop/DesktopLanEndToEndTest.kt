package fi.italeino.aidos.engine.desktop

import fi.italeino.aidos.engine.approval.AppApprovalStatus
import dev.aidos.tcr.engine.LanPairingUi
import fi.italeino.aidos.sdk.client.LanPairing
import fi.italeino.aidos.sdk.client.LanPairingUri
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** RFC-0051 through the real desktop host: switch it on in the pane's model, pair with the SDK, and the approvals still apply. */
class DesktopLanEndToEndTest {
    private val root = Files.createTempDirectory("aidos-desktop-lan").toFile()
    private val host = DesktopEngineHost(
        modelsDir = root.resolve("models").apply { mkdirs() },
        stateDir = root,
        serverPort = { 0 },
        discoveryFile = root.resolve("engine.json"),
    )

    @AfterTest
    fun cleanUp() {
        runBlocking { host.close() }
        root.deleteRecursively()
    }

    private fun eventually(what: String, check: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) { if (check()) return; Thread.sleep(20) }
        throw AssertionError("timed out waiting for: $what")
    }

    private fun get(client: okhttp3.OkHttpClient, credential: fi.italeino.aidos.sdk.client.LanCredential, path: String) =
        client.newCall(LanPairing.request(credential, path).build()).execute().use { it.code }

    @Test
    fun `off by default, on through the model, paired by the SDK, and revoked in Connected Apps`(): Unit = runBlocking {
        host.start()
        assertTrue(host.isRunning.value, "start failed: ${host.lastError.value}")
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
        val model = DesktopLanSharingModel(scope) { LocalLanControl(host.lanSharing) }
        assertFalse(model.state.value.enabled)
        assertNull(host.lanSharing.boundPort, "no LAN listener until the user turns it on")

        host.lanSharing.setEnabled(true, port = 0) // 0: any free port; the pane's default is 18443
        model.refresh()
        eventually("enabled shown") { model.state.value.enabled && model.state.value.fingerprintSha256 != null }
        val port = assertNotNull(host.lanSharing.boundPort)
        assertNotNull(model.state.value.fingerprintSha256)

        model.addByCode("kitchen tablet", 1)
        eventually("pairing shown") { model.state.value.pairing != null }
        val pairing = model.state.value.pairing as LanPairingUi.Code
        val uri = assertNotNull(LanPairingUri.parse(pairing.uri)).copy(host = "127.0.0.1")
        assertEquals(port, uri.port)

        val credential = LanPairing.redeem(uri)
        val client = LanPairing.pinnedClient(credential.fingerprintSha256)
        assertEquals(200, get(client, credential, "/v1/models"))

        // The device is in the same approval store as every app: approved for models, agent access off.
        val record = assertNotNull(host.approvalStore.listAllApprovals().firstOrNull { it.displayName.contains("kitchen tablet") })
        assertEquals(AppApprovalStatus.APPROVED, record.status)
        assertFalse(record.agentAllowed)
        // Agent routes are mounted on the LAN too, and refused without the agent grant.
        assertEquals(403, get(client, credential, "/v1/agent/runs"))

        // The user revokes it under Connected Apps: the next request is refused.
        host.approvalStore.revokeApproval(record.packageName)
        assertEquals(401, get(client, credential, "/v1/models"))

        // Turning sharing off closes the door.
        host.lanSharing.setEnabled(false)
        assertNull(host.lanSharing.boundPort)
    }

    @Test
    fun `the same pane works over the Engine's loopback admin API, for the local client only`(): Unit = runBlocking {
        host.start()
        assertTrue(host.isRunning.value, "start failed: ${host.lastError.value}")
        val running = assertNotNull(EngineAttach.runningEngine(file = root.resolve("engine.json"), selfPid = -1))
        val control = fi.italeino.aidos.engine.control.HttpLanControl(running.baseUrl, running.token)

        assertFalse(control.status().enabled)
        val on = control.setEnabled(true, 0)
        assertTrue(on.enabled)
        assertNotNull(on.boundPort)
        assertNotNull(on.fingerprintSha256)

        val link = control.startCodePairing("laptop", 600)
        assertTrue(link.startsWith("aidos-pair://"))
        val manual = control.addManually("tablet", 600)
        assertTrue(control.status().devices.any { it.id == manual.deviceId })
        control.revoke(manual.deviceId)
        assertTrue(control.status().devices.single { it.id == manual.deviceId }.revoked)

        // A wrong token is refused, and an approved app's token is not an admin.
        val wrong = fi.italeino.aidos.engine.control.HttpLanControl(running.baseUrl, "not-the-token")
        assertFailsWith<fi.italeino.aidos.engine.control.EngineControlException> { wrong.status() }

        // The management API is not on the LAN listener.
        val credential = LanPairing.redeem(assertNotNull(LanPairingUri.parse(link)).copy(host = "127.0.0.1"))
        val lanClient = LanPairing.pinnedClient(credential.fingerprintSha256)
        assertEquals(404, get(lanClient, credential, "/v1/admin/lan"))

        host.lanSharing.setEnabled(false)
    }
}

class EngineAttachTest {
    @Test
    fun `only another live Engine process counts as a running Engine`() {
        val dir = java.nio.file.Files.createTempDirectory("attach-engine").toFile()
        val f = dir.resolve("engine.json")
        assertNull(EngineAttach.runningEngine(f, selfPid = 1), "no file")
        f.writeText("""{"status":"READY","baseUrl":"http://127.0.0.1:1","token":"t","pid":4242}""")
        assertNotNull(EngineAttach.runningEngine(f, selfPid = 1, isAlive = { true }))
        assertNull(EngineAttach.runningEngine(f, selfPid = 4242, isAlive = { true }), "our own Engine writes this file too")
        assertNull(EngineAttach.runningEngine(f, selfPid = 1, isAlive = { false }), "left behind by a crash")
        f.writeText("""{"status":"STARTING","baseUrl":"http://127.0.0.1:1","token":"t","pid":4242}""")
        assertNull(EngineAttach.runningEngine(f, selfPid = 1, isAlive = { true }))
    }
}
