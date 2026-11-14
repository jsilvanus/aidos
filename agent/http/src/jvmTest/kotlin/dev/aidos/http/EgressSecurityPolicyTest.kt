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

    @Test
    fun `DefaultEgressClient aborts chunked streaming response at maxResponseBytes plus one`() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/chunked-large") { exchange ->
            exchange.sendResponseHeaders(200, 0L) // chunked encoding, no Content-Length
            val chunk = ByteArray(1024) { 'a'.code.toByte() }
            try {
                exchange.responseBody.use { out ->
                    repeat(128) {
                        out.write(chunk)
                        out.flush()
                    }
                }
            } catch (_: Exception) {
                // Expected when client closes stream cleanly upon hitting maxResponseBytes + 1
            }
        }
        server.createContext("/chunked-exact") { exchange ->
            exchange.sendResponseHeaders(200, 0L)
            exchange.responseBody.use { out ->
                out.write("1234".toByteArray())
                out.flush()
                out.write("5678".toByteArray())
                out.flush()
            }
        }
        server.start()
        try {
            val port = server.address.port
            val client = DefaultEgressClient(policy = policy)

            val oversizedScope = CapabilityScope.Network(
                allowedHosts = listOf("127.0.0.1"),
                allowedPorts = listOf(port),
                allowPrivateAddresses = true,
                maxResponseBytes = 1024,
            )
            val oversizedResult = client.request(
                EgressRequest(
                    capabilityId = CapabilityId("cap-chunked-oversize"),
                    url = "http://127.0.0.1:$port/chunked-large",
                ),
                scope = oversizedScope,
            )
            assertTrue(oversizedResult.isFailure)
            assertIs<SecurityException>(oversizedResult.exceptionOrNull())
            val oversizedRecord = client.getAuditRecords().last()
            assertEquals(EgressOutcome.BLOCKED_RESPONSE_SIZE, oversizedRecord.outcome)
            assertEquals(1025L, oversizedRecord.responseBytes)

            val exactScope = CapabilityScope.Network(
                allowedHosts = listOf("127.0.0.1"),
                allowedPorts = listOf(port),
                allowPrivateAddresses = true,
                maxResponseBytes = 8,
            )
            val exactResult = client.request(
                EgressRequest(
                    capabilityId = CapabilityId("cap-chunked-exact"),
                    url = "http://127.0.0.1:$port/chunked-exact",
                ),
                scope = exactScope,
            )
            assertTrue(exactResult.isSuccess)
            assertEquals("12345678", exactResult.getOrThrow().bodyString)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `detects and rejects obfuscated IPv4 and IPv4-mapped IPv6 literals without DNS bypass`() {
        val obfuscatedCases = listOf(
            "2130706433" to AddressClass.LOOPBACK,
            "2852039166" to AddressClass.LINK_LOCAL,
            "0177.0.0.1" to AddressClass.LOOPBACK,
            "017700000001" to AddressClass.LOOPBACK,
            "0300.0250.0.1" to AddressClass.PRIVATE,
            "0x7f000001" to AddressClass.LOOPBACK,
            "0x7f.0.0.1" to AddressClass.LOOPBACK,
            "0xa9fea9fe" to AddressClass.LINK_LOCAL,
            "127.1" to AddressClass.LOOPBACK,
            "127.0.1" to AddressClass.LOOPBACK,
            "10.1" to AddressClass.PRIVATE,
            "192.168.1" to AddressClass.PRIVATE,
            "127.0.0.1." to AddressClass.LOOPBACK,
            "::ffff:127.0.0.1" to AddressClass.LOOPBACK,
            "::ffff:7f00:1" to AddressClass.LOOPBACK,
            "::ffff:169.254.169.254" to AddressClass.LINK_LOCAL,
            "::ffff:a9fe:a9fe" to AddressClass.LINK_LOCAL,
            "::ffff:10.0.0.1" to AddressClass.PRIVATE,
            "64:ff9b::127.0.0.1" to AddressClass.LOOPBACK,
            "2002:7f00:0001::" to AddressClass.LOOPBACK,
            "::1" to AddressClass.LOOPBACK,
        )

        val noDnsCallsPolicy = EgressSecurityPolicy(
            dnsResolver = { host -> error("DNS must not be queried for IP literal '$host'") },
        )
        val wildcardScope = CapabilityScope.Network(
            allowedHosts = listOf("*"),
            allowPrivateAddresses = false,
        )

        for ((literal, expectedClass) in obfuscatedCases) {
            assertTrue(EgressSecurityPolicy.isIpLiteral(literal), "Expected isIpLiteral('$literal') to be true")
            assertEquals(expectedClass, EgressSecurityPolicy.classifyIpString(literal), "Classification mismatch for '$literal'")

            val urlHost = if (literal.contains(':')) "[$literal]" else literal
            val url = "http://$urlHost/latest/meta-data/"
            val parsedHost = java.net.URI.create(url).host
            for (scope in listOf(noDnsCallsPolicy.defaultScope, wildcardScope)) {
                val res = noDnsCallsPolicy.validateDestination(url, scope = scope)
                assertIs<EgressValidationResult.Denied>(res, "Expected denial for $url")
                assertEquals(EgressOutcome.BLOCKED_PRE_DNS, res.outcome)
                if (parsedHost != null) {
                    assertEquals(expectedClass, res.addressClass, "Expected $expectedClass for $url")
                    assertTrue(res.reason.contains("IP literal"), "Expected IP literal denial reason for $url, got: ${res.reason}")
                }
            }
        }

        // Bracketed IPv6 literal string checks
        assertTrue(EgressSecurityPolicy.isIpLiteral("[::ffff:127.0.0.1]"))
        assertTrue(EgressSecurityPolicy.isIpLiteral("[::ffff:7f00:1]"))

        // InetAddress classification for 16-byte IPv4-mapped IPv6 directly
        val mappedLoopback = InetAddress.getByAddress(
            byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, -1, -1, 127, 0, 0, 1),
        )
        assertEquals(AddressClass.LOOPBACK, EgressSecurityPolicy.classifyAddress(mappedLoopback))
        val mappedLinkLocal = InetAddress.getByAddress(
            byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, -1, -1, 169.toByte(), 254.toByte(), 169.toByte(), 254.toByte()),
        )
        assertEquals(AddressClass.LINK_LOCAL, EgressSecurityPolicy.classifyAddress(mappedLinkLocal))

        // Obfuscated loopback allowed as pattern still fails post-DNS SSRF check when allowPrivateAddresses=false
        val explicitlyNamedObfuscatedScope = CapabilityScope.Network(
            allowedHosts = listOf("2130706433", "::ffff:127.0.0.1"),
            allowPrivateAddresses = false,
        )
        val obfuscatedV4Res = noDnsCallsPolicy.validateDestination("http://2130706433/", scope = explicitlyNamedObfuscatedScope)
        assertIs<EgressValidationResult.Denied>(obfuscatedV4Res)
        assertEquals(EgressOutcome.BLOCKED_POST_DNS, obfuscatedV4Res.outcome)
        assertEquals(AddressClass.LOOPBACK, obfuscatedV4Res.addressClass)

        val mappedV6Res = noDnsCallsPolicy.validateDestination("http://[::ffff:127.0.0.1]/", scope = explicitlyNamedObfuscatedScope)
        assertIs<EgressValidationResult.Denied>(mappedV6Res)
        assertEquals(EgressOutcome.BLOCKED_POST_DNS, mappedV6Res.outcome)
        assertEquals(AddressClass.LOOPBACK, mappedV6Res.addressClass)
    }

    @Test
    fun `legitimate hostnames starting with digits remain non-IP hostnames`() {
        val legitimateHosts = listOf(
            "123example.com",
            "api.example.com",
            "123.example.com",
            "0xdead.example.com",
            "999.0.0.1",
            "256.1.1.1",
            "",
        )
        for (host in legitimateHosts) {
            assertFalse(EgressSecurityPolicy.isIpLiteral(host), "Host '$host' must not be classified as IP literal")
        }

        val scope = CapabilityScope.Network(allowedHosts = listOf("123example.com", "api.example.com"))
        assertIs<EgressValidationResult.Allowed>(policy.validateDestination("https://123example.com/v1", scope = scope))
        assertIs<EgressValidationResult.Allowed>(policy.validateDestination("https://api.example.com/v1", scope = scope))
    }
}
