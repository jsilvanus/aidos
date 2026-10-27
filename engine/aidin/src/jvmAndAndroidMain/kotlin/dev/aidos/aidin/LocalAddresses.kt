package dev.aidos.aidin

import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * IPv4 addresses on this device's up, non-loopback, non-virtual interfaces, for an invite: the
 * scanning device tries them in order (RFC-0105 "Pairing"). Same API on the JVM and Android.
 */
fun lanAddresses(): List<String> =
    (NetworkInterface.getNetworkInterfaces()?.toList() ?: emptyList())
        .filter { runCatching { it.isUp && !it.isLoopback && !it.isVirtual }.getOrDefault(false) }
        .flatMap { it.inetAddresses.toList() }
        .filterIsInstance<Inet4Address>()
        .filter { !it.isLinkLocalAddress }
        .mapNotNull { it.hostAddress }
        .distinct()
