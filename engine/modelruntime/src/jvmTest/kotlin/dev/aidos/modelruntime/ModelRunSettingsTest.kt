package dev.aidos.modelruntime

import dev.aidos.kernel.ModelAdapter
import dev.aidos.kernel.ModelDescriptor
import dev.aidos.kernel.ModelKind
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Per-model run settings (RFC-0103): clamping, how the runtime hands them to a backend, and that llama.cpp honours them. */
class ModelRunSettingsTest {
    @Test
    fun valuesAreClampedIntoWhatLlamaCppAccepts() {
        val c = ModelRunSettings(
            contextLength = 10, threads = 500, batchSize = 1, gpuLayers = -3,
            temperature = 9f, topP = 0f, topK = 5000,
        ).coerced()
        assertEquals(ModelRunSettings.CONTEXT_RANGE.first, c.contextLength)
        assertEquals(ModelRunSettings.THREADS_RANGE.last, c.threads)
        assertEquals(ModelRunSettings.BATCH_RANGE.first, c.batchSize)
        assertEquals(0, c.gpuLayers)
        assertEquals(2f, c.temperature)
        assertEquals(0.05f, c.topP)
        assertEquals(200, c.topK)
        // Unset stays unset: null means "the backend's default", not a value to clamp.
        assertTrue(ModelRunSettings().coerced().isDefault)
    }

    private class RecordingBackend : InferenceBackend {
        val seen = mutableListOf<ModelRunSettings>()
        override suspend fun catalog() = emptyList<ModelDescriptor>()
        override suspend fun installed() = listOf(ModelDescriptor("m", "m", ModelKind.LLM, "local", true, 512, null, null))
        override suspend fun computeDigest(modelId: String) = ""
        override suspend fun delete(modelId: String) = Unit
        override suspend fun load(modelId: String): Result<ModelAdapter> = Result.failure(IllegalStateException("2-arg load must not be used"))
        override suspend fun load(modelId: String, artifactPath: String?, settings: ModelRunSettings): Result<ModelAdapter> {
            seen += settings
            return Result.success(object : ModelAdapter {
                override val providerId = "local"
                override val modelId = modelId
                override val modelVersion = "v"
                override val contextWindow = settings.contextLength ?: 0
                override val isLocal = true
                override fun supportsNativeToolCalls() = false
                override suspend fun invoke(request: dev.aidos.kernel.ModelRequest) = Result.failure<dev.aidos.kernel.ModelResponse>(UnsupportedOperationException())
            })
        }
        override suspend fun unload(modelId: String) = Unit
    }

    @Test
    fun theRuntimeReadsTheSettingsAtLoadClampsThemAndRemembersWhatItLoadedWith() = runTest {
        var saved = ModelRunSettings(contextLength = 100_000_000, temperature = 0.2f)
        val backend = RecordingBackend()
        val runtime = GlobalModelRuntime(backend, runSettings = RunSettingsSource { saved })

        assertNull(runtime.loadedSettings("m"))
        runtime.load("m", artifactPath = "/x").getOrThrow()
        val expected = ModelRunSettings(contextLength = ModelRunSettings.CONTEXT_RANGE.last, temperature = 0.2f)
        assertEquals(listOf(expected), backend.seen)
        assertEquals(expected, runtime.loadedSettings("m"))

        // Editing the saved settings does not touch the resident copy; only an unload + load picks them up.
        saved = ModelRunSettings(contextLength = 1024)
        runtime.load("m", artifactPath = "/x").getOrThrow()
        assertEquals(1, backend.seen.size)
        runtime.unload("m")
        assertNull(runtime.loadedSettings("m"))
        runtime.load("m", artifactPath = "/x").getOrThrow()
        assertEquals(ModelRunSettings(contextLength = 1024), runtime.loadedSettings("m"))
    }

    @Test
    fun llamaCppLoadsTheEchoModelWithTheRequestedContextCappedAtTheTrainedOne() = runTest {
        val fixture = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .map { File(it, "models/echo-gguf/echo.gguf") }.firstOrNull { it.isFile } ?: error("echo.gguf fixture not found")
        val dir = createTempDirectory("run-settings").toFile()
        fixture.copyTo(File(dir, "echo.gguf"))
        System.setProperty("aidos.models.dir", dir.absolutePath)
        val backend = LlamaCppInferenceBackend()
        try {
            val small = backend.load("echo", null, ModelRunSettings(contextLength = 256, threads = 1, batchSize = 64)).getOrThrow()
            assertEquals(256, small.contextWindow)
            backend.unload("echo")
            // The fixture was trained for 512; asking for more is capped rather than passed to llama.cpp.
            val big = backend.load("echo", null, ModelRunSettings(contextLength = 8192)).getOrThrow()
            assertEquals(512, big.contextWindow)
            backend.unload("echo")
            // No settings: the old default, 2048 capped at the trained 512.
            assertEquals(512, backend.load("echo").getOrThrow().contextWindow)
        } finally {
            backend.unload("echo")
            dir.deleteRecursively()
        }
    }
}
