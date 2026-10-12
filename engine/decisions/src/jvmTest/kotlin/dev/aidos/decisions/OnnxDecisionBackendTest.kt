package dev.aidos.decisions

import dev.aidos.kernel.ModelAdapter
import dev.aidos.kernel.ModelDescriptor
import dev.aidos.kernel.ModelKind
import dev.aidos.modelruntime.InferenceBackend
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The decorator that makes decision models loadable through the normal runtime, without disturbing llama.cpp ids. */
class OnnxDecisionBackendTest {
    private class Recording : InferenceBackend {
        val calls = mutableListOf<String>()
        override suspend fun catalog() = emptyList<ModelDescriptor>()
        override suspend fun installed() = emptyList<ModelDescriptor>()
        override suspend fun computeDigest(modelId: String) = "d".also { calls += "digest:$modelId" }
        override suspend fun delete(modelId: String) { calls += "delete:$modelId" }
        override suspend fun load(modelId: String): Result<ModelAdapter> { calls += "load:$modelId"; return Result.failure(IllegalStateException("delegated")) }
        override suspend fun unload(modelId: String) { calls += "unload:$modelId" }
    }

    @Test
    fun unknownIdsAndPathLikeIdsGoToTheDelegate() = runTest {
        val dir = createTempDirectory().toFile()
        val delegate = Recording()
        val backend = OnnxDecisionBackend(delegate, dir)
        assertTrue(backend.load("llama-3").isFailure)
        assertTrue(backend.load("../escape").isFailure)
        backend.unload("llama-3"); backend.delete("llama-3")
        assertEquals(listOf("load:llama-3", "load:../escape", "unload:llama-3", "delete:llama-3"), delegate.calls)
        assertTrue(backend.installed().isEmpty())
    }

    @Test
    fun anIncompletePackageIsNotAModel() = runTest {
        val dir = createTempDirectory().toFile()
        File(dir, "half").mkdirs(); File(dir, "half/model.onnx").writeText("x")
        assertTrue(OnnxDecisionBackend(Recording(), dir).installed().isEmpty())
    }

    @Test
    fun aRealPackageIsListedLoadedAndAnswers() = runTest {
        val src = System.getenv("AIDOS_LAYA_DIR")?.let(::File) ?: return@runTest println("skipped: AIDOS_LAYA_DIR not set")
        val onnx = System.getenv("AIDOS_LAYA_ONNX")?.let(::File) ?: return@runTest println("skipped: AIDOS_LAYA_ONNX not set")
        val models = createTempDirectory().toFile()
        val pkg = File(models, "laya-multilingual").apply { mkdirs() }
        // Hard links where possible: the graph is 1.3 GB.
        fun link(from: File, to: File) = try { java.nio.file.Files.createLink(to.toPath(), from.toPath()) } catch (e: Exception) { from.copyTo(to) }
        link(onnx, File(pkg, "model.onnx"))
        File(src, "tokenizer/tokenizer.json").copyTo(File(pkg, LayaPackage.TOKENIZER))
        File(src, LayaPackage.CONFIG).copyTo(File(pkg, LayaPackage.CONFIG))

        val delegate = Recording()
        val backend = OnnxDecisionBackend(delegate, models)
        val listed = backend.installed().single()
        assertEquals(ModelKind.DECISION, listed.kind)
        assertEquals("laya-multilingual", listed.id)

        val adapter = backend.load("laya-multilingual").getOrThrow() as DecisionModelAdapter
        val result = adapter.decide(
            DecisionRequest(
                "poista edellinen lause",
                mapOf("cmd" to DecisionQuestion.Noul("The speaker is giving an instruction to the editor rather than dictating content.")),
            )
        ).getOrThrow()
        assertTrue(result.answers.getValue("cmd") is DecisionAnswer.Noul)
        assertTrue(delegate.calls.isEmpty(), "decision ids must not reach llama.cpp")

        backend.unload("laya-multilingual")
        assertFalse(delegate.calls.contains("unload:laya-multilingual"))
        assertTrue(backend.delete("laya-multilingual").let { true })
        assertFalse(File(models, "laya-multilingual").exists())
    }
}
