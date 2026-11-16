package fi.italeino.aidos.engine.http

import dev.aidos.kernel.ModelAdapter
import dev.aidos.kernel.ModelDescriptor
import dev.aidos.kernel.ModelKind
import dev.aidos.kernel.ModelRuntime
import dev.aidos.stt.SttModel
import dev.aidos.stt.SttModelAdapterImpl
import dev.aidos.stt.SttRequest
import dev.aidos.stt.SttRequestException
import dev.aidos.stt.SttResult
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** RFC-0117 amendment: `POST /v1/audio/transcriptions` as OpenAI-style `multipart/form-data`. */
class EngineHttpServerMultipartTranscriptionTest {
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

    /** A header-valid WAV: RIFF...WAVE, then zeros. Enough for the fake model; the real decoder is not run here. */
    private fun wav(size: Int = 64): ByteArray = ByteArray(size).also {
        "RIFF".toByteArray().copyInto(it, 0)
        "WAVE".toByteArray().copyInto(it, 8)
    }

    private fun withServer(
        stt: FakeStt,
        policy: EngineApiPolicy = EngineApiPolicy(),
        block: suspend ApplicationTestBuilder.(token: String) -> Unit,
    ) = testApplication {
        val tokens = TokenManager()
        val server = EngineHttpServer(tokens, SingleModelRuntime("fake-whisper", SttModelAdapterImpl(stt)), policy = policy)
        application { server.installInto(this) }
        block(tokens.generateNewToken().token)
    }

    private suspend fun ApplicationTestBuilder.upload(
        token: String,
        audio: ByteArray?,
        fields: Map<String, String> = mapOf("model" to "fake-whisper"),
    ): HttpResponse = client.submitFormWithBinaryData(
        url = "/v1/audio/transcriptions",
        formData = formData {
            fields.forEach { (key, value) -> append(key, value) }
            if (audio != null) {
                append(
                    "file",
                    audio,
                    Headers.build {
                        append(HttpHeaders.ContentType, "audio/wav")
                        append(HttpHeaders.ContentDisposition, "filename=\"a.wav\"")
                    },
                )
            }
        },
    ) { bearerAuth(token) }

    @Test
    fun multipartTranscribesAndReceivesLanguageAndPrompt() {
        val stt = FakeStt()
        val audio = wav()
        withServer(stt) { token ->
            val r = upload(token, audio, mapOf("model" to "fake-whisper", "language" to "fi", "prompt" to "Herra armahda"))
            assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
            assertEquals("hei maailma", Json.parseToJsonElement(r.bodyAsText()).jsonObject.getValue("text").jsonPrimitive.content)
            assertTrue(stt.last!!.audio.contentEquals(audio))
            assertEquals("fi", stt.last?.language)
            assertEquals("Herra armahda", stt.last?.prompt)
        }
    }

    @Test
    fun responseFormatTextReturnsThePlainTranscript() {
        withServer(FakeStt()) { token ->
            val r = upload(token, wav(), mapOf("model" to "fake-whisper", "response_format" to "text"))
            assertEquals(HttpStatusCode.OK, r.status)
            assertEquals(ContentType.Text.Plain.withoutParameters(), r.contentType()?.withoutParameters())
            assertEquals("hei maailma", r.bodyAsText())
        }
    }

    @Test
    fun missingFileIsBadRequest() {
        withServer(FakeStt()) { token ->
            val r = upload(token, audio = null)
            assertEquals(HttpStatusCode.BadRequest, r.status)
            assertTrue(r.bodyAsText().contains("file"), r.bodyAsText())
        }
    }

    @Test
    fun missingModelIsBadRequest() {
        withServer(FakeStt()) { token ->
            val r = upload(token, wav(), fields = emptyMap())
            assertEquals(HttpStatusCode.BadRequest, r.status)
            assertTrue(r.bodyAsText().contains("model"), r.bodyAsText())
        }
    }

    @Test
    fun oversizedFileIsPayloadTooLarge() {
        val policy = EngineApiPolicy(maxAudioBytes = 4096)
        withServer(FakeStt(), policy) { token ->
            val r = upload(token, wav(5000))
            assertEquals(HttpStatusCode.PayloadTooLarge, r.status, r.bodyAsText())
        }
    }

    @Test
    fun nonWavEncodingIsBadRequestNamingWav() {
        val mp3 = "ID3".toByteArray() + ByteArray(64)
        withServer(FakeStt()) { token ->
            val r = upload(token, mp3)
            assertEquals(HttpStatusCode.BadRequest, r.status)
            assertTrue(r.bodyAsText().contains("send WAV"), r.bodyAsText())
        }
    }

    @Test
    fun timestampFormatsAreRefused() {
        withServer(FakeStt()) { token ->
            for (format in listOf("verbose_json", "srt", "vtt")) {
                val r = upload(token, wav(), mapOf("model" to "fake-whisper", "response_format" to format))
                assertEquals(HttpStatusCode.BadRequest, r.status, format)
                assertTrue(r.bodyAsText().contains("timestamps"), r.bodyAsText())
            }
        }
    }
}
