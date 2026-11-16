package fi.italeino.aidos.engine.http

import fi.italeino.aidos.engine.lan.ExternalDeviceGrants
import fi.italeino.aidos.engine.lan.LanDiscovery
import fi.italeino.aidos.engine.lan.LanTlsIdentity
import fi.italeino.aidos.engine.lan.PairingCodes
import fi.italeino.aidos.sdk.client.LanPairing
import fi.italeino.aidos.sdk.client.LanPairingException
import fi.italeino.aidos.sdk.client.LanPairingUri
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** RFC-0126: the SDK pairing helper against a real HTTPS listener, plus the mDNS announcement. */
class LanPairingEndToEndTest {

    @Test
    fun `pairing uri round trips and rejects anything malformed`() {
        val uri = LanPairingUri("192.168.1.20", 18443, "12345678", "ab".repeat(32))
        assertEquals(uri, LanPairingUri.parse(uri.toString()))
        assertNull(LanPairingUri.parse("https://192.168.1.20:18443/12345678?fp=" + "ab".repeat(32)))
        assertNull(LanPairingUri.parse("aidos-pair://host:99999/12345678?fp=" + "ab".repeat(32)))
        assertNull(LanPairingUri.parse("aidos-pair://host:18443/1234?fp=" + "ab".repeat(32)))
        assertNull(LanPairingUri.parse("aidos-pair://host:18443/12345678?fp=short"))
    }

    @Test
    fun `an SDK client pairs with a code over pinned TLS and then uses its own credential`(): Unit = runBlocking {
        val dir = Files.createTempDirectory("lan-pair").toFile()
        val ssl = LanTlsIdentity.loadOrCreate(dir.resolve("lan.p12"), "pw")
        val approved = mutableSetOf<String>()
        val grants = ExternalDeviceGrants(onIssued = { approved += it.subject })
        val codes = PairingCodes(grants)
        val server = EngineHttpServer(
            TokenManager(), MockModelRuntime(MockModelAdapter()),
            isSubjectApproved = { it in approved }, externalDevices = grants, pairingCodes = codes,
        )
        server.startLan(ssl, io.ktor.server.netty.Netty, port = 0, host = "127.0.0.1")
        try {
            val port = server.getBoundLanPort()!!
            val code = codes.start("phone", 3600)
            val uri = LanPairingUri("127.0.0.1", port, code, ssl.fingerprintSha256)

            val credential = LanPairing.redeem(uri)
            assertTrue(credential.credential.length >= 32)
            assertNotNull(credential.expiresAt)

            val client = LanPairing.pinnedClient(credential.fingerprintSha256)
            client.newCall(LanPairing.request(credential, "/v1/models").build()).execute().use {
                assertEquals(200, it.code)
            }

            // The code was single use.
            assertFailsWith<LanPairingException> { LanPairing.redeem(uri) }
        } finally {
            server.stop()
        }
    }

    @Test
    fun `a client pinned to a different fingerprint never sends the code`(): Unit = runBlocking {
        val dir = Files.createTempDirectory("lan-pair2").toFile()
        val ssl = LanTlsIdentity.loadOrCreate(dir.resolve("lan.p12"), "pw")
        val grants = ExternalDeviceGrants()
        val codes = PairingCodes(grants)
        val server = EngineHttpServer(TokenManager(), MockModelRuntime(MockModelAdapter()), externalDevices = grants, pairingCodes = codes)
        server.startLan(ssl, io.ktor.server.netty.Netty, port = 0, host = "127.0.0.1")
        try {
            val port = server.getBoundLanPort()!!
            val code = codes.start("phone", 3600)
            // A man in the middle has a different certificate, which is what a wrong pin simulates.
            val wrongPin = LanPairingUri("127.0.0.1", port, code, "00".repeat(32))
            assertFailsWith<Exception> { LanPairing.redeem(wrongPin) }
            // The code was not consumed by the failed attempt: the right client can still use it.
            val right = LanPairingUri("127.0.0.1", port, code, ssl.fingerprintSha256)
            assertNotNull(LanPairing.redeem(right).credential)
            assertTrue(grants.list().size == 1)
            assertTrue(Instant.parse(grants.list().single().expiresAt).isAfter(Instant.now()))
        } finally {
            server.stop()
        }
    }

    @Test
    fun `the Engine announces itself over mDNS with its certificate fingerprint`() {
        val fp = "cd".repeat(32)
        val announcement = runCatching { LanDiscovery.announce("aidos-engine-test", 18443, fp) }.getOrNull()
        org.junit.Assume.assumeTrue("mDNS is not available in this environment", announcement != null)
        try {
            val seen = LanDiscovery.find(timeoutMillis = 4_000).firstOrNull { it.name == "aidos-engine-test" }
            org.junit.Assume.assumeTrue("multicast does not loop back in this environment", seen != null)
            assertEquals(fp, seen!!.fingerprintSha256)
            assertEquals(18443, seen.port)
        } finally {
            announcement?.close()
        }
    }
}
