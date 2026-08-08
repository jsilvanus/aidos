package dev.aidos.kernel

import kotlin.jvm.JvmInline

/**
 * Typed identifiers.
 *
 * All IDs are UUIDv7 (RFC-0054): globally unique and time-ordered, never
 * unique-within-project. Import must not collide, a project copied to another device must keep
 * its identity, and cross-project references must remain expressible.
 *
 * They are separate value classes rather than a shared `Uuid` alias so that passing a
 * [SessionId] where a [RunId] is expected does not compile. In a system where almost every
 * function takes three or four IDs, that is worth the boilerplate.
 */
@JvmInline value class ProjectId(val value: String)
@JvmInline value class SessionId(val value: String)
@JvmInline value class RunId(val value: String)
@JvmInline value class TaskId(val value: String)
@JvmInline value class AttemptId(val value: String)
@JvmInline value class PlanId(val value: String)
@JvmInline value class CapabilityId(val value: String)
@JvmInline value class ContentNodeId(val value: String)
@JvmInline value class IntentNodeId(val value: String)
@JvmInline value class EventId(val value: String)
@JvmInline value class AuditId(val value: String)
@JvmInline value class SecretId(val value: String)
@JvmInline value class DeviceId(val value: String)
@JvmInline value class UserId(val value: String)
@JvmInline value class WorkspaceId(val value: String)
@JvmInline value class ScheduledJobId(val value: String)

/** Who took an action. Two fields, never one polymorphic identifier (RFC-0046). */
data class ActorRef(val kind: ActorKind, val id: String)

enum class ActorKind {
    USER,
    SESSION,
    WORKER,
    MCP_SERVER,
    PLUGIN,

    /**
     * The runtime itself: crash recovery, migrations, compaction.
     *
     * Attributing these to the user would be a lie; attributing them to nothing would leave
     * unexplained changes in the audit trail.
     */
    RUNTIME,
}

/** Generates UUIDv7. Injected so tests can seed it (RFC-0038, RFC-0048). */
interface IdGenerator {
    fun next(): String
}
