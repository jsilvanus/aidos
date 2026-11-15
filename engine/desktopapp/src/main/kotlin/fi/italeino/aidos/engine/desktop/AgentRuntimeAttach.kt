package fi.italeino.aidos.engine.desktop

import dev.aidos.api.RuntimeClient
import dev.aidos.api.socket.SocketPaths
import dev.aidos.cli.SocketRuntimeClient
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.nio.file.Path

/** Which Agent runtime the app is talking to (RFC-0115). */
sealed interface AgentConnection {
    val client: RuntimeClient

    /** A running Agent daemon answered on its socket. The app opens no project database itself. */
    data class Attached(override val client: RuntimeClient, val socketPath: Path) : AgentConnection

    /** No daemon is running: the app runs the Agent runtime in-process, as it always has. */
    data class InProcess(override val client: RuntimeClient) : AgentConnection
}

/**
 * Chooses between a running Agent daemon and an in-process runtime (RFC-0115 phase 1, D60).
 *
 * The rule that makes this safe: **when a daemon answers, the app never opens a project database in its own
 * process**, because the daemon holds the project locks and two writers on one SQLite file is the hazard this
 * whole change removes. The in-process runtime is therefore created only on the fallback path ([inProcess]
 * is not called when a daemon answers).
 *
 * A daemon that starts after the app has chosen in-process is not adopted silently (the app already holds the
 * databases); [connect] picks it up the next time it is called, i.e. on reconnect or restart.
 */
object AgentRuntimeAttach {
    fun connect(
        socketPath: Path = SocketPaths.defaultSocketPath(),
        tokenPath: Path = SocketPaths.defaultTokenPath(socketPath),
        probe: (socketPath: Path, tokenPath: Path) -> RuntimeClient? = ::probeDaemon,
        inProcess: () -> RuntimeClient = { DesktopRuntimeClient.create() },
    ): AgentConnection =
        probe(socketPath, tokenPath)?.let { AgentConnection.Attached(it, socketPath) }
            ?: AgentConnection.InProcess(inProcess())

    /** A client for the daemon at [socketPath] if it answers a cheap request within a few seconds, else null. */
    fun probeDaemon(socketPath: Path, tokenPath: Path): RuntimeClient? {
        if (!Files.exists(socketPath) || !Files.isRegularFile(tokenPath)) return null
        // Interactive: this is a window a person is using, so an approval or a grant it sends is that person's click (RFC-0055
        // keeps scripts out, not a GUI). Without this the daemon refuses capability approvals from a process with no TTY.
        val client = SocketRuntimeClient(socketPath, tokenPath, interactive = true)
        return try {
            runBlocking { withTimeout(3_000) { client.projects.list() } }
            client
        } catch (_: TimeoutCancellationException) {
            null
        } catch (_: Exception) {
            null
        }
    }
}
