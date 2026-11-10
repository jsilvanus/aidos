package dev.aidos.daemon

import app.cash.sqldelight.db.SqlDriver
import dev.aidos.mcp.McpSecretResolver
import dev.aidos.settings.SecretRef
import dev.aidos.settings.SecretResolver
import dev.aidos.vault.ConsumerKind
import dev.aidos.vault.ConsumerRef
import dev.aidos.vault.RedactionBoundary
import dev.aidos.vault.RedactionSink
import dev.aidos.vault.Redactor
import dev.aidos.vault.SecretsVault
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Vault entry the Anthropic adapter's API key is stored under (RFC-0035 provider consumer). */
const val ANTHROPIC_KEY_SECRET = "anthropic_api_key"

/**
 * Writes `redaction_events` (RFC-0035): that something was found, at which boundary, of which
 * kind. The match is never passed to this class, so it cannot be recorded.
 */
internal class RedactionEventsSink(
    private val driver: SqlDriver,
    private val projectId: String,
    private val idGen: () -> String,
    private val nowIso: () -> String,
) : RedactionSink {
    override fun record(boundary: RedactionBoundary, detectionKind: String) {
        // An audit failure must never block the write it is auditing, or leak by throwing the string.
        runCatching {
            driver.execute(
                identifier = null,
                sql = "INSERT INTO redaction_events (id, project_id, boundary, detection_kind, content_node_id, occurred_at) VALUES (?, ?, ?, ?, NULL, ?)",
                parameters = 5,
            ) {
                bindString(0, idGen()); bindString(1, projectId); bindString(2, boundary.wire)
                bindString(3, detectionKind); bindString(4, nowIso())
            }
        }
    }
}

/**
 * Redacts the string values inside a JSON document and leaves the structure alone, so a pattern
 * match can never eat a quote and turn an event payload into invalid JSON. Falls back to plain
 * redaction for text that is not JSON. Returns [payload] itself when nothing changed.
 */
internal fun Redactor.redactJson(payload: String, boundary: RedactionBoundary): String {
    val element = runCatching { Json.parseToJsonElement(payload) }.getOrNull() ?: return redact(payload, boundary)
    var changed = false
    fun walk(e: JsonElement): JsonElement = when (e) {
        is JsonObject -> JsonObject(e.mapValues { (_, v) -> walk(v) })
        is JsonArray -> JsonArray(e.map(::walk))
        is JsonPrimitive -> if (e.isString) {
            val r = redact(e.content, boundary)
            if (r !== e.content && r != e.content) { changed = true; JsonPrimitive(r) } else e
        } else e
    }
    val out = walk(element)
    return if (changed) Json.encodeToString(JsonElement.serializer(), out) else payload
}

/**
 * Where diagnostic log lines go (RFC-0035 boundary "Diagnostic logs"): through the redactor
 * first. The host installs its vault-seeded redactor at start-up; until then lines are still
 * pattern-redacted.
 */
object DaemonLog {
    @Volatile
    var redactor: Redactor = Redactor()

    fun err(line: String) = System.err.println(redactor.redact(line, RedactionBoundary.LOG))
}

/**
 * Resolves MCP `secret_refs` from the vault by entry name, naming the server as the consumer so
 * a secret's allow-list is enforced per server (RFC-0035 "Resolution"). A reference the vault
 * does not hold falls back to an environment variable of that name, which keeps pre-vault
 * configurations working; a vault entry always wins.
 */
class VaultMcpSecretResolver(
    private val vault: SecretsVault,
    private val env: (String) -> String? = System::getenv,
    private val envFallback: Boolean = true,
) : McpSecretResolver {
    override suspend fun resolve(reference: String): Result<CharArray> = resolveFor("runtime", reference)

    override suspend fun resolveFor(serverId: String, reference: String): Result<CharArray> {
        val entry = vault.getByName(reference).getOrNull()
        if (entry != null) {
            return vault.resolveFor(entry.id, dev.aidos.vault.SecretRequest(ConsumerRef(ConsumerKind.MCP_SERVER, serverId)))
        }
        if (envFallback) {
            (env(reference) ?: env(reference.uppercase()))?.let { return Result.success(it.toCharArray()) }
        }
        return Result.failure(NoSuchElementException("secrets.not_found: $reference"))
    }
}

/** Resolves `secret:<name>` settings (RFC-0036) from the vault, on behalf of [consumer]. */
class VaultSettingsSecretResolver(
    private val vault: SecretsVault,
    private val consumer: ConsumerRef,
) : SecretResolver {
    override fun resolve(ref: SecretRef): Result<CharArray> = runBlocking {
        val entry = vault.getByName(ref.name).getOrElse { return@runBlocking Result.failure(it) }
        vault.resolveFor(entry.id, dev.aidos.vault.SecretRequest(consumer))
    }
}

/**
 * Git remote credentials from the vault (RFC-0035 Git consumer). A remote `https://host/...` looks
 * up the secret named `git:host`; its value is either `username:token` or just the token (the
 * username is then `x-access-token`, which GitHub-style hosts accept). The consumer is
 * `GIT_REMOTE:host`, so a secret's allow-list can pin it to one host. Never read from the
 * repository's own configuration, which arrives with the repository.
 */
class VaultGitCredentialSource(
    private val vault: SecretsVault,
    private val onResolved: (host: String) -> Unit = {},
) : dev.aidos.git.GitCredentialSource {
    override fun credentialFor(remoteUrl: String): dev.aidos.git.GitCredential? {
        val host = hostOf(remoteUrl) ?: return null
        return runBlocking {
            val entry = vault.getByName("git:$host").getOrNull() ?: return@runBlocking null
            val chars = vault.resolveFor(entry.id, dev.aidos.vault.SecretRequest(ConsumerRef(ConsumerKind.GIT_REMOTE, host))).getOrNull()
                ?: return@runBlocking null
            onResolved(host)
            val text = String(chars)
            chars.fill('\u0000')
            val i = text.indexOf(':')
            if (i > 0) dev.aidos.git.GitCredential(text.substring(0, i), text.substring(i + 1).toCharArray())
            else dev.aidos.git.GitCredential("x-access-token", text.toCharArray())
        }
    }

    private fun hostOf(url: String): String? =
        runCatching { java.net.URI(url).host }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: Regex("^[^@]+@([^:/]+)[:/]").find(url)?.groupValues?.get(1) // scp-style git@host:path
}
