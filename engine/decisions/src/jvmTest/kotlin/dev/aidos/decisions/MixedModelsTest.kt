package dev.aidos.decisions

import dev.aidos.kernel.ModelKind
import dev.aidos.modelruntime.GlobalModelRuntime
import dev.aidos.modelruntime.LlamaCppInferenceBackend
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * One models directory holding a real GGUF (the checked-in echo fixture) and an ONNX decision package,
 * served through the same [GlobalModelRuntime] the Engine uses: what `/v1/models` lists, and what
 * load / unload / delete do to each. The package's graph is a stub (no weights in the repo), so the ONNX
 * side checks routing and listing; loading real Laya weights is covered by the `AIDOS_LAYA_*` tests.
 */
class MixedModelsTest {
    private fun echoGguf(): File? {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            File(dir, "models/echo-gguf/echo.gguf").takeIf { it.isFile }?.let { return it }
            dir = dir.parentFile
        }
        return null
    }

    private fun setup(): Pair<File, GlobalModelRuntime>? {
        val echo = echoGguf() ?: return null
        val models = createTempDirectory("mixed-models").toFile()
        echo.copyTo(File(models, "echo.gguf"))
        File(models, "laya").also {
            it.mkdirs()
            File(it, "model.onnx").writeText("stub-graph")
            File(it, LayaPackage.TOKENIZER).writeText("{}")
            File(it, LayaPackage.CONFIG).writeText("{\"max_len\": 64}")
        }
        System.setProperty("aidos.models.dir", models.absolutePath)
        return models to GlobalModelRuntime(OnnxDecisionBackend(LlamaCppInferenceBackend(), models))
    }

    @Test
    fun listsBothFormatsWithTheirKindAndFormat() = runTest {
        val (_, runtime) = setup() ?: return@runTest
        val installed = runtime.installed().associateBy { it.id }

        val gguf = assertNotNull(installed["echo"])
        assertEquals(ModelKind.LLM, gguf.kind)
        assertEquals("gguf", gguf.format)

        val onnx = assertNotNull(installed["laya"])
        assertEquals(ModelKind.DECISION, onnx.kind)
        assertEquals("onnx", onnx.format)
        assertEquals(64, onnx.contextWindow)
    }

    @Test
    fun ggufLoadsUnloadsAndDeletesWithoutTouchingTheOnnxPackage() = runTest {
        val (models, runtime) = setup() ?: return@runTest

        assertTrue(runtime.load("echo").isSuccess, "the GGUF fixture should load through llama.cpp")
        assertEquals(listOf("echo"), runtime.loaded())
        runtime.unload("echo")
        assertTrue(runtime.loaded().isEmpty())

        runtime.delete("echo")
        assertFalse(File(models, "echo.gguf").exists())
        assertTrue(File(models, "laya/model.onnx").exists(), "deleting a GGUF must not touch ONNX packages")
        assertEquals(listOf("laya"), runtime.installed().map { it.id })
    }

    @Test
    fun anOnnxPackageIsNeverHandedToLlamaCpp() = runTest {
        val (models, runtime) = setup() ?: return@runTest

        // The stub graph is not loadable; the point is the error comes from the ONNX path.
        val failure = runtime.load("laya").exceptionOrNull()
        assertNotNull(failure)
        assertTrue(failure.message.orEmpty().startsWith("MODEL_LOAD_FAILED: laya"), failure.message)
        assertTrue(runtime.loaded().isEmpty())

        runtime.delete("laya")
        assertFalse(File(models, "laya").exists(), "the whole package directory is removed")
        assertTrue(File(models, "echo.gguf").exists(), "deleting an ONNX package must not touch GGUF files")
    }

    @Test
    fun aCatalogInstalledPackageLoadsByItsRecordedPath() = runTest {
        val (models, runtime) = setup() ?: return@runTest

        // Recorded under the Hugging Face id "org/laya"; the directory is the sanitized form.
        val failure = runtime.load("org/laya", File(models, "laya").absolutePath).exceptionOrNull()
        assertNotNull(failure)
        assertTrue(failure.message.orEmpty().startsWith("MODEL_LOAD_FAILED: org/laya"), failure.message)

        val outside = runtime.load("org/laya", createTempDirectory().toString()).exceptionOrNull()
        assertNotNull(outside)
        assertFalse(outside.message.orEmpty().startsWith("MODEL_LOAD_FAILED"), "a path outside the models dir is not treated as a package")
    }
}
