package dev.aidos.aidin

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission
import java.util.Base64

/** A group this node administers: its signing key and the revocation state it publishes. */
class AdministeredGroup(
    val label: String,
    val authority: GroupAuthority,
    val revocationSerial: Long = 0,
    val revoked: Set<NodeId> = emptySet(),
    internal val groupKey: SigningKey,
) {
    val groupId: GroupId get() = authority.groupId

    fun withRevoked(nodeId: NodeId) = AdministeredGroup(label, authority, revocationSerial + 1, revoked + nodeId, groupKey)
}

/** Everything AIDIN persists besides the node key. */
data class AidinState(
    val pairs: List<PairRecord> = emptyList(),
    val memberships: List<GroupMembership> = emptyList(),
    /** User-chosen local names for groups this node is in; never sent to peers. */
    val groupLabels: Map<GroupId, String> = emptyMap(),
    val administered: List<AdministeredGroup> = emptyList(),
)

interface AidinStateStore {
    fun load(): AidinState
    fun save(state: AidinState)
}

class InMemoryAidinStateStore(private var state: AidinState = AidinState()) : AidinStateStore {
    override fun load() = state
    override fun save(state: AidinState) {
        this.state = state
    }
}

/**
 * Desktop store: one JSON file, owner-only where POSIX permissions exist, because it holds the
 * signing keys of groups this node administers. Written atomically (temp file + move).
 */
class FileAidinStateStore(private val file: File) : AidinStateStore {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    override fun load(): AidinState {
        if (!file.isFile) return AidinState()
        return json.decodeFromString(Stored.serializer(), file.readText()).toState()
    }

    override fun save(state: AidinState) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText("")
        try {
            Files.setPosixFilePermissions(tmp.toPath(), setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE))
        } catch (e: UnsupportedOperationException) {
            // Windows: inherits the user profile's ACL.
        }
        tmp.writeText(json.encodeToString(Stored.serializer(), Stored.from(state)))
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    @Serializable
    private data class Stored(
        val pairs: List<StoredPair> = emptyList(),
        val memberships: List<StoredMembership> = emptyList(),
        val administered: List<StoredAdministered> = emptyList(),
    ) {
        fun toState() = AidinState(
            pairs = pairs.map { PairRecord(unb64(it.publicKey), it.label, it.createdAt, it.scopes.mapNotNull(Scope::fromWire).toSet()) },
            memberships = memberships.mapNotNull { m ->
                val cert = m.certificate.toCertificate() ?: return@mapNotNull null
                GroupMembership(unb64(m.groupKey), cert, m.revocations?.toRevocationList())
            },
            groupLabels = memberships.associate { Keys.groupIdOf(unb64(it.groupKey)) to it.label },
            administered = administered.map { a ->
                val key = SigningKey.fromEncoded(unb64(a.privateKey), unb64(a.publicKey))
                AdministeredGroup(a.label, GroupAuthority(key), a.revocationSerial, a.revoked.map(::NodeId).toSet(), key)
            },
        )

        companion object {
            fun from(state: AidinState) = Stored(
                pairs = state.pairs.map { StoredPair(b64(it.publicKey), it.label, it.createdAtMillis, it.scopes.map { s -> s.wire }) },
                memberships = state.memberships.map {
                    StoredMembership(b64(it.groupPublicKey), state.groupLabels[it.groupId].orEmpty(), it.ownCertificate.toWire(), it.revocations?.toWire())
                },
                administered = state.administered.map {
                    StoredAdministered(it.label, b64(it.groupKey.publicKey), b64(it.groupKey.privateKeyEncoded()), it.revocationSerial, it.revoked.map { n -> n.value })
                },
            )
        }
    }

    @Serializable
    private data class StoredPair(val publicKey: String, val label: String, val createdAt: Long, val scopes: List<String>)

    @Serializable
    private data class StoredMembership(val groupKey: String, val label: String, val certificate: WireCertificate, val revocations: WireRevocationList? = null)

    @Serializable
    private data class StoredAdministered(val label: String, val publicKey: String, val privateKey: String, val revocationSerial: Long, val revoked: List<String>)
}

private fun b64(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
private fun unb64(text: String): ByteArray = Base64.getUrlDecoder().decode(text)
