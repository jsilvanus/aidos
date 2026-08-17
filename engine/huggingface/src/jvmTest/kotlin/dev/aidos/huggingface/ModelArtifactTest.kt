package dev.aidos.huggingface

import kotlin.test.Test
import kotlin.test.assertEquals

class ModelArtifactTest {
    @Test
    fun recognizesSupportedArtifactFormats() {
        assertEquals(ModelFormat.GGUF, ModelFormat.fromFilename("Qwen3-4B-Q4_K_M.gguf"))
        assertEquals(ModelFormat.ONNX, ModelFormat.fromFilename("model.onnx"))
        assertEquals(ModelFormat.EXECUTORCH, ModelFormat.fromFilename("model.pte"))
        assertEquals(ModelFormat.TFLITE, ModelFormat.fromFilename("model.tflite"))
        assertEquals(ModelFormat.OPENVINO, ModelFormat.fromFilename("openvino_model.xml"))
        assertEquals(ModelFormat.CORE_ML, ModelFormat.fromFilename("model.mlmodel"))
        assertEquals(ModelFormat.SAFETENSORS, ModelFormat.fromFilename("model.safetensors"))
    }

    @Test
    fun mapsFormatsToDefaultBackendsOnlyWhereAidosHasAnIntendedRuntime() {
        assertEquals(listOf("llama.cpp"), ModelFormat.GGUF.defaultBackends)
        assertEquals(listOf("onnx-runtime"), ModelFormat.ONNX.defaultBackends)
        assertEquals(listOf("executorch"), ModelFormat.EXECUTORCH.defaultBackends)
        assertEquals(emptyList(), ModelFormat.SAFETENSORS.defaultBackends)
    }
}
