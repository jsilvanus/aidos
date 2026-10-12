package dev.aidos.vision

import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VisionPackageTest {
    private val root = Files.createTempDirectory("vision-pkg").toFile()

    @AfterTest
    fun cleanUp() { root.deleteRecursively() }

    private fun pkg(name: String, vararg graphs: String, config: Boolean = true) = root.resolve(name).apply {
        mkdirs()
        graphs.forEach { resolve(it).writeBytes(ByteArray(10)) }
        if (config) {
            resolve("tokenizer.json").writeText("{}")
            resolve("config.json").writeText(
                """{"model_type":"idefics3","image_token_id":49190,"text_config":{"hidden_size":576,"max_position_embeddings":8192}}"""
            )
            resolve("processor_config.json").writeText("""{"image_seq_len":64}""")
            resolve("generation_config.json").writeText("""{"eos_token_id":49279}""")
        }
    }

    private val uint8 = arrayOf("vision_encoder_uint8.onnx", "embed_tokens_uint8.onnx", "decoder_model_merged_uint8.onnx")

    @Test
    fun aCompleteVariantIsAPackage() {
        val dir = pkg("smol", *uint8)
        assertTrue(VisionPackage.isPackage(dir))
        assertEquals(30, VisionPackage.graphs(dir)!!.sizeBytes)
        val cfg = VisionPackage.readConfig(dir)
        assertEquals(576, cfg.hiddenSize)
        assertEquals(64, cfg.imageSeqLen)
        assertEquals(49190, cfg.imageTokenId)
        assertEquals(setOf(49279), cfg.eosTokenIds)
        assertEquals(4096, cfg.contextWindow)
    }

    @Test
    fun graphsFromDifferentVariantsDoNotMakeAPackage() {
        val dir = pkg("mixed", "vision_encoder_uint8.onnx", "embed_tokens_q4.onnx", "decoder_model_merged_fp16.onnx")
        assertNull(VisionPackage.graphs(dir))
        assertFalse(VisionPackage.isPackage(dir))
    }

    @Test
    fun uint8IsPreferredWhenSeveralVariantsAreInstalled() {
        val dir = pkg("both", *uint8, "vision_encoder_int8.onnx", "embed_tokens_int8.onnx", "decoder_model_merged_int8.onnx", "vision_encoder.onnx", "embed_tokens.onnx", "decoder_model_merged.onnx")
        assertEquals("vision_encoder_uint8.onnx", VisionPackage.graphs(dir)!!.encoder.name)
    }

    @Test
    fun missingConfigMeansNotAPackage() {
        assertFalse(VisionPackage.isPackage(pkg("bare", *uint8, config = false)))
    }

    @Test
    fun otherModelTypesAreRefusedWithAClearMessage() {
        val dir = pkg("other", *uint8)
        dir.resolve("config.json").writeText("""{"model_type":"llava","text_config":{}}""")
        val e = runCatching { VisionPackage.readConfig(dir) }.exceptionOrNull()
        assertNotNull(e)
        assertTrue(e.message!!.contains("idefics3"))
    }

    @Test
    fun packagesAreListedAsVisionModelsAndIdsCannotEscapeTheModelsDir() {
        pkg("smol", *uint8)
        val packages = VisionPackages(root, JvmImageDecoder)
        val d = packages.descriptors().single()
        assertEquals("smol", d.id)
        assertEquals(dev.aidos.kernel.ModelKind.VISION, d.kind)
        assertTrue(packages.contains("smol"))
        assertFalse(packages.contains("../smol"))
        assertNull(packages.dirAt(root.parentFile.absolutePath))
        assertNotNull(packages.dirAt(root.resolve("smol").absolutePath))
    }
}
