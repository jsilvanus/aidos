package dev.aidos.cli

import dev.aidos.api.socket.SocketPaths
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import kotlin.system.exitProcess

/**
 * The Aidos CLI executable (M10, RFC-0052, RFC-0004).
 *
 * ```
 * aidos-cli [--socket-path PATH] <command> [args...]
 *
 * Commands:
 *   create-project <name> [description]
 *   list-projects
 *   create-session <projectId> <name>
 *   list-sessions <projectId>
 *   send <sessionId> <message>
 *   watch-events [--since SEQUENCE]
 *   grant <sessionId> <permission> [scope]
 *   list-pending
 *   approve <requestId>
 *   ping
 *   version
 * ```
 *
 * Connects to a running `aidos-daemon` over the socket at `--socket-path` (default
 * `$XDG_RUNTIME_DIR/aidos/runtime.sock`), authenticating with the daemon's connection token
 * (RFC-0052 Authentication) read from alongside it. There is no in-process fallback here — a
 * person typing this at a terminal is exactly the gap M10's audit finding named: this file is
 * what makes M10's done-when literally true "from the CLI," not just as a library call in a
 * test. `AidosCli` itself is transport-agnostic (it is also driven in-process by
 * `MockRuntimeClient`/`RealRuntimeClient` in tests); this file is only the argv/process boundary
 * around it.
 */
fun main(args: Array<String>) {
    var socketPath: Path? = null
    val rest = mutableListOf<String>()

    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--socket-path" -> {
                if (i + 1 >= args.size) fail("--socket-path requires an argument")
                socketPath = Path.of(args[i + 1])
                i += 2
            }
            else -> {
                rest.add(args[i])
                i += 1
            }
        }
    }

    if (rest.isEmpty() || rest[0] == "--help" || rest[0] == "-h") {
        printHelp()
        exitProcess(if (rest.isEmpty()) 1 else 0)
    }

    val resolvedSocketPath = socketPath ?: SocketPaths.defaultSocketPath()
    val client = SocketRuntimeClient(resolvedSocketPath, SocketPaths.defaultTokenPath(resolvedSocketPath))
    val cli = AidosCli(client)

    val command = rest[0]
    val commandArgs = rest.drop(1)

    try {
        runBlocking { dispatch(cli, command, commandArgs) }
    } catch (e: SocketConnectionException) {
        System.err.println("aidos-cli: could not reach the daemon: ${e.message}")
        System.err.println("Is 'aidos-daemon' running? (socket: $resolvedSocketPath)")
        exitProcess(1)
    } catch (e: IllegalStateException) {
        System.err.println("aidos-cli: ${e.message}")
        exitProcess(1)
    }
}

private suspend fun dispatch(cli: AidosCli, command: String, args: List<String>) {
    when (command) {
        "create-project" -> {
            requireArgs(args, 1, "create-project <name> [description]")
            println(cli.createProject(args[0], args.getOrElse(1) { "" }))
        }
        "list-projects" -> cli.listProjects().forEach(::println)
        "create-session" -> {
            requireArgs(args, 2, "create-session <projectId> <name>")
            println(cli.createSession(args[0], args[1]))
        }
        "list-sessions" -> {
            requireArgs(args, 1, "list-sessions <projectId>")
            cli.listSessions(args[0]).forEach(::println)
        }
        "send" -> {
            requireArgs(args, 2, "send <sessionId> <message>")
            println(cli.sendMessage(args[0], args.drop(1).joinToString(" ")))
        }
        "watch-events" -> {
            val since = args.indexOf("--since").takeIf { it >= 0 }?.let { args.getOrNull(it + 1)?.toLongOrNull() }
            coroutineScope {
                val job = cli.watchEvents(this, since)
                // The socket read underneath is a blocking call, not a suspension point, so
                // cancelling this Job is mostly documentation of intent -- what actually stops
                // the process on Ctrl+C/SIGTERM is the JVM halting once shutdown hooks return.
                Runtime.getRuntime().addShutdownHook(Thread { job.cancel() })
                job.join()
            }
        }
        "grant" -> {
            requireArgs(args, 2, "grant <sessionId> <permission> [scope]")
            println(cli.grantCapability(args[0], args[1], args.getOrNull(2)))
        }
        "list-pending" -> cli.listPendingCapabilities().forEach(::println)
        "approve" -> {
            requireArgs(args, 1, "approve <requestId>")
            println(cli.approveCapability(args[0]))
        }
        "ping" -> println(cli.ping())
        "version" -> println(cli.version())
        else -> fail("unknown command: $command (see --help)")
    }
}

private fun requireArgs(args: List<String>, min: Int, usage: String) {
    if (args.size < min) fail("usage: $usage")
}

private fun fail(message: String): Nothing {
    System.err.println("aidos-cli: $message")
    exitProcess(1)
}

private fun printHelp() {
    println(
        """
        aidos-cli [--socket-path PATH] <command> [args...]

        Commands:
          create-project <name> [description]
          list-projects
          create-session <projectId> <name>
          list-sessions <projectId>
          send <sessionId> <message>
          watch-events [--since SEQUENCE]
          grant <sessionId> <permission> [scope]
          list-pending
          approve <requestId>
          ping
          version

        --socket-path PATH   Daemon socket (default: ${'$'}XDG_RUNTIME_DIR/aidos/runtime.sock)
        """.trimIndent()
    )
}
