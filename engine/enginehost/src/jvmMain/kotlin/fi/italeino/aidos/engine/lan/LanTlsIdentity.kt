package fi.italeino.aidos.engine.lan

import io.ktor.network.tls.certificates.buildKeyStore
import io.ktor.network.tls.certificates.saveToFile
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.KeyStore

/**
 * RFC-0051: the Engine's persistent LAN TLS identity on JVM hosts.
 *
 * The certificate is generated once, stored in [file] (a PKCS12 key store, mode left to the OS
 * account's file permissions) and reused, so the fingerprint a device pinned at pairing stays
 * valid across restarts. Delete the file to rotate it; every paired device must then re-pair.
 */
object LanTlsIdentity {
    private const val ALIAS = "aidos-engine-lan"

    fun loadOrCreate(file: File, password: String): LanSslConfig {
        val keyStore = if (file.exists()) {
            KeyStore.getInstance("PKCS12").also { ks -> file.inputStream().use { ks.load(it, password.toCharArray()) } }
        } else {
            file.parentFile?.mkdirs()
            buildKeyStore {
                certificate(ALIAS) {
                    this.password = password
                    daysValid = 3650
                    domains = listOf("localhost") + lanAddresses()
                }
            }.also { it.saveToFile(file, password) }
        }
        return LanSslConfig(keyStore, ALIAS, password, password)
    }

    /** Site-local IPv4 addresses, so a client that pins by address also matches the certificate's names. */
    fun lanAddresses(): List<String> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .filter { it.isSiteLocalAddress }
            .map { it.hostAddress }
    }.getOrDefault(emptyList())
}
