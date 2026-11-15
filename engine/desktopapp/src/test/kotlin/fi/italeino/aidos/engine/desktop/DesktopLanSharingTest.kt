package fi.italeino.aidos.engine.desktop

import fi.italeino.aidos.engine.approval.AppApprovalStatus
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** RFC-0051 on the desktop: LAN is off by default, and an added device goes through the same approvals as any app. */
class DesktopLanSharingTest {
    private fun newSharing(): Triple<DesktopLanSharing, FileAppApprovalStore, java.io.File> {
        val dir = Files.createTempDirectory("lan-desktop").toFile()
        val store = FileAppApprovalStore(dir.resolve("approvals.json"))
        return Triple(DesktopLanSharing(dir, store), store, dir)
    }

    @Test
    fun `LAN sharing is off by default and the choice persists`() {
        val (lan, _, dir) = newSharing()
        assertFalse(lan.isEnabled)
        lan.setEnabled(true, port = 18444)
        val reloaded = DesktopLanSharing(dir, FileAppApprovalStore(dir.resolve("approvals.json")))
        assertTrue(reloaded.isEnabled)
        assertEquals(18444, reloaded.port)
    }

    @Test
    fun `adding a device approves it for model use but not for agent use`() = runBlocking {
        val (lan, store, _) = newSharing()
        val manual = lan.addManually("kitchen tablet", 3600)
        val subject = fi.italeino.aidos.engine.lan.ExternalDeviceGrant.subjectFor(manual.deviceId)
        val record = assertNotNull(store.getApproval(subject))
        assertEquals(AppApprovalStatus.APPROVED, record.status)
        assertFalse(record.agentAllowed, "agent use is a separate, later grant")
        assertTrue(record.displayName.contains("kitchen tablet"))
    }

    @Test
    fun `code pairing registers the device for approval on redemption`() = runBlocking {
        val (lan, store, _) = newSharing()
        val code = lan.startCodePairing("phone", 3600)
        val issued = assertNotNull(lan.pairing.redeem(code))
        assertEquals(AppApprovalStatus.APPROVED, store.getApproval(issued.grant.subject)?.status)
        assertNull(lan.pairing.redeem(code), "a code is single use")
    }

    @Test
    fun `revoking a device ends both its credential and its approval`() = runBlocking {
        val (lan, store, _) = newSharing()
        val manual = lan.addManually("old laptop", 3600)
        val subject = fi.italeino.aidos.engine.lan.ExternalDeviceGrant.subjectFor(manual.deviceId)
        assertNotNull(lan.devices.authenticate(manual.credential))
        lan.revoke(manual.deviceId)
        assertNull(lan.devices.authenticate(manual.credential))
        assertTrue(store.getApproval(subject)?.status != AppApprovalStatus.APPROVED)
    }
}
