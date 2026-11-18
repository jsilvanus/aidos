package dev.aidos.ui.views

import dev.aidos.intent.DerivedStatus
import dev.aidos.intent.IntentActorKind
import dev.aidos.intent.IntentEdgeKind
import dev.aidos.intent.IntentEdgeRecord
import dev.aidos.intent.IntentGranularity
import dev.aidos.intent.IntentLifecycle
import dev.aidos.intent.IntentMinLocality
import dev.aidos.intent.IntentNodeRecord
import dev.aidos.intent.IntentNodeType
import dev.aidos.intent.IntentTargetProfile
import dev.aidos.intent.NodeDispatchPolicy
import dev.aidos.ui.spatial.schematic.BreadboardLayout
import dev.aidos.ui.spatial.schematic.BreadboardSchematicPresenter
import dev.aidos.ui.spatial.schematic.ChipAction
import dev.aidos.ui.workspace.GraphSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** RFC-0111 Design 5 and RFC-0124 0124.5: the Breadboard's pure rules, on live-shaped data. */
class BreadboardViewTest {

    private fun node(
        id: String,
        actor: IntentActorKind = IntentActorKind.AGENT,
        policy: NodeDispatchPolicy = NodeDispatchPolicy.MANUAL_START,
        type: IntentNodeType = IntentNodeType.ATOMIC_TASK,
    ) = IntentNodeRecord(
        id = id,
        projectId = "proj",
        type = type,
        title = "Node $id",
        granularity = IntentGranularity.ATOMIC,
        lifecycle = IntentLifecycle.ACTIVE,
        actorKind = actor,
        dispatchPolicy = policy,
        targetProfile = IntentTargetProfile.ANY,
        minLocality = IntentMinLocality.LOCAL_ONLY,
        createdAt = "2026-11-18T00:00:00Z",
        createdByKind = "USER",
        createdById = "local",
        modifiedAt = "2026-11-18T00:00:00Z",
        modifiedByKind = "USER",
        modifiedById = "local",
    )

    /** DEPENDS_ON from prerequisite to dependent, the convention the intent graph stores. */
    private fun dependsOn(id: String, prerequisite: String, dependent: String) = IntentEdgeRecord(
        id = id,
        projectId = "proj",
        fromNodeId = prerequisite,
        toNodeId = dependent,
        edgeKind = IntentEdgeKind.DEPENDS_ON,
        createdAt = "2026-11-18T00:00:00Z",
    )

    private fun edgeOf(id: String, from: String, kind: IntentEdgeKind) = IntentEdgeRecord(
        id = id,
        projectId = "proj",
        fromNodeId = from,
        toNodeId = "other",
        edgeKind = kind,
        createdAt = "2026-11-18T00:00:00Z",
    )

    private fun snapshot(nodes: List<IntentNodeRecord>, edges: List<IntentEdgeRecord>) = GraphSnapshot(
        projectId = "proj",
        nodes = nodes,
        edges = edges,
        statuses = emptyMap(),
        proposals = emptyList(),
        loadedAt = 1L,
    )

    @Test
    fun wireIsEnergizedOnlyFromADoneSource() {
        val e = dependsOn("e", "a", "b")
        assertTrue(BreadboardSchematicPresenter.isEnergized(e, mapOf("a" to DerivedStatus.DONE)))
        assertFalse(BreadboardSchematicPresenter.isEnergized(e, mapOf("a" to DerivedStatus.IN_PROGRESS)))
        assertFalse(BreadboardSchematicPresenter.isEnergized(e, mapOf("a" to DerivedStatus.NEEDS_REVIEW)))
        assertFalse(BreadboardSchematicPresenter.isEnergized(e, emptyMap()))
    }

    @Test
    fun needsReviewDrawsADiodeGateAndBlocksItsWire() {
        val a = node("a")
        val b = node("b")
        val chips = BreadboardSchematicPresenter.buildCircuitChips(
            listOf(a, b),
            listOf(dependsOn("e", "a", "b")),
            mapOf("a" to DerivedStatus.NEEDS_REVIEW, "b" to DerivedStatus.BLOCKED),
        )
        assertTrue(chips[0].hasVerificationDiode)
        val wires = BreadboardLayout.wires(chips, BreadboardLayout.slots(chips))
        assertEquals(1, wires.size)
        assertFalse(wires.single().energized)
    }

    @Test
    fun columnsFollowDependencyDepthLeftToRight() {
        val a = node("a")
        val b = node("b")
        val c = node("c")
        val nodes = listOf(c, b, a)
        val edges = listOf(dependsOn("e1", "a", "b"), dependsOn("e2", "b", "c"))
        val depths = BreadboardSchematicPresenter.depthsOf(nodes, edges)
        assertEquals(0, depths["a"])
        assertEquals(1, depths["b"])
        assertEquals(2, depths["c"])
        assertEquals(2, BreadboardSchematicPresenter.columnOf(c, snapshot(nodes, edges)))
        val slots = BreadboardLayout.slots(BreadboardSchematicPresenter.buildCircuitChips(nodes, edges, emptyMap()))
        assertTrue(slots.getValue("a").x < slots.getValue("b").x)
        assertTrue(slots.getValue("b").x < slots.getValue("c").x)
    }

    @Test
    fun aDependencyCycleIsCutRatherThanFollowed() {
        val a = node("a")
        val b = node("b")
        val depths = BreadboardSchematicPresenter.depthsOf(listOf(a, b), listOf(dependsOn("e1", "a", "b"), dependsOn("e2", "b", "a")))
        assertTrue(depths.getValue("a") >= 0)
        assertTrue(depths.getValue("b") >= 0)
    }

    @Test
    fun humanNodeIsCompletedAndAgentNodeIsStarted() {
        val human = node("h", actor = IntentActorKind.HUMAN)
        val agent = node("g", actor = IntentActorKind.AGENT)
        assertEquals(ChipAction.COMPLETE, BreadboardSchematicPresenter.chipAction(human, DerivedStatus.NOT_STARTED))
        assertEquals(ChipAction.NONE, BreadboardSchematicPresenter.chipAction(human, DerivedStatus.DONE))
        assertEquals(ChipAction.NONE, BreadboardSchematicPresenter.chipAction(human, DerivedStatus.BLOCKED))
        assertEquals(ChipAction.START, BreadboardSchematicPresenter.chipAction(agent, DerivedStatus.NOT_STARTED))
        assertEquals(ChipAction.NONE, BreadboardSchematicPresenter.chipAction(agent, DerivedStatus.IN_PROGRESS))
    }

    @Test
    fun autoWhenReadyAgentIsNeverTappedAndHybridCompletesWhenReady() {
        val auto = node("a", actor = IntentActorKind.AGENT, policy = NodeDispatchPolicy.AUTO_WHEN_READY)
        assertEquals(ChipAction.NONE, BreadboardSchematicPresenter.chipAction(auto, DerivedStatus.NOT_STARTED))
        val hybrid = node("y", actor = IntentActorKind.HYBRID)
        // A hybrid that is ready is completed on tap; its Start is offered beside it in the strip.
        assertEquals(ChipAction.COMPLETE, BreadboardSchematicPresenter.chipAction(hybrid, DerivedStatus.IN_PROGRESS))
        assertTrue(BreadboardSchematicPresenter.startEnabled(hybrid, DerivedStatus.NOT_STARTED))
    }

    @Test
    fun chipCountsComeFromEdgesOwnedByTheNode() {
        val a = node("a")
        val edges = listOf(
            edgeOf("c1", "a", IntentEdgeKind.CONSTRAINS),
            edgeOf("c2", "a", IntentEdgeKind.CONSTRAINS),
            edgeOf("p1", "a", IntentEdgeKind.ACCEPTS),
            edgeOf("p2", "other", IntentEdgeKind.ACCEPTS),
        )
        val chip = BreadboardSchematicPresenter.buildCircuitChips(listOf(a), edges, emptyMap()).single()
        assertEquals(2, chip.constraintCount)
        assertEquals(1, chip.acceptsCount)
    }

    @Test
    fun stripStepsThroughReadyChipsAndWraps() {
        val a = node("a", actor = IntentActorKind.HUMAN)
        val b = node("b", actor = IntentActorKind.HUMAN)
        val c = node("c", actor = IntentActorKind.HUMAN)
        val chips = BreadboardSchematicPresenter.buildCircuitChips(
            listOf(a, b, c),
            emptyList(),
            mapOf("a" to DerivedStatus.DONE, "b" to DerivedStatus.NOT_STARTED, "c" to DerivedStatus.NOT_STARTED),
        )
        // a is DONE, so only b and c are ready.
        assertEquals("b", BreadboardSchematicPresenter.stripChip(chips, null)!!.node.id)
        assertEquals("c", BreadboardSchematicPresenter.stepReady(chips, "b", 1))
        assertEquals("b", BreadboardSchematicPresenter.stepReady(chips, "c", 1))
        assertEquals("c", BreadboardSchematicPresenter.stepReady(chips, "b", -1))
        assertEquals("b", BreadboardSchematicPresenter.stepReady(chips, null, 1))
    }

    @Test
    fun stripShowsNothingToStepWhenNoChipIsReady() {
        val a = node("a", actor = IntentActorKind.HUMAN)
        val chips = BreadboardSchematicPresenter.buildCircuitChips(listOf(a), emptyList(), mapOf("a" to DerivedStatus.DONE))
        assertTrue(BreadboardSchematicPresenter.readyChips(chips).isEmpty())
        assertEquals("a", BreadboardSchematicPresenter.stripChip(chips, null)!!.node.id)
        assertNull(BreadboardSchematicPresenter.stepReady(chips, null, 1))
    }

    @Test
    fun wireGeometryPutsInputPinsOnTheLeftAndOutputOnTheRight() {
        val a = node("a")
        val b = node("b")
        val chips = BreadboardSchematicPresenter.buildCircuitChips(listOf(a, b), listOf(dependsOn("e", "a", "b")), mapOf("a" to DerivedStatus.DONE))
        val slots = BreadboardLayout.slots(chips)
        val wire = BreadboardLayout.wires(chips, slots).single()
        assertEquals(slots.getValue("a").x + BreadboardLayout.CHIP_W, wire.from.x)
        assertEquals(slots.getValue("b").x, wire.to.x)
        assertTrue(wire.energized)
        val extent = BreadboardLayout.extent(slots)
        assertTrue(extent.x >= wire.to.x && extent.y >= wire.to.y)
    }
}
