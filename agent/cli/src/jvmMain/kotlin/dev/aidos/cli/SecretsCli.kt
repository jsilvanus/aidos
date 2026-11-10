package dev.aidos.cli

import dev.aidos.portability.CliIo
import dev.aidos.portability.SigningKeyStore
import dev.aidos.vault.ConsumerKind
import dev.aidos.vault.ConsumerRef
import dev.aidos.vault.OpenedVault
import dev.aidos.vault.SecretKind
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Instant

/**
 * `aidos secrets ...` (RFC-0035): manage the user-scope vault. Local, no daemon.
 *
 * ```
 * secrets status                         how the vault key is protected
 * secrets set <name> [--kind K]          store a new secret; K = api_key|token|password|ssh_key|generic
 * secrets rotate <name>                  replace the value, same name (consumers are unaffected)
 * secrets delete <name>
 * secrets list                           names and metadata, never values
 * secrets expire <name> <ISO-8601|none>  set or clear the expiry
 * secrets allow <name> [KIND:id ...]     limit who may resolve it (MCP_SERVER:github, PROVIDER:anthropic, GIT_REMOTE:origin); none clears
 * ```
 * The value comes from `AIDOS_SECRET_VALUE`, a no-echo terminal prompt, or one line of piped
 * standard input — never from the command line, where it would land in shell history and `ps`.
 * There is deliberately no command that prints a value.
 */
class SecretsCli(
    private val open: () -> OpenedVault,
    private val io: CliIo,
    private val stdin: () -> String? = { readlnOrNull() },
) {
    /** 0 ok, 1 refused, 2 usage. */
    fun run(args: List<String>): Int {
        if (args.isEmpty()) return usage()
        return try {
            val opened = open()
            try {
                dispatch(opened, args[0], args.drop(1))
            } finally {
                opened.vault.close()
            }
        } catch (e: UsageError) {
            io.err("aidos: ${e.message}"); usage()
        } catch (e: Exception) {
            io.err("aidos: ${e.message}"); 1
        }
    }

    private class UsageError(message: String) : Exception(message)

    private fun dispatch(o: OpenedVault, cmd: String, a: List<String>): Int {
        val v = o.vault
        when (cmd) {
            "status" -> {
                io.out("vault key protection: ${o.protection.name}")
                io.out(o.protection.plainDescription)
                io.out("${runBlocking { v.list() }.size} secret(s) stored")
            }
            "set" -> {
                val name = a.firstOrNull() ?: throw UsageError("secrets set needs a name")
                val kind = kindOf(a.drop(1))
                if (runBlocking { v.getByName(name) }.isSuccess) throw IllegalStateException("secret '$name' already exists; use 'secrets rotate $name'")
                val value = readValue(name)
                runBlocking { v.store(name, kind, value) }
                o.weakProtectionNotice?.let { io.err("note: $it") }
                io.out("stored '$name'")
            }
            "rotate" -> {
                val e = entry(v, a.firstOrNull() ?: throw UsageError("secrets rotate needs a name"))
                runBlocking { v.rotate(e.id, readValue(e.name)) }
                io.out("rotated '${e.name}'; the previous value stays filtered from logs and prompts for 24 hours")
            }
            "delete" -> {
                val e = entry(v, a.firstOrNull() ?: throw UsageError("secrets delete needs a name"))
                runBlocking { v.delete(e.id) }
                io.out("deleted '${e.name}'; anything that references it will now fail with secrets.not_found")
            }
            "list" -> {
                val all = runBlocking { v.list() }.sortedBy { it.name }
                if (all.isEmpty()) io.out("no secrets stored")
                for (e in all) {
                    io.out("${e.name}  ${e.kind.name}  created ${e.createdAt}" +
                        (e.expiresAt?.let { "  expires $it" } ?: "") +
                        (e.lastUsedAt?.let { "  last used $it" } ?: "") +
                        (if (e.allowedConsumers.isEmpty()) "" else "  consumers ${e.allowedConsumers.joinToString(",")}"))
                }
            }
            "expire" -> {
                val e = entry(v, a.getOrNull(0) ?: throw UsageError("secrets expire needs a name and a time or 'none'"))
                val at = a.getOrNull(1) ?: throw UsageError("secrets expire needs a time or 'none'")
                runBlocking { v.setExpiry(e.id, if (at == "none") null else runCatching { Instant.parse(at) }.getOrElse { throw UsageError("'$at' is not an ISO-8601 instant, e.g. 2027-01-31T00:00:00Z") }) }
                io.out(if (at == "none") "'${e.name}' no longer expires" else "'${e.name}' expires $at")
            }
            "allow" -> {
                val e = entry(v, a.firstOrNull() ?: throw UsageError("secrets allow needs a name"))
                val consumers = a.drop(1).map { spec ->
                    val kind = spec.substringBefore(':').let { k -> ConsumerKind.entries.firstOrNull { it.name == k.uppercase() } }
                        ?: throw UsageError("'$spec': consumer kind must be one of ${ConsumerKind.entries.joinToString("|")}")
                    ConsumerRef(kind, spec.substringAfter(':', "").ifBlank { throw UsageError("'$spec' needs an id after the colon") })
                }
                runBlocking { v.setAllowedConsumers(e.id, consumers) }
                io.out(if (consumers.isEmpty()) "'${e.name}' has no allow-list" else "'${e.name}' may be used only by ${consumers.joinToString(", ") { it.key }}")
            }
            else -> throw UsageError("unknown secrets command '$cmd'")
        }
        return 0
    }

    private fun entry(v: dev.aidos.vault.SecretsVault, name: String) =
        runBlocking { v.getByName(name) }.getOrElse { throw IllegalStateException("secrets.not_found: no secret named '$name'") }

    private fun kindOf(a: List<String>): SecretKind {
        val i = a.indexOf("--kind")
        if (i < 0) return SecretKind.GENERIC
        val k = a.getOrNull(i + 1) ?: throw UsageError("--kind needs a value")
        return SecretKind.entries.firstOrNull { it.name == k.uppercase() } ?: throw UsageError("unknown kind '$k'")
    }

    private fun readValue(name: String): CharArray {
        val v = io.env("AIDOS_SECRET_VALUE")?.toCharArray()
            ?: io.password("Value for '$name': ")
            ?: stdin()?.trimEnd('\n', '\r')?.toCharArray()
            ?: throw UsageError("no value: set AIDOS_SECRET_VALUE, use a terminal, or pipe the value on stdin")
        if (v.isEmpty()) throw UsageError("empty value")
        return v
    }

    private fun usage(): Int {
        io.err("usage: secrets status | set <name> [--kind K] | rotate <name> | delete <name> | list | expire <name> <ISO-8601|none> | allow <name> [KIND:id ...]")
        return 2
    }

    companion object {
        val COMMANDS = setOf("secrets")
    }
}

/** Export signing keys kept in the vault (RFC-0041 + RFC-0035): one GENERIC secret per key. */
class VaultSigningKeys(private val opened: () -> OpenedVault) : SigningKeyStore {
    override fun save(name: String, text: String) {
        val o = opened()
        try {
            val secretName = PREFIX + name
            check(runBlocking { o.vault.getByName(secretName) }.isFailure) { "a signing key named '$name' already exists" }
            runBlocking { o.vault.store(secretName, SecretKind.SSH_KEY, text.toCharArray()) }
        } finally { o.vault.close() }
    }

    override fun load(name: String): String? {
        val o = opened()
        try {
            val e = runBlocking { o.vault.getByName(PREFIX + name) }.getOrNull() ?: return null
            val chars = runBlocking { o.vault.resolve(e.id) }.getOrNull() ?: return null
            return try { String(chars) } finally { chars.fill('\u0000') }
        } finally { o.vault.close() }
    }

    private companion object { const val PREFIX = "export-signing-key:" }
}
