package dev.aidos.voice

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlinx.coroutines.runBlocking

/**
 * Tests for M33 STT/TTS provider interfaces (RFC-0057).
 *
 * Split from the original VoiceTest.kt (RFC-0103): SpokenSummaryGenerator and
 * VoiceApprovalHandler moved to agent/voice/VoiceApprovalTest.kt, since they depend on
 * agent/androidapp and agent/settings, not on model inference.
 */
class VoiceProvidersTest {

    @Test
    fun `STT and TTS providers exist (even if no-op for MVP)`() {
        val stt = NoOpSttProvider()
        val tts = NoOpTtsProvider()

        runBlocking {
            assertFalse(stt.isAvailable(), "No-op STT provider should report not available")
            assertFalse(tts.isAvailable(), "No-op TTS provider should report not available")
        }
    }
}
