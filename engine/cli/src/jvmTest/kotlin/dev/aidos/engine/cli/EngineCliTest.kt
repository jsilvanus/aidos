package dev.aidos.engine.cli

import dev.aidos.kernel.ModelAdapter
import dev.aidos.kernel.ModelDescriptor
import dev.aidos.kernel.ModelKind
import dev.aidos.kernel.ModelRequest
import dev.aidos.kernel.ModelResponse
import dev.aidos.kernel.ModelRuntime
import dev.aidos.kernel.StopReason
import dev.aidos.kernel.TokenUsage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EngineCliTest {
    @Test
    fun exposesCatalogInstalledAndLoadedState() = kotlinx.coroutines.test.runTest {
        val model = descriptor("test-model")
        val runtime = FakeRuntime(model)
        val cli = EngineCli(runtime)

        assertEquals(listOf(model), cli.catalog())
        assertEquals(listOf(model), cli.installed())
        assertTrue(cli.loaded().isEmpty())

        assertTrue(cli.load("test-model").isSuccess)
        assertEquals(listOf("test-model"), cli.loaded())

        cli.unload("test-model")
        assertTrue(cli.loaded().isEmpty())
    }

    @Test
    fun inferInvokesAdapterAndUnloadsModel() = kotlinx.coroutines.test.runTest {
        val model = descriptor("test-model")
        val runtime = FakeRuntime(model, responseText = "hello from test")
        val cli = EngineCli(runtime)

        val result = cli.infer("test-model", "hello")

        assertTrue(result.isSuccess)
        assertEquals("hello from test", result.getOrThrow().text)
        assertTrue(runtime.loaded().isEmpty())
        assertEquals("hello", runtime.lastRequest?.messages?.first()?.let { turn ->
            (turn as dev.aidos.kernel.Turn.User).content.first() as dev.aidos.kernel.ContentBlock.Text
        }?.text)
    }

    @Test
    fun exposesVersion() {
        val cli = EngineCli(FakeRuntime(descriptor("test-model")))
        assertEquals("0.1.0", cli.version())
    }

    private fun descriptor(id: String) = ModelDescriptor(
        id = id,
        name = "Test model",
        kind = ModelKind.LLM,
        providerId = "test",
        isLocal = true,
        contextWindow = 4096,
        sizeBytes = null,
        digest = null,
    )

    private class FakeRuntime(
        private val model: ModelDescriptor,
        private val responseText: String = "",
    ) : ModelRuntime {
        private val loadedIds = mutableSetOf<String>()
        var lastRequest: ModelRequest? = null
            private set

        private val adapter = object : ModelAdapter {
            override val providerId = "test"
            override val modelId = model.id
            override val modelVersion = "test"
            override val contextWindow = model.contextWindow
            override val isLocal = true
            override fun supportsNativeToolCalls() = false
            override suspend fun invoke(request: ModelRequest): Result<ModelResponse> {
                lastRequest = request
                return Result.success(
                    ModelResponse(
                        text = responseText,
                        toolCalls = emptyList(),
                        stopReason = StopReason.END_TURN,
                        usage = TokenUsage(1, 1),
                        modelId = model.id,
                        modelVersion = "test",
                    )
                )
            }
        }

        override suspend fun catalog(): List<ModelDescriptor> = listOf(model)
        override suspend fun installed(): List<ModelDescriptor> = listOf(model)

        override suspend fun load(modelId: String): Result<ModelAdapter> {
            if (modelId != model.id) return Result.failure(IllegalArgumentException("unknown model"))
            loadedIds += modelId
            return Result.success(adapter)
        }

        override suspend fun unload(modelId: String) {
            loadedIds -= modelId
        }

        override fun loaded(): List<String> = loadedIds.toList()
    }
}
