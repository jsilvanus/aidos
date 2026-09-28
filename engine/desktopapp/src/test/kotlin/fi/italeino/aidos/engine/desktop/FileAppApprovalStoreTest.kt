package fi.italeino.aidos.engine.desktop

import fi.italeino.aidos.engine.approval.AppApprovalStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** RFC-0103 approval transitions for the desktop store, plus what the file adds: persistence. */
class FileAppApprovalStoreTest {
    private val dir: File = Files.createTempDirectory("aidos-approvals").toFile()
    private val file = File(dir, "approvals.json")

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively()
    }

    @Test
    fun firstHandshakeIsPending() = runTest {
        val record = FileAppApprovalStore(file).recordFirstHandshake("debug.app", "Debug App")
        assertEquals(AppApprovalStatus.PENDING, record.status)
        assertEquals(1, record.attemptCount)
        assertNull(record.decidedAt)
    }

    @Test
    fun approveDenyUndoRevokeFollowTheAndroidTransitions() = runTest {
        val store = FileAppApprovalStore(file)
        store.recordFirstHandshake("debug.app", "Debug App")

        assertEquals(AppApprovalStatus.APPROVED, store.approveApp("debug.app")?.status)
        assertEquals(AppApprovalStatus.PENDING, store.revokeApproval("debug.app")?.status)
        assertEquals(AppApprovalStatus.DENIED, store.denyApp("debug.app")?.status)
        // Revoke only applies to APPROVED; a denied app stays denied.
        assertEquals(AppApprovalStatus.DENIED, store.revokeApproval("debug.app")?.status)
        assertEquals(AppApprovalStatus.PENDING, store.undoDenyApp("debug.app")?.status)
    }

    @Test
    fun actionsOnUnknownAppsReturnNull() = runTest {
        val store = FileAppApprovalStore(file)
        assertNull(store.approveApp("missing"))
        assertNull(store.recordHandshakeAttempt("missing"))
    }

    @Test
    fun decisionsSurviveARestart() = runTest {
        FileAppApprovalStore(file).apply {
            recordFirstHandshake("debug.a", "A")
            recordFirstHandshake("debug.b", "B")
            approveApp("debug.a")
            updateRequestCount("debug.a", 3)
        }

        val reopened = FileAppApprovalStore(file)
        val a = assertNotNull(reopened.getApproval("debug.a"))
        assertEquals(AppApprovalStatus.APPROVED, a.status)
        assertEquals(3, a.requestCount)
        assertEquals(AppApprovalStatus.PENDING, reopened.getApproval("debug.b")?.status)
    }

    @Test
    fun watchEmitsTheCurrentList() = runTest {
        val store = FileAppApprovalStore(file)
        store.recordFirstHandshake("debug.app", "Debug App")
        assertEquals(listOf("debug.app"), store.watchApprovals().first().map { it.packageName })
    }

    @Test
    fun unreadableFileIsKeptAsideNotOverwritten() = runTest {
        file.writeText("{ not json")
        val store = FileAppApprovalStore(file)

        assertTrue(store.listAllApprovals().isEmpty())
        assertEquals("{ not json", File(dir, "approvals.json.corrupt").readText())
    }
}
