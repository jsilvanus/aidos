package dev.aidos.engine.cli

import dev.aidos.kernel.ModelAdapter
import dev.aidos.kernel.ModelDescriptor
import dev.aidos.kernel.ModelKind
import dev.aidos.kernel.ModelRuntime
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
    ) : ModelRuntime {
        private val loadedIds = mutableSetOf<String>()
        private val adapter = object : ModelAdapter {
            override val providerId = "test"
            override val modelId = model.id
            override val modelVersion = "test"
            override val contextWindow = model.contextWindow
            override val isLocal = true
            override fun supportsNativeToolCalls() = false
            override suspend fun invoke(request: dev.aidos.kernel.ModelRequest): Result<dev.aidos.kernel.ModelResponse> =
                Result.failure(UnsupportedOperationException())
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
