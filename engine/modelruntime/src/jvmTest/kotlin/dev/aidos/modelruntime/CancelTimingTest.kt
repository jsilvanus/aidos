package dev.aidos.modelruntime

import dev.aidos.kernel.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Real-model check (set AIDOS_TEST_GGUF to a chat GGUF; skipped otherwise): cancelling a long
 * generation, by coroutine cancel or [CancellableModelAdapter.cancelCurrentInference], must end the
 * generating thread promptly and leave the next request unblocked.
 */
class CancelTimingTest {
    private fun req(p: String, n: Int) = ModelRequest(
        listOf(Turn.User(listOf(ContentBlock.Text(p)), TrustLevel.UNTRUSTED)), emptyList(), ToolChoice.None, n)

    @Test
    fun cancelTiming() = runBlocking {
        val path = System.getenv("AIDOS_TEST_GGUF") ?: return@runBlocking
        val backend = LlamaCppInferenceBackend()
        val f = File(path)
        val t0 = System.nanoTime()
        val adapter = backend.load(f.name, f.absolutePath).getOrThrow() as CancellableModelAdapter
        println("RESULT load ms=${(System.nanoTime()-t0)/1_000_000}")
        for (mode in listOf("coroutine-cancel", "cancelCurrentInference")) {
            val started = CompletableDeferred<Unit>()
            var tokens = 0
            val tg = System.nanoTime()
            val job = launch(Dispatchers.IO) {
                try {
                    adapter.invokeStreaming(req("Write a very long detailed story about a dragon.", 1500)).collect {
                        if (it is ModelStreamEvent.Delta) { tokens++; if (tokens == 20) started.complete(Unit) }
                    }
                } catch (e: CancellationException) { throw e }
            }
            started.await()
            val tFirst = (System.nanoTime() - tg) / 1_000_000
            val tc = System.nanoTime()
            if (mode == "coroutine-cancel") job.cancelAndJoin() else { adapter.cancelCurrentInference(); job.join() }
            val cancelMs = (System.nanoTime() - tc) / 1_000_000
            assertTrue(cancelMs < 3_000, "$mode: cancelled generation still running after ${cancelMs}ms")
            val tokensAtCancel = tokens
            val tn = System.nanoTime()
            val r = withTimeoutOrNull(120_000) { adapter.invoke(req("Say hi.", 8)) }
            val nextMs = (System.nanoTime() - tn) / 1_000_000
            println("RESULT mode=$mode first20tokens_ms=$tFirst cancel_join_ms=$cancelMs tokens_before_cancel=$tokensAtCancel next_request_ms=$nextMs ok=${r?.isSuccess} text=${r?.getOrNull()?.outputs?.firstOrNull()}")
        }
        backend.unload(f.name)
    }
}
