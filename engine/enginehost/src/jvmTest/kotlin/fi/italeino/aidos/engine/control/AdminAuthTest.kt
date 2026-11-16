package fi.italeino.aidos.engine.control

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** RFC-0115: the Engine's admin token changes at every start; a client with a stale one re-reads the file once and retries once. */
class AdminAuthTest {
    private val calls = AtomicInteger()
    private val server: HttpServer = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
        createContext("/v1/admin/status") { ex ->
            calls.incrementAndGet()
            val ok = ex.requestHeaders.getFirst("Authorization") == "Bearer new-token"
            val body = if (ok) STATUS else "{}"
            ex.sendResponseHeaders(if (ok) 200 else 401, body.length.toLong())
            ex.responseBody.use { it.write(body.toByteArray()) }
        }
        start()
    }
    private val base get() = "http://127.0.0.1:${server.address.port}"

    @AfterTest
    fun stop() = server.stop(0)

    @Test
    fun `a stale token recovers after one re-read and one retry`() = runBlocking {
        val control = HttpStatusControl(base, "old-token", reloadToken = { "new-token" })
        control.status()
        assertEquals(2, calls.get())
        // The refreshed token is kept: the next call needs no retry.
        control.status()
        assertEquals(3, calls.get())
    }

    @Test
    fun `a wrong token fails after a single attempt when the file holds the same token`() = runBlocking {
        val control = HttpStatusControl(base, "wrong", reloadToken = { "wrong" })
        val e = assertFailsWith<EngineControlException> { control.status() }
        assertEquals(401, e.httpStatus)
        assertEquals(1, calls.get())
    }

    @Test
    fun `a wrong token is retried once and no more when the file holds another wrong token`() = runBlocking {
        val control = HttpStatusControl(base, "wrong", reloadToken = { "also-wrong" })
        assertFailsWith<EngineControlException> { control.status() }
        assertEquals(2, calls.get())
    }

    @Test
    fun `without a reload source a 401 is reported as it is`() = runBlocking {
        assertFailsWith<EngineControlException> { HttpStatusControl(base, "old-token").status() }
        assertEquals(1, calls.get())
    }

    private companion object {
        const val STATUS = """{"running":true,"stateName":"READY"}"""
    }
}
