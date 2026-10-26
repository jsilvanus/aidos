package dev.aidos.aidin

import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec

/**
 * An ECDSA P-256 key pair: a node's key (RFC-0105 "Node identity") or a group's signing key.
 *
 * P-256 rather than Ed25519 because JSSE on Android only handles Ed25519 reliably from API 33, and
 * the same key later authenticates the mTLS peer link. Public keys travel as X.509
 * SubjectPublicKeyInfo DER, the encoding every JCA provider reads.
 *
 * This key is the Engine's model-system identity. It must never sign agent or tool assertions
 * (IDENTITY-AND-MODEL-ATTESTATION-SUGGESTION.md §3): a node trusted to compute gains no authority
 * over tools.
 */
class SigningKey private constructor(private val keyPair: KeyPair) {
    val publicKey: ByteArray get() = keyPair.public.encoded

    /** PKCS#8 DER of the private key, for a [NodeKeyStore] to persist. */
    fun privateKeyEncoded(): ByteArray = keyPair.private.encoded

    fun sign(data: ByteArray): ByteArray =
        Signature.getInstance(SIGNATURE_ALGORITHM).run {
            initSign(keyPair.private)
            update(data)
            sign()
        }

    companion object {
        internal const val SIGNATURE_ALGORITHM = "SHA256withECDSA"

        fun generate(): SigningKey =
            SigningKey(KeyPairGenerator.getInstance("EC").run {
                initialize(ECGenParameterSpec("secp256r1"))
                generateKeyPair()
            })

        fun fromEncoded(privateKeyPkcs8: ByteArray, publicKeyX509: ByteArray): SigningKey {
            val factory = KeyFactory.getInstance("EC")
            val private: PrivateKey = factory.generatePrivate(PKCS8EncodedKeySpec(privateKeyPkcs8))
            val public: PublicKey = factory.generatePublic(X509EncodedKeySpec(publicKeyX509))
            val key = SigningKey(KeyPair(public, private))
            // A stored pair whose halves do not belong together would sign things nobody can verify.
            require(Keys.verify(key.publicKey, PROBE, key.sign(PROBE))) { "private and public key do not match" }
            return key
        }

        private val PROBE = "aidin-key-probe".encodeToByteArray()
    }
}

/** Hashing, id derivation and signature checks over encoded public keys. */
object Keys {
    // Domain-separated so a node key and a group key never map to the same id.
    private const val NODE_TAG = "aidin-node-id-v1"
    private const val GROUP_TAG = "aidin-group-id-v1"

    fun sha256(vararg parts: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").run {
            parts.forEach { update(it) }
            digest()
        }

    fun nodeIdOf(publicKey: ByteArray): NodeId = NodeId(idOf(NODE_TAG, publicKey))

    fun groupIdOf(groupPublicKey: ByteArray): GroupId = GroupId(idOf(GROUP_TAG, groupPublicKey))

    /** False for a malformed key or signature, never an exception: callers treat both as "not trusted". */
    fun verify(publicKey: ByteArray, data: ByteArray, signature: ByteArray): Boolean =
        try {
            val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(publicKey))
            Signature.getInstance(SigningKey.SIGNATURE_ALGORITHM).run {
                initVerify(key)
                update(data)
                verify(signature)
            }
        } catch (e: Exception) {
            false
        }

    private fun idOf(tag: String, publicKey: ByteArray): String =
        Base32.encode(sha256(tag.encodeToByteArray(), publicKey)).take(NodeId.LENGTH)
}

val SigningKey.nodeId: NodeId get() = Keys.nodeIdOf(publicKey)
