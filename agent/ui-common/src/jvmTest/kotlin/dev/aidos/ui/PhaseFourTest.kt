package dev.aidos.ui

import dev.aidos.intent.DerivedStatus
import dev.aidos.intent.EffectiveDispatchPolicy
import dev.aidos.intent.IntentActorKind
import dev.aidos.intent.IntentGranularity
import dev.aidos.intent.IntentLifecycle
import dev.aidos.intent.IntentMinLocality
import dev.aidos.intent.IntentNodeRecord
import dev.aidos.intent.IntentNodeStatusView
import dev.aidos.intent.IntentNodeType
import dev.aidos.intent.IntentTargetProfile
import dev.aidos.intent.NodeDispatchPolicy
import dev.aidos.ui.paradigms.outliner.CausalOutlinerPresenter
import org.junit.Test
import kotlin.system.measureTimeMillis
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PhaseFourTest {

    @Test
    fun testOutlinerTreeHierarchyWith500NodesPerformance() {
        val totalNodes = 500
        val sampleNodes = mutableListOf<IntentNodeRecord>()
        val sampleStatuses = mutableMapOf<String, IntentNodeStatusView>()

        // 10 top-level goals, each with 49 subtasks
        for (i in 1..10) {
            val rootId = "goal-$i"
            val rootNode = createNode(rootId, "Root Goal #$i", null)
            sampleNodes.add(rootNode)
            sampleStatuses[rootId] = createStatus(rootNode, DerivedStatus.IN_PROGRESS)

            for (j in 1..49) {
                val childId = "subtask-$i-$j"
                val childNode = createNode(childId, "Subtask $i.$j", rootId)
                sampleNodes.add(childNode)
                sampleStatuses[childId] = createStatus(childNode, DerivedStatus.NOT_STARTED)
            }
        }

        assertEquals(500, sampleNodes.size)

        // Measure tree flattening and hierarchy build
        val elapsed = measureTimeMillis {
            val expandedAll = sampleNodes.map { it.id }.toSet()
            val flattened = CausalOutlinerPresenter.buildFlattenedTree(sampleNodes, sampleStatuses, expandedAll)
            assertEquals(500, flattened.size)
            assertEquals(0, flattened.first().depth)
            assertEquals(1, flattened[1].depth)
        }

        assertTrue(elapsed < 200, "Outliner flattening took too long: ${elapsed}ms")
    }

    private fun createNode(id: String, title: String, parentId: String?): IntentNodeRecord {
        return IntentNodeRecord(
            id = id,
            projectId = "project-tree",
            type = if (parentId == null) IntentNodeType.GOAL else IntentNodeType.ATOMIC_TASK,
            title = title,
            parentId = parentId,
            granularity = if (parentId == null) IntentGranularity.GOAL else IntentGranularity.ATOMIC,
            lifecycle = IntentLifecycle.ACTIVE,
            actorKind = IntentActorKind.AGENT,
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

    private fun createStatus(node: IntentNodeRecord, status: DerivedStatus): IntentNodeStatusView {
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
