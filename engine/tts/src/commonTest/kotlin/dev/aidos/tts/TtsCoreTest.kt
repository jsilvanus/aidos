package dev.aidos.tts

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SpeechTextTest {
    @Test
    fun trimsAndAcceptsText() {
        assertEquals("Hei maailma", SpeechText.validate("  Hei maailma \n"))
    }

    @Test
    fun refusesBlankInput() {
        assertFailsWith<TtsRequestException> { SpeechText.validate("   ") }
    }

    @Test
    fun refusesInputOverTheLimit() {
        val e = assertFailsWith<TtsRequestException> { SpeechText.validate("a".repeat(SpeechText.MAX_INPUT_CHARS + 1)) }
        assertTrue(e.message!!.contains("limit is ${SpeechText.MAX_INPUT_CHARS}"))
    }

    @Test
    fun splitsAtSentenceEndsWithinTheLimit() {
        val pieces = SpeechText.split("Ensimmäinen lause. Toinen lause on tässä. Kolmas.", maxChars = 25)
        assertEquals(listOf("Ensimmäinen lause.", "Toinen lause on tässä.", "Kolmas."), pieces)
        assertTrue(pieces.all { it.length <= 25 })
    }

    @Test
    fun splitsAtTheLastSpaceWhenNoSentenceEndFits() {
        val pieces = SpeechText.split("yksi kaksi kolme neljä", maxChars = 10)
        assertEquals(listOf("yksi kaksi", "kolme", "neljä"), pieces)
    }

    @Test
    fun shortTextIsOnePiece() {
        assertEquals(listOf("lyhyt"), SpeechText.split("lyhyt", maxChars = 100))
    }
}

class WavWriterTest {
    @Test
    fun writesCanonicalHeader() {
        val pcm = byteArrayOf(1, 0, 2, 0)
        val wav = WavWriter.pcm16Wav(pcm, 22050)
        assertEquals(48, wav.size)
        assertEquals("RIFF", String(wav, 0, 4, Charsets.US_ASCII))
        assertEquals("WAVE", String(wav, 8, 4, Charsets.US_ASCII))
        assertEquals("data", String(wav, 36, 4, Charsets.US_ASCII))
        assertEquals(22050, littleEndianInt(wav, 24))
        assertEquals(44100, littleEndianInt(wav, 28))
        assertEquals(4, littleEndianInt(wav, 40))
        assertContentEquals(pcm, wav.copyOfRange(44, 48))
    }

    @Test
    fun floatsBecomeClippedLittleEndianPcm() {
        val pcm = WavWriter.floatsToPcm16(floatArrayOf(0f, 1f, -1f, 2f))
        assertEquals(8, pcm.size)
        assertEquals(0, littleEndianShort(pcm, 0))
        assertEquals(32767, littleEndianShort(pcm, 2))
        assertEquals(-32767, littleEndianShort(pcm, 4))
        assertEquals(32767, littleEndianShort(pcm, 6))
    }

    private fun littleEndianInt(b: ByteArray, o: Int) =
        (b[o].toInt() and 0xff) or ((b[o + 1].toInt() and 0xff) shl 8) or ((b[o + 2].toInt() and 0xff) shl 16) or ((b[o + 3].toInt() and 0xff) shl 24)

    private fun littleEndianShort(b: ByteArray, o: Int) = ((b[o].toInt() and 0xff) or (b[o + 1].toInt() shl 8)).toShort().toInt()
}

class PiperConfigTest {
    private val config = """
        {
          "audio": {"sample_rate": 22050},
          "espeak": {"voice": "en-us"},
          "inference": {"noise_scale": 0.5, "length_scale": 1.0, "noise_w": 0.333},
          "num_speakers": 1,
          "phoneme_id_map": {"^": [1], "$": [2], "_": [0], "a": [14], "b": [5, 6]}
        }
    """.trimIndent()

    @Test
    fun readsSampleRateScalesAndIds() {
        val c = PiperConfig.parse(config)
        assertEquals(22050, c.sampleRate)
        assertEquals(0.5f, c.noiseScale)
        assertEquals(1.0f, c.lengthScale)
        assertEquals(0.333f, c.noiseW)
        assertEquals(listOf(5L, 6L), c.idsFor("b"))
        assertEquals(null, c.idsFor("z"))
    }

    @Test
    fun missingSampleRateIsRefused() {
        assertFailsWith<IllegalArgumentException> { PiperConfig.parse("""{"phoneme_id_map": {"a": [1]}}""") }
    }

    @Test
    fun encodesWithBosPadAndEosLikePiper() {
        val c = PiperConfig.parse(config)
        val ids = PiperPhonemeEncoder.encode(listOf("a", "z", "b"), c)
        // ^ , a, pad, (z unknown: skipped), b-two-ids, pad, $
        assertContentEquals(longArrayOf(1, 14, 0, 5, 6, 0, 2), ids)
    }
}

class FinnishPhonemizerTest {
    private val fi = FinnishPhonemizer()

    @Test
    fun doubledVowelIsLongAndStressIsOnTheWordStart() {
        assertEquals(listOf("ˈ", "k", "i", "ː", "t", "o", "s"), fi.phonemize("kiitos"))
    }

    @Test
    fun ngAndAumlautsAndSoftCAreMapped() {
        assertEquals(listOf("ˈ", "k", "e", "ŋ", "æ", "t"), fi.phonemize("kengät"))
        assertEquals(listOf("ˈ", "s", "ø", "k", "u"), fi.phonemize("söku"))
        assertEquals(listOf("ˈ", "s", "e"), fi.phonemize("ce"))
    }

    @Test
    fun geminatesAreTwoSymbols() {
        assertEquals(listOf("ˈ", "k", "ɑ", "p", "p", "ɑ", "l", "e"), fi.phonemize("kappale"))
    }

    @Test
    fun wordsAreSeparatedByASpaceToken() {
        assertEquals(listOf("ˈ", "h", "e", "i", " ", "ˈ", "m", "ɑ", "ː"), fi.phonemize("Hei maa"))
    }

    @Test
    fun digitsAndPunctuationAreDropped() {
        assertEquals(listOf("ˈ", "h", "e", "i"), fi.phonemize("hei!! 42"))
    }
}

class EnglishPhonemizerTest {
    private val dict = EnglishPhonemizer.parseCmudict(
        """
        ;;; comment line
        HELLO  HH AH0 L OW1
        HELLO(2)  HH EH0 L OW1
        WORLD  W ER1 L D
        """.trimIndent(),
    )

    @Test
    fun parsesFirstPronunciationOnly() {
        assertEquals(listOf("HH", "AH0", "L", "OW1"), dict["hello"])
        assertEquals(null, dict["hello(2)"])
    }

    @Test
    fun primaryStressBeforeTheStressedVowel() {
        val p = EnglishPhonemizer(dict).phonemize("Hello world")
        assertEquals(listOf("h", "ʌ", "l", "ˈ", "o", "ʊ", " ", "w", "ˈ", "ɝ", "l", "d"), p)
    }

    @Test
    fun unknownWordsUseTheLetterFallback() {
        val p = EnglishPhonemizer(emptyMap()).phonemize("cat")
        assertEquals(listOf("ˈ", "k", "æ", "t"), p)
    }

    @Test
    fun digraphsInFallbackAreOneSound() {
        val p = EnglishPhonemizer(emptyMap()).phonemize("shop")
        assertEquals(listOf("ˈ", "ʃ", "ɒ", "p"), p)
    }
}
