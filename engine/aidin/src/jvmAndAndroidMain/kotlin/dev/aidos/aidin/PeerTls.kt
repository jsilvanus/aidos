package dev.aidos.aidin

import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.math.BigInteger
import java.net.Socket
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.Date
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509ExtendedTrustManager

/**
 * TLS for the peer link (RFC-0105 "Peer link"): TLS 1.3, both sides present a self-signed
 * certificate carrying their node key. There is no CA. The handshake only proves that each side
 * holds the private key of the certificate it shows; *whether that key is trusted* is decided
 * afterwards by [TrustStore], from pair records and group certificates, never by the TLS layer.
 */
object PeerTls {
    const val PROTOCOL = "TLSv1.3"

    fun context(key: SigningKey, nowMillis: Long = System.currentTimeMillis()): SSLContext {
        val cert = selfSigned(key, nowMillis)
        val password = CharArray(0)
        val keyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            setKeyEntry("node", key.javaKeyPair.private, password, arrayOf(cert))
        }
        val keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).run {
            init(keyStore, password)
            keyManagers
        }
        return SSLContext.getInstance(PROTOCOL).apply {
            init(keyManagers, arrayOf(SelfSignedOnlyTrustManager), SecureRandom())
        }
    }

    /** The node key of the authenticated peer, from the completed handshake. */
    fun peerPublicKey(socket: SSLSocket): ByteArray {
        val chain = socket.session.peerCertificates
        return (chain.single() as X509Certificate).publicKey.encoded
    }

    fun selfSigned(key: SigningKey, nowMillis: Long): X509Certificate {
        val name = X500Name("CN=aidin-${key.nodeId.value}")
        val builder = JcaX509v3CertificateBuilder(
            name,
            BigInteger(64, SecureRandom()),
            // Validity is not what grants trust here; keep it wide so clock skew between phones
            // never breaks a handshake. Trust expiry lives in group certificates.
            Date(nowMillis - DAY_MILLIS),
            Date(nowMillis + 3650 * DAY_MILLIS),
            name,
            key.javaKeyPair.public,
        )
        val signer = JcaContentSignerBuilder(SigningKey.SIGNATURE_ALGORITHM).build(key.javaKeyPair.private)
        return JcaX509CertificateConverter().getCertificate(builder.build(signer))
    }

    private const val DAY_MILLIS = 24L * 60 * 60 * 1000

    /**
     * Accepts exactly one self-signed EC certificate whose signature verifies with its own key.
     * Everything else (identity, authorisation) is the [TrustStore]'s job after the handshake.
     */
    private object SelfSignedOnlyTrustManager : X509ExtendedTrustManager() {
        private fun check(chain: Array<out X509Certificate>?) {
            val cert = chain?.singleOrNull() ?: throw CertificateException("expected one self-signed certificate")
            if (cert.publicKey.algorithm != "EC") throw CertificateException("node keys are EC")
            try {
                cert.verify(cert.publicKey)
            } catch (e: Exception) {
                throw CertificateException("certificate is not self-signed by its key", e)
            }
        }

        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = check(chain)
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = check(chain)
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) = check(chain)
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) = check(chain)
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) = check(chain)
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) = check(chain)
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }
}
