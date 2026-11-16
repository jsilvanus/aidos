package fi.italeino.aidos.engine.http

import fi.italeino.aidos.engine.lan.ExternalDeviceGrants
import fi.italeino.aidos.engine.lan.LanTlsIdentity
import kotlinx.coroutines.runBlocking
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.time.Instant
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** RFC-0126: a real HTTPS listener, certificate pinning by fingerprint, and no cleartext LAN port. */
class LanTlsEndToEndTest {

    private fun pinned(fingerprint: String): HttpClient {
        val tm = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                val fp = MessageDigest.getInstance("SHA-256").digest(chain[0].encoded).joinToString("") { "%02x".format(it) }
                if (fp != fingerprint) throw java.security.cert.CertificateException("fingerprint mismatch")
            }
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val ctx = SSLContext.getInstance("TLS").apply { init(null, arrayOf(tm), null) }
        // Hostname verification is replaced by the pin, which is stronger for a self-signed LAN identity.
        val params = javax.net.ssl.SSLParameters().apply { endpointIdentificationAlgorithm = "" }
        return HttpClient.newBuilder().sslContext(ctx).sslParameters(params).build()
    }

    @Test
    fun `LAN listener serves HTTPS to a pinned client with a device credential and refuses cleartext and wrong pins`(): Unit = runBlocking {
        val dir = Files.createTempDirectory("lan-tls").toFile()
        val ssl = LanTlsIdentity.loadOrCreate(dir.resolve("lan.p12"), "test-password")
        // Same identity on reload: the pinned fingerprint survives a restart.
        assertEquals(ssl.fingerprintSha256, LanTlsIdentity.loadOrCreate(dir.resolve("lan.p12"), "test-password").fingerprintSha256)

        val grants = ExternalDeviceGrants()
        val device = grants.issue("laptop", Instant.now().plusSeconds(600))
        val server = EngineHttpServer(
            TokenManager(),
            MockModelRuntime(MockModelAdapter()),
            isSubjectApproved = { it == device.grant.subject },
            externalDevices = grants,
        )
        server.startLan(ssl, io.ktor.server.netty.Netty, port = 0, host = "127.0.0.1")
        try {
            val port = server.getBoundLanPort()!!
            val request = HttpRequest.newBuilder(URI("https://127.0.0.1:$port/v1/models"))
                .header("Authorization", "Bearer ${device.credential}").build()

            val ok = pinned(ssl.fingerprintSha256).send(request, HttpResponse.BodyHandlers.ofString())
            assertEquals(200, ok.statusCode())

            val noCredential = pinned(ssl.fingerprintSha256).send(
                HttpRequest.newBuilder(URI("https://127.0.0.1:$port/v1/models")).build(), HttpResponse.BodyHandlers.ofString(),
            )
            assertEquals(401, noCredential.statusCode())

            assertFailsWith<Exception> { pinned("00".repeat(32)).send(request, HttpResponse.BodyHandlers.ofString()) }

            // There is no cleartext door on the LAN port.
            assertFailsWith<Exception> {
                HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI("http://127.0.0.1:$port/health")).build(), HttpResponse.BodyHandlers.ofString(),
                ).also { assertTrue(it.statusCode() !in 200..299) }
            }
        } finally {
            server.stop()
        }
    }
}
