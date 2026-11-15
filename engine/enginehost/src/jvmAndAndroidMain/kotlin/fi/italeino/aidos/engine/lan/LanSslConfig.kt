package fi.italeino.aidos.engine.lan

import java.security.KeyStore
import java.security.MessageDigest

/**
 * The Engine's persistent TLS identity for the LAN listener (RFC-0051). TLS itself is the
 * platform/Ktor stack; Aidos only supplies the key store. [fingerprintSha256] is what a paired
 * device pins, so a different certificate on the same address is detectable.
 */
class LanSslConfig(
    val keyStore: KeyStore,
    val alias: String,
    val keyStorePassword: String,
    val privateKeyPassword: String,
) {
    /** Lowercase hex SHA-256 of the DER-encoded leaf certificate. */
    val fingerprintSha256: String
        get() = MessageDigest.getInstance("SHA-256")
            .digest(keyStore.getCertificate(alias).encoded)
            .joinToString("") { "%02x".format(it) }
}
