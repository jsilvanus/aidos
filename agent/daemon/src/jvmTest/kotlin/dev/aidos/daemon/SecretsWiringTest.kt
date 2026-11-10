package dev.aidos.daemon

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.aidos.identity.UuidV7Generator
import dev.aidos.kernel.EventId
import dev.aidos.kernel.PlatformProfile
import dev.aidos.kernel.ProjectId
import dev.aidos.kernel.SessionId
import dev.aidos.executor.RunCreator
import dev.aidos.settings.SecretRef
import dev.aidos.storage.AidosStorage
import dev.aidos.storage.DesktopPaths
import dev.aidos.vault.ConsumerKind
import dev.aidos.vault.ConsumerRef
import dev.aidos.vault.RedactionBoundary
import dev.aidos.vault.Redactor
import dev.aidos.vault.SecretKind
import dev.aidos.vault.SqliteSecretsVault
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** RFC-0035 wiring in the daemon: vault-backed resolution, audit, and redaction at the boundaries. */
class SecretsWiringTest {
    private val nowIso = "2026-11-10T00:00:00Z"
    private fun nextId() = UuidV7Generator().next()

    private fun vault(r: Redactor? = null) = SqliteSecretsVault(
        File(Files.createTempDirectory("wiring-vault").toFile(), "v.db"), SqliteSecretsVault.generateKey(), r,
    )

    private fun projectDriver(): JdbcSqliteDriver {
        val root = Files.createTempDirectory("wiring-project").toFile()
        return AidosStorage.openProject(DesktopPaths.stateDb(root.path), "test") { nowIso }.driver as JdbcSqliteDriver
    }

    // ── VaultMcpSecretResolver ─────────────────────────────────────────────────

    @Test fun `a vault entry wins over an environment variable of the same name`() = runTest {
        val v = vault(); v.store("GH_TOKEN", SecretKind.TOKEN, "from-the-vault-123".toCharArray())
        val r = VaultMcpSecretResolver(v, env = { "from-the-env-123" })
        assertEquals("from-the-vault-123", String(r.resolveFor("github", "GH_TOKEN").getOrThrow()))
    }

    @Test fun `without a vault entry the environment still answers, and with none it is not_found`() = runTest {
        val r = VaultMcpSecretResolver(vault(), env = { if (it == "LEGACY") "env-value-123456" else null })
        assertEquals("env-value-123456", String(r.resolveFor("s", "LEGACY").getOrThrow()))
        assertTrue(r.resolveFor("s", "MISSING").exceptionOrNull()!!.message!!.startsWith("secrets.not_found"))
        assertTrue(VaultMcpSecretResolver(vault(), env = { "x".repeat(20) }, envFallback = false).resolveFor("s", "LEGACY").isFailure)
    }

    @Test fun `a secret's allow-list is enforced per MCP server`() = runTest {
        val v = vault(); val id = v.store("GH_TOKEN", SecretKind.TOKEN, "from-the-vault-123".toCharArray())
        v.setAllowedConsumers(id, listOf(ConsumerRef(ConsumerKind.MCP_SERVER, "github")))
        val r = VaultMcpSecretResolver(v, env = { null })
        assertTrue(r.resolveFor("github", "GH_TOKEN").isSuccess)
        assertTrue(r.resolveFor("evil", "GH_TOKEN").exceptionOrNull()!!.message!!.startsWith("secrets.consumer_not_allowed"))
    }

    @Test fun `a secret setting reference resolves from the vault for its consumer`() = runTest {
        val v = vault(); val id = v.store("provider_key", SecretKind.API_KEY, "setting-ref-value-1".toCharArray())
        v.setAllowedConsumers(id, listOf(ConsumerRef(ConsumerKind.PROVIDER, "openai")))
        assertEquals("setting-ref-value-1", String(VaultSettingsSecretResolver(v, ConsumerRef(ConsumerKind.PROVIDER, "openai")).resolve(SecretRef("provider_key")).getOrThrow()))
        assertTrue(VaultSettingsSecretResolver(v, ConsumerRef(ConsumerKind.PROVIDER, "other")).resolve(SecretRef("provider_key")).isFailure)
        assertTrue(VaultSettingsSecretResolver(v, ConsumerRef(ConsumerKind.PROVIDER, "openai")).resolve(SecretRef("nope")).isFailure)
    }

    // ── redactJson ─────────────────────────────────────────────────────────────

    @Test fun `redactJson redacts string values and keeps the payload valid JSON`() {
        val r = Redactor(); r.register("1", "gh", "known-secret-value".toCharArray())
        val out = r.redactJson("""{"cmd":"export token=abcdefghijklmnop1234 && x","n":3,"nested":[{"v":"known-secret-value"}]}""", RedactionBoundary.EVENT)
        val parsed = kotlinx.serialization.json.Json.parseToJsonElement(out).toString()
        assertFalse(parsed.contains("known-secret-value")); assertFalse(parsed.contains("abcdefghijklmnop1234"))
        assertTrue(parsed.contains("\"n\":3"))
    }

    @Test fun `redactJson returns the same string when nothing is found and falls back for non-JSON`() {
        val r = Redactor(); r.register("1", "gh", "known-secret-value".toCharArray())
        val clean = """{"a":"b"}"""
        assertTrue(r.redactJson(clean, RedactionBoundary.EVENT) === clean)
        assertEquals("plain «redacted:gh» text", r.redactJson("plain known-secret-value text", RedactionBoundary.LOG))
    }

    // ── redaction_events ───────────────────────────────────────────────────────

    @Test fun `the sink records boundary and kind, never the value`() {
        val d = projectDriver(); val pid = nextId()
        d.execute(null, "INSERT INTO projects (id, name, root_path, project_type, created_at, updated_at, state_updated_at) VALUES (?, 't', '/', 'generic', ?, ?, ?)", 4) {
            bindString(0, pid); bindString(1, nowIso); bindString(2, nowIso); bindString(3, nowIso)
        }
        val r = Redactor(sink = RedactionEventsSink(d, pid, { nextId() }, { nowIso }))
        r.register("1", "gh", "known-secret-value".toCharArray())
        r.redact("a known-secret-value b ghp_abcdefghijklmnopqrstuvwxyz0123456789", RedactionBoundary.PROMPT)
        val rows = d.executeQuery(null, "SELECT boundary, detection_kind FROM redaction_events ORDER BY detection_kind",
            { c -> val l = mutableListOf<String>(); while (c.next().value) l += c.getString(0) + "/" + c.getString(1); QueryResult.Value(l) }, 0).value
        assertEquals(listOf("prompt/known_value", "prompt/pattern:github-token"), rows)
        val dump = d.executeQuery(null, "SELECT group_concat(boundary||detection_kind||ifnull(content_node_id,'')) FROM redaction_events", { c -> QueryResult.Value(if (c.next().value) c.getString(0) else null) }, 0).value!!
        assertFalse(dump.contains("known-secret-value")); assertFalse(dump.contains("ghp_"))
    }

    // ── RuntimeCompositionRoot ─────────────────────────────────────────────────

    @Test fun `drive resolves the provider key from the vault and audits it without the value`() = runTest {
        val d = projectDriver(); val pid = nextId(); val sid = nextId()
        d.execute(null, "INSERT INTO projects (id, name, root_path, project_type, created_at, updated_at, state_updated_at) VALUES (?, 't', '/', 'generic', ?, ?, ?)", 4) {
            bindString(0, pid); bindString(1, nowIso); bindString(2, nowIso); bindString(3, nowIso)
        }
        d.execute(null, "INSERT INTO sessions (id, project_id, name, role, state, created_at, last_active_at, state_updated_at) VALUES (?, ?, 't', 'DRIVER', 'RUNNING', ?, ?, ?)", 5) {
            bindString(0, sid); bindString(1, pid); bindString(2, nowIso); bindString(3, nowIso); bindString(4, nowIso)
        }
        val eid = nextId()
        d.execute(null, "INSERT INTO events (id, project_id, sequence, type, schema_version, category, visibility, timestamp, source, payload, causal_depth) VALUES (?, ?, 0, 'UserMessage', 1, 'SIGNAL', 'SESSION', ?, 'user', '{}', 0)", 3) {
            bindString(0, eid); bindString(1, pid); bindString(2, nowIso)
        }
        val runId = RunCreator(d, { nextId() }, { nowIso }).createForUserMessage(
            sessionId = SessionId(sid), projectId = ProjectId(pid), triggerEventId = EventId(eid),
            userMessageSummary = "hello", platformProfile = PlatformProfile.DESKTOP, deviceId = "dev-1", networkAvailable = false,
        )
        val key = "sk-ant-api03-vault-held-key-0123456789"
        val v = vault(); v.store(ANTHROPIC_KEY_SECRET, SecretKind.API_KEY, key.toCharArray())

        RuntimeCompositionRoot(anthropicApiKey = { null }, vault = v).drive(
            projectDriver = d, runId = runId, projectId = ProjectId(pid), sessionId = sid, deviceId = "dev-1",
            platformProfile = PlatformProfile.DESKTOP, networkAvailable = false, idGen = { nextId() }, nowIso = { nowIso },
        )

        val audit = d.executeQuery(null, "SELECT actor_kind, actor_id, subject_ref, detail_json FROM audit_log WHERE kind = 'SecretResolved'",
            { c -> val l = mutableListOf<List<String>>(); while (c.next().value) l += listOf(c.getString(0)!!, c.getString(1)!!, c.getString(2)!!, c.getString(3)!!); QueryResult.Value(l) }, 0).value
        assertTrue(audit.isNotEmpty(), "the provider key resolution is audited")
        assertEquals(listOf("RUNTIME", "anthropic", "secret:anthropic_api_key"), audit.first().take(3))
        assertFalse(audit.flatten().any { it.contains(key) })
        val everything = d.executeQuery(null, "SELECT group_concat(ifnull(output_snapshot,'')) FROM attempts", { c -> QueryResult.Value(if (c.next().value) c.getString(0) else null) }, 0).value ?: ""
        assertFalse(everything.contains(key))
    }

    @Test fun `git credentials come from the vault by host and honour the allow-list`() = runTest {
        val v = vault()
        val id = v.store("git:github.com", SecretKind.TOKEN, "octocat:ghp_exampletoken1234".toCharArray())
        v.store("git:gitlab.com", SecretKind.TOKEN, "bare-token-value-1".toCharArray())
        val resolved = mutableListOf<String>()
        val src = VaultGitCredentialSource(v) { resolved += it }

        val c = src.credentialFor("https://github.com/jsilvanus/aidos.git")!!
        assertEquals("octocat", c.username); assertEquals("ghp_exampletoken1234", String(c.secret))
        val bare = src.credentialFor("git@gitlab.com:group/repo.git")!!
        assertEquals("x-access-token", bare.username); assertEquals("bare-token-value-1", String(bare.secret))
        assertEquals(null, src.credentialFor("https://example.org/x.git"))
        assertEquals(listOf("github.com", "gitlab.com"), resolved)

        v.setAllowedConsumers(id, listOf(ConsumerRef(ConsumerKind.GIT_REMOTE, "elsewhere.example")))
        assertEquals(null, src.credentialFor("https://github.com/jsilvanus/aidos.git"), "allow-list pins the secret to another host")
    }
}
