package fi.italeino.aidos.engine.lan

import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList
import javax.jmdns.JmDNS
import javax.jmdns.ServiceEvent
import javax.jmdns.ServiceInfo
import javax.jmdns.ServiceListener

/** An Engine found on the LAN that offers HTTPS sharing (RFC-0051). Finding it trusts nothing: pairing and the pin do that. */
data class LanEngine(val name: String, val host: String, val port: Int, val fingerprintSha256: String?)

/**
 * RFC-0051 "Service discovery": while LAN sharing is on, the Engine announces `_aidos-engine._tcp`
 * with its port and the fingerprint of its TLS certificate, so a client can find it without typing an address.
 * The announcement is a convenience, never an authority; an unpaired device learns nothing it can use.
 */
object LanDiscovery {
    const val SERVICE_TYPE = "_aidos-engine._tcp.local."

    /** Announces the Engine until [Announcement.close]. [address] picks the interface (default: JmDNS's choice). */
    fun announce(name: String, port: Int, fingerprintSha256: String, address: InetAddress? = null): Announcement {
        val jmdns = if (address != null) JmDNS.create(address) else JmDNS.create()
        jmdns.registerService(
            ServiceInfo.create(SERVICE_TYPE, name, port, 0, 0, mapOf("fp" to fingerprintSha256, "v" to "1")),
        )
        return Announcement(jmdns)
    }

    class Announcement internal constructor(private val jmdns: JmDNS) : AutoCloseable {
        override fun close() {
            runCatching { jmdns.unregisterAllServices() }
            runCatching { jmdns.close() }
        }
    }

    /** Looks for Engines for [timeoutMillis] and returns what answered. Blocking. */
    fun find(timeoutMillis: Long = 3_000, address: InetAddress? = null): List<LanEngine> {
        val jmdns = if (address != null) JmDNS.create(address) else JmDNS.create()
        val found = CopyOnWriteArrayList<LanEngine>()
        val listener = object : ServiceListener {
            override fun serviceAdded(event: ServiceEvent) {
                jmdns.requestServiceInfo(event.type, event.name, true)
            }

            override fun serviceRemoved(event: ServiceEvent) = Unit

            override fun serviceResolved(event: ServiceEvent) {
                // The first resolution can arrive before the TXT record; ask again with a short wait for the full record.
                val first = event.info ?: return
                val info = if (first.getPropertyString("fp") == null) jmdns.getServiceInfo(event.type, event.name, 2_000) ?: first else first
                val host = info.hostAddresses.firstOrNull() ?: return
                found.removeAll { it.name == info.name }
                found += LanEngine(info.name, host, info.port, info.getPropertyString("fp"))
            }
        }
        return try {
            jmdns.addServiceListener(SERVICE_TYPE, listener)
            val deadline = System.currentTimeMillis() + timeoutMillis
            while (System.currentTimeMillis() < deadline) Thread.sleep(50)
            found.distinctBy { it.name to it.host to it.port }
        } finally {
            runCatching { jmdns.removeServiceListener(SERVICE_TYPE, listener) }
            runCatching { jmdns.close() }
        }
    }
}
