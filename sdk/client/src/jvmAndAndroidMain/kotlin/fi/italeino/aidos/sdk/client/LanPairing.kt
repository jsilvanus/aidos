package fi.italeino.aidos.sdk.client

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * What an Engine shows to pair a device over the LAN (RFC-0051 mode A): its address, a single-use
 * code, and the SHA-256 fingerprint of its TLS certificate. Carried as
 * `aidos-pair://host:port/code?fp=<hex>`, which is what a QR code holds.
 *
 * The fingerprint is part of the payload on purpose: the code is only ever sent over a connection
 * that has already proven it ends at the certificate the user is looking at, so a device in the
 * middle cannot collect it.
 */
data class LanPairingUri(val host: String, val port: Int, val code: String, val fingerprintSha256: String) {
    override fun toString(): String = "aidos-pair://$host:$port/$code?fp=$fingerprintSha256"

    companion object {
        private val FORMAT = Regex("^aidos-pair://([^/:?#\\s]+):(\\d{1,5})/(\\d{8})\\?fp=([0-9a-fA-F]{64})$")

        /** Null when [text] is not a pairing URI. */
        fun parse(text: String): LanPairingUri? {
            val m = FORMAT.matchEntire(text.trim()) ?: return null
            val port = m.groupValues[2].toInt()
            if (port !in 1..65535) return null
            return LanPairingUri(m.groupValues[1], port, m.groupValues[3], m.groupValues[4].lowercase())
        }
    }
}

/** A paired device's way into one Engine: where it is, which certificate to trust, and its own credential. */
data class LanCredential(
    val baseUrl: String,
    val fingerprintSha256: String,
    val credential: String,
    /** ISO-8601 instant after which the Engine stops accepting [credential]. */
    val expiresAt: String?,
)

class LanPairingException(message: String, val httpStatus: Int? = null) : Exception(message)

/**
 * Client side of RFC-0051 LAN sharing. Nothing here needs the Engine's Android app or any Aidos
 * type: it is HTTPS with a pinned certificate and a bearer credential, so a generic client can do
 * the same by hand from the manual descriptor.
 */
object LanPairing {
    /**
     * An HTTP client that trusts exactly one certificate, identified by its SHA-256 fingerprint.
     * Hostname verification is replaced by the pin, which is stronger for a self-signed LAN identity.
     */
    fun pinnedClient(fingerprintSha256: String, timeoutSeconds: Long = 30): OkHttpClient {
        val expected = fingerprintSha256.lowercase()
        val trust = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                val leaf = chain.firstOrNull() ?: throw CertificateException("no certificate presented")
                val fp = MessageDigest.getInstance("SHA-256").digest(leaf.encoded).joinToString("") { "%02x".format(it) }
                if (!MessageDigest.isEqual(fp.toByteArray(), expected.toByteArray())) {
                    throw CertificateException("Engine certificate does not match the pinned fingerprint")
                }
            }
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val ctx = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), SecureRandom()) }
        return OkHttpClient.Builder()
            .sslSocketFactory(ctx.socketFactory, trust)
            .hostnameVerifier { _, _ -> true }
            .connectTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .readTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .build()
    }

    /** Exchanges the single-use code for this device's own credential. Blocking; call it off the main thread. */
    fun redeem(uri: LanPairingUri, client: OkHttpClient = pinnedClient(uri.fingerprintSha256)): LanCredential {
        val baseUrl = "https://${uri.host}:${uri.port}"
        val request = Request.Builder()
            .url("$baseUrl/lan/v1/pair")
            .post("""{"code":"${uri.code}"}""".toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw LanPairingException("pairing was refused", response.code)
            val credential = Regex("\"credential\"\\s*:\\s*\"([^\"]+)\"").find(body)?.groupValues?.get(1)
                ?: throw LanPairingException("the Engine returned no credential", response.code)
            val expiresAt = Regex("\"expiresAt\"\\s*:\\s*\"([^\"]+)\"").find(body)?.groupValues?.get(1)
            return LanCredential(baseUrl, uri.fingerprintSha256, credential, expiresAt)
        }
    }

    /** A request to [path] on the paired Engine, already carrying the bearer credential. */
    fun request(credential: LanCredential, path: String): Request.Builder =
        Request.Builder().url(credential.baseUrl + path).header("Authorization", "Bearer ${credential.credential}")
}
