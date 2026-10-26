package dev.aidos.aidin

import java.net.InetAddress
import javax.jmdns.JmDNS
import javax.jmdns.ServiceEvent
import javax.jmdns.ServiceInfo
import javax.jmdns.ServiceListener

/**
 * Desktop DNS-SD for [DiscoveryAdvert]s, on one network interface. The service instance name is
 * the node id, which is already public in the advert and carries no personal name.
 */
class JmdnsDiscovery(address: InetAddress? = null) : Discovery {
    private val jmdns: JmDNS = if (address != null) JmDNS.create(address) else JmDNS.create()
    private val type = "${DiscoveryAdvert.SERVICE_TYPE}.local."
    private val names = HashMap<String, NodeId>()

    override fun announce(advert: DiscoveryAdvert) {
        jmdns.unregisterAllServices()
        jmdns.registerService(ServiceInfo.create(type, advert.nodeId.value, advert.port, 0, 0, advert.toTxt()))
    }

    override fun browse(onFound: (DiscoveredNode) -> Unit, onLost: (NodeId) -> Unit) {
        jmdns.addServiceListener(type, object : ServiceListener {
            override fun serviceAdded(event: ServiceEvent) {
                jmdns.requestServiceInfo(event.type, event.name, true)
            }

            override fun serviceRemoved(event: ServiceEvent) {
                synchronized(names) { names.remove(event.name) }?.let(onLost)
            }

            override fun serviceResolved(event: ServiceEvent) {
                val info = event.info ?: return
                val txt = info.propertyNames.toList().associateWith { info.getPropertyString(it).orEmpty() }
                val advert = DiscoveryAdvert.fromTxt(txt, info.port) ?: return
                val host = info.hostAddresses.firstOrNull() ?: return
                synchronized(names) { names[event.name] = advert.nodeId }
                onFound(DiscoveredNode(advert, host))
            }
        })
    }

    override fun close() {
        jmdns.unregisterAllServices()
        jmdns.close()
    }
}
