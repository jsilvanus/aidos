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
import dev.aidos.ui.paradigms.outliner.CausalOutlinerPresenter
import dev.aidos.ui.workspace.GraphSnapshot
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
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
        // RFC-0124 amendment (UI-20): the presenter reads a store snapshot, so the stress test pushes snapshots into it.
        val store = MutableStateFlow(GraphSnapshot.empty("project-stress"))
        val presenter = CausalOutlinerPresenter(store, backgroundScope)

        // 10,000 updates pushed across parallel background workers
        val updatesCount = 10_000
        val deferredList = (1..updatesCount).chunked(500).map { chunk ->
            async(Dispatchers.Default) {
                for (i in chunk) {
                    val node = createSampleNode("node-$i", "Task #$i", IntentActorKind.HUMAN)
                    store.value = GraphSnapshot(
                        projectId = "project-stress",
                        nodes = listOf(node),
                        edges = emptyList(),
                        statuses = mapOf(node.id to createSampleStatus(node, DerivedStatus.IN_PROGRESS)),
                        proposals = emptyList(),
                        loadedAt = i.toLong(),
                    )
                }
            }
        }

        deferredList.awaitAll()
        runCurrent()

        val finalRows = presenter.state.value.visibleRows
        assertEquals(1, finalRows.size)
        assertTrue(finalRows.first().node.id.startsWith("node-"))
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
}
