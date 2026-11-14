package dev.aidos.routing

import dev.aidos.kernel.EmbeddingModelAdapter
import dev.aidos.kernel.InferenceRouter
import dev.aidos.kernel.ModelAdapter
import dev.aidos.kernel.ModelDescriptor
import dev.aidos.kernel.ModelKind
import dev.aidos.kernel.PlatformProfile
import dev.aidos.kernel.RoutingContext
import dev.aidos.kernel.RoutingDecision
import dev.aidos.kernel.TrustLevel

/**
 * User-owned routing policy (RFC-0020, RFC-0049, RFC-0023, M23).
 *
 * Routing is **never an engine heuristic**. Crossing the network boundary is not automatic
 * unless the user has said so. [UnavailableOffline] is the expected result when a local model
 * is not installed — it is not an error.
 *
 * Policy can forbid all remote models ([allowRemote] = false), allow only specific models
 * ([allowedRemoteModelIds] non-empty), or allow all remote models for a given kind.
 *
 * [RoutingPolicy] is serializable and stored in user settings. A change takes effect on the
 * next Run — not mid-Run (changing mid-Run would be a TOCTOU).
 */
data class RoutingPolicy(
    /** If false, remote models are never selected regardless of network availability. */
    val allowRemote: Boolean = false,
    /**
     * M23 (RFC-0020/0049/0023): true when [allowRemote] is false specifically because the
     * user's setting is `ASK` ("requires explicit approval per Run") rather than `NEVER`. No
     * per-Run approval flow is wired yet (RFC-0008 step 8d — see `AgentLoopTaskRunner`'s own doc
     * comment), so this still doesn't route automatically, but [PolicyInferenceRouter] reports it
     * as [RoutingDecision.RemotePendingApproval] instead of [RoutingDecision.UnavailableOffline]
     * — a distinct, honest signal ("approval is the missing piece") rather than looking identical
     * to an explicit `NEVER`. Meaningless when [allowRemote] is true.
     */
    val remoteRequiresApproval: Boolean = false,
    /** Non-empty = allowlist; empty = allow any remote provider. */
    val allowedRemoteModelIds: Set<String> = emptySet(),
    /** Per-kind preferred local model ID. If null, the runtime picks the first loaded. */
    val preferredLocal: Map<ModelKind, String> = emptyMap(),
)

/**
 * [InferenceRouter] implementation driven by [RoutingPolicy] (RFC-0020, M23).
 *
 * Candidate resolution order:
 * 1. MOBILE without a foreground service: [RoutingDecision.ForegroundRequired].
 * 2. If a local model is loaded and matches [kind] and context window, return [RoutingDecision.Local].
 * 3. If no local model exists AND allowRemote is false (NEVER, or the ASK default):
 *    [RoutingDecision.RemotePendingApproval] naming a candidate adapter if
 *    [RoutingPolicy.remoteRequiresApproval] (ASK) and one exists, else
 *    [RoutingDecision.UnavailableOffline].
 * 4. If allowRemote is true but network is unavailable: [RoutingDecision.UnavailableOffline].
 * 5. If allowRemote is true and network is up but no candidate matches [kind] and allowlist:
 *    [RoutingDecision.DisabledByPolicy].
 * 6. If taint is UNTRUSTED and the only option is egress: [RoutingDecision.RemotePendingApproval].
 * 7. Consider budget (RFC-0028): prefer cheaper/smaller models when budget is limited.
 * 8. Otherwise: [RoutingDecision.RemoteApproved].
 */
class PolicyInferenceRouter(
    private val policy: RoutingPolicy,
    private val localAdapters: List<ModelAdapter> = emptyList(),
    private val remoteAdapters: List<ModelAdapter> = emptyList(),
    private val adapterKinds: Map<String, ModelKind> = emptyMap(),
    private val adapterDescriptors: Map<String, ModelDescriptor> = emptyMap(),
    private val adapterCosts: Map<String, Long> = emptyMap(),
) : InferenceRouter {

    /**
     * Resolves the [ModelKind] served by [adapter] using descriptor metadata, explicit kind map,
     * typed kernel subtype ([EmbeddingModelAdapter]), or [RoutingPolicy.preferredLocal] hints.
     * Untyped generic [ModelAdapter]s default to [ModelKind.LLM].
     */
    private fun resolveAdapterKind(adapter: ModelAdapter): ModelKind? {
        adapterDescriptors[adapter.modelId]?.let { return it.kind }
        adapterKinds[adapter.modelId]?.let { return it }
        if (adapter is EmbeddingModelAdapter) return ModelKind.EMBEDDING
        return null
    }

    private fun matchesKind(adapter: ModelAdapter, requestedKind: ModelKind): Boolean {
        val explicit = resolveAdapterKind(adapter)
        if (explicit != null) return explicit == requestedKind

        val preferredKindsForId = policy.preferredLocal.entries
            .filter { it.value == adapter.modelId }
            .map { it.key }
        if (preferredKindsForId.isNotEmpty()) {
            return requestedKind in preferredKindsForId
        }

        // Plain untyped adapters (e.g. AnthropicAdapter / OpenAiHttpModelAdapter) serve chat LLMs.
        return requestedKind == ModelKind.LLM
    }

    /**
     * Estimates relative cost for an adapter (lower is cheaper).
     * Local adapters cost 0. For remote adapters, prefers explicit [adapterCosts], then
     * [ModelDescriptor] cost metadata / model weight size (`sizeBytes`), then model tier
     * heuristic — never [ModelAdapter.contextWindow].
     */
    internal fun estimateAdapterCost(adapter: ModelAdapter): Long {
        if (adapter.isLocal) return 0L

        adapterCosts[adapter.modelId]?.let { return it.coerceAtLeast(0L) }

        val descriptor = adapterDescriptors[adapter.modelId]
        if (descriptor != null) {
            metadataCost(descriptor.metadata)?.let { return it }
            val bytes = descriptor.sizeBytes
            if (bytes != null && bytes > 0L) {
                return (bytes / (1024L * 1024L)).coerceAtLeast(1L)
            }
        }

        val id = adapter.modelId.lowercase()
        val isOSeries = id == "o1" || id == "o3" ||
            id.startsWith("o1-") || id.startsWith("o3-") ||
            id.contains("/o1") || id.contains("/o3") ||
            id.contains("-o1") || id.contains("-o3")
        return when {
            id.contains("nano") || id.contains("micro") || id.contains("tiny") ||
                id.contains("-1b") || id.contains("-2b") || id.contains("-3b") -> 100L
            id.contains("mini") || id.contains("haiku") || id.contains("flash") ||
                id.contains("lite") || id.contains("small") || id.contains("fast") ||
                id.contains("-7b") || id.contains("-8b") -> 200L
            id.contains("opus") || id.contains("ultra") || id.contains("large") ||
                id.contains("frontier") || isOSeries ||
                (id.contains("gpt-4") && !id.contains("4o")) -> 5_000L
            id.contains("sonnet") || id.contains("medium") || id.contains("4o") ||
                id.contains("pro") -> 1_000L
            else -> 1_000L
        }
    }

    private fun metadataCost(metadata: Map<String, String>): Long? {
        for (key in DIRECT_COST_KEYS) {
            parseNumericCost(metadata[key])?.let { return it }
        }
        val inputCost = parseNumericCost(metadata["input_cost_per_million"])
        val outputCost = parseNumericCost(metadata["output_cost_per_million"])
        if (inputCost != null || outputCost != null) {
            return (inputCost ?: 0L) + (outputCost ?: 0L)
        }
        return null
    }

    private fun parseNumericCost(raw: String?): Long? {
        if (raw == null) return null
        raw.toLongOrNull()?.let { return it.coerceAtLeast(0L) }
        return raw.toDoubleOrNull()?.let { (it * 100.0).toLong().coerceAtLeast(0L) }
    }

    override suspend fun select(kind: ModelKind, context: RoutingContext): RoutingDecision {
        // MOBILE foreground service check (D24): a local inference requires foreground.
        if (context.profile == PlatformProfile.MOBILE &&
            !context.executionWindow.permitsLocalInference()
        ) {
            return RoutingDecision.ForegroundRequired
        }

        val minCtx = context.minimumContextWindow

        // 1. Local candidates — filtered by ModelKind and minimum context window.
        val localCandidates = localAdapters
            .filter { matchesKind(it, kind) }
            .filter { minCtx == null || it.contextWindow >= minCtx }

        if (localCandidates.isNotEmpty()) {
            val preferred = policy.preferredLocal[kind]
            val adapter = if (preferred != null) {
                localCandidates.find { it.modelId == preferred } ?: localCandidates.first()
            } else {
                localCandidates.first()
            }
            return RoutingDecision.Local(adapter)
        }

        // 2. Remote candidates — filtered by ModelKind, minimum context window, and allowlist.
        val remoteCandidates = remoteAdapters
            .filter { matchesKind(it, kind) }
            .filter { minCtx == null || it.contextWindow >= minCtx }
            .filter { adapter ->
                policy.allowedRemoteModelIds.isEmpty() || adapter.modelId in policy.allowedRemoteModelIds
            }

        // 3. Policy forbids remote entirely (M23: NEVER, or the ASK default).
        if (!policy.allowRemote) {
            val candidate = remoteCandidates.firstOrNull()
            return if (policy.remoteRequiresApproval && candidate != null) {
                RoutingDecision.RemotePendingApproval(
                    adapter = candidate,
                    reason = "Remote egress requires approval (ASK policy), but no per-Run " +
                        "approval flow is wired yet, so this fails rather than routing " +
                        "automatically.",
                )
            } else {
                RoutingDecision.UnavailableOffline(kind)
            }
        }

        // 4. Remote allowed by policy, but network is unavailable.
        if (!context.networkAvailable) {
            return RoutingDecision.UnavailableOffline(kind)
        }

        if (remoteCandidates.isEmpty()) {
            return RoutingDecision.DisabledByPolicy("No allowed remote adapter available for kind $kind")
        }

        // 5. RFC-0028: Budget-aware routing. When budget is constrained, prefer cheaper models.
        val budgetRemaining = context.budgetRemaining
        val costUnits = budgetRemaining?.costUnits
        val inputTokens = budgetRemaining?.inputTokens
        val outputTokens = budgetRemaining?.outputTokens
        val modelCalls = budgetRemaining?.modelCalls
        val isBudgetConstrained =
            (costUnits != null && costUnits <= 1000L) ||
                (inputTokens != null && inputTokens <= 10_000L) ||
                (outputTokens != null && outputTokens <= 4_000L) ||
                (modelCalls != null && modelCalls <= 2)

        val remoteAdapter = if (isBudgetConstrained) {
            remoteCandidates.minByOrNull { estimateAdapterCost(it) } ?: remoteCandidates.first()
        } else {
            remoteCandidates.first()
        }

        // 6. Tainted run crossing network boundary requires user confirmation (RFC-0027).
        return if (context.runTaint == TrustLevel.UNTRUSTED) {
            RoutingDecision.RemotePendingApproval(
                adapter = remoteAdapter,
                reason = "Run is tainted (${context.runTaint}); sending data off-device requires approval."
            )
        } else {
            RoutingDecision.RemoteApproved(remoteAdapter)
        }
    }

    private companion object {
        val DIRECT_COST_KEYS = listOf(
            "cost_units",
            "costUnits",
            "cost_per_call",
            "costPerCall",
            "cost_per_million_tokens",
            "costPerMillionTokens",
        )
    }
}
