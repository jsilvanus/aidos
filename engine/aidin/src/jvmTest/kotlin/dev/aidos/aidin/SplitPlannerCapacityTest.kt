package dev.aidos.aidin

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** RFC-0105 "Plan": a plan gives no node more than its contribution budget holds, counted in whole layers. */
class SplitPlannerCapacityTest {
    @Test
    fun plannerNeverGivesANodeMoreLayersThanItsBudgetHolds() {
        val anchorId = SigningKey.generate().nodeId
        val domain = TrustDomain.Group(GroupAuthority(SigningKey.generate()).groupId)
        fun helper(ram: Long) = HelperCandidate(
            nodeId = SigningKey.generate().nodeId,
            state = PeerState.READY,
            heartbeat = Heartbeat(AIDIN_PROTOCOL_VERSION, "test", llamaCppBuild = "b", contributionRamBytes = ram, charging = true),
            helpDomains = setOf(domain),
        )
        fun plan(model: Long, blocks: Int, anchor: Long, vararg helpers: Long) = SplitModelPlanner.plan(
            planId = "p", version = 1, domain = domain, modelId = "m", modelDigest = "d",
            totalModelBytes = model, blockCount = blocks, llamaCppBuildId = "b",
            anchorNodeId = anchorId, anchorRamBudgetBytes = anchor, candidates = helpers.map(::helper),
        )

        // A small anchor next to a big helper: sized by bytes alone, the anchor was handed half of
        // a two-layer model, 1.5 GB against a 1 GB budget.
        val lopsided = assertIs<SplitPlanOutcome.Planned>(plan(3_000_000_000L, 2, 1_000_000_000L, 4_000_000_000L)).plan
        assertEquals(2, lopsided.helpers.single().layerCount)
        assertEquals(0, lopsided.anchorLayerEndExclusive - lopsided.anchorLayerStart)
        assertEquals("2,0", lopsided.tensorSplitArg)

        // Whatever the mix, every share fits its node and the layers add up.
        val budgets = listOf(1_000_000_000L, 3_000_000_000L, 2_500_000_000L, 700_000_000L)
        for (blocks in listOf(6, 12, 24, 33, 80)) {
            val planned = plan(6_000_000_000L, blocks, 1_500_000_000L, *budgets.toLongArray())
            val p = assertIs<SplitPlanOutcome.Planned>(planned, "blocks=$blocks: $planned").plan
            val byNode = p.helpers.associateBy { it.nodeId }
            assertEquals(blocks, p.helpers.sumOf { it.layerCount } + (p.anchorLayerEndExclusive - p.anchorLayerStart))
            assertTrue(p.anchorAllocatedRamBytes <= 1_500_000_000L, "anchor over budget at blocks=$blocks")
            assertTrue(byNode.values.all { it.layerCount >= 1 && it.allocatedRamBytes <= 3_000_000_000L })
            assertEquals(p.helpers.size + 1, p.tensorSplitArg.split(",").size)
        }

        // Enough bytes in total, but not in whole layers: 2 layers of 1.1 GB, and only the helper holds one.
        assertIs<SplitPlanOutcome.Refused>(plan(2_200_000_000L, 2, 1_000_000_000L, 1_400_000_000L))
    }
}
