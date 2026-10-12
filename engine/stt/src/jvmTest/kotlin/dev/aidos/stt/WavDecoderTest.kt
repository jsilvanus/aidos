package dev.aidos.stt

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class WavDecoderTest {
    @Test
    fun readsSixteenBitMono() {
        val pcm = WavDecoder.decode(Wavs.pcm16(shortArrayOf(0, 16384, -16384, 32767), 16_000))
        assertEquals(16_000, pcm.sampleRate)
        assertEquals(listOf(0f, 0.5f, -0.5f), pcm.samples.take(3))
    }

    @Test
    fun averagesChannels() {
        val pcm = WavDecoder.decode(Wavs.pcm16(shortArrayOf(16384, -16384, 16384, 16384), 16_000, channels = 2))
        assertEquals(listOf(0f, 0.5f), pcm.samples.toList())
    }

    @Test
    fun resamples48kTo16kKeepingDuration() {
        val tone = ShortArray(48_000) { (10000 * sin(2 * PI * 440.0 * it / 48_000)).toInt().toShort() }
        val pcm = WavDecoder.decode16k(Wavs.pcm16(tone, 48_000))
        assertEquals(16_000, pcm.sampleRate)
        assertTrue(abs(pcm.samples.size - 16_000) <= 1)
        assertTrue(pcm.samples.maxOrNull()!! > 0.25f, "tone survives downsampling")
    }

    @Test
    fun acceptsStreamedHeaderWithUnknownDataLength() {
        val wav = Wavs.pcm16(ShortArray(100) { 1000 }, 16_000)
        // Zero the data chunk size (offset 40) as a live recorder does before it knows the length.
        for (i in 40..43) wav[i] = 0
        assertEquals(100, WavDecoder.decode(wav).samples.size)
    }

    @Test
    fun refusesWhatItCannotDecode() {
        assertFailsWith<SttRequestException> { WavDecoder.decode(byteArrayOf(1, 2, 3)) }
        assertFailsWith<SttRequestException> { WavDecoder.decode("RIFF".encodeToByteArray() + ByteArray(4) + "WAVE".encodeToByteArray()) }
        val compressed = Wavs.pcm16(ShortArray(8), 16_000).also { it[20] = 2 } // format 2 = ADPCM
        assertFailsWith<SttRequestException> { WavDecoder.decode(compressed) }
    }
}
