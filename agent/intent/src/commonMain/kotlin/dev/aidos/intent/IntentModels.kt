package dev.aidos.intent

import dev.aidos.kernel.PlatformProfile
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

@Serializable
enum class IntentNodeType {
    GROUP,
    GOAL,
    SUB_GOAL,
    ATOMIC_TASK,
    CONSTRAINT,
    ACCEPTANCE_CRITERION,
}

@Serializable
enum class IntentLifecycle {
    ACTIVE,
    ARCHIVED,
}

@Serializable
enum class IntentActorKind {
    HUMAN,
    AGENT,
    HYBRID,
}

@Serializable
enum class NodeDispatchPolicy {
    INHERIT,
    MANUAL_START,
    AUTO_WHEN_READY,
}

@Serializable
enum class EffectiveDispatchPolicy {
    MANUAL_START,
    AUTO_WHEN_READY,
}

@Serializable
enum class IntentGranularity {
    GROUP,
    GOAL,
    SUB_GOAL,
    ATOMIC,
}

@Serializable
enum class IntentTargetProfile {
    MOBILE,
    DESKTOP,
    HEADLESS_SERVER,
    ANY,
}

@Serializable
enum class IntentMinLocality {
    LOCAL_ONLY,
    TRUSTED_CLUSTER,
    ANY,
}

@Serializable
enum class IntentEdgeKind {
    DEPENDS_ON,
    CONSTRAINS,
    ACCEPTS,
}

@Serializable
enum class IntentCheckKind {
    NONE,
    FILE_EXISTS,
    CONTAINS_PATTERN,
    DIFF_NON_EMPTY,
    SHELL_COMMAND,
}

@Serializable
enum class DerivedStatus {
    NOT_STARTED,
    IN_PROGRESS,
    BLOCKED,
    NEEDS_REVIEW,
    DONE,
    STALE,
    CONFLICTED,
}

@Serializable
data class IntentNodeRecord(
    val id: String,
    val projectId: String,
    val type: IntentNodeType,
    val title: String,
    val description: String? = null,
    val priority: Int = 100,
    val lifecycle: IntentLifecycle = IntentLifecycle.ACTIVE,
    val parentId: String? = null,
    val actorKind: IntentActorKind = IntentActorKind.AGENT,
    val dispatchPolicy: NodeDispatchPolicy = NodeDispatchPolicy.INHERIT,
    val granularity: IntentGranularity = IntentGranularity.GOAL,
    val targetProfile: IntentTargetProfile? = null,
    val minLocality: IntentMinLocality? = null,
    val estimatedSteps: Int? = null,
    val scopePaths: List<String> = emptyList(),
    val dueAt: String? = null,
    val recurrenceJson: String? = null,
    val assertedStatus: DerivedStatus? = null,
    val assertedAt: String? = null,
    val assertedByUserId: String? = null,
    val assertionNote: String? = null,
    val checkKind: IntentCheckKind? = null,
    val checkSpec: String? = null,
    val verificationMet: Boolean? = null,
    val verifiedByKind: String? = null,
    val verifiedById: String? = null,
    val verifiedAt: String? = null,
    val createdAt: String,
    val createdByKind: String,
    val createdById: String,
    val modifiedAt: String,
    val modifiedByKind: String,
    val modifiedById: String,
    val tags: List<String> = emptyList(),
    val rowVersion: Int = 1,
)

@Serializable
data class IntentEdgeRecord(
    val id: String,
    val projectId: String,
    val fromNodeId: String,
    val toNodeId: String,
    val edgeKind: IntentEdgeKind,
    val createdAt: String,
)

@Serializable
data class IntentNodeStatusView(
    val node: IntentNodeRecord,
    val derivedStatus: DerivedStatus,
    val effectiveDispatchPolicy: EffectiveDispatchPolicy,
    val unmetDependencyIds: List<String>,
    val activeRunIds: List<String>,
    val pendingPlatformChecks: Boolean,
)

@Serializable
data class IntentGraphSnapshot(
    val schemaVersion: Int = 1,
    val projectId: String,
    val exportedAt: String,
    val nodes: List<IntentNodeRecord>,
    val edges: List<IntentEdgeRecord>,
)

@Serializable
data class SnapshotReconcileResult(
    val addedNodes: Int,
    val updatedNodes: Int,
    val conflictedNodeIds: List<String>,
    val addedEdges: Int,
)

internal object IntentJson {
    val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val stringListSerializer = ListSerializer(String.serializer())

    fun encodeStringList(values: List<String>): String =
        json.encodeToString(stringListSerializer, values)

    fun decodeStringList(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching { json.decodeFromString(stringListSerializer, raw) }.getOrDefault(emptyList())
    }
}

fun isProfileCompatible(targetProfile: IntentTargetProfile?, runtimeProfile: PlatformProfile): Boolean =
    when (targetProfile) {
        null, IntentTargetProfile.ANY -> true
        IntentTargetProfile.MOBILE -> runtimeProfile == PlatformProfile.MOBILE
        IntentTargetProfile.DESKTOP -> runtimeProfile == PlatformProfile.DESKTOP
        IntentTargetProfile.HEADLESS_SERVER -> runtimeProfile == PlatformProfile.HEADLESS_SERVER
    }

fun isLocalityCompatible(required: IntentMinLocality?, available: IntentMinLocality): Boolean =
    when (required) {
        null, IntentMinLocality.ANY -> true
        IntentMinLocality.LOCAL_ONLY -> available == IntentMinLocality.LOCAL_ONLY
        IntentMinLocality.TRUSTED_CLUSTER ->
            available == IntentMinLocality.LOCAL_ONLY || available == IntentMinLocality.TRUSTED_CLUSTER
    }
