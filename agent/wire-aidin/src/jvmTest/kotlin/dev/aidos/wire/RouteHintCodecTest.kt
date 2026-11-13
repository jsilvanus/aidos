package dev.aidos.wire

import dev.aidos.routes.Route
import dev.aidos.routes.RouteHints
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RouteHintCodecTest {
    @Test
    fun encodeEmptyAddressesAndLanOnly() {
        val hints = RouteHints.lanOnly()
        val encoded = RouteHintCodec.encode(emptyList(), hints)
        assertEquals(listOf("lan"), encoded)
    }

    @Test
    fun encodeLanAddressesWithLanMarker() {
        val hints = RouteHints.lanOnly()
        val encoded = RouteHintCodec.encode(listOf("192.168.1.100", "192.168.1.101"), hints)
        assertEquals(listOf("192.168.1.100", "192.168.1.101", "lan"), encoded)
    }

    @Test
    fun encodeDirectRoute() {
        val hints = RouteHints(listOf(Route.Lan, Route.Direct("peer-tailscale", 8766)))
        val encoded = RouteHintCodec.encode(listOf("192.168.1.100"), hints)
        assertEquals(listOf("192.168.1.100", "lan", "direct://peer-tailscale:8766"), encoded)
    }

    @Test
    fun encodeSshRoute() {
        val hints = RouteHints(listOf(Route.Lan, Route.SshReverse("home", "home.example.com", 47100)))
        val encoded = RouteHintCodec.encode(emptyList(), hints)
        assertEquals(listOf("lan", "ssh://home:home.example.com:47100"), encoded)
    }

    @Test
    fun encodeMultipleRoutes() {
        val hints = RouteHints(
            listOf(
                Route.Lan,
                Route.Direct("tailscale", 8766),
                Route.Direct("router-port", 9000),
                Route.SshReverse("home", "example.com", 47100),
            ),
        )
        val encoded = RouteHintCodec.encode(listOf("192.168.1.1"), hints)
        assertEquals(
            listOf(
                "192.168.1.1",
                "lan",
                "direct://tailscale:8766",
                "direct://router-port:9000",
                "ssh://home:example.com:47100",
            ),
            encoded,
        )
    }

    @Test
    fun decodeEmptyAddresses() {
        val (lan, hints) = RouteHintCodec.decode(emptyList()) ?: run {
            assertTrue(false, "decode failed")
            return
        }
        assertEquals(emptyList(), lan)
        assertEquals(listOf(Route.Lan), hints.hints)
    }

    @Test
    fun decodeLanOnly() {
        val (lan, hints) = RouteHintCodec.decode(listOf("lan")) ?: run {
            assertTrue(false, "decode failed")
            return
        }
        assertEquals(emptyList(), lan)
        assertEquals(listOf(Route.Lan), hints.hints)
    }

    @Test
    fun decodeLanAddresses() {
        val (lan, hints) = RouteHintCodec.decode(listOf("192.168.1.100", "192.168.1.101", "lan")) ?: run {
            assertTrue(false, "decode failed")
            return
        }
        assertEquals(listOf("192.168.1.100", "192.168.1.101"), lan)
        assertEquals(listOf(Route.Lan), hints.hints)
    }

    @Test
    fun decodeWithDirectRoute() {
        val (lan, hints) =
            RouteHintCodec.decode(listOf("192.168.1.1", "lan", "direct://peer-tailscale:8766")) ?: run {
                assertTrue(false, "decode failed")
                return
            }
        assertEquals(listOf("192.168.1.1"), lan)
        assertEquals(2, hints.hints.size)
        assertEquals(Route.Lan, hints.hints[0])
        val direct = hints.hints[1] as Route.Direct
        assertEquals("peer-tailscale", direct.host)
        assertEquals(8766, direct.port)
    }

    @Test
    fun decodeWithSshRoute() {
        val (lan, hints) =
            RouteHintCodec.decode(listOf("lan", "ssh://home:example.com:47100")) ?: run {
                assertTrue(false, "decode failed")
                return
            }
        assertEquals(emptyList(), lan)
        assertEquals(2, hints.hints.size)
        assertEquals(Route.Lan, hints.hints[0])
        val ssh = hints.hints[1] as Route.SshReverse
        assertEquals("home", ssh.tunnelId)
        assertEquals("example.com", ssh.serverHost)
        assertEquals(47100, ssh.serverPort)
    }

    @Test
    fun decodeMultipleRoutes() {
        val addresses = listOf(
            "192.168.1.1",
            "lan",
            "direct://tailscale:8766",
            "direct://router:9000",
            "ssh://home:example.com:47100",
        )
        val (lan, hints) = RouteHintCodec.decode(addresses) ?: run {
            assertTrue(false, "decode failed")
            return
        }
        assertEquals(listOf("192.168.1.1"), lan)
        assertEquals(4, hints.hints.size)
        assertEquals(Route.Lan, hints.hints[0])
        assertTrue(hints.hints[1] is Route.Direct)
        assertTrue(hints.hints[2] is Route.Direct)
        assertTrue(hints.hints[3] is Route.SshReverse)
    }

    @Test
    fun decodeSkipsUnknownRouteTypes() {
        val addresses = listOf(
            "lan",
            "direct://peer:8766",
            "unknown://something:123",  // Skipped, forward compatibility
            "ssh://home:example.com:47100",
        )
        val (lan, hints) = RouteHintCodec.decode(addresses) ?: run {
            assertTrue(false, "decode failed")
            return
        }
        // Unknown route type is skipped, not an error
        assertEquals(3, hints.hints.size)
        assertEquals(Route.Lan, hints.hints[0])
        assertTrue(hints.hints[1] is Route.Direct)
        assertTrue(hints.hints[2] is Route.SshReverse)
    }

    @Test
    fun decodeMalformedDirectRoute() {
        // Missing port
        val result1 = RouteHintCodec.decode(listOf("lan", "direct://peer"))
        assertNull(result1)

        // Invalid port
        val result2 = RouteHintCodec.decode(listOf("lan", "direct://peer:abc"))
        assertNull(result2)

        // Port out of range
        val result3 = RouteHintCodec.decode(listOf("lan", "direct://peer:99999"))
        assertNull(result3)
    }

    @Test
    fun decodeMalformedSshRoute() {
        // Wrong number of parts
        val result1 = RouteHintCodec.decode(listOf("lan", "ssh://home:example.com"))
        assertNull(result1)

        // Invalid port
        val result2 = RouteHintCodec.decode(listOf("lan", "ssh://home:example.com:abc"))
        assertNull(result2)
    }

    @Test
    fun roundTrip() {
        val original = RouteHints(
            listOf(
                Route.Lan,
                Route.Direct("tailscale", 8766),
                Route.SshReverse("home", "example.com", 47100),
            ),
        )
        val lanAddresses = listOf("192.168.1.100")

        val encoded = RouteHintCodec.encode(lanAddresses, original)
        val (decodedLan, decoded) = RouteHintCodec.decode(encoded) ?: run {
            assertTrue(false, "decode failed")
            return
        }

        assertEquals(lanAddresses, decodedLan)
        assertEquals(original.hints, decoded.hints)
    }

    @Test
    fun parseDirectRouteSuccess() {
        val route = (RouteHintCodec.parseDirectRoute("direct://example.com:8766") as Route.Direct)
        assertEquals("example.com", route.host)
        assertEquals(8766, route.port)
    }

    @Test
    fun parseDirectRouteFails() {
        assertNull(RouteHintCodec.parseDirectRoute("not-direct"))
        assertNull(RouteHintCodec.parseDirectRoute("direct://example.com"))  // Missing port
        assertNull(RouteHintCodec.parseDirectRoute("direct://example.com:abc"))  // Invalid port
    }

    @Test
    fun parseSshRouteSuccess() {
        val route = (RouteHintCodec.parseSshRoute("ssh://home:example.com:47100") as Route.SshReverse)
        assertEquals("home", route.tunnelId)
        assertEquals("example.com", route.serverHost)
        assertEquals(47100, route.serverPort)
    }

    @Test
    fun parseSshRouteFails() {
        assertNull(RouteHintCodec.parseSshRoute("not-ssh"))
        assertNull(RouteHintCodec.parseSshRoute("ssh://home:example.com"))  // Missing port
        assertNull(RouteHintCodec.parseSshRoute("ssh://home"))  // Too few parts
    }
}
