package dev.aidos.ui.views

import dev.aidos.intent.DerivedStatus
import dev.aidos.intent.IntentActorKind
import dev.aidos.intent.IntentEdgeKind
import dev.aidos.intent.IntentEdgeRecord
import dev.aidos.intent.IntentNodeRecord
import dev.aidos.intent.IntentNodeType
import dev.aidos.ui.spatial.schematic.MetroActorStyle
import dev.aidos.ui.spatial.schematic.MetroLineUiModel
import dev.aidos.ui.spatial.schematic.TransitMetroPresenter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** RFC-0111 Design 2 and RFC-0124 0124.5: Metro lines, station order, current stop, transfers, styles. */
class MetroViewTest {

    private fun node(
        id: String,
        type: IntentNodeType,
        parentId: String?,
        actor: IntentActorKind = IntentActorKind.AGENT,
        title: String = id,
    ) = IntentNodeRecord(
        id = id,
        projectId = "proj",
        type = type,
        title = title,
        parentId = parentId,
        actorKind = actor,
        createdAt = "2026-11-18T00:00:00Z",
        createdByKind = "user",
        createdById = "u",
        modifiedAt = "2026-11-18T00:00:00Z",
        modifiedByKind = "user",
        modifiedById = "u",
    )

    private fun depends(from: String, to: String) = IntentEdgeRecord(
        id = "$from->$to",
        projectId = "proj",
        fromNodeId = from,
        toNodeId = to,
        edgeKind = IntentEdgeKind.DEPENDS_ON,
        createdAt = "2026-11-18T00:00:00Z",
    )

    @Test
    fun linePerTopLevelGoalOrGroupWithDistinctColours() {
        val nodes = listOf(
            node("g1", IntentNodeType.GOAL, null),
            node("g2", IntentNodeType.GOAL, null),
            node("grp", IntentNodeType.GROUP, null),
            node("t1", IntentNodeType.ATOMIC_TASK, "g1"),
            node("t2", IntentNodeType.ATOMIC_TASK, "g2"),
        )
        val lines = TransitMetroPresenter.buildMetroLines(nodes, emptyMap())

        assertEquals(listOf("g1", "g2", "grp"), lines.map { it.lineId })
        assertEquals(3, lines.map { it.colorHex }.toSet().size)
    }

    @Test
    fun paletteHasEightColoursAndCycles() {
        assertEquals(8, TransitMetroPresenter.LINE_PALETTE.toSet().size)
        val nodes = (1..9).map { node("g$it", IntentNodeType.GOAL, null) }
        val lines = TransitMetroPresenter.buildMetroLines(nodes, emptyMap())

        assertEquals(TransitMetroPresenter.LINE_PALETTE[0], lines[8].colorHex)
    }

    @Test
    fun stationOrderFollowsDependenciesNotInputOrder() {
        val nodes = listOf(
            node("g", IntentNodeType.GOAL, null),
            node("t3", IntentNodeType.ATOMIC_TASK, "g"),
            node("t2", IntentNodeType.ATOMIC_TASK, "g"),
            node("t1", IntentNodeType.ATOMIC_TASK, "g"),
        )
        val edges = listOf(depends("t2", "t1"), depends("t3", "t2"))
        val line = TransitMetroPresenter.buildMetroLines(nodes, emptyMap(), edges).single()

        assertEquals(listOf("g", "t1", "t2", "t3"), line.stations.map { it.node.id })
    }

    @Test
    fun constraintsAndCriteriaAreNotStationsButMarkTheirTask() {
        val nodes = listOf(
            node("g", IntentNodeType.GOAL, null),
            node("t", IntentNodeType.ATOMIC_TASK, "g"),
            node("ac", IntentNodeType.ACCEPTANCE_CRITERION, "t"),
            node("c", IntentNodeType.CONSTRAINT, "g"),
        )
        val line = TransitMetroPresenter.buildMetroLines(nodes, emptyMap()).single()

        assertEquals(listOf("g", "t"), line.stations.map { it.node.id })
        assertTrue(line.stations.last().hasTurnstileGate)
    }

    @Test
    fun currentStationSkipsDoneStations() {
        val nodes = listOf(
            node("g", IntentNodeType.GOAL, null),
            node("t1", IntentNodeType.ATOMIC_TASK, "g"),
            node("t2", IntentNodeType.ATOMIC_TASK, "g"),
            node("t3", IntentNodeType.ATOMIC_TASK, "g"),
        )
        val edges = listOf(depends("t2", "t1"), depends("t3", "t2"))
        val statuses = mapOf(
            "g" to DerivedStatus.DONE,
            "t1" to DerivedStatus.DONE,
            "t2" to DerivedStatus.IN_PROGRESS,
            "t3" to DerivedStatus.NOT_STARTED,
        )
        val line = TransitMetroPresenter.buildMetroLines(nodes, statuses, edges).single()

        assertEquals("t2", TransitMetroPresenter.currentStation(line)?.node?.id)
        assertEquals(listOf("t3"), TransitMetroPresenter.upcomingStations(line, TransitMetroPresenter.currentStation(line)).map { it.node.id })
    }

    @Test
    fun currentStationIsNullWhenEveryStopIsDone() {
        val nodes = listOf(node("g", IntentNodeType.GOAL, null), node("t", IntentNodeType.ATOMIC_TASK, "g"))
        val statuses = mapOf("g" to DerivedStatus.DONE, "t" to DerivedStatus.DONE)
        val line = TransitMetroPresenter.buildMetroLines(nodes, statuses).single()

        assertNull(TransitMetroPresenter.currentStation(line))
    }

    @Test
    fun transferDetectedAcrossLines() {
        val nodes = listOf(
            node("gA", IntentNodeType.GOAL, null, title = "Database"),
            node("a1", IntentNodeType.ATOMIC_TASK, "gA"),
            node("gB", IntentNodeType.GOAL, null, title = "Deploy"),
            node("b1", IntentNodeType.ATOMIC_TASK, "gB"),
        )
        val lines = TransitMetroPresenter.buildMetroLines(nodes, emptyMap(), listOf(depends("b1", "a1")))
        val lineA = lines.first { it.lineId == "gA" }
        val lineB = lines.first { it.lineId == "gB" }

        assertEquals(listOf("gA"), TransitMetroPresenter.transfers(lineB, lines).map { it.lineId })
        assertTrue(TransitMetroPresenter.transfers(lineA, lines).isEmpty())
        val b1 = lineB.stations.first { it.node.id == "b1" }
        assertEquals("Database", TransitMetroPresenter.stationTransfers(b1, "gB", lines).single().title)
    }

    @Test
    fun stationStyleFollowsActor() {
        assertEquals(MetroActorStyle.SOLID, TransitMetroPresenter.stationStyle(node("h", IntentNodeType.ATOMIC_TASK, null, IntentActorKind.HUMAN)))
        assertEquals(MetroActorStyle.RING, TransitMetroPresenter.stationStyle(node("a", IntentNodeType.ATOMIC_TASK, null, IntentActorKind.AGENT)))
        assertEquals(MetroActorStyle.HALF_FILLED, TransitMetroPresenter.stationStyle(node("y", IntentNodeType.ATOMIC_TASK, null, IntentActorKind.HYBRID)))
    }

    @Test
    fun signalDotFollowsStatus() {
        assertEquals(0xFF22C55EL, TransitMetroPresenter.signalHex(DerivedStatus.DONE))
        assertEquals(0xFFEF4444L, TransitMetroPresenter.signalHex(DerivedStatus.BLOCKED))
        assertEquals(0xFFF59E0BL, TransitMetroPresenter.signalHex(DerivedStatus.NEEDS_REVIEW))
        assertNull(TransitMetroPresenter.signalHex(DerivedStatus.NOT_STARTED))
    }

    @Test
    fun orphanTasksLandOnUnfiledLineInsteadOfVanishing() {
        val nodes = listOf(node("g", IntentNodeType.GOAL, null), node("loose", IntentNodeType.ATOMIC_TASK, null))
        val lines: List<MetroLineUiModel> = TransitMetroPresenter.buildMetroLines(nodes, emptyMap())

        assertEquals(listOf("g", "unfiled"), lines.map { it.lineId })
        assertEquals(listOf("loose"), lines.last().stations.map { it.node.id })
    }
}
