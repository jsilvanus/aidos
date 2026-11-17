package fi.italeino.aidos.engine.http

import dev.aidos.kernel.ModelAdapter
import dev.aidos.kernel.ModelDescriptor
import dev.aidos.kernel.ModelKind
import dev.aidos.kernel.ModelRuntime
import dev.aidos.stt.SttModel
import dev.aidos.stt.SttModelAdapterImpl
import dev.aidos.stt.SttRequest
import dev.aidos.stt.SttResult
import dev.aidos.tts.SpeechRequest
import dev.aidos.tts.SpeechResult
import dev.aidos.tts.SpeechText
import dev.aidos.tts.TtsModel
import dev.aidos.tts.TtsModelAdapterImpl
import dev.aidos.tts.TtsRequestException
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `POST /v1/audio/speech` backed by a fake text-to-speech voice (always runs): WAV and PCM output, input and option
 * checks, and the refusals for a model that is not a voice. The real Piper voice is covered in :tts, opt-in.
 */
class EngineHttpServerSpeechTest {
    private class FakeVoice : TtsModel {
        override val modelId = "fake-piper"
        var last: SpeechRequest? = null
        override fun synthesize(request: SpeechRequest): SpeechResult {
            last = request
            if (request.input.isBlank()) throw TtsRequestException("input must not be empty")
            return SpeechResult(ByteArray(2 * 2205) { 1 }, 22050)
        }
        override fun close() = Unit
    }

    private class SingleModelRuntime(private val id: String, private val kind: ModelKind, private val adapter: ModelAdapter) : ModelRuntime {
        override suspend fun catalog() = installed()
        override suspend fun installed() = listOf(ModelDescriptor(id, id, kind, "local", true, 4000, 1L, "d"))
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

    private fun voice(voice: FakeVoice = FakeVoice()) = SingleModelRuntime("fake-piper", ModelKind.TTS, TtsModelAdapterImpl(voice))

    @Test
    fun voiceAnswersWithAWavFileByDefault() {
        val fake = FakeVoice()
        withServer(voice(fake)) { token ->
            val r = client.post("/v1/audio/speech") {
                bearerAuth(token); contentType(ContentType.Application.Json)
                setBody("""{"model":"fake-piper","input":"Hyvää huomenta"}""")
            }
            assertEquals(HttpStatusCode.OK, r.status)
            assertEquals("audio/wav", r.contentType()?.withoutParameters()?.toString())
            val bytes = r.readBytes()
            assertEquals("RIFF", String(bytes, 0, 4, Charsets.US_ASCII))
            assertEquals(44 + 2 * 2205, bytes.size)
            assertEquals("Hyvää huomenta", fake.last?.input)
            assertEquals(1.0, fake.last?.speed)
        }
    }

    @Test
    fun pcmFormatReturnsRawSamplesWithTheRate() {
        withServer(voice()) { token ->
            val r = client.post("/v1/audio/speech") {
                bearerAuth(token); contentType(ContentType.Application.Json)
                setBody("""{"model":"fake-piper","input":"hei","response_format":"pcm","speed":1.5,"voice":"0"}""")
            }
            assertEquals(HttpStatusCode.OK, r.status)
            assertEquals("22050", r.headers["X-Aidos-Sample-Rate"])
            assertEquals(2 * 2205, r.readBytes().size)
        }
    }

    @Test
    fun otherFormatsAreRefusedClearly() {
        withServer(voice()) { token ->
            val r = client.post("/v1/audio/speech") {
                bearerAuth(token); contentType(ContentType.Application.Json)
                setBody("""{"model":"fake-piper","input":"hei","response_format":"mp3"}""")
            }
            assertEquals(HttpStatusCode.BadRequest, r.status)
            assertEquals("unsupported_format", errorCode(r.bodyAsText()))
        }
    }

    @Test
    fun inputOverTheLimitIsRefusedBeforeTheModelRuns() {
        val fake = FakeVoice()
        withServer(voice(fake)) { token ->
            val long = "a".repeat(SpeechText.MAX_INPUT_CHARS + 1)
            val r = client.post("/v1/audio/speech") {
                bearerAuth(token); contentType(ContentType.Application.Json)
                setBody("""{"model":"fake-piper","input":"$long"}""")
            }
            assertEquals(HttpStatusCode.BadRequest, r.status)
            assertEquals("input_too_long", errorCode(r.bodyAsText()))
            assertEquals(null, fake.last)
        }
    }

    @Test
    fun speedOutsideTheRangeIsRefused() {
        withServer(voice()) { token ->
            val r = client.post("/v1/audio/speech") {
                bearerAuth(token); contentType(ContentType.Application.Json)
                setBody("""{"model":"fake-piper","input":"hei","speed":9}""")
            }
            assertEquals(HttpStatusCode.BadRequest, r.status)
            assertEquals("invalid_request", errorCode(r.bodyAsText()))
        }
    }

    @Test
    fun aSpeechToTextModelIsNotAVoice() {
        val stt = SttModelAdapterImpl(object : SttModel {
            override val modelId = "fake-whisper"
            override fun transcribe(request: SttRequest): SttResult = SttResult("", "fi", 0.0)
            override fun close() = Unit
        })
        withServer(SingleModelRuntime("fake-whisper", ModelKind.STT, stt)) { token ->
            val r = client.post("/v1/audio/speech") {
                bearerAuth(token); contentType(ContentType.Application.Json)
                setBody("""{"model":"fake-whisper","input":"hei"}""")
            }
            assertEquals(HttpStatusCode.BadRequest, r.status)
            assertTrue(r.bodyAsText().contains("not a text-to-speech model"))
        }
    }

    @Test
    fun blankInputIsAClientError() {
        withServer(voice()) { token ->
            val r = client.post("/v1/audio/speech") {
                bearerAuth(token); contentType(ContentType.Application.Json)
                setBody("""{"model":"fake-piper","input":"   "}""")
            }
            assertEquals(HttpStatusCode.BadRequest, r.status)
        }
    }

    private fun errorCode(body: String): String =
        Json.parseToJsonElement(body).jsonObject.getValue("error").jsonObject.getValue("code").jsonPrimitive.content
}
