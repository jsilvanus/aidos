package fi.italeino.aidos.engine.ui

import dev.aidos.decisions.LayaPackage
import dev.aidos.kernel.ModelKind
import dev.aidos.stt.WhisperPackage
import dev.aidos.vision.VisionPackage
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PackageValidationTest {

    private fun isRunnable(format: String, kind: ModelKind): Boolean =
        format == "gguf" || (format == "onnx" && (kind == ModelKind.DECISION || kind == ModelKind.STT || kind == ModelKind.VISION))

    private fun validateInstalled(file: File?, recordedSizeBytes: Long): Boolean {
        if (file == null || recordedSizeBytes <= 0L) return false
        return when {
            file.isFile -> file.length() == recordedSizeBytes
            file.isDirectory -> (LayaPackage.isPackage(file) ||
                WhisperPackage.isPackage(file) ||
                VisionPackage.isPackage(file)) &&
                file.listFiles { f -> f.isFile }.orEmpty().sumOf { it.length() } == recordedSizeBytes
            else -> false
        }
    }

    @Test
    fun isRunnableLogicSupportsGgufAndOnnxPackages() {
        assertTrue(isRunnable("gguf", ModelKind.LLM))
        assertTrue(isRunnable("gguf", ModelKind.EMBEDDING))
        assertTrue(isRunnable("onnx", ModelKind.DECISION))
        assertTrue(isRunnable("onnx", ModelKind.STT))
        assertTrue(isRunnable("onnx", ModelKind.VISION))

        assertFalse(isRunnable("onnx", ModelKind.LLM))
        assertFalse(isRunnable("safetensors", ModelKind.LLM))
        assertFalse(isRunnable("unknown", ModelKind.DECISION))
    }

    @Test
    fun validatesSingleFileGguf() {
        val temp = Files.createTempFile("model", ".gguf").toFile().also { it.deleteOnExit() }
        temp.writeText("gguf-weights")
        val size = temp.length()

        assertTrue(validateInstalled(temp, size))
        assertFalse(validateInstalled(temp, size + 10))
        assertFalse(validateInstalled(File(temp.parentFile, "nonexistent.gguf"), size))
    }

    @Test
    fun validatesLayaDecisionPackageDirectory() {
        val dir = Files.createTempDirectory("laya-pkg").toFile().also { it.deleteOnExit() }
        File(dir, "model.onnx").writeText("graph")
        File(dir, LayaPackage.TOKENIZER).writeText("{}")
        File(dir, LayaPackage.CONFIG).writeText("{}")

        val totalSize = dir.listFiles { f -> f.isFile }!!.sumOf { it.length() }

        assertTrue(LayaPackage.isPackage(dir))
        assertTrue(validateInstalled(dir, totalSize))
        assertFalse(validateInstalled(dir, totalSize - 1))

        // Incomplete package fails
        File(dir, LayaPackage.CONFIG).delete()
        assertFalse(LayaPackage.isPackage(dir))
        assertFalse(validateInstalled(dir, dir.listFiles { f -> f.isFile }!!.sumOf { it.length() }))
    }

    @Test
    fun validatesWhisperSttPackageDirectory() {
        val dir = Files.createTempDirectory("whisper-pkg").toFile().also { it.deleteOnExit() }
        File(dir, "encoder_model.onnx").writeText("enc")
        File(dir, "decoder_model_merged.onnx").writeText("dec")
        File(dir, "tokenizer.json").writeText("{}")

        val totalSize = dir.listFiles { f -> f.isFile }!!.sumOf { it.length() }

        assertTrue(WhisperPackage.isPackage(dir))
        assertTrue(validateInstalled(dir, totalSize))

        // Incomplete package fails
        File(dir, "decoder_model_merged.onnx").delete()
        assertFalse(WhisperPackage.isPackage(dir))
        assertFalse(validateInstalled(dir, dir.listFiles { f -> f.isFile }!!.sumOf { it.length() }))
    }

    @Test
    fun validatesVisionSmolVlmPackageDirectory() {
        val dir = Files.createTempDirectory("vision-pkg").toFile().also { it.deleteOnExit() }
        File(dir, "vision_encoder_uint8.onnx").writeText("vis")
        File(dir, "embed_tokens_uint8.onnx").writeText("emb")
        File(dir, "decoder_model_merged_uint8.onnx").writeText("dec")
        File(dir, "tokenizer.json").writeText("{}")
        File(dir, "config.json").writeText("{}")
        File(dir, "processor_config.json").writeText("{}")
        File(dir, "generation_config.json").writeText("{}")

        val totalSize = dir.listFiles { f -> f.isFile }!!.sumOf { it.length() }

        assertTrue(VisionPackage.isPackage(dir))
        assertTrue(validateInstalled(dir, totalSize))

        // Incomplete package fails
        File(dir, "vision_encoder_uint8.onnx").delete()
        assertFalse(VisionPackage.isPackage(dir))
        assertFalse(validateInstalled(dir, dir.listFiles { f -> f.isFile }!!.sumOf { it.length() }))
    }

    @Test
    fun recursiveDeletionCleansUpDirectoryTree() {
        val dir = Files.createTempDirectory("pkg-to-delete").toFile().also { it.deleteOnExit() }
        File(dir, "file1.onnx").writeText("data1")
        File(dir, "file2.json").writeText("data2")
        val sub = File(dir, "subdir").also { it.mkdir() }
        File(sub, "nested.bin").writeText("data3")

        assertTrue(dir.exists())
        if (dir.isDirectory) dir.deleteRecursively() else if (dir.isFile) dir.delete()
        assertFalse(dir.exists())
    }
}
