package dev.aidos.androidapp.voice

import dev.aidos.ui.assistant.SttBackend
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HybridSttRouterTest {

    private class FakeSttEngine(
        override val backend: SttBackend,
        var available: Boolean = true,
        var startError: Throwable? = null,
        var partialText: String = "",
        var finalResult: Result<String> = Result.success(""),
    ) : LocalSttEngine {
        var startedCount = 0
        var stoppedCount = 0
        var cancelledCount = 0

        override fun isAvailable(): Boolean = available

        override suspend fun start(
            language: String?,
            onPartial: (String) -> Unit,
            onError: (String) -> Unit,
        ) {
            startedCount++
            startError?.let { throw it }
            if (partialText.isNotEmpty()) {
                onPartial(partialText)
            }
        }

        override suspend fun stop(): Result<String> {
            stoppedCount++
            return finalResult
        }

        override fun cancel() {
            cancelledCount++
        }
    }

    @Test
    fun `auto prefers aidos engine when both are available`() = runTest {
        val aidos = FakeSttEngine(
            backend = SttBackend.AIDOS_ENGINE,
            partialText = "build",
            finalResult = Result.success("build the project"),
        )
        val android = FakeSttEngine(
            backend = SttBackend.ANDROID_DEVICE,
            finalResult = Result.success("from android"),
        )
        val router = HybridSttRouter(aidosStt = aidos, androidDeviceStt = android)

        assertEquals(
            setOf(SttBackend.AUTO, SttBackend.ANDROID_DEVICE, SttBackend.AIDOS_ENGINE),
            router.state.value.availableBackends,
        )

        router.startListening()
        assertTrue(router.state.value.isListening)
        assertEquals(SttBackend.AIDOS_ENGINE, router.state.value.activeBackend)
        assertEquals("build", router.state.value.partialText)

        val stopped = router.stopListening()
        assertEquals("build the project", stopped)
        assertFalse(router.state.value.isListening)
        assertEquals(1, aidos.startedCount)
        assertEquals(0, android.startedCount)
    }

    @Test
    fun `auto falls back to android device stt when aidos engine is unavailable or fails to start`() = runTest {
        val aidos = FakeSttEngine(
            backend = SttBackend.AIDOS_ENGINE,
            available = true,
            startError = IllegalStateException("Engine offline"),
        )
        val android = FakeSttEngine(
            backend = SttBackend.ANDROID_DEVICE,
            partialText = "open inbox",
            finalResult = Result.success("open inbox"),
        )
        val router = HybridSttRouter(aidosStt = aidos, androidDeviceStt = android)

        router.startListening("en-US")
        assertTrue(router.state.value.isListening)
        assertEquals(SttBackend.ANDROID_DEVICE, router.state.value.activeBackend)
        assertEquals("open inbox", router.stopListening())
        assertEquals(1, aidos.startedCount)
        assertEquals(1, android.startedCount)
    }

    @Test
    fun `explicit android device selection bypasses aidos engine`() = runTest {
        val aidos = FakeSttEngine(backend = SttBackend.AIDOS_ENGINE)
        val android = FakeSttEngine(
            backend = SttBackend.ANDROID_DEVICE,
            finalResult = Result.success("local device dictation"),
        )
        val router = HybridSttRouter(aidosStt = aidos, androidDeviceStt = android)

        router.selectBackend(SttBackend.ANDROID_DEVICE)
        router.startListening()
        assertEquals(SttBackend.ANDROID_DEVICE, router.state.value.activeBackend)
        assertEquals("local device dictation", router.stopListening())
        assertEquals(0, aidos.startedCount)
        assertEquals(1, android.startedCount)
    }

    @Test
    fun `reports clear error when no stt backend is available`() = runTest {
        val aidos = FakeSttEngine(backend = SttBackend.AIDOS_ENGINE, available = false)
        val android = FakeSttEngine(backend = SttBackend.ANDROID_DEVICE, available = false)
        val router = HybridSttRouter(aidosStt = aidos, androidDeviceStt = android)

        router.startListening()
        assertFalse(router.state.value.isListening)
        assertNull(router.state.value.activeBackend)
        assertEquals("No speech recognition engine available", router.state.value.errorMessage)
    }
}
