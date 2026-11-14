package dev.aidos.executor

import app.cash.sqldelight.db.SqlDriver
import dev.aidos.intent.DerivedStatus
import dev.aidos.intent.DerivedStatusComputer
import dev.aidos.intent.EffectiveDispatchPolicy
import dev.aidos.intent.IntentActorKind
import dev.aidos.intent.IntentGraphRepository
import dev.aidos.intent.IntentLifecycle
import dev.aidos.intent.IntentMinLocality
import dev.aidos.intent.IntentNodeStatusView
import dev.aidos.intent.isLocalityCompatible
import dev.aidos.intent.isProfileCompatible
import dev.aidos.kernel.EventId
import dev.aidos.kernel.PlatformProfile
import dev.aidos.kernel.ProjectId
import dev.aidos.kernel.RunId
import dev.aidos.kernel.SessionId

/**
 * Coordinates Run creation and completion against Dual-Mode Intent Graph nodes (RFC-0110).
 *
 * Enforces:
 * - **Human-task isolation:** `HUMAN` nodes cannot be targeted by autonomous agent Runs.
 * - **Profile & locality gating:** prevents dispatching `DESKTOP`-only tasks onto `MOBILE`.
 * - **Atomic step budgets:** clamps Run `max_steps` using `estimatedSteps`.
 * - **Split verification:** `MOBILE` Runs that cannot execute `SHELL_COMMAND` checks (or `HYBRID`
 *   tasks) park in `NEEDS_REVIEW` (`confirmed = 0`) until verified on `DESKTOP` or by `USER`.
 */
class IntentRunCoordinator(
    driver: SqlDriver,
    private val idGen: () -> String,
    private val nowIso: () -> String,
    private val eventStore: EventStore = EventStore(driver),
    private val runCreator: RunCreator = RunCreator(driver, idGen, nowIso),
    private val repository: IntentGraphRepository = IntentGraphRepository(driver, idGen = idGen, nowIso = nowIso),
    private val statusComputer: DerivedStatusComputer = DerivedStatusComputer(driver),
) {
    fun startTargetedRun(
        sessionId: SessionId,
        projectId: ProjectId,
        nodeId: String,
        platformProfile: PlatformProfile,
        availableLocality: IntentMinLocality = IntentMinLocality.LOCAL_ONLY,
        deviceId: String,
        networkAvailable: Boolean,
        defaultMaxSteps: Int = 24,
    ): RunId {
        val node = repository.getNode(projectId.value, nodeId)
            ?: throw IllegalArgumentException("Intent node '$nodeId' not found in project '${projectId.value}'.")
        require(node.lifecycle == IntentLifecycle.ACTIVE) {
            "Archived intent node '${node.id}' cannot be targeted by a Run."
        }
        require(node.actorKind != IntentActorKind.HUMAN) {
            "Human-task isolation: cannot start autonomous agent Run for HUMAN node '${node.id}'."
        }

        val currentStatus = statusComputer.deriveStatus(node.id, projectId.value)
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

        val effectiveMaxSteps = node.estimatedSteps?.coerceIn(1, defaultMaxSteps) ?: defaultMaxSteps
        val triggerEventId = idGen()
        eventStore.publish(
            id = triggerEventId,
            projectId = projectId.value,
            type = EventTypes.USER_COMMAND,
            source = "intent:${node.id}",
            payload = """{"intent_node_id":"${escapeJson(node.id)}","title":"${escapeJson(node.title)}"}""",
            nowIso = nowIso(),
        )

        val runId = runCreator.createForUserMessage(
            sessionId = sessionId,
            projectId = projectId,
            triggerEventId = EventId(triggerEventId),
            userMessageSummary = "Intent ${node.id}: ${node.title}",
            platformProfile = platformProfile,
            deviceId = deviceId,
            networkAvailable = networkAvailable,
            maxSteps = effectiveMaxSteps,
        )
        repository.recordRunTargeting(projectId.value, runId.value, node.id)
        return runId
    }

    fun completeTargetedRun(
        projectId: ProjectId,
        runId: RunId,
        nodeId: String,
        platformProfile: PlatformProfile,
        verifiedByCheckId: String? = null,
    ) {
        val node = repository.getNode(projectId.value, nodeId)
            ?: throw IllegalArgumentException("Intent node '$nodeId' not found in project '${projectId.value}'.")

        val pendingPlatformChecks = platformProfile == PlatformProfile.MOBILE &&
            statusComputer.hasPendingPlatformChecks(nodeId, projectId.value)

        val canAutoConfirmWithCheck = node.actorKind != IntentActorKind.HYBRID &&
            !pendingPlatformChecks &&
            verifiedByCheckId != null

        if (canAutoConfirmWithCheck) {
            val checkId = verifiedByCheckId
            repository.recordRunImplements(
                projectId = projectId.value,
                runId = runId.value,
                nodeId = nodeId,
                confirmed = true,
                confirmedByKind = "CHECK",
                confirmedById = checkId,
            )
            repository.confirmPendingImplementation(
                projectId = projectId.value,
                nodeId = nodeId,
                confirmedByKind = "CHECK",
                confirmedById = checkId,
            )
        } else {
            repository.recordRunImplements(
                projectId = projectId.value,
                runId = runId.value,
                nodeId = nodeId,
                confirmed = false,
            )
        }
    }

    fun findReadyAutoDispatchNodes(
        projectId: ProjectId,
        platformProfile: PlatformProfile,
        availableLocality: IntentMinLocality = IntentMinLocality.LOCAL_ONLY,
        projectDefaultPolicy: EffectiveDispatchPolicy = EffectiveDispatchPolicy.MANUAL_START,
    ): List<IntentNodeStatusView> = statusComputer.listReadyForAgent(
        projectId = projectId.value,
        profile = platformProfile,
        locality = availableLocality,
        projectDefaultPolicy = projectDefaultPolicy,
        onlyAutoDispatch = true,
    )

    private fun escapeJson(value: String): String =
        value.replace("\\", "\\\\").replace("\"", "\\\"")
}
