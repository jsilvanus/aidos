package dev.aidos.ui

import dev.aidos.api.CreateIntentNodeRequest
import dev.aidos.api.CreateIntentProposalRequest
import dev.aidos.api.IntentCommands
import dev.aidos.api.UpdateIntentNodeRequest
import dev.aidos.intent.DerivedStatus
import dev.aidos.intent.EffectiveDispatchPolicy
import dev.aidos.intent.IntentActorKind
import dev.aidos.intent.IntentEdgeKind
import dev.aidos.intent.IntentEdgeRecord
import dev.aidos.intent.IntentGranularity
import dev.aidos.intent.IntentLifecycle
import dev.aidos.intent.IntentMinLocality
import dev.aidos.intent.IntentNodeRecord
import dev.aidos.intent.IntentNodeStatusView
import dev.aidos.intent.IntentNodeType
import dev.aidos.intent.IntentOperation
import dev.aidos.intent.IntentProposal
import dev.aidos.intent.IntentTargetProfile
import dev.aidos.intent.NodeDispatchPolicy
import dev.aidos.intent.ProposalResolutionDecision
import dev.aidos.intent.SnapshotReconcileResult
import dev.aidos.kernel.PlatformProfile
import dev.aidos.ui.layout.AdaptiveLayoutState
import dev.aidos.ui.layout.FormFactor
import dev.aidos.ui.layout.ScaffoldNavigationType
import dev.aidos.ui.layout.computeFormFactor
import dev.aidos.ui.layout.navigationTypeForFormFactor
import dev.aidos.ui.state.IntentGraphSubscription
import dev.aidos.ui.theme.AidosDarkColorScheme
import dev.aidos.ui.theme.AidosLightColorScheme
import dev.aidos.ui.theme.AidosOledColorScheme
import dev.aidos.ui.theme.ColorTokens
import dev.aidos.ui.theme.SpacingTokens
import dev.aidos.ui.theme.colorForActor
import dev.aidos.ui.theme.colorForStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PhaseOneTest {

    @Test
    fun testWindowSizeClassBreakpointsAndNavigationComponents() {
        val simulatedWidths = listOf(
            360f to (FormFactor.HANDHELD_COMPACT to ScaffoldNavigationType.BOTTOM_NAVIGATION),
            599f to (FormFactor.HANDHELD_COMPACT to ScaffoldNavigationType.BOTTOM_NAVIGATION),
            600f to (FormFactor.HANDHELD_EXPANDED to ScaffoldNavigationType.NAVIGATION_RAIL),
            720f to (FormFactor.HANDHELD_EXPANDED to ScaffoldNavigationType.NAVIGATION_RAIL),
            839f to (FormFactor.HANDHELD_EXPANDED to ScaffoldNavigationType.NAVIGATION_RAIL),
            840f to (FormFactor.DESKTOP_DOCKED to ScaffoldNavigationType.PERMANENT_DRAWER),
            1080f to (FormFactor.DESKTOP_DOCKED to ScaffoldNavigationType.PERMANENT_DRAWER),
            2560f to (FormFactor.DESKTOP_DOCKED to ScaffoldNavigationType.PERMANENT_DRAWER),
        )

        val adaptiveState = AdaptiveLayoutState()

        for ((width, expected) in simulatedWidths) {
            val (expectedFormFactor, expectedNavType) = expected
            val computedFactor = computeFormFactor(width)
            assertEquals(expectedFormFactor, computedFactor, "Failed for width $width")
            assertEquals(expectedNavType, navigationTypeForFormFactor(computedFactor), "NavType failed for $width")

            adaptiveState.updateDimensions(width, 1000f)
            assertEquals(expectedFormFactor, adaptiveState.formFactor)
        }
    }

    @Test
    fun testThemeTokensAndColorSemantics() {
        // Actor colors
        assertEquals(ColorTokens.ActorHuman, colorForActor(IntentActorKind.HUMAN))
        assertEquals(ColorTokens.ActorAgent, colorForActor(IntentActorKind.AGENT))
        assertEquals(ColorTokens.ActorHybrid, colorForActor(IntentActorKind.HYBRID))

        // Status colors
        assertEquals(ColorTokens.StatusBlocked, colorForStatus(DerivedStatus.BLOCKED))
        assertEquals(ColorTokens.StatusReview, colorForStatus(DerivedStatus.NEEDS_REVIEW))
        assertEquals(ColorTokens.StatusDone, colorForStatus(DerivedStatus.DONE))
        assertEquals(ColorTokens.StatusInProgress, colorForStatus(DerivedStatus.IN_PROGRESS))

        // Accessibility target
        assertEquals(48, SpacingTokens.minTouchTarget.value.toInt())

        // Themes defined
        assertNotNull(AidosDarkColorScheme)
        assertNotNull(AidosLightColorScheme)
        assertNotNull(AidosOledColorScheme)
        assertEquals(ColorTokens.OledBlack, AidosOledColorScheme.background)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun testPresenterStressTenThousandUpdates() = runTest {
        val fakeCommands = FakeIntentCommands()
        val subscription = IntentGraphSubscription(
            intentCommands = fakeCommands,
            scope = this,
            ioDispatcher = Dispatchers.Default,
        )

        subscription.bindProject("project-stress")

        // 10,000 updates pushed across parallel background workers
        val updatesCount = 10_000
        val deferredList = (1..updatesCount).chunked(500).map { chunk ->
            async(Dispatchers.Default) {
                for (i in chunk) {
                    val node = createSampleNode("node-$i", "Task #$i", IntentActorKind.HUMAN)
                    subscription.applyBatchUpdate(
                        nodes = listOf(node),
                        statuses = mapOf(node.id to createSampleStatus(node, DerivedStatus.IN_PROGRESS)),
                    )
                }
            }
        }

        deferredList.awaitAll()

        val finalState = subscription.state.value
        assertEquals(1, finalState.nodes.size)
        assertEquals(1, finalState.actionableHumanNodes.size)
        assertTrue(finalState.nodes.first().id.startsWith("node-"))
    }

    private fun createSampleNode(id: String, title: String, actorKind: IntentActorKind): IntentNodeRecord {
        return IntentNodeRecord(
            id = id,
            projectId = "project-stress",
            type = IntentNodeType.ATOMIC_TASK,
            title = title,
            description = "Description for $id",
            granularity = IntentGranularity.ATOMIC,
            lifecycle = IntentLifecycle.ACTIVE,
            actorKind = actorKind,
            dispatchPolicy = NodeDispatchPolicy.MANUAL_START,
            targetProfile = IntentTargetProfile.ANY,
            minLocality = IntentMinLocality.LOCAL_ONLY,
            createdAt = "2026-11-14T20:00:00Z",
            createdByKind = "USER",
            createdById = "local-user",
            modifiedAt = "2026-11-14T20:00:00Z",
            modifiedByKind = "USER",
            modifiedById = "local-user",
        )
    }

    private fun createSampleStatus(node: IntentNodeRecord, status: DerivedStatus): IntentNodeStatusView {
        return IntentNodeStatusView(
            node = node,
            derivedStatus = status,
            effectiveDispatchPolicy = EffectiveDispatchPolicy.MANUAL_START,
            unmetDependencyIds = emptyList(),
            activeRunIds = emptyList(),
            pendingPlatformChecks = false,
        )
    }

    private class FakeIntentCommands : IntentCommands {
        override suspend fun createNode(request: CreateIntentNodeRequest) = error("Not needed")
        override suspend fun updateNode(request: UpdateIntentNodeRequest) = error("Not needed")
        override suspend fun archiveNode(projectId: String, nodeId: String) = error("Not needed")
        override suspend fun getNodeStatus(projectId: String, nodeId: String, projectDefaultPolicy: EffectiveDispatchPolicy) = null
        override suspend fun listNodes(projectId: String, includeArchived: Boolean, actorKind: IntentActorKind?, parentId: String?, filterByParent: Boolean) = emptyList<IntentNodeRecord>()
        override suspend fun listActionableForHuman(projectId: String, projectDefaultPolicy: EffectiveDispatchPolicy) = emptyList<IntentNodeStatusView>()
        override suspend fun listReadyForAgent(projectId: String, profile: PlatformProfile, locality: IntentMinLocality, projectDefaultPolicy: EffectiveDispatchPolicy, onlyAutoDispatch: Boolean) = emptyList<IntentNodeStatusView>()
        override suspend fun completeHumanNode(projectId: String, nodeId: String, userId: String, note: String?) = error("Not needed")
        override suspend fun addEdge(projectId: String, fromNodeId: String, toNodeId: String, edgeKind: IntentEdgeKind) = error("Not needed")
        override suspend fun removeEdge(projectId: String, fromNodeId: String, toNodeId: String, edgeKind: IntentEdgeKind) = false
        override suspend fun listEdges(projectId: String, fromNodeId: String?, toNodeId: String?, edgeKind: IntentEdgeKind?) = emptyList<IntentEdgeRecord>()
        override suspend fun propose(request: CreateIntentProposalRequest) = error("Not needed")
        override suspend fun listProposals(projectId: String, state: String?) = emptyList<IntentProposal>()
        override suspend fun resolveProposal(projectId: String, proposalId: String, decision: ProposalResolutionDecision, resolvedByUserId: String, editedOperations: List<IntentOperation>?) = error("Not needed")
        override suspend fun confirmImplementation(projectId: String, nodeId: String, confirmedByKind: String, confirmedById: String, criterionIds: List<String>?) {}
        override suspend fun exportSnapshotJson(projectId: String) = ""
        override suspend fun importAndReconcileSnapshot(projectId: String, snapshotJson: String) = error("Not needed")
    }
}
