package fi.italeino.aidos.engine.http

import fi.italeino.aidos.engine.lan.ExternalDeviceGrants
import fi.italeino.aidos.engine.lan.PairingCodes
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.auth.UserIdPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.response.respondText
import io.ktor.server.routing.get as routeGet
import io.ktor.server.testing.testApplication
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** RFC-0126: the LAN door authenticates per-device credentials and keeps every per-app check. */
class LanSharingTest {
    private val clock = AtomicReference(Instant.parse("2026-11-15T10:00:00Z"))
    private val grants = ExternalDeviceGrants(now = { clock.get() })
    private val approved = mutableSetOf<String>()
    private val chatBody = """{"model":"test-model","messages":[{"role":"user","content":"hi"}]}"""

    private fun server(pairing: PairingCodes? = null, tokens: TokenManager = TokenManager()) = EngineHttpServer(
        tokens,
        MockModelRuntime(MockModelAdapter()),
        isSubjectApproved = { it in approved },
        externalDevices = grants,
        pairingCodes = pairing,
        extraRoutes = {
            routeGet("/v1/agent/whoami") { call.respondText(call.principal<UserIdPrincipal>()?.name ?: "none") }
        },
    )

    private fun issue(label: String = "laptop") =
        grants.issue(label, clock.get().plusSeconds(3600)).also { approved += it.grant.subject }

    @Test
    fun `a paired and approved device can list models and chat`() = testApplication {
        val device = issue()
        val s = server()
        application { s.installInto(this, lan = true) }
        val models = client.get("/v1/models") { header(HttpHeaders.Authorization, "Bearer ${device.credential}") }
        assertEquals(HttpStatusCode.OK, models.status)
        val chat = client.post("/v1/chat/completions") {
            header(HttpHeaders.Authorization, "Bearer ${device.credential}")
            contentType(ContentType.Application.Json)
            setBody(chatBody)
        }
        assertEquals(HttpStatusCode.OK, chat.status)
    }

    @Test
    fun `no credential, a binder token, or a made-up credential are refused on the LAN`() = testApplication {
        issue()
        val tokens = TokenManager()
        val binder = tokens.generateNewToken().token
        approved += TokenManager.ENGINE_SUBJECT
        val s = server(tokens = tokens)
        application { s.installInto(this, lan = true) }
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/models").status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/models") { header(HttpHeaders.Authorization, "Bearer $binder") }.status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/models") { header(HttpHeaders.Authorization, "Bearer deadbeef") }.status)
    }

    @Test
    fun `a LAN credential is not accepted on the loopback listener`() = testApplication {
        val device = issue()
        val s = server()
        application { s.installInto(this, lan = false) }
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/models") { header(HttpHeaders.Authorization, "Bearer ${device.credential}") }.status)
    }

    @Test
    fun `model use approval still applies - revoking the device's approval stops it at once`() = testApplication {
        val device = issue()
        val s = server()
        application { s.installInto(this, lan = true) }
        val auth = { header: io.ktor.client.request.HttpRequestBuilder -> header.header(HttpHeaders.Authorization, "Bearer ${device.credential}") }
        assertEquals(HttpStatusCode.OK, client.get("/v1/models") { auth(this) }.status)
        approved -= device.grant.subject // user revokes in Connected Apps
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/models") { auth(this) }.status)
    }

    @Test
    fun `an unapproved device is refused even with a valid credential`() = testApplication {
        val device = grants.issue("phone", clock.get().plusSeconds(3600)) // never approved
        val s = server()
        application { s.installInto(this, lan = true) }
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/models") { header(HttpHeaders.Authorization, "Bearer ${device.credential}") }.status)
    }

    @Test
    fun `expired and revoked grants stop authenticating`() {
        val device = issue()
        assertNotNull(grants.authenticate(device.credential))
        clock.set(clock.get().plusSeconds(7200))
        assertNull(grants.authenticate(device.credential))
        clock.set(clock.get().minusSeconds(7200))
        grants.revoke(device.grant.id)
        assertNull(grants.authenticate(device.credential))
        assertTrue(!grants.isSubjectUsable(device.grant.subject))
    }

    @Test
    fun `model administration and peer endpoints are not served on the LAN`() = testApplication {
        val device = issue()
        val s = server()
        application { s.installInto(this, lan = true) }
        val auth = { b: io.ktor.client.request.HttpRequestBuilder -> b.header(HttpHeaders.Authorization, "Bearer ${device.credential}") }
        assertEquals(HttpStatusCode.NotFound, client.post("/v1/models/test-model/open") { auth(this) }.status)
        assertEquals(HttpStatusCode.NotFound, client.post("/aidin/v1/route") { auth(this); setBody("{}") }.status)
    }

    @Test
    fun `host routes such as agent run inside the auth block as the ext subject`() = testApplication {
        val device = issue()
        val s = server()
        application { s.installInto(this, lan = true) }
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/agent/whoami").status)
        val who = client.get("/v1/agent/whoami") { header(HttpHeaders.Authorization, "Bearer ${device.credential}") }
        assertEquals(device.grant.subject, who.bodyAsText())
        assertTrue(who.bodyAsText().startsWith("ext:"))
    }

    @Test
    fun `pairing code is exchanged once for a credential and never works as a bearer`() = testApplication {
        val codes = PairingCodes(grants, now = { clock.get() })
        val s = server(pairing = codes)
        application { s.installInto(this, lan = true) }
        val code = codes.start("tablet", 3600)
        // The code itself is not a credential.
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/models") { header(HttpHeaders.Authorization, "Bearer $code") }.status)
        val ok = client.post("/lan/v1/pair") { contentType(ContentType.Application.Json); setBody("""{"code":"$code"}""") }
        assertEquals(HttpStatusCode.OK, ok.status)
        assertTrue(ok.bodyAsText().contains("\"credential\""))
        val second = client.post("/lan/v1/pair") { contentType(ContentType.Application.Json); setBody("""{"code":"$code"}""") }
        assertEquals(HttpStatusCode.Unauthorized, second.status)
    }

    @Test
    fun `pairing codes expire, and wrong guesses burn them`() {
        val codes = PairingCodes(grants, now = { clock.get() }, codeLifetimeSeconds = 60, maxAttempts = 3)
        val expiring = codes.start("a", 3600)
        clock.set(clock.get().plusSeconds(61))
        assertNull(codes.redeem(expiring))

        val guessed = codes.start("b", 3600)
        repeat(3) { assertNull(codes.redeem("00000000")) }
        assertNull(codes.redeem(guessed)) // burned by the failed guesses
    }

    @Test
    fun `credentials are stored hashed and survive a restart`() {
        val dir = java.nio.file.Files.createTempDirectory("lan").toFile()
        val file = dir.resolve("devices.json")
        val a = ExternalDeviceGrants(file, now = { clock.get() })
        val device = a.issue("desk", clock.get().plusSeconds(3600))
        assertTrue(!file.readText().contains(device.credential))
        val b = ExternalDeviceGrants(file, now = { clock.get() })
        assertEquals(device.grant.id, b.authenticate(device.credential)?.id)
    }
}
