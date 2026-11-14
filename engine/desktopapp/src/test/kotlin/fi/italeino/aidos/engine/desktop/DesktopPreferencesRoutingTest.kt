package fi.italeino.aidos.engine.desktop

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DesktopPreferencesRoutingTest {
    @Test
    fun persistsGlobalProjectAndRunLaunchRoutingSettings() {
        val dir = createTempDirectory("aidos-desktop-prefs-test").toFile()
        try {
            val file = File(dir, "preferences.json")
            val prefs = DesktopPreferences(file)

            prefs.setGlobalMainModelId("qwen-main")
            prefs.setGlobalDefaultRunModelId("qwen-run")
            prefs.setGlobalLocality("trusted_cluster")
            prefs.setGlobalGuardianPolicy("GUARDIAN")
            prefs.setGlobalDecisionModelId("laya-multilingual")
            prefs.setGlobalGuardianLlmModelId("guardian-llm")

            prefs.setProjectMainModelId("project-main")
            prefs.setProjectDefaultRunModelId("project-run")
            prefs.setProjectAllowMainModelRunOverride(false)
            prefs.setProjectEnforcedLocality("local_only")
            prefs.setProjectGuardianPolicy("MANUAL")
            prefs.setProjectDecisionModelId("laya-multilingual")
            prefs.setProjectGuardianLlmModelId("project-guardian-llm")

            prefs.setRunGuardianPolicyOverride("GUARDIAN")
            prefs.setRunModelOverride("run-override")
            prefs.setRunLocalityOverride("any")

            val reloaded = DesktopPreferences(file).data.value
            assertEquals("qwen-main", reloaded.globalMainModelId)
            assertEquals("qwen-run", reloaded.globalDefaultRunModelId)
            assertEquals("trusted_cluster", reloaded.globalLocality)
            assertEquals("GUARDIAN", reloaded.globalGuardianPolicy)
            assertEquals("laya-multilingual", reloaded.globalDecisionModelId)
            assertEquals("guardian-llm", reloaded.globalGuardianLlmModelId)

            assertEquals("project-main", reloaded.projectMainModelId)
            assertEquals("project-run", reloaded.projectDefaultRunModelId)
            assertEquals(false, reloaded.projectAllowMainModelRunOverride)
            assertEquals("local_only", reloaded.projectEnforcedLocality)
            assertEquals("MANUAL", reloaded.projectGuardianPolicy)
            assertEquals("project-guardian-llm", reloaded.projectGuardianLlmModelId)

            assertEquals("GUARDIAN", reloaded.runGuardianPolicyOverride)
            assertEquals("run-override", reloaded.runModelOverride)
            assertEquals("any", reloaded.runLocalityOverride)

            prefs.setRunGuardianPolicyOverride(null)
            prefs.setRunLocalityOverride(null)
            val cleared = DesktopPreferences(file).data.value
            assertNull(cleared.runGuardianPolicyOverride)
            assertNull(cleared.runLocalityOverride)
        } finally {
            dir.deleteRecursively()
        }
    }
}
