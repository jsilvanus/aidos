package fi.italeino.aidos.engine.lan

import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * RFC-0126: one external device's grant to use this Engine over the LAN.
 *
 * Only a SHA-256 of the credential is stored, so the file on disk cannot be replayed. Expiry is
 * mandatory. The credential is its own class: a Binder-issued token never authenticates a LAN
 * request, and a LAN credential never authenticates a loopback or Binder one.
 */
@Serializable
data class ExternalDeviceGrant(
    val id: String,
    val label: String,
    val credentialSha256: String,
    val createdAt: String,
    val expiresAt: String,
    val revoked: Boolean = false,
) {
    /** The subject the HTTP layer authorises as. It is the key into per-app approvals (model use, agent use, locality). */
    val subject: String get() = subjectFor(id)

    companion object {
        const val SUBJECT_PREFIX = "ext:"
        fun subjectFor(id: String) = SUBJECT_PREFIX + id
        fun isExternalSubject(subject: String) = subject.startsWith(SUBJECT_PREFIX)
    }
}

/** A freshly created grant plus the only copy of its plaintext credential. */
data class IssuedExternalDevice(val grant: ExternalDeviceGrant, val credential: String)

/**
 * Per-device credentials for LAN access (RFC-0126). Thread-safe; optionally persisted to [file].
 *
 * Authorising a request here is only the first of the Engine's checks: the host also requires the
 * device's per-app approval (model use), its agent grant for `/v1/agent`, and its locality limit,
 * all keyed by [ExternalDeviceGrant.subject].
 */
class ExternalDeviceGrants(
    private val file: File? = null,
    private val now: () -> Instant = { Instant.now() },
    private val newId: () -> String = { java.util.UUID.randomUUID().toString() },
    /** Called after a grant is created and persisted; the host registers it for per-app approval here. */
    private val onIssued: (ExternalDeviceGrant) -> Unit = {},
) {
    private val random = SecureRandom()
    private val grants = ConcurrentHashMap<String, ExternalDeviceGrant>()
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    init {
        file?.takeIf { it.exists() }?.let { f ->
            runCatching {
                json.decodeFromString<List<ExternalDeviceGrant>>(f.readText()).forEach { grants[it.id] = it }
            }
        }
    }

    /** Issues a credential for [label] that stops working at [expiresAt]. Expiry in the past is refused. */
    @Synchronized
    fun issue(label: String, expiresAt: Instant): IssuedExternalDevice {
        require(label.isNotBlank()) { "device label required" }
        require(expiresAt.isAfter(now())) { "expiry must be in the future" }
        val bytes = ByteArray(32).also(random::nextBytes)
        val credential = bytes.joinToString("") { "%02x".format(it) }
        val grant = ExternalDeviceGrant(
            id = newId(),
            label = label.trim().take(MAX_LABEL),
            credentialSha256 = sha256(credential),
            createdAt = now().toString(),
            expiresAt = expiresAt.toString(),
        )
        grants[grant.id] = grant
        persist()
        onIssued(grant)
        return IssuedExternalDevice(grant, credential)
    }

    /** The grant a bearer credential belongs to, or null when unknown, expired or revoked. */
    fun authenticate(credential: String?): ExternalDeviceGrant? {
        if (credential.isNullOrEmpty()) return null
        val hash = sha256(credential)
        val match = grants.values.firstOrNull { constantTimeEquals(it.credentialSha256, hash) } ?: return null
        return match.takeIf { isUsable(it) }
    }

    /** Whether the grant for [subject] (an `ext:` subject) is still usable; false for anything else. */
    fun isSubjectUsable(subject: String): Boolean {
        if (!ExternalDeviceGrant.isExternalSubject(subject)) return false
        val g = grants[subject.removePrefix(ExternalDeviceGrant.SUBJECT_PREFIX)] ?: return false
        return isUsable(g)
    }

    fun list(): List<ExternalDeviceGrant> = grants.values.sortedBy { it.createdAt }

    @Synchronized
    fun revoke(id: String): Boolean {
        val g = grants[id] ?: return false
        grants[id] = g.copy(revoked = true)
        persist()
        return true
    }

    /** Drops expired and revoked grants from disk; they could not authenticate anyway. */
    @Synchronized
    fun purgeInactive(): Int {
        val dead = grants.values.filterNot(::isUsable).map { it.id }
        dead.forEach(grants::remove)
        if (dead.isNotEmpty()) persist()
        return dead.size
    }

    private fun isUsable(g: ExternalDeviceGrant) = !g.revoked && Instant.parse(g.expiresAt).isAfter(now())

    private fun persist() {
        val f = file ?: return
        f.parentFile?.mkdirs()
        val tmp = File(f.path + ".tmp")
        tmp.writeText(json.encodeToString(kotlinx.serialization.builtins.ListSerializer(ExternalDeviceGrant.serializer()), list()))
        if (!tmp.renameTo(f)) { f.writeText(tmp.readText()); tmp.delete() }
    }

    companion object {
        const val MAX_LABEL = 64

        fun sha256(text: String): String =
            MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

        fun constantTimeEquals(a: String, b: String): Boolean =
            MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))
    }
}

/**
 * Short-lived, single-use numeric bootstrap codes for SDK pairing (RFC-0126 Mode A).
 *
 * A code is only ever exchanged for a grant's credential; it is never itself accepted as a
 * bearer credential. Wrong guesses burn the code after [maxAttempts] so an eight-digit space
 * cannot be walked.
 */
class PairingCodes(
    private val grants: ExternalDeviceGrants,
    private val now: () -> Instant = { Instant.now() },
    private val codeLifetimeSeconds: Long = 300,
    private val maxAttempts: Int = 5,
) {
    private data class Pending(val label: String, val grantLifetimeSeconds: Long, val expiresAt: Instant, var attempts: Int = 0)

    private val random = SecureRandom()
    private val pending = ConcurrentHashMap<String, Pending>()
    private var globalFailures = 0
    private var globalWindowStart: Instant = now()

    /** Starts pairing for a device the user is adding; returns the code to show them. */
    @Synchronized
    fun start(label: String, grantLifetimeSeconds: Long): String {
        sweep()
        var code: String
        do { code = "%08d".format(random.nextInt(100_000_000)) } while (pending.containsKey(code))
        pending[code] = Pending(label, grantLifetimeSeconds, now().plusSeconds(codeLifetimeSeconds))
        return code
    }

    /** Exchanges [code] for a credential. Single use; null on any failure (unknown, expired, used, throttled). */
    @Synchronized
    fun redeem(code: String): IssuedExternalDevice? {
        sweep()
        val t = now()
        if (t.isAfter(globalWindowStart.plusSeconds(60))) { globalWindowStart = t; globalFailures = 0 }
        if (globalFailures >= GLOBAL_FAILURES_PER_MINUTE) return null
        val p = pending[code]
        if (p == null) {
            globalFailures++
            // Every outstanding code absorbs a share of the guess, so a scan cannot target one for free.
            pending.values.forEach { it.attempts++ }
            pending.entries.removeIf { it.value.attempts >= maxAttempts }
            return null
        }
        pending.remove(code)
        return grants.issue(p.label, t.plusSeconds(p.grantLifetimeSeconds))
    }

    private fun sweep() {
        val t = now()
        pending.entries.removeIf { it.value.expiresAt.isBefore(t) || it.value.attempts >= maxAttempts }
    }

    private companion object {
        const val GLOBAL_FAILURES_PER_MINUTE = 30
    }
}
