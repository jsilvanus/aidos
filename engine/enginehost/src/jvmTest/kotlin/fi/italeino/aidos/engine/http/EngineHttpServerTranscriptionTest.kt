package fi.italeino.aidos.engine.http

import dev.aidos.kernel.ModelAdapter
import dev.aidos.kernel.ModelDescriptor
import dev.aidos.kernel.ModelKind
import dev.aidos.kernel.ModelRuntime
import dev.aidos.stt.SttModel
import dev.aidos.stt.SttModelAdapterImpl
import dev.aidos.stt.SttPackages
import dev.aidos.stt.SttRequest
import dev.aidos.stt.SttRequestException
import dev.aidos.stt.SttResult
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import java.io.File
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** `/v1/audio/transcriptions` backed by a speech-to-text model: a fake (always runs) and a real Whisper package (opt-in). */
class EngineHttpServerTranscriptionTest {
    private class FakeStt : SttModel {
        override val modelId = "fake-whisper"
        var last: SttRequest? = null
        override fun transcribe(request: SttRequest): SttResult {
            last = request
            if (request.audio.size < 4) throw SttRequestException("audio must be a RIFF/WAV file")
            return SttResult("hei maailma", request.language ?: "en", 1.0)
        }
        override fun close() = Unit
    }

    private class SingleModelRuntime(private val id: String, private val adapter: ModelAdapter) : ModelRuntime {
        override suspend fun catalog() = installed()
        override suspend fun installed() = listOf(ModelDescriptor(id, id, ModelKind.STT, "local", true, 448, 1L, "d"))
        override suspend fun load(modelId: String): Result<ModelAdapter> =
            if (modelId == id) Result.success(adapter) else Result.failure(IllegalStateException("Model $modelId is not installed"))
        override suspend fun delete(modelId: String) = Unit
        override suspend fun unload(modelId: String) = Unit
        override fun loaded(): List<String> = listOf(id)
    }

    private fun withServer(runtime: ModelRuntime, block: suspend ApplicationTestBuilder.(token: String) -> Unit) = testApplication {
        val tokens = TokenManager()
        val server = EngineHttpServer(tokens, runtime)
        application { server.installInto(this) }
        block(tokens.generateNewToken().token)
    }

    private fun body(model: String, audio: ByteArray, extra: String = "") =
        """{"model":"$model","file":"${Base64.getEncoder().encodeToString(audio)}"$extra}"""

    @Test
    fun speechModelTranscribesAndReceivesLanguageAndPrompt() {
        val stt = FakeStt()
        withServer(SingleModelRuntime("fake-whisper", SttModelAdapterImpl(stt))) { token ->
            val r = client.post("/v1/audio/transcriptions") {
                bearerAuth(token); contentType(ContentType.Application.Json)
                setBody(body("fake-whisper", ByteArray(64), ""","language":"fi","prompt":"Herra armahda""""))
            }
            assertEquals(HttpStatusCode.OK, r.status)
            assertEquals("hei maailma", Json.parseToJsonElement(r.bodyAsText()).jsonObject.getValue("text").jsonPrimitive.content)
            assertEquals("fi", stt.last?.language)
            assertEquals("Herra armahda", stt.last?.prompt)
        }
    }

    @Test
    fun unusableAudioIsAClientError() {
        withServer(SingleModelRuntime("fake-whisper", SttModelAdapterImpl(FakeStt()))) { token ->
            val r = client.post("/v1/audio/transcriptions") {
                bearerAuth(token); contentType(ContentType.Application.Json)
                setBody(body("fake-whisper", byteArrayOf(1)))
            }
            assertEquals(HttpStatusCode.BadRequest, r.status)
            assertTrue(r.bodyAsText().contains("WAV"))
        }
    }

    /**
     * The same route against a real Whisper package from disk. Opt-in: set `AIDOS_STT_MODEL_DIR` to a package
     * directory (`node scripts/stt/fetch-whisper.mjs <dir>`).
     */
    @Test
    fun realWhisperPackageTranscribesOverHttp() {
        val dir = System.getenv("AIDOS_STT_MODEL_DIR")?.takeIf { it.isNotBlank() }?.let(::File)
        assumeTrue("AIDOS_STT_MODEL_DIR not set; skipping real-model transcription test", dir != null)
        val models = kotlin.io.path.createTempDirectory("engine-models").toFile()
        dir!!.copyRecursively(File(models, "whisper"))
        val packages = SttPackages(models)
        val runtime = object : ModelRuntime {
            override suspend fun catalog() = packages.descriptors()
            override suspend fun installed() = packages.descriptors()
            override suspend fun load(modelId: String): Result<ModelAdapter> = packages.load(modelId)
            override suspend fun delete(modelId: String) = Unit
            override suspend fun unload(modelId: String) { packages.unload(modelId) }
            override fun loaded(): List<String> = emptyList()
        }
        val wav = javaClass.getResourceAsStream("/jfk.wav")!!.readBytes()
        withServer(runtime) { token ->
            val r = client.post("/v1/audio/transcriptions") {
                bearerAuth(token); contentType(ContentType.Application.Json)
                setBody(body("whisper", wav, ""","language":"en""""))
            }
            assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
            val text = Json.parseToJsonElement(r.bodyAsText()).jsonObject.getValue("text").jsonPrimitive.content
            println("HTTP STT: $text")
            assertTrue(text.contains("country", ignoreCase = true), text)
        }
        packages.unload("whisper")
        models.deleteRecursively()
    }
}
