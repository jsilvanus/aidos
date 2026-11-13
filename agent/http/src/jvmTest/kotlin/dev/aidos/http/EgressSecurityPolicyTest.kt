package dev.aidos.http

import com.sun.net.httpserver.HttpServer
import dev.aidos.kernel.BasicResourceHandle
import dev.aidos.kernel.CapabilityId
import dev.aidos.kernel.CapabilityScope
import dev.aidos.kernel.RedirectPolicy
import dev.aidos.kernel.ToolOutcome
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class EgressSecurityPolicyTest {

    private val mockDns: (String) -> Array<InetAddress> = { host ->
        when (host.lowercase()) {
            "metadata.internal" -> arrayOf(InetAddress.getByName("169.254.169.254"))
            "localhost.internal" -> arrayOf(InetAddress.getByName("127.0.0.1"))
            "router.local" -> arrayOf(InetAddress.getByName("192.168.1.1"))
            "corp.internal" -> arrayOf(InetAddress.getByName("10.0.10.5"))
            "api.huggingface.co" -> arrayOf(InetAddress.getByName("104.18.23.12"))
            "safe.example.com" -> arrayOf(InetAddress.getByName("93.184.216.34"))
            else -> arrayOf(InetAddress.getByName("93.184.216.34"))
        }
    }

    private val policy = EgressSecurityPolicy(dnsResolver = mockDns)

    @Test
    fun `rejects IP literal 169_254_169_254 pre-DNS as required by RFC-0042`() {
        val result = policy.validateDestination("http://169.254.169.254/latest/meta-data/")
        assertIs<EgressValidationResult.Denied>(result)
        assertEquals(EgressOutcome.BLOCKED_PRE_DNS, result.outcome)
        assertTrue(result.reason.contains("IP literal '169.254.169.254' is rejected"))
    }

    @Test
    fun `rejects loopback IP literal 127_0_0_1 pre-DNS unless explicitly granted`() {
        val result = policy.validateDestination("http://127.0.0.1:8080/v1/chat")
        assertIs<EgressValidationResult.Denied>(result)
        assertEquals(EgressOutcome.BLOCKED_PRE_DNS, result.outcome)
    }

    @Test
    fun `allows IP literal when explicitly in allowedHosts`() {
        val scope = CapabilityScope.Network(
            allowedHosts = listOf("127.0.0.1"),
            allowedPorts = listOf(8080),
            allowPrivateAddresses = true
        )
        val result = policy.validateDestination("http://127.0.0.1:8080/v1/chat", scope = scope)
        assertIs<EgressValidationResult.Allowed>(result)
    }

    @Test
    fun `rejects unsupported scheme file and ftp`() {
        val fileResult = policy.validateDestination("file:///etc/passwd")
        assertIs<EgressValidationResult.Denied>(fileResult)
        assertTrue(fileResult.reason.contains("Unsupported scheme"))

        val ftpResult = policy.validateDestination("ftp://example.com/file")
        assertIs<EgressValidationResult.Denied>(ftpResult)
    }

    @Test
    fun `enforces allowedPorts when specified in scope`() {
        val scope = CapabilityScope.Network(
            allowedHosts = listOf("safe.example.com"),
            allowedPorts = listOf(443)
        )
        val port80Result = policy.validateDestination("http://safe.example.com/", scope = scope)
        assertIs<EgressValidationResult.Denied>(port80Result)
        assertTrue(port80Result.reason.contains("Port 80 is not in allowedPorts"))

        val port443Result = policy.validateDestination("https://safe.example.com/", scope = scope)
        assertIs<EgressValidationResult.Allowed>(port443Result)
    }

    @Test
    fun `matches wildcard domains and exact domains`() {
        val scope = CapabilityScope.Network(
            allowedHosts = listOf("*.huggingface.co", "example.com")
        )
        val apiHf = policy.validateDestination("https://api.huggingface.co/models", scope = scope)
        assertIs<EgressValidationResult.Allowed>(apiHf)

        val exactExample = policy.validateDestination("https://example.com/index.html", scope = scope)
        assertIs<EgressValidationResult.Allowed>(exactExample)

        val rogue = policy.validateDestination("https://malicious.org/data", scope = scope)
        assertIs<EgressValidationResult.Denied>(rogue)
        assertTrue(rogue.reason.contains("does not match allowedHosts"))
    }

    @Test
    fun `blocks host that resolves to loopback post-DNS`() {
        val scope = CapabilityScope.Network(
            allowedHosts = listOf("localhost.internal"),
            allowPrivateAddresses = false
        )
        val result = policy.validateDestination("https://localhost.internal/v1", scope = scope)
        assertIs<EgressValidationResult.Denied>(result)
        assertEquals(EgressOutcome.BLOCKED_POST_DNS, result.outcome)
        assertEquals(AddressClass.LOOPBACK, result.addressClass)
        assertTrue(result.reason.contains("loopback address"))
    }

    @Test
    fun `blocks host that resolves to link-local cloud metadata post-DNS`() {
        val scope = CapabilityScope.Network(
            allowedHosts = listOf("metadata.internal"),
            allowPrivateAddresses = false
        )
        val result = policy.validateDestination("https://metadata.internal/meta-data", scope = scope)
        assertIs<EgressValidationResult.Denied>(result)
        assertEquals(EgressOutcome.BLOCKED_POST_DNS, result.outcome)
        assertEquals(AddressClass.LINK_LOCAL, result.addressClass)
        assertTrue(result.reason.contains("cloud metadata defense"))
    }

    @Test
    fun `blocks host that resolves to private network RFC-1918 post-DNS`() {
        val scope192 = CapabilityScope.Network(
            allowedHosts = listOf("router.local"),
            allowPrivateAddresses = false
        )
        val res192 = policy.validateDestination("https://router.local/admin", scope = scope192)
        assertIs<EgressValidationResult.Denied>(res192)
        assertEquals(EgressOutcome.BLOCKED_POST_DNS, res192.outcome)
        assertEquals(AddressClass.PRIVATE, res192.addressClass)

        val scope10 = CapabilityScope.Network(
            allowedHosts = listOf("corp.internal"),
            allowPrivateAddresses = false
        )
        val res10 = policy.validateDestination("https://corp.internal/api", scope = scope10)
        assertIs<EgressValidationResult.Denied>(res10)
        assertEquals(EgressOutcome.BLOCKED_POST_DNS, res10.outcome)
    }

    @Test
    fun `allows private address when allowPrivateAddresses is true`() {
        val scope = CapabilityScope.Network(
            allowedHosts = listOf("router.local"),
            allowPrivateAddresses = true
        )
        val res = policy.validateDestination("https://router.local/status", scope = scope)
        assertIs<EgressValidationResult.Allowed>(res)
    }

    @Test
    fun `rejects cross-host redirect under SAME_HOST_ONLY policy`() {
        val scope = CapabilityScope.Network(
            allowedHosts = listOf("example.com", "other.com"),
            followRedirects = RedirectPolicy.SAME_HOST_ONLY
        )
        val result = policy.validateRedirect(
            fromUrl = "https://example.com/redirect",
            toLocation = "https://other.com/target",
            scope = scope
        )
        assertIs<EgressValidationResult.Denied>(result)
        assertEquals(EgressOutcome.BLOCKED_REDIRECT, result.outcome)
        assertTrue(result.reason.contains("Cross-host redirect blocked by SAME_HOST_ONLY"))
    }

    @Test
    fun `allows same-host redirect under SAME_HOST_ONLY policy`() {
        val scope = CapabilityScope.Network(
            allowedHosts = listOf("example.com"),
            followRedirects = RedirectPolicy.SAME_HOST_ONLY
        )
        val result = policy.validateRedirect(
            fromUrl = "https://example.com/old-path",
            toLocation = "/new-path",
            scope = scope
        )
        assertIs<EgressValidationResult.Allowed>(result)
    }

    @Test
    fun `HttpTool blocks SSRF attempts to 169_254_169_254 with security violation`() = runBlocking {
        val client = DefaultEgressClient(policy = policy)
        HttpTool.egressClient = client

        val handle = BasicResourceHandle(CapabilityId("cap-egress-test"))
        val args = buildJsonObject {
            put("url", "http://169.254.169.254/latest/meta-data/")
        }

        val result = HttpTool.execute(handle, "http:get", args)
        assertIs<ToolOutcome.Failed>(result.outcome)
        val err = (result.outcome as ToolOutcome.Failed).error
        assertEquals("http.security_violation", err.code)
        assertTrue(result.content.first().toString().contains("RFC-0042 Egress Denied"))

        // Audit log should record the denied egress call
        val records = client.getAuditRecords()
        assertEquals(1, records.size)
        val record = records.first()
        assertEquals("169.254.169.254", record.destinationHost)
        assertEquals(EgressOutcome.BLOCKED_PRE_DNS, record.outcome)
        assertEquals("GET", record.method)
    }

    @Test
    fun `DefaultEgressClient rejects responses larger than maxResponseBytes`() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/large") { exchange ->
            val body = "0123456789abcdefghijklmnopqrstuvwxyz".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val port = server.address.port
            val scope = CapabilityScope.Network(
                allowedHosts = listOf("127.0.0.1"),
                allowedPorts = listOf(port),
                allowPrivateAddresses = true,
                maxResponseBytes = 8,
            )
            val client = DefaultEgressClient(policy = policy)
            val result = client.request(
                EgressRequest(
                    capabilityId = CapabilityId("cap-response-size"),
                    url = "http://127.0.0.1:$port/large",
                ),
                scope = scope,
            )
            assertTrue(result.isFailure)
            val record = client.getAuditRecords().last()
            assertEquals(EgressOutcome.BLOCKED_RESPONSE_SIZE, record.outcome)
        } finally {
            server.stop(0)
        }
    }
}
