package fi.italeino.aidos.engine.desktop

import dev.aidos.modelruntime.ModelRunSettings
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DesktopPreferencesRunSettingsTest {
    private val file = Files.createTempDirectory("prefs").resolve("preferences.json").toFile()

    @Test
    fun settingsAreSavedPerModelAndSurviveARestart() {
        val prefs = DesktopPreferences(file)
        assertTrue(prefs.runSettingsFor("a/b").isDefault, "an unconfigured model runs with defaults")
        prefs.setRunSettings("a/b", ModelRunSettings(contextLength = 4096, temperature = 0.1f, topK = 10))
        prefs.setRunSettings("c/d", ModelRunSettings(threads = 2))

        val reopened = DesktopPreferences(file)
        assertEquals(ModelRunSettings(contextLength = 4096, temperature = 0.1f, topK = 10), reopened.runSettingsFor("a/b"))
        assertEquals(ModelRunSettings(threads = 2), reopened.runSettingsFor("c/d"))
        assertEquals(ModelRunSettings(threads = 2), reopened.runSettingsSource.settingsFor("c/d"))
    }

    @Test
    fun savingDefaultsRemovesTheRecordAndStoredValuesAreClampedOnRead() {
        val prefs = DesktopPreferences(file)
        prefs.setRunSettings("m", ModelRunSettings(threads = 8))
        prefs.setRunSettings("m", ModelRunSettings())
        assertTrue(prefs.data.value.modelRunSettings.isEmpty())

        prefs.setRunSettings("m", ModelRunSettings(contextLength = 1, threads = 9999))
        assertEquals(ModelRunSettings.CONTEXT_RANGE.first, prefs.runSettingsFor("m").contextLength)
        assertEquals(ModelRunSettings.THREADS_RANGE.last, prefs.runSettingsFor("m").threads)
    }
}

class DesktopRunSettingsMmapTest {
    @kotlin.test.Test
    fun mmapSettingSurvivesStorageAndReachesTheRuntimeSettings() {
        val off = dev.aidos.modelruntime.ModelRunSettings(useMmap = false)
        val stored = StoredRunSettings.of(off)
        kotlin.test.assertEquals(false, stored.toSettings().useMmap)
        // Default (null) stays default: mmap on where the platform supports it.
        kotlin.test.assertEquals(null, StoredRunSettings.of(dev.aidos.modelruntime.ModelRunSettings.DEFAULT).toSettings().useMmap)
    }
}
