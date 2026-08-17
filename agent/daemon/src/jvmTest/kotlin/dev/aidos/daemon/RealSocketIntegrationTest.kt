package dev.aidos.daemon

import dev.aidos.api.CreateProjectRequest
import dev.aidos.api.CreateSessionRequest
import dev.aidos.api.EventFilter
import dev.aidos.api.GrantCapabilityRequest
import dev.aidos.api.ProjectLocation
import dev.aidos.api.ProjectResult
import dev.aidos.api.SessionResult
import dev.aidos.api.socket.SocketPaths
import dev.aidos.cli.AidosCli
import dev.aidos.cli.SocketConnectionException
import dev.aidos.cli.SocketRuntimeClient
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Real end-to-end socket transport test (M10, RFC-0052/RFC-0004/RFC-0055).
 *
 * `DaemonCliIntegrationTest` drives `AidosCli` against an in-process `RuntimeClient` -- its own
 * doc comment already says so. `CliFrontendTest`'s M10 tests drive it against
 * `MockRuntimeClient`. Neither exercises the thing the 2026-08-10 audit actually flagged missing:
 * *"the only `fun main()`... is the daemon's, not a CLI"* and *"`RuntimeSocketServer.start()`...
 * never opens a socket."*
 *
 * This test spawns `dev.aidos.daemon.MainKt` as a genuine separate OS process, waits for it to
 * open a real Unix domain socket, and drives every command through `SocketRuntimeClient` --
 * actual `ServerSocketChannel`/`SocketChannel` I/O, actual JSON on the wire, actual token
 * handshake. If the socket transport regresses to a stub, this is the test that fails.
 */
class RealSocketIntegrationTest {

    private val tempHome = Files.createTempDirectory("aidos-e2e-home").toFile()
    private val tempRuntimeDir = Files.createTempDirectory("aidos-e2e-runtime").toFile()
    private var daemonProcess: Process? = null

    @AfterTest
    fun cleanup() {
        daemonProcess?.destroyForcibly()?.also { it.waitFor() }
        tempHome.deleteRecursively()
        tempRuntimeDir.deleteRecursively()
    }

    /**
     * [anthropicApiKey] set to a non-null placeholder is enough to make [PolicyInferenceRouter]
     * name a remote candidate adapter — `AnthropicAdapter.invoke()` (a real network call) is only
     * ever reached once a MODEL_CALL task actually runs past routing, and every test below stops
     * at a `RemotePendingApproval` park or its denial, never at an approval that would resume as
     * far as invoking the adapter. That resume path (approval → the named adapter actually
     * invoked) is exercised without a real network call in `AgentLoopTaskRunnerTest`'s
     * `fakeModel`-based suite instead — this class's job is proving the real wire transport, not
     * re-proving the mechanism a fake model already covers end to end.
     */
    private fun startDaemon(anthropicApiKey: String? = null): Path {
        val socketPath = tempRuntimeDir.toPath().resolve("aidos").resolve("runtime.sock")
        val javaBinary = Path.of(System.getProperty("java.home"), "bin", "java").toString()
        val classpath = System.getProperty("java.class.path")

        val builder = ProcessBuilder(
            javaBinary,
            "-Duser.home=${tempHome.path}",
            "-cp", classpath,
            "dev.aidos.daemon.MainKt",
            "--socket-path", socketPath.toString(),
        ).redirectErrorStream(true)
        if (anthropicApiKey != null) {
            builder.environment()["ANTHROPIC_API_KEY"] = anthropicApiKey
        }
        val process = builder.start()
        daemonProcess = process

        val tokenPath = SocketPaths.defaultTokenPath(socketPath)
        val deadlineMs = System.currentTimeMillis() + 30_000
        while (System.currentTimeMillis() < deadlineMs) {
            if (Files.exists(socketPath) && Files.exists(tokenPath)) return socketPath
            if (!process.isAlive) {
                val output = process.inputStream.bufferedReader().readText()
                error("daemon process exited early (code ${process.exitValue()}):\n$output")
            }
            Thread.sleep(100)
        }
        val output = process.inputStream.bufferedReader().readText()
        error("daemon did not open its socket within 30s. Output so far:\n$output")
    }

    @Test
    fun `real CLI over a real socket -- project, session, send, ping, version`() = runBlocking {
        val socketPath = startDaemon()
        val tokenPath = SocketPaths.defaultTokenPath(socketPath)
        val client = SocketRuntimeClient(socketPath, tokenPath, interactive = true)
        val cli = AidosCli(client)

        assertTrue(withTimeout(10_000) { cli.ping() }, "daemon must respond to ping over the real socket")

        val version = cli.version()
        assertTrue(version.isNotBlank())

        val projectId = cli.createProject("e2e-project", "created over a real socket")
        assertTrue(projectId.isNotBlank())

        val projects = cli.listProjects()
        assertTrue(projects.any { it.startsWith(projectId) }, "created project must appear in projects.list")

        val sessionId = cli.createSession(projectId, "e2e-session")
        assertTrue(sessionId.isNotBlank())

        val sessions = cli.listSessions(projectId)
        assertTrue(sessions.any { it.contains("e2e-session") })

        val runId = cli.sendMessage(sessionId, "hello over the real socket")
        assertTrue(runId.isNotBlank())
    }

    @Test
    fun `events subscribe delivers real events over the socket, sinceSequence replays the gap`() = runBlocking {
        val socketPath = startDaemon()
        val tokenPath = SocketPaths.defaultTokenPath(socketPath)
        val client = SocketRuntimeClient(socketPath, tokenPath, interactive = true)

        // Create a project first so there is at least one real event on the project's stream
        // before we ever subscribe -- this is what sinceSequence=0 needs to replay.
        val projectResult = client.projects.create(
            CreateProjectRequest("event-project", "", ProjectLocation.RuntimeManaged("event-project"))
        )
        val projectId = (projectResult as ProjectResult.Success).project.id
        client.sessions.create(CreateSessionRequest(projectId, "sess-1"))

        val events = withTimeout(10_000) {
            client.events.subscribe(EventFilter(projectIds = listOf(projectId), sinceSequence = 0L))
                .take(1)
                .toList()
        }
        assertEquals(1, events.size, "sinceSequence=0 must replay the SessionCreated event, not start from live-only")
    }

    @Test
    fun `a connection presenting the wrong token is rejected`() {
        val socketPath = startDaemon()
        val wrongTokenPath = tempRuntimeDir.toPath().resolve("wrong-token")
        Files.writeString(wrongTokenPath, "not-the-real-token")

        val client = SocketRuntimeClient(socketPath, wrongTokenPath, interactive = true)
        assertFailsWith<SocketConnectionException> {
            runBlocking { withTimeout(10_000) { client.runtime.ping() } }
        }
    }

    @Test
    fun `a non-interactive connection is refused for grant`() {
        val socketPath = startDaemon()
        val tokenPath = SocketPaths.defaultTokenPath(socketPath)

        val sessionId = runBlocking {
            val setup = SocketRuntimeClient(socketPath, tokenPath, interactive = true)
            val projectResult = setup.projects.create(
                CreateProjectRequest("interactive-test", "", ProjectLocation.RuntimeManaged("interactive-test"))
            )
            val projectId = (projectResult as ProjectResult.Success).project.id
            val sessionResult = setup.sessions.create(CreateSessionRequest(projectId, "sess"))
            (sessionResult as SessionResult.Success).session.id
        }

        val scripted = SocketRuntimeClient(socketPath, tokenPath, interactive = false)
        assertFailsWith<SocketConnectionException> {
            runBlocking {
                withTimeout(10_000) {
                    scripted.capabilities.grant(GrantCapabilityRequest(sessionId, "FS_WRITE"))
                }
            }
        }
    }

    @Test
    fun `sending under ASK egress parks the run, and deny-run fails it for real over the socket`() = runBlocking {
        // A non-null key is enough to give PolicyInferenceRouter a remote candidate to name in
        // RemotePendingApproval -- the declared default egress policy is ASK (RuntimeCompositionRoot's
        // own doc comment), and no Settings row overrides it for this fresh temp home.
        val socketPath = startDaemon(anthropicApiKey = "sk-test-not-a-real-key")
        val tokenPath = SocketPaths.defaultTokenPath(socketPath)
        val client = SocketRuntimeClient(socketPath, tokenPath, interactive = true)
        val cli = AidosCli(client)

        val projectId = cli.createProject("approval-e2e", "")
        val sessionId = cli.createSession(projectId, "approval-session")
        val runId = withTimeout(10_000) { cli.sendMessage(sessionId, "hello") }
        assertTrue(runId.isNotBlank())

        // sessions.send() drives the Run inline (RuntimeCompositionRoot), so by the time
        // sendMessage() returns over the socket, the Run has already reached RemotePendingApproval
        // and parked -- deny-run must find a real continuation to resolve.
        cli.denyRun(runId, "not right now")

        // The continuation is deleted once resolved (not left dangling) -- a second resolution
        // attempt against the same run must find nothing, proven here via approve-run, which
        // (unlike deny-run) surfaces the gateway's result instead of discarding it.
        val error = kotlin.runCatching { cli.approveRun(runId) }.exceptionOrNull()
        assertTrue(error != null, "approving an already-denied run must fail — its continuation is gone")
        assertTrue(
            error.message?.contains("continuation.not_found") == true,
            "expected a continuation.not_found error, got: ${error.message}",
        )
    }

    @Test
    fun `approving a run with no pending continuation reports an error over the socket`() = runBlocking {
        val socketPath = startDaemon()
        val tokenPath = SocketPaths.defaultTokenPath(socketPath)
        val client = SocketRuntimeClient(socketPath, tokenPath, interactive = true)
        val cli = AidosCli(client)

        val error = kotlin.runCatching { cli.approveRun("no-such-run") }.exceptionOrNull()
        assertTrue(error != null)
    }
}
