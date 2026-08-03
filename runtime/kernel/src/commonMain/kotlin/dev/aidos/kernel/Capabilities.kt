package dev.aidos.kernel

import kotlinx.datetime.Instant

/**
 * A security grant (RFC-0018).
 *
 * The word means exactly one thing in Aidos. Tool operations are [Operation]; model classes are
 * [ModelKind]. All three were once called "Capability", and they collided in the same modules
 * and in the same security reasoning.
 */
data class Capability(
    val id: CapabilityId,
    val projectId: ProjectId,
    val permission: Permission,
    val subjectId: String,
    val subjectKind: SubjectKind,
    val scope: CapabilityScope,
    val constraints: CapabilityConstraints,
    val issuedAt: Instant,
    val issuedBy: GrantSource,
    val parentCapabilityId: CapabilityId?,
    val allowsDelegation: Boolean,
    val expiresAt: Instant?,
    val revokedAt: Instant?,
    val revocationEpoch: Long,
    val auditRef: AuditId,
)

/**
 * Every actor that can hold authority.
 *
 * Plugins and MCP servers are subjects in their own right. Without that, a tool provider
 * executes under the *calling session's* authority — the confused-deputy problem one level out.
 */
enum class SubjectKind { SESSION, WORKER, PLUGIN, MCP_SERVER, FRONTEND }

sealed interface GrantSource {
    data object User : GrantSource
    data class Delegation(val delegatingSessionId: SessionId) : GrantSource
    data class Default(val sourceConfigId: String) : GrantSource
}

enum class Permission {
    FS_READ,
    FS_WRITE,
    GIT_READ,
    GIT_WRITE,

    /** DESKTOP and HEADLESS_SERVER only. Does not exist on MOBILE (RFC-0049). */
    SHELL_EXEC,

    MODEL_QUERY,
    NETWORK_EGRESS,
    SECRETS_READ,
    WORKER_CREATE,

    /** Subscriptions are authorized; without this the event bus is a side channel (RFC-0004). */
    EVENT_SUBSCRIBE,

    NOTIFY,
}

sealed interface CapabilityScope {
    data class Filesystem(
        val projectId: ProjectId,
        val rootRelativePath: String,
    ) : CapabilityScope

    data class Shell(
        val projectId: ProjectId,
        val workingDirectory: String,
        val allowedCommands: List<String>?,
    ) : CapabilityScope

    data class Git(
        val projectId: ProjectId,
        val allowedOperations: Set<GitOperation>,
    ) : CapabilityScope

    data class Network(
        val allowedHosts: List<String>,
        val allowedPorts: List<Int>? = null,
        val allowPrivateAddresses: Boolean = false,
        val followRedirects: RedirectPolicy = RedirectPolicy.SAME_HOST_ONLY,
        val maxResponseBytes: Long = 32L * 1024 * 1024,
    ) : CapabilityScope

    data class Model(
        val allowedProviders: List<String>?,
        val allowedKinds: Set<ModelKind>,
    ) : CapabilityScope

    data class Worker(
        val maxWorkerCount: Int,
        val allowedRoles: Set<SessionRole>,
    ) : CapabilityScope

    /** Specific IDs only. There is no wildcard grant for secrets (RFC-0054). */
    data class Secrets(
        val projectId: ProjectId,
        val allowedSecretIds: List<SecretId>,
    ) : CapabilityScope

    data class Events(val topicPatterns: List<String>) : CapabilityScope
}

enum class GitOperation { READ, WRITE, PUSH, PULL, WORKTREE }

enum class RedirectPolicy { NONE, SAME_HOST_ONLY, ANY_ALLOWED_HOST }

enum class SessionRole { DRIVER, WORKER }

/**
 * Constraints on a grant. Immutable — counters live in the `capability_usage` table, because a
 * mutable count inside an immutable value was a modelling error with no backing column.
 */
data class CapabilityConstraints(
    val maxDurationSeconds: Int? = null,
    val maxBytesRead: Long? = null,
    val maxBytesWritten: Long? = null,
    val requiresApprovalPerUse: Boolean = false,
    val maxExerciseCount: Int? = null,

    /** Cost is a security constraint, not an accounting figure (RFC-0028). */
    val budget: Budget? = null,
)

sealed interface CapabilityCheckResult {
    data object Allowed : CapabilityCheckResult
    data class Denied(val reason: DenialReason) : CapabilityCheckResult
}

enum class DenialReason {
    NO_CAPABILITY,
    CAPABILITY_EXPIRED,
    CAPABILITY_REVOKED,
    SCOPE_MISMATCH,
    CONSTRAINT_EXCEEDED,
    BUDGET_EXHAUSTED,
    REQUIRES_APPROVAL,

    /** The Run has read untrusted content and this effect is attenuated away (RFC-0027). */
    ATTENUATED_BY_TAINT,
}

/**
 * A path relative to a handle's root.
 *
 * Absolute paths, parent traversal, and NUL bytes are rejected at construction. Escape is
 * prevented *by construction* rather than by filtering — which is why no path-canonicalization
 * rules appear anywhere in the capability model. There is no canonicalization step to get wrong.
 */
class RelPath private constructor(val value: String) {
    companion object {
        private const val NUL = '\u0000'

        fun of(raw: String): Result<RelPath> = when {
            raw.isEmpty() ->
                Result.failure(IllegalArgumentException("empty path"))
            raw.startsWith('/') || raw.startsWith('\\') ->
                Result.failure(IllegalArgumentException("absolute path: $raw"))
            raw.contains(NUL) ->
                Result.failure(IllegalArgumentException("NUL byte in path"))
            raw.length > 1 && raw[1] == ':' ->
                Result.failure(IllegalArgumentException("drive-qualified path: $raw"))
            raw.split('/', '\\').any { it == ".." } ->
                Result.failure(IllegalArgumentException("parent traversal: $raw"))
            else ->
                Result.success(RelPath(raw))
        }
    }

    override fun toString(): String = value
    override fun equals(other: Any?): Boolean = other is RelPath && other.value == value
    override fun hashCode(): Int = value.hashCode()
}

/**
 * Designation and authority in one object. A handle resolves paths against its own root, so a
 * caller cannot name a location it was not given.
 */
sealed interface ResourceHandle {
    val capabilityId: CapabilityId
}

interface DirHandle : ResourceHandle {
    suspend fun read(relative: RelPath): Result<ByteArray>
    suspend fun write(relative: RelPath, content: ByteArray): Result<Unit>
    suspend fun list(relative: RelPath): Result<List<DirEntry>>
    suspend fun exists(relative: RelPath): Boolean
}

data class DirEntry(val name: String, val isDirectory: Boolean, val sizeBytes: Long)

/** An operation the runtime performs on the caller's behalf, named rather than searched for. */
interface Operation<out T> {
    val name: String
    val effect: EffectKind
    val recoveryClass: RecoveryClass
}

/**
 * The sole authority for creating, validating, and revoking capabilities (RFC-0018).
 *
 * Note what is absent: there is no `check(subject, permission, pathString)`. The runtime never
 * searches for an authority that would permit an operation — the caller names one. That is the
 * difference between capability discipline and an access-control list keyed by identity, and it
 * is the reason indirect prompt injection is bounded rather than fully empowered.
 */
interface CapabilityManager {
    suspend fun grant(
        subjectId: String,
        subjectKind: SubjectKind,
        permission: Permission,
        scope: CapabilityScope,
        constraints: CapabilityConstraints,
        expiresAt: Instant?,
        grantedBy: UserId,
    ): Result<Capability>

    /** Strictly attenuating in scope, constraints, expiry, and budget. Budget divides (RFC-0028). */
    suspend fun delegate(
        parent: CapabilityId,
        toSubjectId: String,
        toSubjectKind: SubjectKind,
        attenuatedScope: CapabilityScope,
        attenuatedConstraints: CapabilityConstraints,
    ): Result<Capability>

    suspend fun openHandle(subjectId: String, capabilityId: CapabilityId): Result<ResourceHandle>

    /** Validated immediately before exercise. [runTaint] attenuates the effective grant. */
    suspend fun validate(
        subjectId: String,
        capabilityId: CapabilityId,
        operation: Operation<*>,
        runTaint: TrustLevel,
    ): CapabilityCheckResult

    /**
     * Increments the project epoch and recursively revokes delegated children, in one
     * transaction.
     */
    suspend fun revoke(capabilityId: CapabilityId, revokedBy: String)

    suspend fun loadForSubject(subjectId: String): List<Capability>

    /**
     * Caches compare against this rather than relying on an invalidation message arriving.
     * A missed notification then degrades to a re-read, not to a security failure.
     */
    fun currentEpoch(projectId: ProjectId): Long
}
