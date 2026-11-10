package dev.aidos.portability

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Password encryption and Ed25519 signing for archives (RFC-0041 Encryption, Signing).
 *
 * The RFC says algorithms are configurable and names Argon2id + ChaCha20-Poly1305. This build uses
 * what the JDK ships, so no native or third-party crypto enters the dependency graph:
 * PBKDF2-HMAC-SHA256 and AES-256-GCM. The header records both names, so a later build can add
 * Argon2id/ChaCha20 without breaking existing archives.
 *
 * File layout: `AIDOSENC` + version byte, int header length, JSON header, then chunks of
 * `int length + AES-GCM(ciphertext+tag)`. Each chunk's nonce is `prefix || counter`, and its
 * AAD is the header plus a final-chunk flag, so reordering, dropping or truncating chunks fails
 * authentication.
 */
object ArchiveCrypto {
    private val MAGIC = "AIDOSENC".toByteArray(Charsets.US_ASCII)
    private const val VERSION: Int = 1
    const val DEFAULT_ITERATIONS = 600_000
    private const val CHUNK = 64 * 1024
    private const val MAX_ITERATIONS = 20_000_000
    private val rng = SecureRandom()

    @Serializable
    private data class Header(
        val cipher: String = "aes-256-gcm",
        val kdf: String = "pbkdf2-hmac-sha256",
        val iterations: Int,
        val salt: String,
        @SerialName("nonce_prefix") val noncePrefix: String,
        @SerialName("chunk_size") val chunkSize: Int = CHUNK,
    )

    fun isEncrypted(head: ByteArray): Boolean =
        head.size >= MAGIC.size && head.copyOf(MAGIC.size).contentEquals(MAGIC)

    private fun key(password: CharArray, salt: ByteArray, iterations: Int): SecretKeySpec {
        val spec = PBEKeySpec(password, salt, iterations, 256)
        try {
            val raw = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            return SecretKeySpec(raw, "AES")
        } finally {
            spec.clearPassword()
        }
    }

    private fun nonce(prefix: ByteArray, counter: Long): ByteArray =
        ByteBuffer.allocate(12).put(prefix).putLong(counter).array()

    private fun aad(header: ByteArray, final: Boolean): ByteArray = header + byteArrayOf(if (final) 1 else 0)

    fun encrypt(input: InputStream, output: OutputStream, password: CharArray, iterations: Int = DEFAULT_ITERATIONS) {
        val salt = ByteArray(16).also(rng::nextBytes)
        val prefix = ByteArray(4).also(rng::nextBytes)
        val b64 = Base64.getEncoder()
        val header = ArchiveFormat.json.encodeToString(
            Header(iterations = iterations, salt = b64.encodeToString(salt), noncePrefix = b64.encodeToString(prefix)),
        ).toByteArray(Charsets.UTF_8)
        val key = key(password, salt, iterations)

        val out = DataOutputStream(output)
        out.write(MAGIC); out.writeByte(VERSION); out.writeInt(header.size); out.write(header)

        var current = readChunk(input)
        var counter = 0L
        while (true) {
            val next = if (current.size == CHUNK) readChunk(input) else ByteArray(0)
            val final = next.isEmpty()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, nonce(prefix, counter)))
            cipher.updateAAD(aad(header, final))
            val sealed = cipher.doFinal(current)
            out.writeInt(sealed.size); out.write(sealed)
            if (final) break
            current = next
            counter++
        }
        out.flush()
    }

    /** Throws [PortabilityException] `import.decrypt_failed` for a wrong password or any tampering. */
    fun decrypt(input: InputStream, output: OutputStream, password: CharArray) {
        val din = DataInputStream(input)
        val magic = ByteArray(MAGIC.size)
        try {
            din.readFully(magic)
        } catch (_: EOFException) {
            throw bad("not an encrypted Aidos archive")
        }
        if (!magic.contentEquals(MAGIC)) throw bad("not an encrypted Aidos archive")
        if (din.readUnsignedByte() != VERSION) throw bad("unsupported encryption version")
        val headerLen = din.readInt()
        if (headerLen !in 1..4096) throw bad("corrupt encryption header")
        val headerBytes = ByteArray(headerLen).also(din::readFully)
        val header = try {
            ArchiveFormat.json.decodeFromString<Header>(headerBytes.toString(Charsets.UTF_8))
        } catch (e: Exception) {
            throw bad("corrupt encryption header")
        }
        if (header.cipher != "aes-256-gcm" || header.kdf != "pbkdf2-hmac-sha256") {
            throw PortabilityException("import.unsupported_cipher", "unsupported cipher/kdf ${header.cipher}/${header.kdf}")
        }
        if (header.iterations !in 1..MAX_ITERATIONS || header.chunkSize != CHUNK) throw bad("corrupt encryption header")
        val dec = Base64.getDecoder()
        val key = key(password, dec.decode(header.salt), header.iterations)
        val prefix = dec.decode(header.noncePrefix)

        var counter = 0L
        try {
            var len = din.readInt()
            while (true) {
                if (len < 16 || len > CHUNK + 16) throw bad("corrupt chunk length")
                val sealed = ByteArray(len).also(din::readFully)
                val nextLen = try { din.readInt() } catch (_: EOFException) { -1 }
                val final = nextLen == -1
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, nonce(prefix, counter)))
                cipher.updateAAD(aad(headerBytes, final))
                output.write(cipher.doFinal(sealed))
                if (final) break
                len = nextLen
                counter++
            }
        } catch (e: GeneralSecurityException) {
            throw PortabilityException("import.decrypt_failed", "wrong password, or the archive was modified")
        } catch (e: EOFException) {
            throw PortabilityException("import.decrypt_failed", "the archive is truncated")
        }
        output.flush()
    }

    private fun bad(msg: String) = PortabilityException("import.decrypt_failed", msg)

    private fun readChunk(input: InputStream): ByteArray {
        val buf = ByteArray(CHUNK)
        var n = 0
        while (n < CHUNK) {
            val r = input.read(buf, n, CHUNK - n)
            if (r < 0) break
            n += r
        }
        return if (n == CHUNK) buf else buf.copyOf(n)
    }

    // ── Signing ──────────────────────────────────────────────────────────────

    fun generateSigningKeyPair(): KeyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()

    fun sign(privateKey: PrivateKey, data: ByteArray): ByteArray =
        Signature.getInstance("Ed25519").run { initSign(privateKey); update(data); sign() }

    fun verify(publicKey: PublicKey, data: ByteArray, signature: ByteArray): Boolean =
        try {
            Signature.getInstance("Ed25519").run { initVerify(publicKey); update(data); verify(signature) }
        } catch (_: GeneralSecurityException) {
            false
        }

    /** SHA-256 of the SubjectPublicKeyInfo: what a user compares to confirm who signed. */
    fun fingerprint(publicKey: PublicKey): String = ArchiveFormat.sha256Hex(publicKey.encoded)

    fun encodePublic(k: PublicKey): String = Base64.getEncoder().encodeToString(k.encoded)
    fun encodePrivate(k: PrivateKey): String = Base64.getEncoder().encodeToString(k.encoded)

    fun decodePublic(b64: String): PublicKey =
        KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(b64)))

    fun decodePrivate(b64: String): PrivateKey =
        KeyFactory.getInstance("Ed25519").generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(b64)))
}
