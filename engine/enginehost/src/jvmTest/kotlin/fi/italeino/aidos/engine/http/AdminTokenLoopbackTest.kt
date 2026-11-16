package fi.italeino.aidos.engine.http

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * RFC-0115: the desktop admin token is minted per start and accepted from loopback only. App tokens are not touched.
 */
class AdminTokenLoopbackTest {
    private val admin = "desktop.localhost"

    private fun server(tokens: TokenManager, loopback: Boolean) = EngineHttpServer(
        tokens,
        MockModelRuntime(MockModelAdapter()),
        isSubjectApproved = { true },
        adminSubjects = setOf(admin, TokenManager.ENGINE_SUBJECT),
        isLoopbackPeer = { loopback },
    )

    @Test
    fun `the admin token works from a loopback peer`() = testApplication {
        val tokens = TokenManager()
        val t = tokens.generateProcessToken(admin)
        val s = server(tokens, loopback = true)
        application { s.installInto(this) }
        assertEquals(HttpStatusCode.OK, client.get("/v1/models") { header(HttpHeaders.Authorization, "Bearer ${t.token}") }.status)
    }

    @Test
    fun `the admin token from a non-loopback peer gets the same 401 as an unknown token`() = testApplication {
        val tokens = TokenManager()
        val t = tokens.generateProcessToken(admin)
        val s = server(tokens, loopback = false)
        application { s.installInto(this) }
        val real = client.get("/v1/models") { header(HttpHeaders.Authorization, "Bearer ${t.token}") }
        val bogus = client.get("/v1/models") { header(HttpHeaders.Authorization, "Bearer ${"0".repeat(64)}") }
        assertEquals(HttpStatusCode.Unauthorized, real.status)
        assertEquals(bogus.status, real.status)
    }

    @Test
    fun `a forwarded-for header does not make a remote peer loopback`() = testApplication {
        val tokens = TokenManager()
        val t = tokens.generateProcessToken(admin)
        val s = server(tokens, loopback = false)
        application { s.installInto(this) }
        val r = client.get("/v1/models") {
            header(HttpHeaders.Authorization, "Bearer ${t.token}")
            header("X-Forwarded-For", "127.0.0.1")
        }
        assertEquals(HttpStatusCode.Unauthorized, r.status)
    }

    @Test
    fun `an app token is not an admin token and is not restricted to loopback`() = testApplication {
        val tokens = TokenManager()
        val app = tokens.generateNewToken(subject = "com.example.app")
        val s = server(tokens, loopback = false)
        application { s.installInto(this) }
        assertEquals(HttpStatusCode.OK, client.get("/v1/models") { header(HttpHeaders.Authorization, "Bearer ${app.token}") }.status)
    }

    @Test
    fun `a new start mints a new admin token and the old one is dead`() {
        val first = TokenManager().generateProcessToken(admin)
        val secondManager = TokenManager()
        val second = secondManager.generateProcessToken(admin)
        assertNotEquals(first.token, second.token)
        assertEquals(64, second.token.length, "256 bits, hex")
        assertNull(secondManager.authenticate(first.token))
        assertEquals(admin, secondManager.authenticate(second.token)?.subject)
    }

    @Test
    fun `the process token has no expiry date`() {
        val m = TokenManager()
        val t = m.generateProcessToken(admin)
        assertTrue(t.expiresAt > java.time.Instant.now().plusSeconds(86400L * 365 * 100))
        assertEquals(t.token, m.currentValidToken(admin))
    }

    @Test
    fun `loopback address recognition`() {
        for (ok in listOf("127.0.0.1", "127.1.2.3", "::1", "[::1]", "0:0:0:0:0:0:0:1", "localhost", "::ffff:127.0.0.1")) {
            assertTrue(isLoopbackAddress(ok), ok)
        }
        for (bad in listOf("192.168.1.5", "10.0.0.1", "8.8.8.8", "fe80::1%eth0", "example.com", "", "127.0.0.1.evil.com")) {
            assertFalse(isLoopbackAddress(bad), bad)
        }
    }
}
