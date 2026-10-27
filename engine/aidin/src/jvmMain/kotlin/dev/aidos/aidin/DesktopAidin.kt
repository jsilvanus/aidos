package dev.aidos.aidin

import kotlinx.coroutines.CoroutineScope
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Desktop wiring for [AidinNode] (RFC-0105 phase 1): node key and trust state as owner-only files
 * under [directory], mDNS discovery through JmDNS. The Android host builds its own (Keystore,
 * NsdManager).
 */
object DesktopAidin {
    /** Fixed so a paired peer finds this machine at the same port after a restart; 0 picks any. */
    const val DEFAULT_PORT = 47100

    fun create(
        directory: File,
        scope: CoroutineScope,
        engineVersion: String,
        heartbeatContent: (() -> Heartbeat)? = null,
        discovery: Discovery? = runCatching { JmdnsDiscovery() }.getOrNull(),
    ): AidinNode {
        directory.mkdirs()
        val key = FileNodeKeyStore(File(directory, "node-key")).loadOrCreate()
        val store = FileAidinStateStore(File(directory, "aidin-state.json"))
        return if (heartbeatContent == null) {
            AidinNode(key, store, scope, engineVersion, discovery)
        } else {
            AidinNode(key, store, scope, engineVersion, discovery, heartbeatContent = heartbeatContent)
        }
    }

    /**
     * IPv4 addresses on this machine's up, non-loopback, non-virtual-looking interfaces, for an
     * invite: the scanning device tries them in order.
     */
    fun lanAddresses(): List<String> =
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { runCatching { it.isUp && !it.isLoopback && !it.isVirtual }.getOrDefault(false) }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .filter { !it.isLinkLocalAddress }
            .map { it.hostAddress }
            .distinct()
}
