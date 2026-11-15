package dev.aidos.storage

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** RFC-0039: size/depth limits, unknown-field preservation, and enums with an UNKNOWN variant. */
class VersionedJsonTest {
    @Serializable data class Thing(val a: Int, val b: String = "x")
    enum class Kind { ONE, TWO, UNKNOWN }

    @Test fun `oversized input is rejected before parsing`() {
        val big = "{\"a\":\"" + "x".repeat(100) + "\"}"
        assertFailsWith<DeserializationLimitException> { VersionedJson.validateLimits(big, maxBytes = 50) }
    }

    @Test fun `deeply nested input is rejected`() {
        val deep = "[".repeat(100) + "]".repeat(100)
        assertFailsWith<DeserializationLimitException> { VersionedJson.validateLimits(deep, maxDepth = 64) }
    }

    @Test fun `brackets inside strings do not count toward depth`() {
        VersionedJson.validateLimits("{\"s\":\"" + "[".repeat(500) + "\"}")
    }

    @Test fun `unknown fields survive a decode-encode round trip`() {
        val raw = """{"a":1,"b":"y","future":{"z":[1,2]}}"""
        val env = VersionedJson.decodePreservingUnknown(raw, Thing.serializer(), setOf("a", "b"))
        assertEquals(Thing(1, "y"), env.value)
        assertTrue("future" in env.unknownFields)
        val out = VersionedJson.encodePreservingUnknown(env.copy(value = env.value.copy(a = 2)), Thing.serializer())
        val reparsed = VersionedJson.json.parseToJsonElement(out) as kotlinx.serialization.json.JsonObject
        assertEquals(JsonPrimitive(2), reparsed["a"])
        assertTrue("future" in reparsed)
    }

    @Test fun `unknown enum wire value maps to UNKNOWN not the first constant`() {
        assertEquals(Kind.TWO, VersionedJson.decodeEnumOrUnknown("TWO", Kind.UNKNOWN))
        assertEquals(Kind.UNKNOWN, VersionedJson.decodeEnumOrUnknown("THREE", Kind.UNKNOWN))
    }
}
