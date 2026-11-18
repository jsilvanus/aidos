package dev.aidos.ui.views

import dev.aidos.intent.DerivedStatus
import dev.aidos.intent.EffectiveDispatchPolicy
import dev.aidos.intent.IntentActorKind
import dev.aidos.intent.IntentGranularity
import dev.aidos.intent.IntentLifecycle
import dev.aidos.intent.IntentNodeRecord
import dev.aidos.intent.IntentNodeStatusView
import dev.aidos.intent.IntentNodeType
import dev.aidos.intent.IntentTargetProfile
import dev.aidos.kernel.PlatformProfile
import dev.aidos.ui.layout.FormFactor
import dev.aidos.ui.spatial.radar.PlacedBlip
import dev.aidos.ui.spatial.radar.RadarFilter
import dev.aidos.ui.spatial.radar.RadarZone
import dev.aidos.ui.spatial.radar.SEALED_LIMIT
import dev.aidos.ui.spatial.radar.blipPosition
import dev.aidos.ui.spatial.radar.buildRadar
import dev.aidos.ui.spatial.radar.hitTest
import dev.aidos.ui.spatial.radar.profileForFormFactor
import dev.aidos.ui.spatial.radar.wedgeIndex
import dev.aidos.ui.spatial.radar.zoneOf
import dev.aidos.ui.workspace.GraphSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertNotNull

/** RFC-0111 Design 4: radar zones, wedges, placement, hit testing and filters. */
class RadarViewTest {

    private fun node(
        id: String,
        type: IntentNodeType = IntentNodeType.ATOMIC_TASK,
        parent: String? = null,
        actor: IntentActorKind = IntentActorKind.AGENT,
        target: IntentTargetProfile? = null,
        modifiedAt: String = "2026-11-18T00:00:00Z",
        lifecycle: IntentLifecycle = IntentLifecycle.ACTIVE,
    ) = IntentNodeRecord(
        id = id,
        projectId = "p",
        type = type,
        title = id,
        parentId = parent,
        actorKind = actor,
        targetProfile = target,
        granularity = IntentGranularity.ATOMIC,
        lifecycle = lifecycle,
        createdAt = "2026-11-01T00:00:00Z",
        createdByKind = "USER",
        createdById = "local",
        modifiedAt = modifiedAt,
        modifiedByKind = "USER",
        modifiedById = "local",
    )

    private fun status(n: IntentNodeRecord, derived: DerivedStatus, unmet: List<String> = emptyList()) =
        IntentNodeStatusView(
            node = n,
            derivedStatus = derived,
            effectiveDispatchPolicy = EffectiveDispatchPolicy.MANUAL_START,
            unmetDependencyIds = unmet,
            activeRunIds = emptyList(),
            pendingPlatformChecks = false,
        )

    private fun snapshot(nodes: List<IntentNodeRecord>, statuses: List<IntentNodeStatusView> = emptyList()) =
        GraphSnapshot(
            projectId = "p",
            nodes = nodes,
            edges = emptyList(),
            statuses = statuses.associateBy { it.node.id },
            proposals = emptyList(),
            loadedAt = 1L,
        )

    @Test
    fun zoneFollowsStatusAndUnmetDependencies() {
        assertEquals(RadarZone.STRIKE_ZONE, zoneOf(DerivedStatus.NOT_STARTED, emptyList()))
        assertEquals(RadarZone.HORIZON, zoneOf(DerivedStatus.NOT_STARTED, listOf("x")))
        assertEquals(RadarZone.HORIZON, zoneOf(DerivedStatus.BLOCKED, emptyList()))
        assertEquals(RadarZone.IN_FLIGHT, zoneOf(DerivedStatus.IN_PROGRESS, emptyList()))
        assertEquals(RadarZone.IN_FLIGHT, zoneOf(DerivedStatus.NEEDS_REVIEW, emptyList()))
        assertEquals(RadarZone.SEALED, zoneOf(DerivedStatus.DONE, emptyList()))
    }

    @Test
    fun wedgeIsTheTopGoalAncestor() {
        val a = node("goalA", IntentNodeType.GOAL)
        val inner = node("innerGoal", IntentNodeType.GOAL, parent = "goalA")
        val b = node("goalB", IntentNodeType.GOAL)
        val taskUnderInner = node("t1", parent = "innerGoal")
        val taskUnderB = node("t2", parent = "goalB")
        val orphan = node("t3")
        val snap = snapshot(listOf(a, inner, b, taskUnderInner, taskUnderB, orphan))

        // Top GOALs are goalA (index 0) and goalB (index 1); the nested GOAL is not a wedge of its own.
        assertEquals(0, wedgeIndex(taskUnderInner, snap))
        assertEquals(1, wedgeIndex(taskUnderB, snap))
        // A task with no GOAL ancestor goes to the extra wedge after the last top GOAL.
        assertEquals(2, wedgeIndex(orphan, snap))
    }

    @Test
    fun positionsStayInTheirZoneBand() {
        val bands = mapOf(
            RadarZone.STRIKE_ZONE to 0.10f..0.30f,
            RadarZone.IN_FLIGHT to 0.36f..0.56f,
            RadarZone.HORIZON to 0.62f..0.86f,
            RadarZone.SEALED to 0.92f..0.98f,
        )
        for ((zone, band) in bands) {
            for (i in 0 until 7) {
                val p = blipPosition(zone, wedge = 0, indexInWedge = i, count = 7)
                assertTrue(p.radius in band, "$zone radius ${p.radius} outside $band")
            }
        }
    }

    @Test
    fun horizonBlipsStayInsideTheirWedge() {
        val wedgeCount = 4
        for (wedge in 0 until wedgeCount) {
            for (i in 0 until 5) {
                val p = blipPosition(RadarZone.HORIZON, wedge, i, count = 5, wedgeCount = wedgeCount)
                assertTrue(p.fraction >= wedge.toFloat() / wedgeCount, "wedge $wedge fraction ${p.fraction}")
                assertTrue(p.fraction <= (wedge + 1).toFloat() / wedgeCount, "wedge $wedge fraction ${p.fraction}")
            }
        }
    }

    @Test
    fun hitTestPicksTheNearestBlipWithinReachOnly() {
        val blips = listOf(
            PlacedBlip("a", 100f, 100f),
            PlacedBlip("b", 110f, 100f),
            PlacedBlip("c", 300f, 300f),
        )
        assertEquals("a", hitTest(96f, 100f, blips, maxDistance = 24f)?.nodeId)
        assertEquals("b", hitTest(108f, 100f, blips, maxDistance = 24f)?.nodeId)
        assertEquals("c", hitTest(300f, 310f, blips, maxDistance = 24f)?.nodeId)
        assertNull(hitTest(200f, 200f, blips, maxDistance = 24f))
        assertNull(hitTest(100f, 130f, blips, maxDistance = 24f), "30 units away is beyond 24")
    }

    @Test
    fun sealedBlipsAreCappedAtThirtyMostRecent() {
        // done0 is the oldest and done39 the newest, so the 30 kept are done10 to done39.
        val nodes = (0 until 40).map { i -> node("done$i", modifiedAt = "2026-11-18T00:%02d:00Z".format(i)) }
        val statuses = nodes.map { status(it, DerivedStatus.DONE) }
        val model = buildRadar(snapshot(nodes, statuses), RadarFilter.ALL, device = null)
        val sealed = model.nodes.filter { it.zone == RadarZone.SEALED }
        assertEquals(SEALED_LIMIT, sealed.size)
        // The 30 kept are the most recently modified: the ones with the largest modifiedAt strings.
        assertEquals((10 until 40).map { "done$it" }.toSet(), sealed.map { it.node.id }.toSet())
    }

    @Test
    fun onlyTasksAndSubGoalsAreBlips() {
        val nodes = listOf(
            node("goal", IntentNodeType.GOAL),
            node("sub", IntentNodeType.SUB_GOAL),
            node("task"),
            node("criterion", IntentNodeType.ACCEPTANCE_CRITERION),
        )
        val model = buildRadar(snapshot(nodes), RadarFilter.ALL, device = null)
        assertEquals(setOf("sub", "task"), model.nodes.map { it.node.id }.toSet())
    }

    @Test
    fun tractorBeamsGoFromHorizonBlipsToUnmetDependenciesOnTheRadar() {
        val prereq = node("prereq")
        val waiting = node("waiting")
        val snap = snapshot(
            listOf(prereq, waiting),
            listOf(
                status(prereq, DerivedStatus.IN_PROGRESS),
                status(waiting, DerivedStatus.BLOCKED, unmet = listOf("prereq", "offRadar")),
            ),
        )
        val model = buildRadar(snap, RadarFilter.ALL, device = null)
        assertEquals(listOf("waiting" to "prereq"), model.beams.map { it.fromId to it.toId })
    }

    @Test
    fun mineAndAgentsFilterByActor() {
        val nodes = listOf(
            node("h", actor = IntentActorKind.HUMAN),
            node("a", actor = IntentActorKind.AGENT),
        )
        val snap = snapshot(nodes)
        assertEquals(listOf("h"), buildRadar(snap, RadarFilter.MINE, null).nodes.map { it.node.id })
        assertEquals(listOf("a"), buildRadar(snap, RadarFilter.AGENTS, null).nodes.map { it.node.id })
        assertEquals(2, buildRadar(snap, RadarFilter.ALL, null).nodes.size)
    }

    @Test
    fun thisDeviceMatchesTheTargetProfileOnly() {
        val nodes = listOf(
            node("m", target = IntentTargetProfile.MOBILE),
            node("d", target = IntentTargetProfile.DESKTOP),
            node("any", target = IntentTargetProfile.ANY),
            node("none"),
        )
        val snap = snapshot(nodes)
        val onPhone = buildRadar(snap, RadarFilter.THIS_DEVICE, PlatformProfile.MOBILE)
        assertEquals(listOf("m"), onPhone.nodes.map { it.node.id })
        // With no known platform, nothing matches the device filter yet.
        assertTrue(buildRadar(snap, RadarFilter.THIS_DEVICE, null).nodes.isEmpty())
    }

    @Test
    fun handheldsAreMobileAndDockedWindowsAreDesktop() {
        assertEquals(PlatformProfile.MOBILE, profileForFormFactor(FormFactor.HANDHELD_COMPACT))
        assertEquals(PlatformProfile.MOBILE, profileForFormFactor(FormFactor.HANDHELD_EXPANDED))
        assertEquals(PlatformProfile.DESKTOP, profileForFormFactor(FormFactor.DESKTOP_DOCKED))
    }

    @Test
    fun wedgesAreLabelledByTopGoalAndUnfiledIsAddedOnlyWhenNeeded() {
        val goal = node("g", IntentNodeType.GOAL)
        val task = node("t", parent = "g")
        val withoutOrphan = buildRadar(
            snapshot(listOf(goal, task), listOf(status(task, DerivedStatus.BLOCKED, unmet = listOf("z")))),
            RadarFilter.ALL, null,
        )
        assertEquals(listOf("g"), withoutOrphan.wedges.mapNotNull { it.goalId })
        assertNull(withoutOrphan.wedges.firstOrNull { it.goalId == null })

        val orphan = node("o")
        val withOrphan = buildRadar(
            snapshot(listOf(goal, orphan), listOf(status(orphan, DerivedStatus.BLOCKED, unmet = listOf("z")))),
            RadarFilter.ALL, null,
        )
        assertNotNull(withOrphan.wedges.firstOrNull { it.goalId == null })
    }
}
