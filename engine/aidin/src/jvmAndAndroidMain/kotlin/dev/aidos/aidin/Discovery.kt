package dev.aidos.aidin

/**
 * RFC-0105 "Discovery": what a node announces on the local network as DNS-SD service
 * [SERVICE_TYPE]. Only the protocol version, node id, port and rotating group tags; no names,
 * models or resource state. Seeing an advert grants nothing: a node is contacted only when a pair
 * record or a shared group says so, and everything else happens over the authenticated link.
 */
data class DiscoveryAdvert(
    val nodeId: NodeId,
    val port: Int,
    val groupTags: Set<String> = emptySet(),
    val protocolVersion: Int = AIDIN_PROTOCOL_VERSION,
) {
    /** DNS-SD TXT record entries. Each stays well under the 255-byte entry limit. */
    fun toTxt(): Map<String, String> = buildMap {
        put("v", protocolVersion.toString())
        put("n", nodeId.value)
        if (groupTags.isNotEmpty()) put("g", groupTags.sorted().joinToString(","))
    }

    companion object {
        const val SERVICE_TYPE = "_aidin._tcp"

        /** Null for a TXT record that is not a well-formed advert of a protocol we speak. */
        fun fromTxt(txt: Map<String, String>, port: Int): DiscoveryAdvert? {
            val version = txt["v"]?.toIntOrNull() ?: return null
            if (version != AIDIN_PROTOCOL_VERSION || port !in 1..65535) return null
            val nodeId = txt["n"]?.let { runCatching { NodeId(it) }.getOrNull() } ?: return null
            val tags = txt["g"].orEmpty().split(',').filter { it.length == GroupTags.TAG_LENGTH }.toSet()
            return DiscoveryAdvert(nodeId, port, tags, version)
        }
    }
}

/**
 * Rotating, per-day tags for group ids, so an advert lets members recognise each other without
 * letting an outsider follow one group's devices from day to day. Members check today's and
 * yesterday's tag to cope with clock skew around midnight.
 */
object GroupTags {
    const val TAG_LENGTH = 10
    private const val DAY_MILLIS = 24L * 60 * 60 * 1000

    fun tag(groupId: GroupId, nowMillis: Long): String = tagForDay(groupId, Math.floorDiv(nowMillis, DAY_MILLIS))

    /** Groups of mine that [advert] claims, by today's or yesterday's tag. */
    fun matching(advert: DiscoveryAdvert, myGroups: Collection<GroupId>, nowMillis: Long): Set<GroupId> {
        val day = Math.floorDiv(nowMillis, DAY_MILLIS)
        return myGroups.filter { g -> tagForDay(g, day) in advert.groupTags || tagForDay(g, day - 1) in advert.groupTags }.toSet()
    }

    private fun tagForDay(groupId: GroupId, day: Long): String =
        Base32.encode(Keys.sha256(Canonical("aidin-group-tag-v1").string(groupId.value).long(day).toByteArray()))
            .take(TAG_LENGTH)
}

/** A node seen on the network: its advert and where to reach it. */
data class DiscoveredNode(val advert: DiscoveryAdvert, val host: String)

/**
 * Platform DNS-SD. Desktop: [JmdnsDiscovery] (jvmMain). Android: NsdManager (androidMain, not yet
 * written: it needs a device to test).
 */
interface Discovery : AutoCloseable {
    fun announce(advert: DiscoveryAdvert)
    fun browse(onFound: (DiscoveredNode) -> Unit, onLost: (NodeId) -> Unit)
}
