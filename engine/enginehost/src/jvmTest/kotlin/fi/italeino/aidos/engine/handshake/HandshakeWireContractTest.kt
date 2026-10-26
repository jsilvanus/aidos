package fi.italeino.aidos.engine.handshake

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins the handshake Bundle vocabulary for Aidos Engine API v1. The identical list is pinned in
 * sdk/client (`HandshakeWireContractTest`); engine/ and sdk/ are separate Gradle projects, so a
 * shared constant is impossible and two literal copies are the tripwire. Changing any of these is
 * a breaking change: bump the API version (sdk/CONTRACT.md) instead.
 */
class HandshakeWireContractTest {
    @Test
    fun bundleKeysAreFrozenForApiV1() {
        assertEquals(
            listOf(
                "status", "port", "token", "apiVersion", "capabilitiesJson", "deepLinkPendingIntent",
                "engineVersion", "minApiVersion"
            ),
            listOf(
                HandshakeWire.KEY_STATUS, HandshakeWire.KEY_PORT, HandshakeWire.KEY_TOKEN,
                HandshakeWire.KEY_API_VERSION, HandshakeWire.KEY_CAPABILITIES_JSON, HandshakeWire.KEY_DEEP_LINK,
                HandshakeWire.KEY_ENGINE_VERSION, HandshakeWire.KEY_MIN_API_VERSION
            )
        )
    }

    @Test
    fun statusValuesAreFrozenForApiV1() {
        assertEquals(
            listOf("APPROVED", "PENDING_APPROVAL", "DENIED"),
            listOf(HandshakeWire.STATUS_APPROVED, HandshakeWire.STATUS_PENDING_APPROVAL, HandshakeWire.STATUS_DENIED)
        )
        assertEquals(1, HandshakeWire.API_VERSION)
        assertEquals(1, HandshakeWire.MIN_API_VERSION)
    }

    @Test
    fun versionHeadersAreFrozenForApiV1() {
        assertEquals("X-Aidos-Sdk-Version", HandshakeWire.HEADER_SDK_VERSION)
        assertEquals("X-Aidos-Api-Version", HandshakeWire.HEADER_API_VERSION)
    }
}
