package dev.aidos.aidin

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import java.util.ArrayDeque

/**
 * Android DNS-SD for [DiscoveryAdvert]s through the platform NsdManager (RFC-0105 "Discovery").
 * The service instance name is the node id, as on desktop.
 *
 * NsdManager resolves one service at a time on older Android versions, so resolves are queued.
 * A Wi-Fi multicast lock is held while browsing: some devices drop mDNS replies without it.
 */
class NsdDiscovery(context: Context) : Discovery {
    private val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val multicastLock = (context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
        .createMulticastLock("aidin-discovery").apply { setReferenceCounted(false) }

    private var registration: NsdManager.RegistrationListener? = null
    private var discovery: NsdManager.DiscoveryListener? = null
    private val names = HashMap<String, NodeId>()
    private val resolveQueue = ArrayDeque<NsdServiceInfo>()
    private var resolving = false
    private var onFound: ((DiscoveredNode) -> Unit)? = null

    @Synchronized
    override fun announce(advert: DiscoveryAdvert) {
        registration?.let { runCatching { nsd.unregisterService(it) } }
        val info = NsdServiceInfo().apply {
            serviceName = advert.nodeId.value
            serviceType = DiscoveryAdvert.SERVICE_TYPE
            port = advert.port
            advert.toTxt().forEach { (k, v) -> setAttribute(k, v) }
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) = Unit
            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) = Unit
            override fun onServiceUnregistered(info: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) = Unit
        }
        registration = listener
        nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    @Synchronized
    override fun browse(onFound: (DiscoveredNode) -> Unit, onLost: (NodeId) -> Unit) {
        this.onFound = onFound
        multicastLock.acquire()
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit

            override fun onServiceFound(info: NsdServiceInfo) = enqueue(info)

            override fun onServiceLost(info: NsdServiceInfo) {
                synchronized(this@NsdDiscovery) { names.remove(info.serviceName) }?.let(onLost)
            }
        }
        discovery = listener
        nsd.discoverServices(DiscoveryAdvert.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    @Synchronized
    private fun enqueue(info: NsdServiceInfo) {
        resolveQueue.add(info)
        if (!resolving) resolveNext()
    }

    /** Caller holds the monitor. */
    @Suppress("DEPRECATION") // resolveService: the API 34 callback form needs minSdk 34.
    private fun resolveNext() {
        val next = resolveQueue.poll() ?: run {
            resolving = false
            return
        }
        resolving = true
        nsd.resolveService(next, object : NsdManager.ResolveListener {
            override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                synchronized(this@NsdDiscovery) { resolveNext() }
            }

            override fun onServiceResolved(info: NsdServiceInfo) {
                val txt = info.attributes.mapValues { (_, v) -> v?.decodeToString().orEmpty() }
                val advert = DiscoveryAdvert.fromTxt(txt, info.port)
                val host = info.host?.hostAddress
                val found = if (advert != null && host != null) {
                    synchronized(this@NsdDiscovery) { names[info.serviceName] = advert.nodeId }
                    DiscoveredNode(advert, host)
                } else {
                    null
                }
                found?.let { onFound?.invoke(it) }
                synchronized(this@NsdDiscovery) { resolveNext() }
            }
        })
    }

    @Synchronized
    override fun close() {
        registration?.let { runCatching { nsd.unregisterService(it) } }
        discovery?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        registration = null
        discovery = null
        resolveQueue.clear()
        if (multicastLock.isHeld) multicastLock.release()
    }
}
