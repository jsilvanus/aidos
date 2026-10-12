package dev.aidos.decisions

import dev.aidos.kernel.ModelKind
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Package lookup, listing and deletion, without needing a model checkpoint. */
class DecisionPackagesTest {
    private val models: File = Files.createTempDirectory("decision-packages").toFile().also { it.deleteOnExit() }

    private fun pkg(name: String, withConfig: Boolean = true): File = File(models, name).also {
        it.mkdirs()
        File(it, "model.onnx").writeText("graph")
        File(it, "model.onnx_data").writeText("tensors")
        File(it, LayaPackage.TOKENIZER).writeText("{}")
        if (withConfig) File(it, LayaPackage.CONFIG).writeText("{\"max_len\": 128}")
    }

    @Test
    fun listsOnlyCompletePackagesAsOnnxDecisionModels() {
        pkg("good")
        pkg("incomplete", withConfig = false)
        File(models, "stray.gguf").writeText("not a package")

        val listed = DecisionPackages(models).descriptors()

        assertEquals(listOf("good"), listed.map { it.id })
        assertEquals(ModelKind.DECISION, listed.single().kind)
        assertEquals("onnx", listed.single().format)
        assertEquals(128, listed.single().contextWindow)
    }

    @Test
    fun resolvesACatalogInstallByItsRecordedPathNotItsHuggingFaceId() {
        val dir = pkg("org_laya-onnx")
        val packages = DecisionPackages(models)

        // The catalog id "org/laya-onnx" can never be a directory name...
        assertFalse(packages.contains("org/laya-onnx"))
        // ...so the host loads by the recorded path.
        assertEquals(dir.canonicalFile, packages.dirAt(dir.absolutePath))
        assertTrue(packages.handles("org/laya-onnx", dir.absolutePath))
        assertFalse(packages.handles("org/laya-onnx", null))
    }

    @Test
    fun refusesPackagePathsOutsideTheModelsDirectory() {
        val outside = Files.createTempDirectory("elsewhere").toFile().also { it.deleteOnExit() }
        File(outside, "model.onnx").writeText("g")
        File(outside, LayaPackage.TOKENIZER).writeText("{}")
        File(outside, LayaPackage.CONFIG).writeText("{}")
        val packages = DecisionPackages(models)

        assertNull(packages.dirAt(outside.absolutePath))
        assertNull(packages.dirAt(File(models, "../" + outside.name).path))
        assertNull(packages.dirAt(models.absolutePath), "the models dir itself is not a package")
        assertNull(packages.dirAt(null))
    }

    @Test
    fun deleteRemovesTheWholeDirectoryIncludingTheExternalDataSidecar() {
        val dir = pkg("gone")
        val packages = DecisionPackages(models)

        assertTrue(packages.delete("gone"))
        assertFalse(dir.exists())
        assertFalse(packages.delete("gone"), "deleting twice reports nothing to delete")
    }

    @Test
    fun unloadOfAModelThatIsNotLoadedIsDelegated() {
        pkg("idle")
        assertFalse(DecisionPackages(models).unload("idle"))
        assertNotNull(DecisionPackages(models).dirFor("idle"))
    }
}
