package dev.aidos.cli

import app.cash.sqldelight.db.SqlDriver
import dev.aidos.kernel.PlatformProfile
import dev.aidos.mcp.McpServerLoadOutcome
import dev.aidos.mcp.McpServerRegistry
import dev.aidos.mcp.McpServerStore
import dev.aidos.mcp.core.McpTransport
import dev.aidos.portability.CliIo
import kotlinx.datetime.Clock

/**
 * `aidos mcp ...` (RFC-0031): manage user-scope MCP server registrations in `user.db`.
 *
 * ```
 * mcp add <name> --transport stdio --command <cmd> [--args a,b] [--profiles P1,P2] [--secret-ref K=V ...] [--auto-restart]
 * mcp add <name> --transport http --url <url> [--profiles P1,P2] [--secret-ref K=V ...] [--auto-restart]
 * mcp remove <name>
 * mcp list
 * ```
 */
class McpCli(
    private val openDriver: () -> SqlDriver,
    private val io: CliIo,
    private val closeDriver: (SqlDriver) -> Unit = { it.close() },
    private val nowIso: () -> String = { Clock.System.now().toString() },
    private val deviceProfile: PlatformProfile = PlatformProfile.DESKTOP,
) {
    /** 0 ok, 1 refused, 2 usage. */
    fun run(args: List<String>): Int {
        if (args.isEmpty()) return usage()
        return try {
            val driver = openDriver()
            try {
                dispatch(driver, args[0], args.drop(1))
            } finally {
                closeDriver(driver)
            }
        } catch (e: UsageError) {
            io.err("aidos: ${e.message}")
            usage()
        } catch (e: Exception) {
            io.err("aidos: ${e.message}")
            1
        }
    }

    private class UsageError(message: String) : Exception(message)

    private fun dispatch(driver: SqlDriver, cmd: String, args: List<String>): Int {
        val registry = McpServerRegistry(driver)
        when (cmd) {
            "add" -> {
                val name = args.firstOrNull()?.takeIf { !it.startsWith("-") }
                    ?: throw UsageError("mcp add needs a server name")
                val parsed = parseAddArgs(args.drop(1))
                registry.upsert(
                    serverId = name,
                    transport = parsed.transport,
                    profiles = parsed.profiles,
                    autoRestart = parsed.autoRestart,
                    nowIso = nowIso(),
                )
                io.out("registered mcp server '$name' (${parsed.transportLabel})")
            }
            "remove", "delete" -> {
                val name = args.firstOrNull()?.takeIf { !it.startsWith("-") }
                    ?: throw UsageError("mcp $cmd needs a server name")
                registry.delete(name)
                io.out("removed mcp server '$name'")
            }
            "list" -> {
                val outcomes = McpServerStore(driver).loadAll(deviceProfile)
                if (outcomes.isEmpty()) {
                    io.out("no mcp servers registered")
                } else {
                    for (outcome in outcomes) {
                        when (outcome) {
                            is McpServerLoadOutcome.Loaded -> {
                                val reg = outcome.load.registration
                                val kind = when (val t = reg.transport) {
                                    is McpTransport.Stdio -> "stdio (${t.command})"
                                    is McpTransport.Http -> "http (${t.endpointUrl})"
                                    is McpTransport.Binder -> "binder (${t.packageName})"
                                }
                                val profiles = outcome.load.profiles.joinToString(",") { it.name }
                                io.out("${reg.serverId}\t$kind\t[$profiles]")
                            }
                            is McpServerLoadOutcome.Rejected -> {
                                io.out("${outcome.serverId}\tREJECTED\t${outcome.reason}")
                            }
                        }
                    }
                }
            }
            else -> throw UsageError("unknown mcp subcommand '$cmd'")
        }
        return 0
    }

    private data class ParsedAdd(
        val transport: McpTransport,
        val transportLabel: String,
        val profiles: Set<PlatformProfile>,
        val autoRestart: Boolean,
    )

    private fun parseAddArgs(args: List<String>): ParsedAdd {
        var transportType: String? = null
        var command: String? = null
        val cmdArgs = mutableListOf<String>()
        var url: String? = null
        var profiles: Set<PlatformProfile>? = null
        val secretRefs = linkedMapOf<String, String>()
        var autoRestart = false

        var i = 0
        while (i < args.size) {
            when (val flag = args[i]) {
                "--transport" -> {
                    transportType = args.getOrNull(i + 1) ?: throw UsageError("--transport needs a value")
                    i += 2
                }
                "--command" -> {
                    command = args.getOrNull(i + 1) ?: throw UsageError("--command needs a value")
                    i += 2
                }
                "--args" -> {
                    val raw = args.getOrNull(i + 1) ?: throw UsageError("--args needs a comma-separated value")
                    if (raw.isNotEmpty()) cmdArgs.addAll(raw.split(','))
                    i += 2
                }
                "--url" -> {
                    url = args.getOrNull(i + 1) ?: throw UsageError("--url needs a value")
                    i += 2
                }
                "--profiles" -> {
                    val raw = args.getOrNull(i + 1) ?: throw UsageError("--profiles needs a comma-separated value")
                    profiles = raw.split(',').filter { it.isNotBlank() }.map { token ->
                        runCatching { PlatformProfile.valueOf(token.trim().uppercase()) }
                            .getOrElse { throw UsageError("unknown profile '$token'") }
                    }.toSet()
                    i += 2
                }
                "--secret-ref" -> {
                    val raw = args.getOrNull(i + 1) ?: throw UsageError("--secret-ref needs KEY=SECRET_NAME")
                    val eq = raw.indexOf('=')
                    if (eq <= 0 || eq == raw.lastIndex) {
                        throw UsageError("--secret-ref must be KEY=SECRET_NAME")
                    }
                    secretRefs[raw.substring(0, eq)] = raw.substring(eq + 1)
                    i += 2
                }
                "--auto-restart" -> {
                    autoRestart = true
                    i += 1
                }
                else -> throw UsageError("unknown option '$flag'")
            }
        }

        return when (transportType?.lowercase()) {
            "stdio" -> {
                val cmd = command ?: throw UsageError("--transport stdio requires --command")
                val resolvedProfiles = profiles ?: setOf(PlatformProfile.DESKTOP, PlatformProfile.HEADLESS_SERVER)
                ParsedAdd(
                    transport = McpTransport.Stdio(command = cmd, args = cmdArgs, secretRefs = secretRefs),
                    transportLabel = "stdio",
                    profiles = resolvedProfiles,
                    autoRestart = autoRestart,
                )
            }
            "http" -> {
                val endpoint = url ?: throw UsageError("--transport http requires --url")
                val resolvedProfiles = profiles ?: PlatformProfile.entries.toSet()
                val authHeader = secretRefs.keys.singleOrNull()?.takeIf { it.isNotBlank() } ?: "Authorization"
                ParsedAdd(
                    transport = McpTransport.Http(
                        endpointUrl = endpoint,
                        authHeaderName = authHeader,
                        secretRefs = secretRefs,
                    ),
                    transportLabel = "http",
                    profiles = resolvedProfiles,
                    autoRestart = autoRestart,
                )
            }
            null -> throw UsageError("--transport is required (stdio|http)")
            else -> throw UsageError("unsupported transport '$transportType' (expected stdio|http)")
        }
    }

    private fun usage(): Int {
        io.err(USAGE)
        return 2
    }

    companion object {
        val COMMANDS = setOf("mcp")
        private val USAGE = """
            usage:
              aidos mcp add <name> --transport stdio --command <cmd> [--args a,b] [--profiles P1,P2] [--secret-ref K=V] [--auto-restart]
              aidos mcp add <name> --transport http --url <url> [--profiles P1,P2] [--secret-ref K=V] [--auto-restart]
              aidos mcp remove <name>
              aidos mcp list
        """.trimIndent()
    }
}
