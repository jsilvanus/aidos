package dev.aidos.engine.cli

import dev.aidos.kernel.ContentBlock
import dev.aidos.kernel.ModelAdapter
import dev.aidos.kernel.ModelDescriptor
import dev.aidos.kernel.ModelKind
import dev.aidos.kernel.ModelRequest
import dev.aidos.kernel.ModelResponse
import dev.aidos.kernel.ModelRuntime
import dev.aidos.kernel.StopReason
import dev.aidos.kernel.ModelRef
import dev.aidos.kernel.TextOutput
import dev.aidos.kernel.Usage
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
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
        val content = runtime.lastRequest?.messages?.first()?.let { (it as dev.aidos.kernel.Turn.User).content.first() }
        assertEquals("hello", (content as ContentBlock.Text).text)
    }

    @Test
    fun chatKeepsConversationTurnsAndUnloadsAfterwards() = kotlinx.coroutines.test.runTest {
        val model = descriptor("test-model")
        val runtime = FakeRuntime(model, responseText = "reply")
        val cli = EngineCli(runtime)

        val result = cli.chat("test-model", listOf("first", "second"))

        assertTrue(result.isSuccess)
        assertEquals(2, result.getOrThrow().size)
        assertEquals(4, runtime.requests.last().messages.size)
        assertTrue(runtime.loaded().isEmpty())
    }

    @Test
    fun backendSmokeTestUsesRuntimeWithoutLoading() = kotlinx.coroutines.test.runTest {
        val cli = EngineCli(FakeRuntime(descriptor("test-model")))
        val result = cli.testBackend()
        assertTrue(result.passed)
        assertEquals(1, result.catalogCount)
        assertEquals(1, result.installedCount)
        assertTrue(cli.loaded().isEmpty())
    }

    @Test
    fun inspectParsesArchitectureQuantizationSizeAndParameterCount() {
        val file = File.createTempFile("aidos-test-", ".gguf")
        try {
            writeSyntheticGguf(file)
            val cli = EngineCli(FakeRuntime(descriptor("test-model")))
            val inspection = cli.inspectModel(file).getOrThrow()

            assertEquals(3, inspection.version)
            assertEquals("llama", inspection.architecture)
            assertEquals("Test Llama 3B", inspection.modelName)
            assertEquals("3B", inspection.sizeLabel)
            assertEquals(32768, inspection.contextWindow)
            assertEquals("Q4_K_M", inspection.quantization)
            assertEquals(15, inspection.parameterCount)
            assertEquals(2, inspection.tensorCount)
        } finally {
            file.delete()
        }
    }

    @Test
    fun invalidModelInspectionFailsCleanly() {
        val cli = EngineCli(FakeRuntime(descriptor("test-model")))
        val result = cli.inspectModel(File("build/does-not-exist.gguf"))
        assertTrue(result.isFailure)
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

    /** Minimal valid GGUF v3 containing the metadata and tensor descriptors used by inspection. */
    private fun writeSyntheticGguf(file: File) {
        val out = ByteArrayOutputStream()
        fun u32(value: Long) { out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value.toInt()).array()) }
        fun u64(value: Long) { out.write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(value).array()) }
        fun string(value: String) { val bytes = value.toByteArray(); u64(bytes.size.toLong()); out.write(bytes) }
        fun kvString(key: String, value: String) { string(key); u32(8); string(value) }
        fun kvU32(key: String, value: Long) { string(key); u32(4); u32(value) }

        u32(0x46554747L)
        u32(3)
        u64(2)
        u64(6)
        kvString("general.architecture", "llama")
        kvString("general.name", "Test Llama 3B")
        kvU32("general.context_length", 32768)
        kvU32("general.file_type", 15)
        kvString("general.size_label", "3B")
        kvString("general.description", "synthetic test")

        string("token_embd.weight")
        u32(2); u64(3); u64(4); u64(0); u32(0)
        string("output.weight")
        u32(1); u64(3); u64(48); u32(0)

        file.writeBytes(out.toByteArray())
    }

    private class FakeRuntime(
        private val model: ModelDescriptor,
        private val responseText: String = "",
    ) : ModelRuntime {
        private val loadedIds = mutableSetOf<String>()
        var lastRequest: ModelRequest? = null
            private set
        val requests = mutableListOf<ModelRequest>()

        private val adapter = object : ModelAdapter {
            override val providerId = "test"
            override val modelId = model.id
            override val modelVersion = "test"
            override val contextWindow = model.contextWindow
            override val isLocal = true
            override fun supportsNativeToolCalls() = false
            override suspend fun invoke(request: ModelRequest): Result<ModelResponse> {
                lastRequest = request
                requests += request
                return Result.success(
                    ModelResponse(
                        outputs = listOf(TextOutput(responseText)),
                        stopReason = StopReason.END_TURN,
                        usage = Usage(inputTokens = 1, outputTokens = 1, totalTokens = 2),
                        model = ModelRef(id = model.id, version = "test"),
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
        override suspend fun unload(modelId: String) { loadedIds -= modelId }
        override fun loaded(): List<String> = loadedIds.toList()
    }
}
