package fi.italeino.aidos.engine.desktop

import fi.italeino.aidos.engine.approval.AppApprovalStatus
import fi.italeino.aidos.engine.control.EngineControlException
import fi.italeino.aidos.engine.control.HttpApprovalsControl
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The tray's feed (RFC-0115, D59) over the real loopback admin API of a real host. */
class ApprovalsFeedTest {
    private val root = Files.createTempDirectory("aidos-approvals").toFile()
    private val host = DesktopEngineHost(
        modelsDir = root.resolve("models").apply { mkdirs() }, stateDir = root, serverPort = { 0 }, discoveryFile = root.resolve("engine.json"),
    )

    @AfterTest
    fun tearDown() { runBlocking { host.close() }; root.deleteRecursively() }

    @Test
    fun `a new app shows up as a pending approval, with no content, and approving it is one call`(): Unit = runBlocking {
        host.start()
        val running = assertNotNull(EngineAttach.runningEngine(root.resolve("engine.json"), selfPid = -1))
        val feed = HttpApprovalsControl(running.baseUrl, running.token)

        assertTrue(feed.pending().isEmpty())
        host.approvalStore.recordFirstHandshake("com.example.notes", "Notes")
        val item = feed.pending().single()
        assertEquals("app:com.example.notes", item.id)
        assertEquals("app", item.kind)
        assertTrue(item.title.contains("Notes"))

        assertTrue(feed.decide(item.id, true))
        assertEquals(AppApprovalStatus.APPROVED, host.approvalStore.getApproval("com.example.notes")?.status)
        assertTrue(feed.pending().isEmpty())
        assertFalse(feed.decide(item.id, false), "already answered")
        assertFalse(feed.decide("tool:no-such-id", true))
        assertFalse(feed.decide("nonsense", true))
    }

    @Test
    fun `a denial denies, and a wrong token is refused`(): Unit = runBlocking {
        host.start()
        val running = assertNotNull(EngineAttach.runningEngine(root.resolve("engine.json"), selfPid = -1))
        host.approvalStore.recordFirstHandshake("com.example.other", "Other")
        val feed = HttpApprovalsControl(running.baseUrl, running.token)
        assertTrue(feed.decide("app:com.example.other", false))
        assertEquals(AppApprovalStatus.DENIED, host.approvalStore.getApproval("com.example.other")?.status)
        assertFailsWith<EngineControlException> { HttpApprovalsControl(running.baseUrl, "wrong").pending() }
    }
}

class AdminShutdownTest {
    private fun post(url: String, token: String): Int {
        val r = java.net.http.HttpClient.newHttpClient().send(
            java.net.http.HttpRequest.newBuilder(java.net.URI(url)).header("Authorization", "Bearer $token").POST(java.net.http.HttpRequest.BodyPublishers.noBody()).build(),
            java.net.http.HttpResponse.BodyHandlers.ofString(),
        )
        return r.statusCode()
    }

    private fun hostWith(root: java.io.File, shutdown: (() -> Unit)?) = DesktopEngineHost(
        modelsDir = root.resolve("models").apply { mkdirs() }, stateDir = root, serverPort = { 0 },
        discoveryFile = root.resolve("engine.json"), onAdminShutdown = shutdown,
    )

    @Test
    fun `a headless Engine can be stopped through the admin API, by the local client only`(): Unit = runBlocking {
        val root = Files.createTempDirectory("aidos-shutdown").toFile()
        val latch = java.util.concurrent.CountDownLatch(1)
        val host = hostWith(root) { latch.countDown() }
        try {
            host.start()
            val running = assertNotNull(EngineAttach.runningEngine(root.resolve("engine.json"), selfPid = -1))
            assertEquals(401, post(running.baseUrl + "/v1/admin/shutdown", "wrong"))
            assertEquals(1, latch.count)
            assertEquals(202, post(running.baseUrl + "/v1/admin/shutdown", running.token))
            assertTrue(latch.await(5, java.util.concurrent.TimeUnit.SECONDS), "the host was asked to stop")
        } finally { host.close(); root.deleteRecursively() }
    }

    @Test
    fun `an Engine that lives in a window has no shutdown route`(): Unit = runBlocking {
        val root = Files.createTempDirectory("aidos-noshutdown").toFile()
        val host = hostWith(root, null)
        try {
            host.start()
            val running = assertNotNull(EngineAttach.runningEngine(root.resolve("engine.json"), selfPid = -1))
            assertEquals(404, post(running.baseUrl + "/v1/admin/shutdown", running.token))
        } finally { host.close(); root.deleteRecursively() }
    }
}
