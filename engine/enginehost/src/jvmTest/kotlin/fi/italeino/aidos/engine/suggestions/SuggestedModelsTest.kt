package fi.italeino.aidos.engine.suggestions

import fi.italeino.aidos.engine.ui.formatSize
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SuggestedModelsTest {

    @Test
    fun suggestionsPointAtJsilvanusEchoArtifacts() {
        val urls = SuggestedModels.all.associate { it.id to it.downloadUrl }
        assertEquals("https://huggingface.co/jsilvanus/aidos-echo-gguf/resolve/main/echo.gguf", urls["jsilvanus/aidos-echo-gguf"])
        assertEquals("https://huggingface.co/jsilvanus/aidos-rot13-gguf/resolve/main/rot13.gguf", urls["jsilvanus/aidos-rot13-gguf"])
    }

    @Test
    fun onlyGgufIsMarkedRunnable() {
        // The Android Engine has a llama.cpp backend and no ONNX runtime, so only GGUF can run.
        // A GGUF is still not runnable when it needs another backend (the Laya model).
        assertTrue(SuggestedModels.all.any { it.runnable })
        SuggestedModels.all.filter { it.runnable }.forEach { assertEquals("gguf", it.format, it.id) }
        assertTrue(SuggestedModels.all.first { it.id == "convaiinnovations/laya-multilingual" }.runnable.not())
    }

    @Test
    fun dismissedSuggestionsAreHiddenAndRestoreShowsAll() {
        val visible = SuggestedModels.visible(setOf("jsilvanus/aidos-rot13-gguf"))
        assertEquals(listOf("jsilvanus/aidos-echo-gguf", "convaiinnovations/laya-multilingual"), visible.map { it.id })
        assertEquals(SuggestedModels.all, SuggestedModels.visible(emptySet()))
    }

    @Test
    fun artifactNameIsFilesystemSafeAndDistinctPerRepo() {
        val names = SuggestedModels.all.map(SuggestedModels::artifactName)
        assertEquals(
            listOf(
                "jsilvanus_aidos-echo-gguf_echo.gguf",
                "jsilvanus_aidos-rot13-gguf_rot13.gguf",
                "mys_laya-multilingual-GGUF_laya_multilingual_q8_0.gguf",
            ),
            names,
        )
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
