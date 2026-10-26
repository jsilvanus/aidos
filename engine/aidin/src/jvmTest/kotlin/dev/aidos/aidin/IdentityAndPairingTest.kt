package dev.aidos.aidin

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IdentityAndPairingTest {
    @Test
    fun nodeIdIsStableBase32AndDiffersFromGroupIdOfSameKey() {
        val key = SigningKey.generate()
        val id = key.nodeId
        assertEquals(NodeId.LENGTH, id.value.length)
        assertEquals(id, Keys.nodeIdOf(key.publicKey.copyOf()))
        assertNotEquals(id.value, Keys.groupIdOf(key.publicKey).value)
        assertNotEquals(id, SigningKey.generate().nodeId)
    }

    @Test
    fun base32MatchesRfc4648Vectors() {
        assertEquals("", Base32.encode(byteArrayOf()))
        assertEquals("my", Base32.encode("f".encodeToByteArray()))
        assertEquals("mzxw6ytboi", Base32.encode("foobar".encodeToByteArray()))
    }

    @Test
    fun signaturesVerifyOnlyForTheSignedBytesAndKey() {
        val key = SigningKey.generate()
        val data = "hello".encodeToByteArray()
        val sig = key.sign(data)
        assertTrue(Keys.verify(key.publicKey, data, sig))
        assertFalse(Keys.verify(key.publicKey, "hellO".encodeToByteArray(), sig))
        assertFalse(Keys.verify(SigningKey.generate().publicKey, data, sig))
        assertFalse(Keys.verify(byteArrayOf(1, 2, 3), data, sig))
        assertFalse(Keys.verify(key.publicKey, data, byteArrayOf(0)))
    }

    @Test
    fun fileKeyStoreKeepsTheSameNodeAcrossRestarts() {
        val dir = Files.createTempDirectory("aidin-keys").toFile()
        try {
            val first = FileNodeKeyStore(dir).loadOrCreate()
            val second = FileNodeKeyStore(dir).loadOrCreate()
            assertEquals(first.nodeId, second.nodeId)
            assertTrue(Keys.verify(first.publicKey, byteArrayOf(7), second.sign(byteArrayOf(7))))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun fileKeyStoreStartsOverWhenOnlyHalfThePairExists() {
        val dir = Files.createTempDirectory("aidin-keys").toFile()
        try {
            val first = FileNodeKeyStore(dir).loadOrCreate()
            dir.resolve("aidin-node.pub").delete()
            val second = FileNodeKeyStore(dir).loadOrCreate()
            assertNotEquals(first.nodeId, second.nodeId)
            assertEquals(second.nodeId, FileNodeKeyStore(dir).loadOrCreate().nodeId)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun mismatchedStoredKeyHalvesAreRejected() {
        val a = SigningKey.generate()
        val b = SigningKey.generate()
        assertFailsWith<IllegalArgumentException> { SigningKey.fromEncoded(a.privateKeyEncoded(), b.publicKey) }
    }

    @Test
    fun inviteRoundTripsThroughItsUri() {
        val key = SigningKey.generate()
        val invite = PairingInvite.create(key, listOf("192.168.1.20", "fe80::1"), 47100)
        val parsed = PairingInvite.parse(invite.toUri())
        assertEquals(invite, parsed)
        assertEquals(key.nodeId, parsed!!.nodeId)
    }

    @Test
    fun malformedOrForeignInvitesParseToNull() {
        val good = PairingInvite.create(SigningKey.generate(), listOf("10.0.0.2"), 47100).toUri()
        assertNull(PairingInvite.parse("https://example.org"))
        assertNull(PairingInvite.parse(good.replace("v=1", "v=2")))
        assertNull(PairingInvite.parse(good.replace("p=47100", "p=notaport")))
        assertNull(PairingInvite.parse(good.replace("p=47100", "p=70000")))
        assertNull(PairingInvite.parse(good.substringBefore("&s=")))
        assertNull(PairingInvite.parse(good.replace("&s=", "&s=%%%")))
    }

    @Test
    fun sasIsSymmetricSixDigitsAndChangesWithAnyInput() {
        val a = SigningKey.generate().publicKey
        val b = SigningKey.generate().publicKey
        val secret = ByteArray(PairingInvite.SECRET_BYTES) { it.toByte() }
        val code = Sas.code(a, b, secret)
        assertEquals(6, code.length)
        assertTrue(code.all { it.isDigit() })
        assertEquals(code, Sas.code(b, a, secret))
        // A key swapped by someone in between produces a different code (with overwhelming likelihood).
        assertNotEquals(code, Sas.code(a, SigningKey.generate().publicKey, secret))
        assertNotEquals(code, Sas.code(a, b, ByteArray(PairingInvite.SECRET_BYTES)))
    }
}
