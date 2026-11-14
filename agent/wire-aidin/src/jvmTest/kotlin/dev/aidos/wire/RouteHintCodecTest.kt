package dev.aidos.wire

import dev.aidos.routes.Route
import dev.aidos.routes.RouteHints
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
        assertEquals(emptyList(), lan)
        // Unknown route type is skipped, not an error
        assertEquals(3, hints.hints.size)
        assertEquals(Route.Lan, hints.hints[0])
        assertTrue(hints.hints[1] is Route.Direct)
        assertTrue(hints.hints[2] is Route.SshReverse)
    }

    @Test
    fun decodeMalformedDirectRoute() {
        assertNull(RouteHintCodec.decode(listOf("lan", "direct://peer")))
        assertNull(RouteHintCodec.decode(listOf("lan", "direct://peer:abc")))
        assertNull(RouteHintCodec.decode(listOf("lan", "direct://peer:99999")))
    }

    @Test
    fun decodeRejectsOutOfRangePorts() {
        val badPorts = listOf("0", "-1", "65536", "+8766", "08766")
        for (badPort in badPorts) {
            val directFailure = RouteHintCodec.decodeStrict(listOf("lan", "direct://peer:$badPort"))
            assertTrue(directFailure.isFailure, "direct port '$badPort' should fail")
            assertNull(RouteHintCodec.parseDirectRoute("direct://peer:$badPort"))

            val sshFailure = RouteHintCodec.decodeStrict(listOf("lan", "ssh://home:example.com:$badPort"))
            assertTrue(sshFailure.isFailure, "ssh port '$badPort' should fail")
            assertNull(RouteHintCodec.parseSshRoute("ssh://home:example.com:$badPort"))
        }

        // Valid port boundary values 1 and 65535
        assertEquals(1, RouteHintCodec.parseDirectRoute("direct://peer:1")?.port)
        assertEquals(65535, RouteHintCodec.parseDirectRoute("direct://peer:65535")?.port)
        assertEquals(1, RouteHintCodec.parseSshRoute("ssh://home:example.com:1")?.serverPort)
        assertEquals(65535, RouteHintCodec.parseSshRoute("ssh://home:example.com:65535")?.serverPort)
    }

    @Test
    fun decodeRejectsBlankAndMalformedHostsAndTunnelIds() {
        val malformed = listOf(
            listOf("lan", "direct://:8766"),
            listOf("lan", "direct://   :8766"),
            listOf("lan", "direct://bad host:8766"),
            listOf("lan", "direct://bad\nhost:8766"),
            listOf("lan", "direct://bad\u0000host:8766"),
            listOf("lan", "ssh://:example.com:47100"),
            listOf("lan", "ssh://   :example.com:47100"),
            listOf("lan", "ssh://home::47100"),
            listOf("lan", "ssh://home:   :47100"),
            listOf("lan", "ssh://home:bad host:47100"),
            listOf("lan", "ssh://bad id:example.com:47100"),
            listOf("", "lan"),
            listOf("   ", "lan"),
            listOf("192.168.1.1\n", "lan"),
            listOf("lan", "lan"),
            listOf("lan", "direct:peer:8766"),
        )
        for (payload in malformed) {
            val result = RouteHintCodec.decodeStrict(payload)
            assertTrue(result.isFailure, "Expected failure for $payload")
            assertNull(RouteHintCodec.decode(payload))
        }
    }

    @Test
    fun decodeRejectsOversizedPayloadsAndHostLengths() {
        // Too many hints (> MAX_HINTS)
        val tooMany = List(RouteHintCodec.MAX_HINTS + 1) { "192.168.1.1" }
        assertNull(RouteHintCodec.decode(tooMany))
        assertTrue(RouteHintCodec.decodeStrict(tooMany).isFailure)

        // Single entry exceeding MAX_ENTRY_LENGTH
        val oversizedEntry = listOf("lan", "direct://" + "a".repeat(RouteHintCodec.MAX_ENTRY_LENGTH) + ":8766")
        assertNull(RouteHintCodec.decode(oversizedEntry))
        assertTrue(RouteHintCodec.decodeStrict(oversizedEntry).isFailure)

        // Host exceeding MAX_HOST_LENGTH even within MAX_ENTRY_LENGTH
        val oversizedHost = "direct://" + "a".repeat(RouteHintCodec.MAX_HOST_LENGTH + 1) + ":8766"
        assertNull(RouteHintCodec.parseDirectRoute(oversizedHost))
        assertNull(RouteHintCodec.decode(listOf("lan", oversizedHost)))

        // Total frame bytes exceeding MAX_FRAME_BYTES while under MAX_HINTS and MAX_ENTRY_LENGTH
        val longHost = "a".repeat(250)
        val oversizedFrame = listOf("lan") + List(RouteHintCodec.MAX_HINTS - 1) { "direct://$longHost:8766" }
        assertTrue(oversizedFrame.size <= RouteHintCodec.MAX_HINTS)
        assertNull(RouteHintCodec.decode(oversizedFrame))
        assertTrue(RouteHintCodec.decodeStrict(oversizedFrame).isFailure)
    }

    @Test
    fun encodeRejectsInvalidLanAddressesAndOversizedFrames() {
        assertFailsWith<IllegalArgumentException> {
            RouteHintCodec.encode(listOf(""), RouteHints.lanOnly())
        }
        assertFailsWith<IllegalArgumentException> {
            RouteHintCodec.encode(listOf("192.168.1.1\n"), RouteHints.lanOnly())
        }
        assertFailsWith<IllegalArgumentException> {
            RouteHintCodec.encode(List(RouteHintCodec.MAX_HINTS + 1) { "192.168.1.1" }, RouteHints.lanOnly())
        }
    }

    @Test
    fun decodeMalformedSshRoute() {
        assertNull(RouteHintCodec.decode(listOf("lan", "ssh://home:example.com")))
        assertNull(RouteHintCodec.decode(listOf("lan", "ssh://home:example.com:abc")))
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
        assertNull(RouteHintCodec.parseDirectRoute("direct://example.com"))
        assertNull(RouteHintCodec.parseDirectRoute("direct://example.com:abc"))
        assertNull(RouteHintCodec.parseDirectRoute("direct://:8766"))
        assertNull(RouteHintCodec.parseDirectRoute("direct://example.com:0"))
        assertNull(RouteHintCodec.parseDirectRoute("direct://example.com:65536"))
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
        assertNull(RouteHintCodec.parseSshRoute("ssh://home:example.com"))
        assertNull(RouteHintCodec.parseSshRoute("ssh://home"))
        assertNull(RouteHintCodec.parseSshRoute("ssh://:example.com:47100"))
        assertNull(RouteHintCodec.parseSshRoute("ssh://home::47100"))
        assertNull(RouteHintCodec.parseSshRoute("ssh://home:example.com:0"))
        assertNull(RouteHintCodec.parseSshRoute("ssh://home:example.com:65536"))
    }
}
