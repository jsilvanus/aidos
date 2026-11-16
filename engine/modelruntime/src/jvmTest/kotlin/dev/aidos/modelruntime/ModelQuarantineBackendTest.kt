package dev.aidos.modelruntime

import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** RFC-0022: the llama.cpp backend moves a mismatched file to `quarantine/`, where nothing reads it. */
class ModelQuarantineBackendTest {
    private fun withModelsDir(block: suspend (File) -> Unit) = runTest {
        val dir = Files.createTempDirectory("aidos-models-quarantine").toFile()
        val previous = System.getProperty("aidos.models.dir")
        System.setProperty("aidos.models.dir", dir.path)
        try {
            block(dir)
        } finally {
            if (previous == null) System.clearProperty("aidos.models.dir") else System.setProperty("aidos.models.dir", previous)
            dir.deleteRecursively()
        }
    }

    @Test
    fun `quarantined model is out of the store, not installed and cannot be loaded`() = withModelsDir { dir ->
        val backend = LlamaCppInferenceBackend()
        File(dir, "bad-model.gguf").writeText("not the weights")

        backend.quarantine("bad-model", "sha256:want", "sha256:0123456789abcdef")

        assertFalse(File(dir, "bad-model.gguf").exists())
        val quarantined = File(dir, "quarantine").listFiles { f -> f.name.endsWith(".quarantined") }!!
        assertEquals(1, quarantined.size)
        assertTrue(backend.installed().none { it.id == "bad-model" })
        assertTrue(backend.catalog().none { it.id == "bad-model" })
        assertTrue(backend.load("bad-model").isFailure)
    }

    @Test
    fun `engine start applies retention to old quarantined files`() = withModelsDir { dir ->
        val q = File(dir, "quarantine").apply { mkdirs() }
        val stale = File(q, "old.gguf.aaa.quarantined").apply {
            writeText("x")
            setLastModified(System.currentTimeMillis() - 8L * 24 * 60 * 60 * 1000)
        }
        LlamaCppInferenceBackend()
        assertFalse(stale.exists())
    }
}
