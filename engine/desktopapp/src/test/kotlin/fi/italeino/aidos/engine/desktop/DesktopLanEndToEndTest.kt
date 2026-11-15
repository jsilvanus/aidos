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

    private fun get(client: okhttp3.OkHttpClient, credential: fi.italeino.aidos.sdk.client.LanCredential, path: String) =
        client.newCall(LanPairing.request(credential, path).build()).execute().use { it.code }

    @Test
    fun `off by default, on through the model, paired by the SDK, and revoked in Connected Apps`(): Unit = runBlocking {
        host.start()
        assertTrue(host.isRunning.value, "start failed: ${host.lastError.value}")
        val model = DesktopLanSharingModel(host)
        assertFalse(model.state.value.enabled)
        assertNull(host.lanSharing.boundPort, "no LAN listener until the user turns it on")

        host.lanSharing.setEnabled(true, port = 0) // 0: any free port; the pane's default is 18443
        model.refresh()
        assertTrue(model.state.value.enabled)
        val port = assertNotNull(host.lanSharing.boundPort)
        assertNotNull(model.state.value.fingerprintSha256)

        model.addByCode("kitchen tablet", 1)
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
}
