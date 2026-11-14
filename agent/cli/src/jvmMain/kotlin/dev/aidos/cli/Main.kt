package dev.aidos.cli

import dev.aidos.api.RuntimeClient
import dev.aidos.api.socket.SocketPaths
import dev.aidos.identity.ProjectRegistry
import dev.aidos.identity.UuidV7Generator
import dev.aidos.kernel.ProjectId
import dev.aidos.portability.PortabilityCli
import dev.aidos.portability.ProjectRegistryPort
import dev.aidos.storage.AidosStorage
import dev.aidos.storage.DesktopPaths
import dev.aidos.portability.SecretScanner
import dev.aidos.vault.DesktopVault
import dev.aidos.vault.Redactor
import java.io.File
import java.time.Instant
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
 *   approve-run <runId>
 *   deny-run <runId> [reason]
 *   answer-run <runId> <answer...>
 *   ping
 *   version
 *   diagnose [--output PATH] [--include-trace] [--runs ID,...] [--include-sensitive]
 *   export <projectDir> [--output F] [--encrypt] [--sign KEY] ...   (RFC-0041; local, no daemon)
 *   import <archive> [--into DIR] [--rename|--replace] ...          (RFC-0041; local, no daemon)
 *   keygen <prefix> | keygen --vault NAME [pubfile]                 (RFC-0041; local, no daemon)
 *   secrets status|set|rotate|delete|list|expire|allow ...          (RFC-0035; local, no daemon)
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

    if (rest[0] in PortabilityCli.COMMANDS) exitProcess(runPortability(rest))
    if (rest[0] in SecretsCli.COMMANDS) exitProcess(SecretsCli({ DesktopVault.open() }, dev.aidos.portability.SystemCliIo).run(rest.drop(1)))

    val resolvedSocketPath = socketPath ?: SocketPaths.defaultSocketPath()
    val client = SocketRuntimeClient(resolvedSocketPath, SocketPaths.defaultTokenPath(resolvedSocketPath))
    val cli = AidosCli(client)

    val command = rest[0]
    val commandArgs = rest.drop(1)

    try {
        runBlocking { dispatch(cli, command, commandArgs, client) }
    } catch (e: SocketConnectionException) {
        System.err.println("aidos-cli: could not reach the daemon: ${e.message}")
        System.err.println("Is 'aidos-daemon' running? (socket: $resolvedSocketPath)")
        exitProcess(1)
    } catch (e: IllegalStateException) {
        System.err.println("aidos-cli: ${e.message}")
        exitProcess(1)
    }
}

internal data class DiagnoseCliArgs(
    val outputPath: String = "aidos-diagnostic.zip",
    val includeTrace: Boolean = false,
    val selectedRuns: List<String> = emptyList(),
    val includeSensitive: Boolean = false,
)

internal fun parseDiagnoseArgs(args: List<String>): DiagnoseCliArgs {
    var outputPath: String? = null
    var includeTrace = false
    val selectedRuns = mutableListOf<String>()
    var includeSensitive = false
    var i = 0
    while (i < args.size) {
        when (val arg = args[i]) {
            "--output", "-o" -> {
                if (i + 1 >= args.size) throw IllegalStateException("--output requires a path argument")
                outputPath = args[i + 1]
                i += 2
            }
            "--include-trace" -> {
                includeTrace = true
                i += 1
            }
            "--runs" -> {
                if (i + 1 >= args.size) throw IllegalStateException("--runs requires a comma-separated run ID list")
                args[i + 1].split(',').map { it.trim() }.filter { it.isNotEmpty() }.forEach(selectedRuns::add)
                i += 2
            }
            "--include-sensitive" -> {
                includeSensitive = true
                i += 1
            }
            else -> {
                if (arg.startsWith("-")) {
                    throw IllegalStateException("unknown option for diagnose: $arg")
                }
                if (outputPath == null) {
                    outputPath = arg
                } else {
                    throw IllegalStateException("unexpected extra argument for diagnose: $arg")
                }
                i += 1
            }
        }
    }
    return DiagnoseCliArgs(
        outputPath = outputPath ?: "aidos-diagnostic.zip",
        includeTrace = includeTrace,
        selectedRuns = selectedRuns,
        includeSensitive = includeSensitive,
    )
}

internal suspend fun dispatch(
    cli: AidosCli,
    command: String,
    args: List<String>,
    client: RuntimeClient? = null,
    homeDir: String = System.getProperty("user.home"),
) {
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
        "approve-run" -> {
            requireArgs(args, 1, "approve-run <runId>")
            println(cli.approveRun(args[0]))
        }
        "deny-run" -> {
            requireArgs(args, 1, "deny-run <runId> [reason]")
            cli.denyRun(args[0], args.drop(1).joinToString(" "))
            println("denied: run ${args[0]} failed")
        }
        "answer-run" -> {
            requireArgs(args, 2, "answer-run <runId> <answer...>")
            println(cli.answerRun(args[0], args.drop(1).joinToString(" ")))
        }
        "ping" -> println(cli.ping())
        "version" -> println(cli.version())
        "diagnose" -> {
            val parsed = parseDiagnoseArgs(args)
            val diagClient = client ?: fail("diagnose requires a RuntimeClient")
            DiagnoseCommand(runtimeClient = diagClient, homeDir = homeDir).diagnose(
                outputPath = parsed.outputPath,
                includeTrace = parsed.includeTrace,
                selectedRuns = parsed.selectedRuns,
                includeSensitive = parsed.includeSensitive,
            )
        }
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

internal fun helpText(): String =
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
          approve-run <runId>
          deny-run <runId> [reason]
          answer-run <runId> <answer...>
          ping
          version
          diagnose [--output PATH] [--include-trace] [--runs ID,...] [--include-sensitive]

        --socket-path PATH   Daemon socket (default: ${'$'}XDG_RUNTIME_DIR/aidos/runtime.sock)
    """.trimIndent()

private fun printHelp() {
    println(helpText())
}

/**
 * RFC-0041: export/import work on files, so they run in this process. The registry port opens
 * the user-scope user.db lazily, and only for `import` -- an export touches no user state.
 */
private fun runPortability(args: List<String>): Int {
    val ids = UuidV7Generator()
    val userDb by lazy {
        File(DesktopPaths.userDb()).parentFile.mkdirs()
        AidosStorage.openUser(DesktopPaths.userDb(), "cli") { Instant.now().toString() }
    }
    val registry = object : ProjectRegistryPort {
        private val reg by lazy { ProjectRegistry(userDb.driver, ids) }
        override fun isRegistered(projectId: String) = reg.isRegistered(ProjectId(projectId))
        override fun register(projectId: String, path: String, nowIso: String) =
            reg.register(ProjectId(projectId), path, nowIso = nowIso)
    }
    return PortabilityCli.run(args, registry, newId = { ids.next() }, keys = VaultSigningKeys { DesktopVault.open() }, scanner = exportScanner())
}

/**
 * RFC-0035: the export boundary. Pattern detection always; vault values too when the vault can be
 * opened (an unreachable keystore must not stop an export, only narrow what it can recognise).
 */
private fun exportScanner(): SecretScanner {
    val redactor by lazy {
        Redactor().also { r -> runCatching { DesktopVault.open().vault.use { it.registerAllWith(r) } } }
    }
    return SecretScanner { text -> redactor.detections(text) }
}
