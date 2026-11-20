package fi.italeino.aidos.engine.desktop

import fi.italeino.aidos.engine.usage.UsageCategory
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FileUsageStoreTest {

    @Test
    fun persistsLifetimeAcrossRestartsWhileResettingSession() {
        val stateDir = Files.createTempDirectory("file-usage-store-test").toFile()
        try {
            val first = FileUsageStore(stateDir, maxConcurrency = 2)
            first.record(UsageCategory.CHAT, tokens = 250L, timestampMs = 1000L)
            first.record(UsageCategory.STT, audioSeconds = 2.5, timestampMs = 2000L)

            val usageFile = File(stateDir, "usage.json")
            assertTrue(usageFile.isFile, "usage.json should be written in stateDir")

            val second = FileUsageStore(stateDir, maxConcurrency = 2)
            val snap = second.snapshot()
            assertEquals(0L, snap.session[UsageCategory.CHAT]?.requests)
            assertEquals(0L, snap.session[UsageCategory.CHAT]?.tokens)
            assertEquals(1L, snap.lifetime[UsageCategory.CHAT]?.requests)
            assertEquals(250L, snap.lifetime[UsageCategory.CHAT]?.tokens)
            assertEquals(1L, snap.lifetime[UsageCategory.STT]?.requests)
            assertEquals(2.5, snap.lifetime[UsageCategory.STT]?.audioSeconds ?: 0.0, 0.001)
        } finally {
            stateDir.deleteRecursively()
        }
    }

    @Test
    fun recoversGracefullyFromCorruptUsageFile() {
        val stateDir = Files.createTempDirectory("file-usage-corrupt-test").toFile()
        try {
            File(stateDir, "usage.json").writeText("{not-valid-json")
            val store = FileUsageStore(stateDir)
            assertEquals(0L, store.snapshot().lifetime[UsageCategory.CHAT]?.requests)
            assertTrue(File(stateDir, "usage.json.corrupt").isFile)
        } finally {
            stateDir.deleteRecursively()
        }
    }
}
