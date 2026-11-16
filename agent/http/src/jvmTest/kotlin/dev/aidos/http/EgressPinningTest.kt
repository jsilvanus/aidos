package dev.aidos.http

import com.sun.net.httpserver.HttpServer
import com.sun.net.httpserver.HttpsConfigurator
import com.sun.net.httpserver.HttpsServer
import dev.aidos.kernel.CapabilityId
import dev.aidos.kernel.CapabilityScope
import dev.aidos.kernel.RedirectPolicy
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.file.Files
import java.security.KeyStore
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** RFC-0042 point 3: the address that was checked is the address that is connected. */
class EgressPinningTest {

    private fun scope(vararg ports: Int, private: Boolean = true, redirects: RedirectPolicy = RedirectPolicy.SAME_HOST_ONLY, hosts: List<String>) =
        CapabilityScope.Network(
            allowedHosts = hosts,
            allowedPorts = ports.toList(),
            allowPrivateAddresses = private,
            followRedirects = redirects,
        )

    private fun okServer(): HttpServer {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            exchange.sendResponseHeaders(200, 2)
            exchange.responseBody.use { it.write("ok".toByteArray()) }
        }
        server.start()
        return server
    }

    @Test
    fun `a name that answers differently on a second lookup is dialled at the checked address`() = runBlocking {
        val server = okServer()
        try {
            val lookups = AtomicInteger()
            // First answer is the checked one; every later answer is the rebinding target.
            val policy = EgressSecurityPolicy(dnsResolver = {
                if (lookups.getAndIncrement() == 0) arrayOf(InetAddress.getByName("127.0.0.1"))
                else arrayOf(InetAddress.getByName("10.255.255.1"))
            })
            val port = server.address.port
            val result = DefaultEgressClient(policy = policy).request(
                EgressRequest(CapabilityId("cap-pin"), "http://rebind.test:$port/"),
                scope(port, hosts = listOf("rebind.test")),
            )
            // The name rebind.test does not resolve anywhere real: reaching the server proves the
            // client used the checked address and did not look the name up again.
            assertEquals(200, result.getOrThrow().statusCode)
            assertEquals(1, lookups.get(), "the name must be resolved exactly once")
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `a name that resolves to a public and a private address is refused`() {
        val policy = EgressSecurityPolicy(dnsResolver = {
            arrayOf(InetAddress.getByName("93.184.216.34"), InetAddress.getByName("10.0.0.5"))
        })
        val denied = policy.validateDestination(
            "https://mixed.example/",
            scope = CapabilityScope.Network(allowedHosts = listOf("mixed.example"), allowedPorts = listOf(443)),
        )
        assertIs<EgressValidationResult.Denied>(denied)
        assertEquals(EgressOutcome.BLOCKED_POST_DNS, denied.outcome)
    }

    @Test
    fun `the connected socket is checked again by the policy`() {
        val policy = EgressSecurityPolicy()
        val net = CapabilityScope.Network(allowedHosts = listOf("x.example"))
        assertEquals(EgressOutcome.BLOCKED_POST_DNS, policy.addressDenial("x.example", InetAddress.getByName("10.0.0.5"), net)?.outcome)
        assertEquals(EgressOutcome.BLOCKED_POST_DNS, policy.addressDenial("x.example", InetAddress.getByName("169.254.169.254"), net)?.outcome)
        assertEquals(null, policy.addressDenial("x.example", InetAddress.getByName("93.184.216.34"), net))
    }

    @Test
    fun `a redirect hop is resolved and checked on its own`() = runBlocking {
        val first = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val port = first.address.port
        first.createContext("/") { exchange ->
            exchange.responseHeaders.add("Location", "http://second.test:$port/")
            exchange.sendResponseHeaders(302, -1)
            exchange.close()
        }
        first.start()
        try {
            val policy = EgressSecurityPolicy(dnsResolver = { host ->
                when (host) {
                    "first.test" -> arrayOf(InetAddress.getByName("127.0.0.1"))
                    else -> arrayOf(InetAddress.getByName("169.254.169.254"))
                }
            })
            val client = DefaultEgressClient(policy = policy)
            val result = client.request(
                EgressRequest(CapabilityId("cap-hop"), "http://first.test:$port/"),
                scope(port, redirects = RedirectPolicy.ANY_ALLOWED_HOST, hosts = listOf("first.test", "second.test")),
            )
            assertTrue(result.isFailure, "the second hop resolves to the metadata address")
            assertEquals(EgressOutcome.BLOCKED_POST_DNS, client.getAuditRecords().last().outcome)
        } finally {
            first.stop(0)
        }
    }

    // --- TLS still verifies the real host name when the connection is pinned to an address ---

    private fun selfSigned(dnsName: String): Pair<KeyStore, X509TrustManager> {
        val dir = Files.createTempDirectory("egress-tls").toFile()
        try {
            val file = File(dir, "ks.p12")
            val proc = ProcessBuilder(
                "keytool", "-genkeypair", "-alias", "s", "-keyalg", "RSA", "-keysize", "2048",
                "-dname", "CN=$dnsName", "-ext", "SAN=dns:$dnsName", "-validity", "2",
                "-storetype", "PKCS12", "-keystore", file.path, "-storepass", "changeit", "-keypass", "changeit",
            ).redirectErrorStream(true).start()
            val out = proc.inputStream.readBytes().decodeToString()
            check(proc.waitFor() == 0) { "keytool failed: $out" }
            val ks = KeyStore.getInstance("PKCS12").apply { file.inputStream().use { load(it, "changeit".toCharArray()) } }
            val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(ks) }
            return ks to (tmf.trustManagers.single() as X509TrustManager)
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun httpsServer(ks: KeyStore): HttpsServer {
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(ks, "changeit".toCharArray()) }
        val ctx = SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, null, null) }
        val server = HttpsServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.httpsConfigurator = HttpsConfigurator(ctx)
        server.createContext("/") { exchange ->
            exchange.sendResponseHeaders(200, 2)
            exchange.responseBody.use { it.write("ok".toByteArray()) }
        }
        server.start()
        return server
    }

    private fun trustingClient(tm: X509TrustManager): OkHttpClient {
        val ctx = SSLContext.getInstance("TLS").apply { init(null, arrayOf(tm), null) }
        return OkHttpClient.Builder().sslSocketFactory(ctx.socketFactory, tm).build()
    }

    @Test
    fun `TLS verifies the requested host name even though the connection goes to the pinned address`() = runBlocking {
        val (matching, matchingTrust) = selfSigned("pinned.test")
        val (other, otherTrust) = selfSigned("someone-else.test")
        val good = httpsServer(matching)
        val bad = httpsServer(other)
        try {
            val policy = EgressSecurityPolicy(dnsResolver = { arrayOf(InetAddress.getByName("127.0.0.1")) })

            val goodPort = good.address.port
            val ok = DefaultEgressClient(policy = policy, baseClient = trustingClient(matchingTrust)).request(
                EgressRequest(CapabilityId("cap-tls-ok"), "https://pinned.test:$goodPort/"),
                scope(goodPort, hosts = listOf("pinned.test")),
            )
            assertEquals(200, ok.getOrThrow().statusCode)

            // The certificate is trusted but names another host: the connection must not be accepted.
            val badPort = bad.address.port
            val refused = DefaultEgressClient(policy = policy, baseClient = trustingClient(otherTrust)).request(
                EgressRequest(CapabilityId("cap-tls-bad"), "https://pinned.test:$badPort/"),
                scope(badPort, hosts = listOf("pinned.test")),
            )
            assertTrue(refused.isFailure, "a certificate for another host name was accepted")
        } finally {
            good.stop(0)
            bad.stop(0)
        }
    }
}
