package fi.italeino.aidos.engine.suggestions

import fi.italeino.aidos.engine.ui.formatSize
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SuggestedModelsTest {

    @Test
    fun suggestionsPointAtJsilvanusEchoArtifacts() {
        val urls = SuggestedModels.all.associate { it.id to it.downloadUrl }
        assertEquals("https://huggingface.co/jsilvanus/echo-gguf/resolve/main/echo.gguf", urls["jsilvanus/echo-gguf"])
        assertEquals("https://huggingface.co/jsilvanus/echo-onnx/resolve/main/echo.onnx", urls["jsilvanus/echo-onnx"])
    }

    @Test
    fun onlyGgufIsMarkedRunnable() {
        // The Android Engine has a llama.cpp backend and no ONNX runtime.
        assertTrue(SuggestedModels.all.single { it.format == "gguf" }.runnable)
        assertFalse(SuggestedModels.all.single { it.format == "onnx" }.runnable)
    }

    @Test
    fun dismissedSuggestionsAreHiddenAndRestoreShowsAll() {
        val visible = SuggestedModels.visible(setOf("jsilvanus/echo-onnx"))
        assertEquals(listOf("jsilvanus/echo-gguf"), visible.map { it.id })
        assertEquals(SuggestedModels.all, SuggestedModels.visible(emptySet()))
    }

    @Test
    fun artifactNameIsFilesystemSafeAndDistinctPerRepo() {
        val names = SuggestedModels.all.map(SuggestedModels::artifactName)
        assertEquals(listOf("jsilvanus_echo-gguf_echo.gguf", "jsilvanus_echo-onnx_echo.onnx"), names)
    }

    @Test
    fun formatSizeKeepsSubMegabyteModelsVisible() {
        assertEquals("258 KB", formatSize(264_192))
        assertEquals("2.3 MB", formatSize(2_367_680))
        assertEquals("4 GB", formatSize(4L * 1024 * 1024 * 1024))
        assertEquals("size unknown", formatSize(null))
        assertEquals("size unknown", formatSize(0))
    }
}
