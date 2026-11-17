package dev.aidos.tts

import java.io.File
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Records every graph call and returns a fixed number of samples per call, so the text path is tested without ONNX. */
private class RecordingGraph(private val samplesPerCall: Int = 100, override val takesSpeaker: Boolean = false) : SpeechGraph {
    val calls = mutableListOf<Triple<LongArray, FloatArray, Long>>()
    var closed = false
    override fun run(ids: LongArray, scales: FloatArray, speaker: Long): FloatArray {
        calls += Triple(ids, scales, speaker)
        return FloatArray(samplesPerCall) { 0.5f }
    }
    override fun close() { closed = true }
}

/** Phonemizer that speaks each letter as itself, so ids are predictable. */
private object LetterPhonemizer : Phonemizer {
    override fun phonemize(text: String): List<String> = text.filter { it.isLetter() }.map { it.toString() }
}

class PiperVoiceTest {
    private val config = PiperConfig.parse(
        """
        {
          "audio": {"sample_rate": 1000},
          "inference": {"noise_scale": 0.5, "length_scale": 1.0, "noise_w": 0.25},
          "num_speakers": 2,
          "phoneme_id_map": {"^": [1], "$": [2], "_": [0], "a": [10], "b": [11]}
        }
        """.trimIndent(),
    )

    @Test
    fun speaksOneSentenceAsOneGraphCallWithPiperIdsAndScales() {
        val graph = RecordingGraph()
        val voice = PiperVoice("v", config, LetterPhonemizer, graph)
        val result = voice.synthesize(SpeechRequest("ab"))
        assertEquals(1, graph.calls.size)
        val (ids, scales, speaker) = graph.calls.single()
        assertContentEquals(longArrayOf(1, 10, 0, 11, 0, 2), ids)
        assertContentEquals(floatArrayOf(0.5f, 1.0f, 0.25f), scales)
        assertEquals(0L, speaker)
        assertEquals(1000, result.sampleRate)
        assertEquals(100, result.pcm16.size / 2)
    }

    @Test
    fun speedScalesTheLengthNotThePitch() {
        val graph = RecordingGraph()
        PiperVoice("v", config, LetterPhonemizer, graph).synthesize(SpeechRequest("ab", speed = 2.0))
        assertEquals(0.5f, graph.calls.single().second[1])
    }

    @Test
    fun longInputIsSpokenPieceByPieceWithAPauseBetween() {
        val graph = RecordingGraph(samplesPerCall = 10)
        val text = "a".repeat(399) + ". " + "b".repeat(50)
        val result = PiperVoice("v", config, LetterPhonemizer, graph).synthesize(SpeechRequest(text))
        assertEquals(2, graph.calls.size)
        // 10 + 150 ms of silence at 1000 Hz (150 samples) + 10 samples: the pause is between pieces only.
        assertEquals(10 + 150 + 10, result.pcm16.size / 2)
    }

    @Test
    fun textWithNothingSpeakableIsRefused() {
        val graph = RecordingGraph()
        assertFailsWith<TtsRequestException> {
            PiperVoice("v", config, LetterPhonemizer, graph).synthesize(SpeechRequest("123 !?"))
        }
        assertEquals(0, graph.calls.size)
    }

    @Test
    fun speedOutsideTheRangeIsRefused() {
        val voice = PiperVoice("v", config, LetterPhonemizer, RecordingGraph())
        assertFailsWith<TtsRequestException> { voice.synthesize(SpeechRequest("ab", speed = 0.1)) }
        assertFailsWith<TtsRequestException> { voice.synthesize(SpeechRequest("ab", speed = 5.0)) }
    }

    @Test
    fun aNumericVoiceSelectsASpeakerAndUnknownVoicesAreRefused() {
        val graph = RecordingGraph(takesSpeaker = true)
        val voice = PiperVoice("v", config, LetterPhonemizer, graph)
        voice.synthesize(SpeechRequest("ab", voice = "1"))
        assertEquals(1L, graph.calls.single().third)
        assertFailsWith<TtsRequestException> { voice.synthesize(SpeechRequest("ab", voice = "7")) }
        assertFailsWith<TtsRequestException> { voice.synthesize(SpeechRequest("ab", voice = "anna")) }
    }

    @Test
    fun closingTheVoiceClosesTheGraph() {
        val graph = RecordingGraph()
        PiperVoice("v", config, LetterPhonemizer, graph).close()
        assertTrue(graph.closed)
    }
}

class VoicePackageTest {
    private fun dir(): File = kotlin.io.path.createTempDirectory("tts-pkg").toFile()

    private fun voice(root: File, id: String, phonemizer: String, withDict: Boolean = false, manifest: Boolean = true): File {
        val d = File(root, id).apply { mkdirs() }
        File(d, "voice.onnx").writeBytes(byteArrayOf(0))
        File(d, "voice.onnx.json").writeText("""{"audio":{"sample_rate":22050},"phoneme_id_map":{"a":[1]}}""")
        if (manifest) File(d, VoicePackage.MANIFEST).writeText("""{"language":"x","phonemizer":"$phonemizer"}""")
        if (withDict) File(d, VoicePackage.DICTIONARY).writeText("HELLO  HH AH0 L OW1\n")
        return d
    }

    @Test
    fun aCompletePackageOpensWithItsManifest() {
        val root = dir()
        val pkg = VoicePackage.open(voice(root, "fi", "finnish-rules"))
        assertEquals("finnish-rules", pkg!!.phonemizerName)
        assertTrue(pkg.phonemizer() is FinnishPhonemizer)
        root.deleteRecursively()
    }

    @Test
    fun aDirectoryWithoutAManifestIsNotAVoice() {
        val root = dir()
        assertEquals(null, VoicePackage.open(voice(root, "nope", "finnish-rules", manifest = false)))
        root.deleteRecursively()
    }

    @Test
    fun cmudictVoiceNeedsItsDictionary() {
        val root = dir()
        assertEquals(null, VoicePackage.open(voice(root, "en", "cmudict", withDict = false)))
        val withDict = VoicePackage.open(voice(root, "en2", "cmudict", withDict = true))
        assertTrue(withDict!!.phonemizer() is EnglishPhonemizer)
        root.deleteRecursively()
    }
}

class TtsPackagesTest {
    private fun installed(): Pair<File, TtsPackages> {
        val root = kotlin.io.path.createTempDirectory("tts-models").toFile()
        val d = File(root, "fi-harri").apply { mkdirs() }
        File(d, "voice.onnx").writeBytes(byteArrayOf(1, 2, 3))
        File(d, "voice.onnx.json").writeText("""{"audio":{"sample_rate":22050},"phoneme_id_map":{"a":[1],"^":[1],"${'$'}":[2],"_":[0]}}""")
        File(d, VoicePackage.MANIFEST).writeText("""{"language":"fi","phonemizer":"finnish-rules"}""")
        return root to TtsPackages(root, graphFactory = { RecordingGraph() })
    }

    @Test
    fun aVoiceIsListedAsTtsAndLoadsAsAnAdapter() {
        val (root, packages) = installed()
        val d = packages.descriptors().single()
        assertEquals("fi-harri", d.id)
        assertEquals(dev.aidos.kernel.ModelKind.TTS, d.kind)
        val adapter = packages.load("fi-harri").getOrThrow()
        assertEquals("fi-harri", adapter.modelId)
        // A chat turn to a voice is refused, not answered with text.
        val chat = dev.aidos.kernel.ModelRequest(
            messages = emptyList(), tools = emptyList(),
            toolChoice = dev.aidos.kernel.ToolChoice.None, maxOutputTokens = 1, stopConditions = emptyList(),
        )
        assertTrue(runBlocking { adapter.invoke(chat) }.isFailure)
        root.deleteRecursively()
    }

    @Test
    fun pathsThatLeaveTheModelsDirectoryAreRefused() {
        val (root, packages) = installed()
        assertEquals(null, packages.dirFor("../fi-harri"))
        assertEquals(null, packages.dirAt("/etc"))
        assertTrue(packages.load("missing").isFailure)
        root.deleteRecursively()
    }
}

/**
 * Opt-in: a real Piper voice on disk. Set AIDOS_TTS_MODEL_DIR to a voice package directory (see VoicePackage) to run it;
 * without the variable the test does nothing. It checks that Finnish and English both produce non-silent audio of a
 * plausible length. Never run in CI, because the weights are not in the repository.
 */
class PiperRealModelTest {
    @Test
    fun synthesisesFinnishAndEnglishFromARealVoice() {
        val dir = System.getenv("AIDOS_TTS_MODEL_DIR")?.let { File(it) } ?: return
        val pkg = VoicePackage.open(dir) ?: error("AIDOS_TTS_MODEL_DIR is not a voice package: ${dir.path}")
        val config = PiperConfig.parse(pkg.config.readText(Charsets.UTF_8))
        val voice = PiperVoice("real", config, pkg.phonemizer(), OrtPiperGraph(pkg.model))
        voice.use {
            val sentence = if (pkg.language == "fi") "Hyvää huomenta, seurakunta." else "Good morning, congregation."
            val result = it.synthesize(SpeechRequest(sentence))
            val seconds = result.durationSeconds
            assertTrue(seconds > 0.8 && seconds < 15.0, "unexpected length $seconds s")
            val peak = (0 until result.pcm16.size / 2).maxOf { i ->
                Math.abs(((result.pcm16[2 * i].toInt() and 0xff) or (result.pcm16[2 * i + 1].toInt() shl 8)).toShort().toInt())
            }
            assertTrue(peak > 1000, "audio is silent (peak $peak)")
        }
    }
}
