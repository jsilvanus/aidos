package dev.aidos.ui

import dev.aidos.api.*
import dev.aidos.intent.*
import dev.aidos.kernel.PlatformProfile
import dev.aidos.tcr.a2ui.A2UI_SCHEMA
import dev.aidos.ui.generative.CardOutcome
import dev.aidos.ui.generative.cardModelsFor
import dev.aidos.ui.workspace.GraphSnapshot
import dev.aidos.ui.workspace.MAX_CARDS_PER_NODE
import dev.aidos.ui.workspace.ProjectGraphStore
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** RFC-0124 generative layer (UI-51): stored agent cards load, group, cap, validate and place. */
class GenerativeCardsTest {

    private fun manifestJson(target: String = "goal_1", type: String = "StepCounter", manifestId: String = "ui_manifest_42"): String =
        """{"${'$'}schema":"$A2UI_SCHEMA","manifestId":"$manifestId","targetNodeId":"$target","root":{"type":"$type","props":{"current":2,"total":5}}}"""

    private fun record(callId: String, target: String = "goal_1", json: String = manifestJson(target), taint: String = "UNTRUSTED") =
        UiManifestRecord(callId, "run_abcdef123456", "p1", target, json, taint, null)

    private fun snapshotWith(vararg records: UiManifestRecord): GraphSnapshot =
        GraphSnapshot.empty("p1").copy(manifests = records.groupBy { it.targetNodeId })

    @Test
    fun storeGroupsManifestsByTargetNewestFirstAndCapsThem() = runTest {
        val intents = FakeIntentCommands(
            manifests = (1..5).map { record("c$it") } + record("c6", target = "goal_2"),
        )
        val store = ProjectGraphStore("p1", FakeRuntimeClient(intents), this, clock = { 42L })

        store.refreshNow()

        val byNode = store.snapshot.value.manifests
        assertEquals(listOf("c1", "c2", "c3"), byNode.getValue("goal_1").map { it.callId })
        assertEquals(MAX_CARDS_PER_NODE, byNode.getValue("goal_1").size)
        assertEquals(listOf("c6"), byNode.getValue("goal_2").map { it.callId })
        assertEquals(null, store.snapshot.value.error)
    }

    @Test
    fun storeStillLoadsTheGraphWhenManifestsFail() = runTest {
        val intents = FakeIntentCommands(manifestsFail = true)
        val store = ProjectGraphStore("p1", FakeRuntimeClient(intents), this, clock = { 42L })

        store.refreshNow()

        val snap = store.snapshot.value
        assertEquals(null, snap.error)
        assertEquals(42L, snap.loadedAt)
        assertTrue(snap.manifests.isEmpty())
    }

    @Test
    fun rfcExampleIsShownUnderItsNode() {
        val cards = cardModelsFor("goal_1", snapshotWith(record("c1")))
        assertEquals(1, cards.size)
        val shown = assertIs<CardOutcome.Shown>(cards.single().outcome)
        assertEquals("goal_1", shown.manifest.targetNodeId)
    }

    @Test
    fun unknownComponentTypeFailsClosed() {
        val cards = cardModelsFor("goal_1", snapshotWith(record("c1", json = manifestJson(type = "Rocket"))))
        assertIs<CardOutcome.Hidden>(cards.single().outcome)
    }

    @Test
    fun manifestTargetingAnotherNodeFailsClosed() {
        val mismatched = record("c1", target = "goal_2", json = manifestJson(target = "goal_1"))
        val cards = cardModelsFor("goal_2", snapshotWith(mismatched))
        assertIs<CardOutcome.Hidden>(cards.single().outcome)
    }

    @Test
    fun cardsOnlyAppearUnderTheirOwnNode() {
        assertTrue(cardModelsFor("goal_9", snapshotWith(record("c1"))).isEmpty())
    }

    // Fakes: only the parts the store reads are real.

    private class FakeRuntimeClient(override val intents: IntentCommands) : RuntimeClient {
        override val projects: ProjectCommands get() = error("Not needed")
        override val sessions: SessionCommands get() = error("Not needed")
        override val capabilities: CapabilityCommands get() = error("Not needed")
        override val knowledge: KnowledgeQueries get() = error("Not needed")
        override val diff: DiffQueries get() = error("Not needed")
        override val artifacts: ArtifactQueries get() = error("Not needed")
        override val events: EventSubscriptions get() = error("Not needed")
        override val runtime: RuntimeInfo get() = error("Not needed")
    }

    private class FakeIntentCommands(
        private val manifests: List<UiManifestRecord> = emptyList(),
        private val manifestsFail: Boolean = false,
    ) : IntentCommands {
        override suspend fun createNode(request: CreateIntentNodeRequest) = error("Not needed")
        override suspend fun updateNode(request: UpdateIntentNodeRequest) = error("Not needed")
        override suspend fun archiveNode(projectId: String, nodeId: String) = error("Not needed")
        override suspend fun getNodeStatus(projectId: String, nodeId: String, projectDefaultPolicy: EffectiveDispatchPolicy): IntentNodeStatusView? = null
        override suspend fun listNodes(projectId: String, includeArchived: Boolean, actorKind: IntentActorKind?, parentId: String?, filterByParent: Boolean): List<IntentNodeRecord> = emptyList()
        override suspend fun listActionableForHuman(projectId: String, projectDefaultPolicy: EffectiveDispatchPolicy) = emptyList<IntentNodeStatusView>()
        override suspend fun listReadyForAgent(projectId: String, profile: PlatformProfile, locality: IntentMinLocality, projectDefaultPolicy: EffectiveDispatchPolicy, onlyAutoDispatch: Boolean) = emptyList<IntentNodeStatusView>()
        override suspend fun completeHumanNode(projectId: String, nodeId: String, userId: String, note: String?): IntentNodeRecord = error("Not needed")
        override suspend fun addEdge(projectId: String, fromNodeId: String, toNodeId: String, edgeKind: IntentEdgeKind) = error("Not needed")
        override suspend fun removeEdge(projectId: String, fromNodeId: String, toNodeId: String, edgeKind: IntentEdgeKind) = false
        override suspend fun listEdges(projectId: String, fromNodeId: String?, toNodeId: String?, edgeKind: IntentEdgeKind?) = emptyList<IntentEdgeRecord>()
        override suspend fun propose(request: CreateIntentProposalRequest) = error("Not needed")
        override suspend fun listProposals(projectId: String, state: String?) = emptyList<IntentProposal>()
        override suspend fun resolveProposal(projectId: String, proposalId: String, decision: ProposalResolutionDecision, resolvedByUserId: String, editedOperations: List<IntentOperation>?) = error("Not needed")
        override suspend fun confirmImplementation(projectId: String, nodeId: String, confirmedByKind: String, confirmedById: String, criterionIds: List<String>?) {}
        override suspend fun listUiManifests(projectId: String, targetNodeId: String?, limit: Int): List<UiManifestRecord> {
            if (manifestsFail) error("manifests unavailable")
            return manifests.filter { targetNodeId == null || it.targetNodeId == targetNodeId }.take(limit)
        }
        override suspend fun exportSnapshotJson(projectId: String) = ""
        override suspend fun importAndReconcileSnapshot(projectId: String, snapshotJson: String) = error("Not needed")
    }
}
