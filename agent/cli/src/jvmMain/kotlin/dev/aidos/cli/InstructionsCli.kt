package dev.aidos.cli

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import dev.aidos.portability.CliIo
import dev.aidos.prompt.InstructionDiscovery
import dev.aidos.storage.InstructionAdoptionStore
import kotlinx.datetime.Clock
import java.io.File

/**
 * `aidos instructions ...` (RFC-0016): review and adopt a project's AGENTS.md / CLAUDE.md set.
 *
 * ```
 * instructions status <project-root>   show the set, its hash, adoption state, unread nested files
 * instructions adopt  <project-root>   record adoption for this project (and user-scope recognition)
 * instructions revoke <project-root>   withdraw adoption of the current set
 * ```
 * Nothing reaches a prompt until `adopt` has been run for the set's exact hash.
 */
class InstructionsCli(
    private val openProjectDriver: (projectRoot: String) -> SqlDriver,
    private val openUserDriver: () -> SqlDriver,
    private val io: CliIo,
    private val nowIso: () -> String = { Clock.System.now().toString() },
) {
    /** 0 ok, 1 refused, 2 usage. */
    fun run(args: List<String>): Int {
        val cmd = args.firstOrNull()
        val root = args.getOrNull(1)
        if (cmd == null || root == null || cmd !in setOf("status", "adopt", "revoke")) return usage()
        var nested = 0
        val set = InstructionDiscovery.discover(File(root), nestedCountOut = { nested = it })
        if (set == null) {
            io.err("aidos: no AGENTS.md or CLAUDE.md at $root")
            return 1
        }
        val project = openProjectDriver(root)
        val user = openUserDriver()
        try {
            val found = project.executeQuery(
                null, "SELECT id, name FROM projects LIMIT 1",
                { c -> QueryResult.Value(if (c.next().value) c.getString(0)!! to (c.getString(1) ?: "") else null) }, 0,
            ).value
            if (found == null) {
                io.err("aidos: $root is not an Aidos project")
                return 1
            }
            val (projectId, projectName) = found
            val store = InstructionAdoptionStore(project, user)
            when (cmd) {
                "status" -> {
                    io.out("set ${set.hash}")
                    set.sources.forEach { io.out("  ${it.filename} (blob ${it.blobHash})") }
                    io.out(
                        when {
                            store.isAdopted(projectId, set.hash) -> "adopted: yes"
                            store.isKnown(set.hash) -> "adopted: no (you have accepted this exact text in another project)"
                            else -> "adopted: no (unseen - its text is excluded from prompts until adopted)"
                        }
                    )
                }
                "adopt" -> {
                    set.sources.forEach { io.out("--- ${it.filename} ---"); io.out(it.content) }
                    val manifest = set.sources.joinToString(",", "[", "]") {
                        "{\"filename\":\"${it.filename}\",\"blob_hash\":\"${it.blobHash}\"}"
                    }
                    store.adopt(projectId, set.hash, manifest, nowIso(), projectName = projectName)
                    io.out("adopted instruction set ${set.hash}")
                }
                "revoke" -> {
                    store.revoke(projectId, set.hash)
                    io.out("revoked adoption of ${set.hash}")
                }
            }
            if (nested > 0 && cmd != "revoke") io.out("warning: $nested nested instruction file(s) below the root are not read")
            return 0
        } finally {
            project.close()
            user.close()
        }
    }

    private fun usage(): Int {
        io.err(USAGE)
        return 2
    }

    companion object {
        val COMMANDS = setOf("instructions")
        private const val USAGE = "usage: aidos instructions <status|adopt|revoke> <project-root>"
    }
}
