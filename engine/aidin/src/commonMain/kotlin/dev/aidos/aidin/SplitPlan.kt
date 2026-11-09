package dev.aidos.aidin

import kotlinx.serialization.Serializable
import kotlin.math.roundToInt

/**
 * RFC-0105 "Contribution policy (hard limits, per device)".
 *
 * Enforced by the node itself, not by the planner's goodwill: RAM budget, charging-only
 * (default on for phones), minimum battery (default 40%), thermal (drain at `THERMAL_STATUS_SEVERE`),
 * and per-group on/off. Crossing a limit moves the node to DRAINING.
 */
data class ContributionPolicy(
    val ramBudgetBytes: Long,
    val chargingOnly: Boolean = false,
    val minBatteryPercent: Int = 40,
    /** If non-null, only groups in this set may use this node's compute. */
    val enabledGroups: Set<GroupId>? = null,
) {
    fun shouldDrain(
        batteryPercent: Int? = null,
        charging: Boolean? = null,
        thermalStatus: String? = null,
        groupId: GroupId? = null,
    ): Boolean {
        if (ramBudgetBytes <= 0L) return true
        if (groupId != null && enabledGroups != null && groupId !in enabledGroups) return true
        if (thermalStatus != null && thermalStatus.uppercase() in SEVERE_THERMAL_STATUSES) return true
        if (chargingOnly && charging == false) return true
        if (batteryPercent != null && charging != true && batteryPercent < minBatteryPercent) return true
        return false
    }

    companion object {
        val SEVERE_THERMAL_STATUSES = setOf(
            "THERMAL_STATUS_SEVERE",
            "THERMAL_STATUS_CRITICAL",
            "THERMAL_STATUS_EMERGENCY",
            "THERMAL_STATUS_SHUTDOWN",
            "SEVERE",
            "CRITICAL",
            "EMERGENCY",
            "SHUTDOWN",
        )
    }
}

/** One helper's layer assignment and RAM share in a [SplitPlan]. */
data class HelperShare(
    val nodeId: NodeId,
    val layerStart: Int,
    val layerEndExclusive: Int,
    val allocatedRamBytes: Long,
    val tensorSplitWeight: Float,
) {
    val layerCount: Int get() = (layerEndExclusive - layerStart).coerceAtLeast(0)

    fun toWire(): WireHelperShare = WireHelperShare(
        nodeId = nodeId.value,
        layerStart = layerStart,
        layerEndExclusive = layerEndExclusive,
        allocatedRamBytes = allocatedRamBytes,
        tensorSplitWeight = tensorSplitWeight,
    )
}

fun WireHelperShare.toHelperShare(): HelperShare? = runCatching {
    HelperShare(
        nodeId = NodeId(nodeId),
        layerStart = layerStart,
        layerEndExclusive = layerEndExclusive,
        allocatedRamBytes = allocatedRamBytes,
        tensorSplitWeight = tensorSplitWeight,
    )
}.getOrNull()

/**
 * RFC-0105 "Plan": a versioned plan `{planId, version, groupId|pair, model digest, anchor,
 * helpers with layer shares, ctx, llama.cpp build id}`.
 */
data class SplitPlan(
    val planId: String,
    val version: Long,
    val domain: TrustDomain,
    val modelId: String,
    val modelDigest: String,
    val anchorNodeId: NodeId,
    val helpers: List<HelperShare>,
    val anchorLayerStart: Int,
    val anchorLayerEndExclusive: Int,
    val anchorAllocatedRamBytes: Long,
    /** Comma-separated proportions for `llama-server --tensor-split` (helpers in order, then anchor). */
    val tensorSplitArg: String,
    val contextLength: Int,
    val llamaCppBuildId: String,
) {
    /** Every node that sees activations for this plan (anchor + all helpers). */
    val participantNodeIds: Set<NodeId>
        get() = buildSet {
            add(anchorNodeId)
            helpers.forEach { add(it.nodeId) }
        }

    fun toWire(): WireSplitPlan = WireSplitPlan(
        planId = planId,
        version = version,
        domain = when (val d = domain) {
            is TrustDomain.Pairing -> "pair"
            is TrustDomain.Group -> "group:${d.groupId.value}"
        },
        modelId = modelId,
        modelDigest = modelDigest,
        anchorNodeId = anchorNodeId.value,
        helpers = helpers.map { it.toWire() },
        anchorLayerStart = anchorLayerStart,
        anchorLayerEndExclusive = anchorLayerEndExclusive,
        anchorAllocatedRamBytes = anchorAllocatedRamBytes,
        tensorSplitArg = tensorSplitArg,
        contextLength = contextLength,
        llamaCppBuildId = llamaCppBuildId,
    )
}

fun WireSplitPlan.toSplitPlan(): SplitPlan? = runCatching {
    val parsedDomain = when {
        domain == "pair" -> TrustDomain.Pairing
        domain.startsWith("group:") -> TrustDomain.Group(GroupId(domain.removePrefix("group:")))
        else -> return null
    }
    val parsedHelpers = helpers.map { it.toHelperShare() ?: return null }
    SplitPlan(
        planId = planId,
        version = version,
        domain = parsedDomain,
        modelId = modelId,
        modelDigest = modelDigest,
        anchorNodeId = NodeId(anchorNodeId),
        helpers = parsedHelpers,
        anchorLayerStart = anchorLayerStart,
        anchorLayerEndExclusive = anchorLayerEndExclusive,
        anchorAllocatedRamBytes = anchorAllocatedRamBytes,
        tensorSplitArg = tensorSplitArg,
        contextLength = contextLength,
        llamaCppBuildId = llamaCppBuildId,
    )
}.getOrNull()

/** Candidate helper evaluated by [SplitModelPlanner]. */
data class HelperCandidate(
    val nodeId: NodeId,
    val state: PeerState,
    val heartbeat: Heartbeat,
    /** Trust domains in which the anchor authorizes this peer for [Scope.HELP]. */
    val helpDomains: Set<TrustDomain>,
)

sealed interface SplitPlanOutcome {
    data class Planned(val plan: SplitPlan) : SplitPlanOutcome
    data class Refused(val reason: String) : SplitPlanOutcome
}

/**
 * RFC-0105 "Plan" & "Who may be in a plan":
 * - Reads model layer count, per-layer/total tensor bytes, and KV cache footprint for the chosen context.
 * - Filters helpers to those in the same [TrustDomain] with [Scope.HELP], matching [llamaCppBuildId],
 *   state `READY` or `ACTIVE`, and not `draining` / severe thermal.
 * - Picks the fewest helpers that fit, preferring charging / mains-powered nodes and larger RAM budgets.
 * - Refuses a plan that needs more RAM than the anchor + eligible helpers offer.
 */
object SplitModelPlanner {

    fun plan(
        planId: String,
        version: Long,
        domain: TrustDomain,
        modelId: String,
        modelDigest: String,
        totalModelBytes: Long,
        blockCount: Int,
        kvCacheBytes: Long = 0L,
        contextLength: Int = 4096,
        llamaCppBuildId: String,
        anchorNodeId: NodeId,
        anchorRamBudgetBytes: Long,
        candidates: List<HelperCandidate>,
    ): SplitPlanOutcome {
        require(version >= 1L) { "plan version must be positive" }
        require(blockCount >= 1) { "blockCount must be >= 1" }
        require(totalModelBytes > 0L) { "totalModelBytes must be > 0" }

        val totalRequiredBytes = totalModelBytes + kvCacheBytes.coerceAtLeast(0L)
        if (anchorRamBudgetBytes <= 0L) {
            return SplitPlanOutcome.Refused("Anchor $anchorNodeId offers 0 RAM budget")
        }

        // Filter candidates by trust domain (Scope.HELP), liveness, draining/thermal, and llama.cpp build id.
        val eligible = candidates.filter { c ->
            c.nodeId != anchorNodeId &&
                domain in c.helpDomains &&
                (c.state == PeerState.READY || c.state == PeerState.ACTIVE) &&
                !c.heartbeat.draining &&
                c.heartbeat.llamaCppBuild == llamaCppBuildId &&
                c.heartbeat.contributionRamBytes > 0L &&
                c.heartbeat.thermal?.uppercase() !in ContributionPolicy.SEVERE_THERMAL_STATUSES
        }.sortedWith(
            // Prefer charging / mains-powered (charging == true or null on desktop), then largest RAM budget
            compareByDescending<HelperCandidate> { it.heartbeat.charging != false }
                .thenByDescending { it.heartbeat.contributionRamBytes }
                .thenBy { it.nodeId.value }
        )

        // Pick the fewest helpers needed to cover totalRequiredBytes (at least 1 helper for a split plan).
        val selected = mutableListOf<HelperCandidate>()
        var accumulatedRam = anchorRamBudgetBytes
        for (candidate in eligible) {
            if (selected.isNotEmpty() && accumulatedRam >= totalRequiredBytes) break
            selected += candidate
            accumulatedRam += candidate.heartbeat.contributionRamBytes
        }

        if (selected.isEmpty()) {
            return SplitPlanOutcome.Refused("No eligible helper nodes with matching build '$llamaCppBuildId' in $domain")
        }
        if (accumulatedRam < totalRequiredBytes) {
            return SplitPlanOutcome.Refused(
                "Insufficient group RAM: required $totalRequiredBytes bytes, offered $accumulatedRam bytes across anchor + ${selected.size} helper(s)"
            )
        }
        if (selected.size + 1 > blockCount) {
            return SplitPlanOutcome.Refused(
                "Model has only $blockCount layer(s), fewer than the ${selected.size + 1} nodes required to fit it"
            )
        }

        // Cap each helper's used RAM to what is needed while keeping proportional shares.
        val participantBudgets = selected.map { it.heartbeat.contributionRamBytes } + anchorRamBudgetBytes
        val budgetSum = participantBudgets.sum().toDouble()
        val rawWeights = participantBudgets.map { (it.toDouble() / budgetSum).toFloat() }

        // Assign contiguous layer ranges [layerStart, layerEndExclusive) to each helper, then the anchor.
        val participantCount = selected.size + 1
        val layerCounts = IntArray(participantCount) { 1 }
        var remainingLayers = blockCount - participantCount
        if (remainingLayers > 0) {
            val extraByWeight = rawWeights.map { (it * remainingLayers).roundToInt() }.toIntArray()
            var assignedExtra = extraByWeight.sum()
            for (i in 0 until participantCount) {
                layerCounts[i] += extraByWeight[i]
            }
            // Adjust rounding residue on the largest-budget participant so sum == blockCount.
            var idx = 0
            while (assignedExtra < remainingLayers) {
                layerCounts[idx % participantCount]++
                assignedExtra++
                idx++
            }
            while (assignedExtra > remainingLayers) {
                val target = (0 until participantCount).firstOrNull { layerCounts[it] > 1 } ?: break
                layerCounts[target]--
                assignedExtra--
            }
        }

        var cursor = 0
        val helperShares = selected.mapIndexed { index, candidate ->
            val count = layerCounts[index]
            val start = cursor
            val end = cursor + count
            cursor = end
            val fraction = count.toDouble() / blockCount.toDouble()
            val allocatedBytes = ((totalRequiredBytes * fraction).toLong()).coerceAtLeast(1L)
            HelperShare(
                nodeId = candidate.nodeId,
                layerStart = start,
                layerEndExclusive = end,
                allocatedRamBytes = allocatedBytes,
                tensorSplitWeight = count.toFloat() / blockCount.toFloat(),
            )
        }
        val anchorStart = cursor
        val anchorEnd = blockCount
        val anchorCount = (anchorEnd - anchorStart).coerceAtLeast(1)
        val anchorAllocatedBytes = (totalRequiredBytes - helperShares.sumOf { it.allocatedRamBytes }).coerceAtLeast(1L)

        val tensorSplitArg = (helperShares.map { it.layerCount } + anchorCount).joinToString(",")

        return SplitPlanOutcome.Planned(
            SplitPlan(
                planId = planId,
                version = version,
                domain = domain,
                modelId = modelId,
                modelDigest = modelDigest,
                anchorNodeId = anchorNodeId,
                helpers = helperShares,
                anchorLayerStart = anchorStart,
                anchorLayerEndExclusive = anchorEnd,
                anchorAllocatedRamBytes = anchorAllocatedBytes.coerceAtMost(anchorRamBudgetBytes),
                tensorSplitArg = tensorSplitArg,
                contextLength = contextLength,
                llamaCppBuildId = llamaCppBuildId,
            )
        )
    }
}

/** Request body for `POST /aidin/v1/route` (RFC-0105 "Placement mapping for peers"). */
@Serializable
data class RouteQueryRequest(
    val model: String,
)

/**
 * Response body for `POST /aidin/v1/route`.
 *
 * Before sending any request content, the origin queries `/aidin/v1/route` for the model,
 * receives the current plan version and participating node IDs (anchor + helpers), and checks
 * every node against its own trust store.
 */
@Serializable
data class RouteInfo(
    val model: String,
    /** Placement as served by the peer itself: `local`, `cluster`, or `remote`. */
    val peerPlacement: String,
    val anchorNodeId: String,
    /** Empty when the peer serves a non-split model; populated with every helper's nodeId when split. */
    val helperNodeIds: List<String> = emptyList(),
    val planId: String? = null,
    val planVersion: Long? = null,
    /** Group ID if the split plan is scoped to a group, or null for pairwise/local. */
    val groupId: String? = null,
)
