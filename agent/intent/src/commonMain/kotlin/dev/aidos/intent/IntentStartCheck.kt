package dev.aidos.intent

import dev.aidos.kernel.PlatformProfile

/**
 * RFC-0110 / RFC-0124: the guards every agent Run on an intent node must pass, shared by
 * `IntentRunCoordinator.startTargetedRun` (executor) and `RealRuntimeClient.intents.startNodeRun`
 * (api). They live in `:intent` because `:api` cannot depend on `:executor`.
 *
 * Returns the node when it may be started.
 *
 * @throws IllegalArgumentException when the node is missing, archived, `HUMAN`, or incompatible
 *   with [platformProfile] / [availableLocality] (human-task isolation, profile and locality gating).
 *   Derived-status refusals are `IllegalArgumentException` too, keeping `startTargetedRun`'s
 *   historical contract.
 */
fun checkStartable(
    repository: IntentGraphRepository,
    statusComputer: DerivedStatusComputer,
    projectId: String,
    nodeId: String,
    platformProfile: PlatformProfile,
    availableLocality: IntentMinLocality,
): IntentNodeRecord {
    val node = repository.getNode(projectId, nodeId)
        ?: throw IllegalArgumentException("Intent node '$nodeId' not found in project '$projectId'.")
    require(node.lifecycle == IntentLifecycle.ACTIVE) {
        "Archived intent node '${node.id}' cannot be targeted by a Run."
    }
    require(node.actorKind != IntentActorKind.HUMAN) {
        "Human-task isolation: cannot start autonomous agent Run for HUMAN node '${node.id}'."
    }

    val currentStatus = statusComputer.deriveStatus(node.id, projectId)
    require(
        currentStatus !in setOf(DerivedStatus.BLOCKED, DerivedStatus.DONE, DerivedStatus.CONFLICTED),
    ) {
        "Cannot start targeted Run for intent node '${node.id}' in derived status $currentStatus."
    }

    require(isProfileCompatible(node.targetProfile, platformProfile)) {
        "Intent node '${node.id}' requires targetProfile=${node.targetProfile}, incompatible with $platformProfile."
    }
    require(isLocalityCompatible(node.minLocality, availableLocality)) {
        "Intent node '${node.id}' requires minLocality=${node.minLocality}, incompatible with $availableLocality."
    }
    return node
}
