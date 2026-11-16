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
 * bearer credential. Wrong guesses are counted against the address they came from: after
 * [maxAttempts] in a minute that address is refused, right code or not, and a cap on wrong
 * guesses from all addresses together bounds what many addresses can try. With both, a code's
 * five minutes allow at most a few hundred guesses at an eight-digit space.
 *
 * The all-addresses bound slows, it does not refuse. Once [GLOBAL_FAILURES_PER_MINUTE] wrong
 * guesses have landed in a minute, further attempts are spaced [SLOW_SPACING_SECONDS] apart and
 * wait for their turn (up to [MAX_WAIT_SECONDS]). It used to refuse everything for the rest of the
 * minute, so six addresses guessing five times each blocked the pairing the user had just started;
 * now the right code still redeems, a little later. The rate guessers get is the same, so the bound
 * on guesses is only slightly looser (at most 30 more a minute). A flood that outlasts the wait
 * ceiling is still refused: nothing here can make a flood harmless, only stop it from being free.
 *
 * Wrong guesses used to be charged to every outstanding code instead, so any device on the
 * network could cancel a pairing the user had just started by sending five bad codes.
 */
class PairingCodes(
    private val grants: ExternalDeviceGrants,
    private val now: () -> Instant = { Instant.now() },
    private val codeLifetimeSeconds: Long = 300,
    private val maxAttempts: Int = 5,
    /** How a throttled attempt waits for its turn. Tests replace it with one that moves their clock. */
    private val pause: suspend (millis: Long) -> Unit = { kotlinx.coroutines.delay(it) },
) {
    private data class Pending(val label: String, val grantLifetimeSeconds: Long, val expiresAt: Instant)
    private class Failures(var windowStart: Instant, var count: Int = 0)

    private val random = SecureRandom()
    private val pending = ConcurrentHashMap<String, Pending>()
    private val failuresBySource = HashMap<String, Failures>()
    private var globalFailures = 0
    private var globalWindowStart: Instant = now()
    private var nextSlot: Instant = now()

    /** Starts pairing for a device the user is adding; returns the code to show them. */
    @Synchronized
    fun start(label: String, grantLifetimeSeconds: Long): String {
        sweep()
        var code: String
        do { code = "%08d".format(random.nextInt(100_000_000)) } while (pending.containsKey(code))
        pending[code] = Pending(label, grantLifetimeSeconds, now().plusSeconds(codeLifetimeSeconds))
        return code
    }

    /**
     * Exchanges [code] for a credential. Single use; null on any failure (unknown, expired, used,
     * throttled). [source] is the address the attempt came from; attempts without one share a bucket.
     * Under a many-address flood the attempt first waits for its turn (see the class comment).
     */
    suspend fun redeem(code: String, source: String = UNKNOWN_SOURCE): IssuedExternalDevice? {
        val waitMillis = reserveTurn(source) ?: return null
        if (waitMillis > 0) pause(waitMillis)
        return attempt(code, source)
    }

    /** Null when this attempt is refused outright; otherwise how long it waits before it is evaluated. */
    @Synchronized
    private fun reserveTurn(source: String): Long? {
        sweep()
        val t = now()
        resetGlobalWindow(t)
        val mine = failuresBySource[source]
        if (mine != null && mine.count >= maxAttempts) return null
        if (globalFailures < GLOBAL_FAILURES_PER_MINUTE) return 0
        val slot = if (nextSlot.isAfter(t)) nextSlot else t
        val wait = java.time.Duration.between(t, slot)
        if (wait.seconds >= MAX_WAIT_SECONDS) return null
        nextSlot = slot.plusSeconds(SLOW_SPACING_SECONDS)
        return wait.toMillis()
    }

    @Synchronized
    private fun attempt(code: String, source: String): IssuedExternalDevice? {
        sweep()
        val t = now()
        resetGlobalWindow(t)
        val mine = failuresBySource[source]
        // Locked out while it waited.
        if (mine != null && mine.count >= maxAttempts) return null
        val p = pending.remove(code)
        if (p == null) {
            globalFailures++
            if (mine != null) mine.count++
            else if (failuresBySource.size < MAX_TRACKED_SOURCES) failuresBySource[source] = Failures(t, 1)
            return null
        }
        return grants.issue(p.label, t.plusSeconds(p.grantLifetimeSeconds))
    }

    private fun resetGlobalWindow(t: Instant) {
        if (t.isAfter(globalWindowStart.plusSeconds(WINDOW_SECONDS))) { globalWindowStart = t; globalFailures = 0 }
    }

    private fun sweep() {
        val t = now()
        pending.entries.removeIf { it.value.expiresAt.isBefore(t) }
        failuresBySource.entries.removeIf { t.isAfter(it.value.windowStart.plusSeconds(WINDOW_SECONDS)) }
    }

    private companion object {
        const val GLOBAL_FAILURES_PER_MINUTE = 30
        const val WINDOW_SECONDS = 60L
        const val SLOW_SPACING_SECONDS = 2L
        const val MAX_WAIT_SECONDS = 10L
        // Bounded so a flood of addresses cannot grow the table; the global cap covers the overflow.
        const val MAX_TRACKED_SOURCES = 1024
        const val UNKNOWN_SOURCE = "?"
    }
}
