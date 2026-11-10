package dev.aidos.settings

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.aidos.kernel.ActorKind
import dev.aidos.kernel.ActorRef
import dev.aidos.storage.AidosStorage
import dev.aidos.storage.DesktopPaths
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * One test group per normative RFC-0036 requirement that the original catalogue/resolve tests did
 * not reach: loading `aidos.toml`, scope enforcement with file+line, audit, fail-closed to the
 * default, unknown-key preservation, secret_ref, migration. `docs/rfcs/STATUS.md` maps each
 * requirement to the test here.
 */
class Rfc0036RequirementsTest {

    private val now = "2026-11-10T06:00:00Z"

    private fun userDb() = AidosStorage
        .openUser(DesktopPaths.userDb(Files.createTempDirectory("s36u").toFile().path), "t") { now }
        .driver as JdbcSqliteDriver

    private fun stateDb() = AidosStorage
        .openProject(DesktopPaths.stateDb(Files.createTempDirectory("s36p").toFile().path), "t") { now }
        .driver as JdbcSqliteDriver

    private fun count(d: SqlDriver, sql: String): Long = d.executeQuery(null, sql, { c ->
        QueryResult.Value(if (c.next().value) c.getLong(0)!! else 0L)
    }, 0).value

    private val toml = """
        # a project file
        [routing]
        remote_egress = "ALLOW"        # a project must not be able to do this

        [retention]
        aged_days = 14   # fine

        [knowledge]
        exclude_paths = ["dist/**", "# not a comment"]

        [future]
        flag = true
    """.trimIndent()

    // ── MVP 6: aidos.toml parsing with per-line error reporting ─────────────

    @Test
    fun `toml strips trailing comments but not a hash inside a string`() {
        val r = TomlParser.parse(toml)
        assertEquals(emptyList(), r.errors)
        assertEquals(JsonPrimitive("ALLOW"), r.values["routing.remote_egress"])
        assertEquals(JsonPrimitive(14), r.values["retention.aged_days"])
        assertTrue("# not a comment" in r.values.getValue("knowledge.exclude_paths").toString())
        assertEquals(3, r.lines["routing.remote_egress"])
    }

    // ── MVP 3 / Security 1: SECURITY/SPEND from a project is a visible error ─

    @Test
    fun `a project setting a SECURITY key is reported with file and line, audited and not applied`() {
        val user = userDb(); val state = stateDb()
        val audit = UserDbSettingsAudit(user)
        val load = ProjectSettingsLoader(state, audit).load("p1", toml, now)

        val violation = load.diagnostics.single { it.errorClass == SettingErrorClass.SCOPE_VIOLATION }
        assertEquals("routing.remote_egress", violation.key)
        assertEquals(3, violation.line)
        assertEquals("aidos.toml:3", violation.originPath)
        assertTrue("cannot be set by a project" in violation.message && "Ignored" in violation.message)

        // Never cached, never applied.
        assertEquals(0, count(state, "SELECT COUNT(*) FROM settings WHERE key = 'routing.remote_egress'"))
        val errors = mutableListOf<SettingError>()
        val r = SettingsStore(user, state).resolve(
            Settings.routingRemoteEgress, projectId = "p1", project = load.snapshot, errors = errors)
        assertEquals(EgressPolicy.ASK, r.value)
        assertEquals(SettingOrigin.DEFAULT, r.origin)
        assertEquals(ScopeClass.SECURITY, r.overriddenBy)   // a lower scope tried and was refused

        // Audited with the attempted value.
        val row = audit.entries("routing.remote_egress").single()
        assertEquals(SettingAuditKind.SCOPE_VIOLATION, row.kind)
        assertEquals("\"ALLOW\"", row.newValueJson)
        assertEquals("p1", row.scopeId)
    }

    @Test
    fun `reloading an unchanged offending file does not append the same violation again`() {
        val user = userDb(); val state = stateDb()
        val audit = UserDbSettingsAudit(user)
        val loader = ProjectSettingsLoader(state, audit)
        repeat(3) { loader.load("p1", toml, now) }
        assertEquals(1, audit.entries("routing.remote_egress").size)
    }

    @Test
    fun `a SPEND key in a project file is refused too`() {
        val load = ProjectSettingsLoader(null).load("p1", "[budget]\nrun_cost_ceiling = 999999999\n", now)
        assertEquals(SettingErrorClass.SCOPE_VIOLATION, load.diagnostics.single().errorClass)
        assertTrue(load.snapshot.entries.isEmpty())
    }

    // ── MVP 2: resolution with origin and line ──────────────────────────────

    @Test
    fun `a project value reports the exact file and line it came from`() {
        val user = userDb(); val state = stateDb()
        val load = ProjectSettingsLoader(state).load("p1", toml, now)
        val r = SettingsStore(user, state).resolve(Settings.retentionAgedDays, project = load.snapshot)
        assertEquals(14, r.value)
        assertEquals(SettingOrigin.PROJECT, r.origin)
        assertEquals("aidos.toml:6", r.originPath)
    }

    @Test
    fun `the state db cache resolves when no snapshot is supplied`() {
        val user = userDb(); val state = stateDb()
        ProjectSettingsLoader(state).load("p1", toml, now)
        val r = SettingsStore(user, state).resolve(Settings.retentionAgedDays, projectId = "p1")
        assertEquals(14, r.value)
        assertEquals(SettingOrigin.PROJECT, r.origin)
    }

    @Test
    fun `PROJECT_SAFE accepts a project value and the file replaces the cache on reload`() {
        val state = stateDb()
        val loader = ProjectSettingsLoader(state)
        loader.load("p1", toml, now)
        assertEquals(1, count(state, "SELECT COUNT(*) FROM settings WHERE key = 'knowledge.exclude_paths'"))
        loader.load("p1", "[retention]\naged_days = 7\n", now)     // the file is the source
        assertEquals(0, count(state, "SELECT COUNT(*) FROM settings WHERE key = 'knowledge.exclude_paths'"))
        loader.load("p1", null, now)                               // no file at all
        assertEquals(0, count(state, "SELECT COUNT(*) FROM settings WHERE scope = 'project'"))
    }

    @Test
    fun `a session may override a PREFERENCE but not PROJECT_SAFE, SECURITY or SPEND`() {
        val store = SettingsStore(userDb(), null)
        val ok = store.resolve(Settings.retentionAgedDays, sessionValues = mapOf("retention.aged_days" to "5"))
        assertEquals(5, ok.value); assertEquals(SettingOrigin.SESSION, ok.origin)

        for (d in listOf(Settings.knowledgeExcludePaths, Settings.routingRemoteEgress, Settings.budgetRunCostCeiling)) {
            val errors = mutableListOf<SettingError>()
            val bad = store.resolve(d, sessionValues = mapOf(d.key to "\"x\""), errors = errors)
            assertEquals(SettingOrigin.DEFAULT, bad.origin, d.key)
            assertEquals(SettingErrorClass.SCOPE_VIOLATION, errors.single().errorClass, d.key)
            assertEquals(d.scopeClass, bad.overriddenBy, d.key)
        }
    }

    // ── MVP 4 / Fail closed ─────────────────────────────────────────────────

    @Test
    fun `an invalid project value resolves to the default, not to the user's value`() {
        val user = userDb(); val state = stateDb()
        SettingsWriter(user).writeUser(Settings.retentionAgedDays, JsonPrimitive(60), SettingSetByKind.USER, now)
        val load = ProjectSettingsLoader(state).load("p1", "[retention]\naged_days = 99999\n", now)

        val bad = load.diagnostics.single()
        assertEquals(SettingErrorClass.VALIDATION_FAILED, bad.errorClass)
        assertEquals(2, bad.line)

        val r = SettingsStore(user, state).resolve(Settings.retentionAgedDays, project = load.snapshot)
        assertEquals(30, r.value)                       // the default, not 60
        assertEquals(SettingOrigin.DEFAULT, r.origin)
    }

    @Test
    fun `a SECURITY descriptor must name a most restrictive value`() {
        assertFailsWith<IllegalStateException> {
            setting<Boolean>("x.y") { scopeClass(ScopeClass.SECURITY); default(true); codec(BooleanCodec) }
        }
    }

    @Test
    fun `writeUser rejects a value the codec rejects and writes nothing`() {
        val user = userDb()
        val result = SettingsWriter(user).writeUser(
            Settings.routingRemoteEgress, JsonPrimitive("SOMETIMES"), SettingSetByKind.USER, now)
        assertTrue(result.isFailure)
        assertEquals(0, count(user, "SELECT COUNT(*) FROM settings"))
    }

    // ── Security 5: SECURITY changes audited with old, new, actor ───────────

    @Test
    fun `a SECURITY change is audited with old value, new value and actor`() {
        val user = userDb()
        val audit = UserDbSettingsAudit(user)
        val writer = SettingsWriter(user, audit = audit)
        val me = ActorRef(ActorKind.USER, "device-1")

        writer.writeUser(Settings.routingRemoteEgress, JsonPrimitive("ALLOW"), SettingSetByKind.USER, now, me)
        writer.writeUser(Settings.routingRemoteEgress, JsonPrimitive("NEVER"), SettingSetByKind.USER, now, me)
        writer.writeUser(Settings.routingRemoteEgress, JsonPrimitive("NEVER"), SettingSetByKind.USER, now, me) // no-op

        val rows = audit.entries("routing.remote_egress")
        assertEquals(2, rows.size)
        assertNull(rows[0].oldValueJson); assertEquals("\"ALLOW\"", rows[0].newValueJson)
        assertEquals("\"ALLOW\"", rows[1].oldValueJson); assertEquals("\"NEVER\"", rows[1].newValueJson)
        assertTrue(rows.all { it.actor == me && it.kind == SettingAuditKind.CHANGED })
    }

    @Test
    fun `a SPEND change is audited and a PREFERENCE change is not`() {
        val user = userDb(); val audit = UserDbSettingsAudit(user)
        val writer = SettingsWriter(user, audit = audit)
        writer.writeUser(Settings.budgetRunCostCeiling, JsonPrimitive(5), SettingSetByKind.USER, now)
        writer.writeUser(Settings.retentionAgedDays, JsonPrimitive(5), SettingSetByKind.USER, now)
        assertEquals(listOf("budget.run_cost_ceiling"), audit.entries().map { it.key })
    }

    @Test
    fun `writing the same user key twice leaves one row`() {
        val user = userDb()
        val writer = SettingsWriter(user)
        writer.writeUser(Settings.retentionAgedDays, JsonPrimitive(5), SettingSetByKind.USER, now)
        writer.writeUser(Settings.retentionAgedDays, JsonPrimitive(6), SettingSetByKind.USER, now)
        assertEquals(1, count(user, "SELECT COUNT(*) FROM settings WHERE key = 'retention.aged_days'"))
        assertEquals(6, SettingsStore(user, null).resolve(Settings.retentionAgedDays).value)
    }

    @Test
    fun `a refused project write is audited`() {
        val user = userDb()
        val audit = UserDbSettingsAudit(user)
        val result = SettingsWriter(user, stateDb(), audit).writeProject(
            Settings.networkAllowPlaintextHttp, "p1", JsonPrimitive(true), SettingSetByKind.USER, now)
        assertTrue(result.isFailure)
        assertEquals(SettingAuditKind.SCOPE_VIOLATION, audit.entries().single().kind)
    }

    // ── Security 6: unknown keys preserved, never interpreted ───────────────

    @Test
    fun `an unknown key warns, is kept in the cache and never resolves`() {
        val state = stateDb()
        val load = ProjectSettingsLoader(state).load("p1", toml, now)

        val warn = load.diagnostics.single { it.errorClass == SettingErrorClass.UNKNOWN_KEY }
        assertEquals("future.flag", warn.key)
        assertEquals(12, warn.line)
        assertFalse("future.flag" in load.snapshot.entries)
        assertEquals(1, count(state, "SELECT COUNT(*) FROM settings WHERE key = 'future.flag'"))
        assertNull(Settings.forKey("future.flag"))
    }

    // ── Settings are not secrets ────────────────────────────────────────────

    private val keyRef = setting<SecretRef>("test.key_ref") {
        scopeClass(ScopeClass.SECURITY)
        default(SecretRef("none"))
        mostRestrictive(SecretRef("none"))
        codec(SecretRefCodec)
    }

    @Test
    fun `a secret_ref setting accepts a reference and rejects a bare credential`() {
        assertEquals(SecretRef("anthropic-key"),
            SecretRefCodec.decode(JsonPrimitive("secret:anthropic-key")).getOrThrow())
        assertTrue(SecretRefCodec.decode(JsonPrimitive("sk-ant-api03-abcdef")).isFailure)

        val user = userDb()
        val rejected = SettingsWriter(user).writeUser(
            keyRef, JsonPrimitive("sk-ant-api03-abcdef"), SettingSetByKind.USER, now)
        assertTrue(rejected.isFailure)
        assertEquals(0, count(user, "SELECT COUNT(*) FROM settings"))
    }

    @Test
    fun `a secret_ref resolves at the point of use and the secret is never stored or audited`() {
        val user = userDb(); val audit = UserDbSettingsAudit(user)
        SettingsWriter(user, audit = audit).writeUser(
            keyRef, JsonPrimitive("secret:anthropic-key"), SettingSetByKind.USER, now)

        val resolved = SettingsStore(user, null).resolve(keyRef)
        val secret = resolved.resolveSecret { ref ->
            assertEquals("anthropic-key", ref.name)
            Result.success("s3cr3t".toCharArray())
        }.getOrThrow()
        assertEquals("s3cr3t", String(secret))

        assertEquals(0, count(user, "SELECT COUNT(*) FROM settings WHERE value_json LIKE '%s3cr3t%'"))
        assertTrue(audit.entries().none { "s3cr3t" in (it.newValueJson ?: "") })
        assertNotNull(audit.entries().single().newValueJson)  // the reference itself is auditable
    }

    // ── Migration ───────────────────────────────────────────────────────────

    @Test
    fun `a renamed key is honoured under its new name with a warning`() {
        val loader = ProjectSettingsLoader(null, renamedKeys = mapOf("retention.days" to "retention.aged_days"))
        val load = loader.load("p1", "[retention]\ndays = 9\n", now)
        assertEquals(SettingErrorClass.DEPRECATED_KEY, load.diagnostics.single().errorClass)
        assertEquals(9, SettingsStore(userDb(), null)
            .resolve(Settings.retentionAgedDays, project = load.snapshot).value)
    }

    @Test
    fun `an explicit new key beats its old alias`() {
        val loader = ProjectSettingsLoader(null, renamedKeys = mapOf("retention.days" to "retention.aged_days"))
        val load = loader.load("p1", "[retention]\ndays = 9\naged_days = 20\n", now)
        assertEquals(20, SettingsStore(userDb(), null)
            .resolve(Settings.retentionAgedDays, project = load.snapshot).value)
    }

    @Test
    fun `a removed key is kept but ignored with a warning`() {
        val state = stateDb()
        val load = ProjectSettingsLoader(state, removedKeys = setOf("retention.aged_days"))
            .load("p1", "[retention]\naged_days = 9\n", now)
        assertEquals(SettingErrorClass.DEPRECATED_KEY, load.diagnostics.single().errorClass)
        assertTrue(load.snapshot.entries.isEmpty())
        assertEquals(1, count(state, "SELECT COUNT(*) FROM settings WHERE key = 'retention.aged_days'"))
    }
}
