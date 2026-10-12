package dev.aidos.huggingface

import dev.aidos.kernel.BasicResourceHandle
import dev.aidos.kernel.CapabilityId
import dev.aidos.kernel.ContentBlock
import dev.aidos.kernel.EffectBroker
import dev.aidos.kernel.ModelKind
import dev.aidos.kernel.PlatformProfile
import dev.aidos.kernel.Preview
import dev.aidos.kernel.Tool
import dev.aidos.kernel.ToolCall
import dev.aidos.kernel.ToolCallResult
import dev.aidos.kernel.ToolDescriptor
import dev.aidos.kernel.ToolOutcome
import dev.aidos.kernel.TrustLevel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HuggingFaceClientTest {

    @Test
    fun testInferModelKindFromTags() {
        // Create a dummy client just for testing inferModelKind which is a pure function
        // The function doesn't depend on broker, so we can test the logic directly
        
        assertEquals(
            ModelKind.LLM,
            HuggingFaceClient.inferModelKind(
                tags = listOf("text-generation", "llm", "instruct"),
                pipeline = null,
            )
        )

        assertEquals(
            ModelKind.EMBEDDING,
            HuggingFaceClient.inferModelKind(
                tags = listOf("embedding", "sentence-transformers"),
                pipeline = null,
            )
        )

        assertEquals(
            ModelKind.STT,
            HuggingFaceClient.inferModelKind(
                tags = listOf("speech-recognition"),
                pipeline = "automatic-speech-recognition",
            )
        )

        assertEquals(
            ModelKind.VISION,
            HuggingFaceClient.inferModelKind(
                tags = listOf("image-to-text", "multimodal"),
                pipeline = null,
            )
        )
    }

    @Test
    fun search_sendsEncodedQueryAndOneFilterParamPerTag() = runBlocking {
        val broker = RecordingBroker("HTTP 200\n\n[]")
        client(broker).search(query = "qwen coder & more", filter = "gguf,text-generation").getOrThrow()

        val url = broker.urls.single()
        assertTrue("search=qwen%20coder%20%26%20more" in url, url)
        assertTrue("filter=gguf&" in url || url.endsWith("filter=gguf"), url)
        assertTrue("filter=text-generation" in url, url)
        assertTrue("gguf,text-generation" !in url, url)
    }

    @Test
    fun search_failsOnNonSuccessStatusInsteadOfReturningNoModels() = runBlocking {
        val broker = RecordingBroker("HTTP 429\n\n{\"error\":\"rate limited\"}")
        val result = client(broker).search(query = null, filter = "gguf")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("HTTP 429"))
    }

    @Test
    fun getModel_readsLicenseFromTags() = runBlocking {
        val broker = RecordingBroker(
            "HTTP 200\n\n{\"id\":\"jsilvanus/echo-gguf\",\"tags\":[\"gguf\",\"license:eupl-1.2\"]," +
                "\"siblings\":[{\"rfilename\":\"echo.gguf\"}]}"
        )
        val model = client(broker).getModel("jsilvanus/echo-gguf").getOrThrow()

        assertEquals("eupl-1.2", model.license)
        assertEquals(listOf("echo"), model.quantizations.map { it.name })
    }

    @Test
    fun getModel_ggufRepoKeepsProjectorsAndShardsOutOfQuantizations() = runBlocking {
        val body = """{"id":"acme/vl-GGUF","tags":["gguf"],"siblings":[
            {"rfilename":"vl-Q4_K_M.gguf","size":10},
            {"rfilename":"mmproj-vl-f16.gguf","size":5},
            {"rfilename":"big-Q8_0-00001-of-00002.gguf","size":7},
            {"rfilename":"README.md"}]}"""
        val model = client(RecordingBroker("HTTP 200\n\n$body")).getModel("acme/vl-GGUF").getOrThrow()

        assertEquals(listOf("vl-Q4_K_M"), model.quantizations.map { it.name })
        assertEquals(
            listOf(ArtifactRole.WEIGHTS, ArtifactRole.PROJECTOR, ArtifactRole.SHARD),
            model.artifacts.map { it.role },
        )
    }

    @Test
    fun getModel_onnxRepoYieldsAPackageWithItsSidecarAndRootTokenizer() = runBlocking {
        val body = """{"id":"acme/laya-onnx","tags":["onnx"],"siblings":[
            {"rfilename":"onnx/model.onnx","size":100,"lfs":{"sha256":"aa"}},
            {"rfilename":"onnx/model.onnx_data","size":900},
            {"rfilename":"tokenizer.json","size":4},
            {"rfilename":"rl_agent_config.json","size":1},
            {"rfilename":"model.safetensors","size":2000}]}"""
        val model = client(RecordingBroker("HTTP 200\n\n$body")).getModel("acme/laya-onnx").getOrThrow()

        val pkg = model.packages.single()
        assertEquals("onnx", pkg.directory)
        assertEquals(setOf("onnx/model.onnx", "onnx/model.onnx_data", "tokenizer.json", "rl_agent_config.json"), pkg.files.map { it.path }.toSet())
        assertEquals(1005L, pkg.sizeBytes)
        assertTrue(pkg.isDecisionPackage)
        assertEquals(ArtifactRole.EXTERNAL_DATA, model.artifacts.first { it.filename.endsWith("_data") }.role)
        // safetensors is recognized but has no backend, so it is never offered as runnable
        assertEquals(emptyList(), model.artifacts.first { it.format == ModelFormat.SAFETENSORS }.compatibleBackends)
    }

    @Test
    fun onnxPackage_withoutLayaFilesIsNotADecisionPackage() {
        val pkgs = ModelPackage.detectOnnx("a/b", listOf(RepoFile("model.onnx"), RepoFile("model_quantized.onnx"), RepoFile("tokenizer.json")))
        assertEquals(2, pkgs.size)
        assertTrue(pkgs.none { it.isDecisionPackage })
    }

    @Test
    fun downloadUrls_percentEncodeEachPathSegment() {
        val pkg = ModelPackage.detectOnnx("a/b", listOf(RepoFile("my dir/m#1.onnx"))).single()
        assertEquals("https://huggingface.co/a/b/resolve/main/my%20dir/m%231.onnx", pkg.downloadUrl(pkg.files.single()))
    }

    private fun client(broker: EffectBroker) =
        HuggingFaceClient(broker, BasicResourceHandle(CapabilityId("huggingface")))

    /** Answers every http:get with [body] and records the requested URLs. */
    private class RecordingBroker(private val body: String) : EffectBroker {
        val urls = mutableListOf<String>()

        override fun register(tool: Tool) = Unit

        override fun descriptorsFor(
            subjectId: String,
            profile: PlatformProfile,
            networkAvailable: Boolean,
        ): List<ToolDescriptor> = emptyList()

        override suspend fun invoke(subjectId: String, call: ToolCall, runTaint: TrustLevel): ToolCallResult {
            urls += call.arguments["url"]!!.jsonPrimitive.content
            return ToolCallResult(
                callId = call.callId,
                outcome = ToolOutcome.Ok,
                content = listOf(ContentBlock.Text(body)),
                trustLevel = TrustLevel.UNTRUSTED,
            )
        }

        override suspend fun preview(subjectId: String, call: ToolCall): Result<Preview> =
            Result.failure(UnsupportedOperationException())

        override suspend fun cancel(callId: String) = Unit
    }

    @Test
    fun testDefaultsToLLMWhenKindUnclear() {
        assertEquals(
            ModelKind.LLM,
            HuggingFaceClient.inferModelKind(
                tags = listOf("unknown", "tags"),
                pipeline = null,
            )
        )
    }
}
