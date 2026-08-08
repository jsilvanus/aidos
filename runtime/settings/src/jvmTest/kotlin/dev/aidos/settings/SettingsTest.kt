package dev.aidos.settings

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.aidos.storage.AidosStorage
import dev.aidos.storage.DatabaseKind
import dev.aidos.storage.DesktopPaths
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * M1 done-when: declared settings carry type, default, range, and scope class;
 * SECURITY/SPEND settings are enforced user-scope-only with a visible error and audit row on a
 * project attempt; resolution is nearest-first with origin reporting; invalid input fails
 * closed; aidos.toml parses with per-line error reporting (RFC-0036).
 */
class SettingsTest {

    private fun openUserDriver(): JdbcSqliteDriver {
        val root = Files.createTempDirectory("settings-test").toFile()
        val db = AidosStorage.openUser(DesktopPaths.userDb(root.path), "test-1.0") { "2026-08-05T00:00:00Z" }
        return db.driver as JdbcSqliteDriver
    }

    private fun openProjectDriver(): JdbcSqliteDriver {
        val root = Files.createTempDirectory("settings-proj-test").toFile()
        val db = AidosStorage.openProject(DesktopPaths.stateDb(root.path), "test-1.0") { "2026-08-05T00:00:00Z" }
        return db.driver as JdbcSqliteDriver
    }

    // ─── Catalogue ──────────────────────────────────────────────────────────

    @Test
    fun `every declared setting has a key, default, scope class, and codec`() {
        for (s in Settings.all) {
            assertTrue(s.key.isNotBlank(), "${s.key}: blank key")
            assertNotNull(s.default, "${s.key}: null default")
            assertNotNull(s.codec, "${s.key}: null codec")
            // SECURITY settings must have a mostRestrictive value (fail-closed rule)
            if (s.scopeClass == ScopeClass.SECURITY) {
                assertNotNull(s.mostRestrictive, "${s.key}: SECURITY setting without mostRestrictive")
            }
        }
    }

    @Test
    fun `settings can be found by key`() {
        assertNotNull(Settings.forKey("routing.remote_egress"))
        assertNull(Settings.forKey("no.such.setting"))
    }

    // ─── Resolution: nearest-first ──────────────────────────────────────────

    @Test
    fun `default is returned when no row exists`() {
        val store = SettingsStore(openUserDriver(), null)
        val r = store.resolve(Settings.retentionAgedDays)
        assertEquals(30, r.value)
        assertEquals(SettingOrigin.DEFAULT, r.origin)
        assertNull(r.originPath)
    }

    @Test
    fun `user scope overrides the default`() {
        val userDriver = openUserDriver()
        val writer = SettingsWriter(userDriver)
        writer.writeUser(Settings.retentionAgedDays, JsonPrimitive(90), SettingSetByKind.USER, "2026-08-05T00:00:00Z")

        val store = SettingsStore(userDriver, null)
        val r = store.resolve(Settings.retentionAgedDays)
        assertEquals(90, r.value)
        assertEquals(SettingOrigin.USER, r.origin)
        assertEquals("user.db", r.originPath)
    }

    @Test
    fun `project scope overrides user scope for PROJECT_SAFE settings`() {
        val userDriver = openUserDriver()
        val projectDriver = openProjectDriver()
        val writer = SettingsWriter(userDriver, projectDriver)
        writer.writeUser(Settings.retentionAgedDays, JsonPrimitive(90), SettingSetByKind.USER, "2026-08-05T00:00:00Z")
        writer.writeProject(Settings.retentionAgedDays, "proj-1", JsonPrimitive(7), SettingSetByKind.RUNTIME, "2026-08-05T00:00:00Z")

        val store = SettingsStore(userDriver, projectDriver)
        val r = store.resolve(Settings.retentionAgedDays, projectId = "proj-1")
        assertEquals(7, r.value)
        assertEquals(SettingOrigin.PROJECT, r.origin)
    }

    // ─── Scope enforcement ──────────────────────────────────────────────────

    @Test
    fun `SECURITY setting at project scope produces visible error and falls through`() {
        val userDriver = openUserDriver()
        val projectDriver = openProjectDriver()

        // Bypass the writer's check to simulate a project attempting to set a SECURITY key
        // (e.g. from a malicious aidos.toml). We need to insert directly.
        projectDriver.execute(null,
            "INSERT INTO settings (scope, scope_id, key, value_json, set_at, set_by_kind) " +
                "VALUES ('project', 'p1', 'routing.remote_egress', '\"ALLOW\"', '2026-08-05T00:00:00Z', 'RUNTIME')",
            0)

        val errors = mutableListOf<SettingError>()
        val store = SettingsStore(userDriver, projectDriver)
        val r = store.resolve(Settings.routingRemoteEgress, projectId = "p1", errors = errors)

        // The project's ALLOW must NOT take effect
        assertFalse(r.value == EgressPolicy.ALLOW, "project must not override SECURITY setting")
        // An error must have been recorded
        assertEquals(1, errors.size)
        assertEquals(SettingErrorClass.SCOPE_VIOLATION, errors[0].errorClass)
        assertTrue(errors[0].message.contains("cannot be set by a project"), errors[0].message)
        // Falls to default (ASK)
        assertEquals(EgressPolicy.ASK, r.value)
        assertEquals(SettingOrigin.DEFAULT, r.origin)
    }

    @Test
    fun `SettingsWriter rejects writing SECURITY setting to project scope`() {
        val projectDriver = openProjectDriver()
        val writer = SettingsWriter(openUserDriver(), projectDriver)
        val result = writer.writeProject(Settings.routingRemoteEgress, "p1",
            JsonPrimitive("ALLOW"), SettingSetByKind.USER, "2026-08-05T00:00:00Z")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("cannot be set at project scope"))
    }

    @Test
    fun `SettingsWriter rejects writing SPEND setting to project scope`() {
        val projectDriver = openProjectDriver()
        val writer = SettingsWriter(openUserDriver(), projectDriver)
        val result = writer.writeProject(Settings.budgetRunCostCeiling, "p1",
            JsonPrimitive(999_999), SettingSetByKind.USER, "2026-08-05T00:00:00Z")
        assertTrue(result.isFailure)
    }

    // ─── Fail-closed validation ──────────────────────────────────────────────

    @Test
    fun `invalid SECURITY value fails closed to mostRestrictive`() {
        val userDriver = openUserDriver()
        // Insert a bad value directly
        userDriver.execute(null,
            "INSERT INTO settings (scope, scope_id, key, value_json, set_at, set_by_kind) " +
                "VALUES ('user', NULL, 'routing.remote_egress', '\"BADVALUE\"', '2026-08-05T00:00:00Z', 'RUNTIME')",
            0)

        val errors = mutableListOf<SettingError>()
        val store = SettingsStore(userDriver, null)
        val r = store.resolve(Settings.routingRemoteEgress, errors = errors)

        // Must fail closed to NEVER (most restrictive), not ASK (default)
        assertEquals(EgressPolicy.NEVER, r.value, "SECURITY must fail closed to most restrictive")
        assertEquals(1, errors.size)
        assertEquals(SettingErrorClass.VALIDATION_FAILED, errors[0].errorClass)
    }

    @Test
    fun `invalid non-SECURITY value falls through to default`() {
        val userDriver = openUserDriver()
        userDriver.execute(null,
            "INSERT INTO settings (scope, scope_id, key, value_json, set_at, set_by_kind) " +
                "VALUES ('user', NULL, 'retention.aged_days', '\"not-a-number\"', '2026-08-05T00:00:00Z', 'RUNTIME')",
            0)

        val errors = mutableListOf<SettingError>()
        val store = SettingsStore(userDriver, null)
        val r = store.resolve(Settings.retentionAgedDays, errors = errors)

        assertEquals(30, r.value)  // default
        assertEquals(SettingOrigin.DEFAULT, r.origin)
        assertEquals(1, errors.size)
    }

    @Test
    fun `range violation falls through to default`() {
        val userDriver = openUserDriver()
        userDriver.execute(null,
            "INSERT INTO settings (scope, scope_id, key, value_json, set_at, set_by_kind) " +
                "VALUES ('user', NULL, 'retention.aged_days', '99999', '2026-08-05T00:00:00Z', 'RUNTIME')",
            0)

        val errors = mutableListOf<SettingError>()
        val store = SettingsStore(userDriver, null)
        val r = store.resolve(Settings.retentionAgedDays, errors = errors)

        assertEquals(30, r.value)  // range violation → default
        assertEquals(1, errors.size)
    }

    // ─── aidos.toml parsing ─────────────────────────────────────────────────

    @Test
    fun `toml parses flat key-value pairs`() {
        val toml = """
            # aidos.toml example
            [retention]
            aged_days = 60

            [model]
            default_kind = "LLM"
        """.trimIndent()
        val r = TomlParser.parse(toml)
        assertTrue(r.errors.isEmpty(), "no parse errors expected: ${r.errors}")
        assertEquals("60", r.values["retention.aged_days"]?.toString())
        assertEquals("\"LLM\"", r.values["model.default_kind"]?.toString())
    }

    @Test
    fun `toml parses string arrays`() {
        val toml = """
            [knowledge]
            exclude_paths = ["node_modules/**", "build/**"]
        """.trimIndent()
        val r = TomlParser.parse(toml)
        assertTrue(r.errors.isEmpty(), "no parse errors: ${r.errors}")
        val arr = r.values["knowledge.exclude_paths"]
        assertNotNull(arr)
        assertTrue(arr.toString().contains("node_modules"))
    }

    @Test
    fun `toml parse continues and reports per-line errors`() {
        val toml = """
            aged_days = 30
            bad line without equals
            default_kind = "LLM"
        """.trimIndent()
        val r = TomlParser.parse(toml)
        assertEquals(1, r.errors.size)
        assertEquals(2, r.errors[0].line)
        // The good lines are still parsed
        assertNotNull(r.values["aged_days"])
        assertNotNull(r.values["default_kind"])
    }

    @Test
    fun `toml boolean parsing`() {
        val toml = "allow_plaintext_http = false"
        val r = TomlParser.parse(toml)
        assertTrue(r.errors.isEmpty())
        assertEquals("false", r.values["allow_plaintext_http"]?.toString())
    }

    @Test
    fun `speech settings are declared with proper defaults`() {
        assertNotNull(Settings.speechTtsModelId)
        assertEquals("", Settings.speechTtsModelId.default)
        assertNotNull(Settings.speechSummaryOnFinish)
        assertEquals(false, Settings.speechSummaryOnFinish.default)
        assertNotNull(Settings.speechVoiceApprovals)
        assertEquals(VoiceApprovalsLevel.OFF, Settings.speechVoiceApprovals.default)
        assertNotNull(Settings.speechDuckOtherAudio)
        assertEquals(true, Settings.speechDuckOtherAudio.default)
    }

    @Test
    fun `speech settings can be found by key`() {
        assertNotNull(Settings.forKey("speech.tts_model_id"))
        assertNotNull(Settings.forKey("speech.summary_on_finish"))
        assertNotNull(Settings.forKey("speech.voice_approvals"))
        assertNotNull(Settings.forKey("speech.duck_other_audio"))
    }

    @Test
    fun `voice approvals enum encodes and decodes`() {
        val codec = Settings.speechVoiceApprovals.codec as EnumCodec<VoiceApprovalsLevel>
        val offEncoded = codec.encode(VoiceApprovalsLevel.OFF)
        val tier1Encoded = codec.encode(VoiceApprovalsLevel.TIER1)
        val tier2Encoded = codec.encode(VoiceApprovalsLevel.TIER2)

        assertEquals("OFF", offEncoded.toString().trim('"'))
        assertEquals("TIER1", tier1Encoded.toString().trim('"'))
        assertEquals("TIER2", tier2Encoded.toString().trim('"'))

        val offDecoded = codec.decode(offEncoded)
        assertTrue(offDecoded.isSuccess)
        assertEquals(VoiceApprovalsLevel.OFF, offDecoded.getOrNull())
    }
}
