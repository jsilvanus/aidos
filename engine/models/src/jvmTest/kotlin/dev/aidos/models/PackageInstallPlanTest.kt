package dev.aidos.models

import dev.aidos.huggingface.ModelFormat
import dev.aidos.huggingface.ModelPackage
import dev.aidos.huggingface.RepoFile
import dev.aidos.kernel.ModelKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PackageInstallPlanTest {
    private fun pkg(speech: Boolean = false, vision: Boolean = false, decision: Boolean = false) = ModelPackage(
        modelId = "o/m", name = "m", format = ModelFormat.ONNX, directory = "onnx",
        files = listOf(RepoFile("onnx/model.onnx", 10, "a"), RepoFile("tokenizer.json", 1, "b"), RepoFile("onnx/enc.onnx_data", 5)),
        isDecisionPackage = decision, isSpeechPackage = speech, isVisionPackage = vision,
    )

    @Test
    fun choosesDecisionThenSpeechThenVision() {
        val d = pkg(decision = true); val s = pkg(speech = true); val v = pkg(vision = true)
        assertEquals(d, PackageInstall.choose(listOf(v, s, d)))
        assertEquals(s, PackageInstall.choose(listOf(v, s)))
        assertEquals(v, PackageInstall.choose(listOf(v)))
        assertNull(PackageInstall.choose(listOf(pkg())))
    }

    @Test
    fun requestMapsKindAndPutsGraphsLastUnderBaseNames() {
        val r = PackageInstall.toRequest("o/m", pkg(vision = true), "/models/o_m")
        assertEquals(ModelKind.VISION, r.kind)
        assertEquals("onnx", r.format)
        assertEquals("model.onnx", r.files.last().name)
        assertEquals(setOf("enc.onnx_data", "tokenizer.json", "model.onnx"), r.files.map { it.name }.toSet())
        assertEquals("https://huggingface.co/o/m/resolve/main/onnx/model.onnx", r.files.last().downloadUrl)
        assertEquals(ModelKind.STT, PackageInstall.kindOf(pkg(speech = true)))
        assertEquals(ModelKind.DECISION, PackageInstall.kindOf(pkg(decision = true)))
    }

    @Test
    fun runnableCoversEveryOnnxKindTheEngineHas() {
        assertTrue(PackageInstall.isRunnable("gguf", ModelKind.LLM))
        for (k in listOf(ModelKind.DECISION, ModelKind.STT, ModelKind.VISION)) assertTrue(PackageInstall.isRunnable("onnx", k))
        assertFalse(PackageInstall.isRunnable("onnx", ModelKind.LLM))
        assertFalse(PackageInstall.isRunnable("safetensors", ModelKind.LLM))
    }

    @Test
    fun directoryNameIsFilesystemSafe() {
        assertEquals("HuggingFaceTB_SmolVLM-256M", PackageInstall.directoryName("HuggingFaceTB/SmolVLM-256M"))
    }
}
