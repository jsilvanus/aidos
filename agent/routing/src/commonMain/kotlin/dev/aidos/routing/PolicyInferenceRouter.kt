package dev.aidos.routing

import dev.aidos.kernel.DeviceSignals
import dev.aidos.kernel.EmbeddingModelAdapter
import dev.aidos.kernel.InferenceRouter
import dev.aidos.kernel.ModelAdapter
import dev.aidos.kernel.ModelDescriptor
import dev.aidos.kernel.ModelKind
import dev.aidos.kernel.ModelRuntime
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
/**
 * Allowed inference locality tier for [RoutingPolicy] and per-Run / worker overrides (RFC-0104).
 */
enum class EnforcedLocality {
    /** Only local runtime models are permitted. */
    LOCAL,

    /** Local runtime models and trusted Aidos cluster adapters are permitted; cloud remote is forbidden. */
    CLUSTER,

    /** Local, cluster, and cloud remote adapters (subject to egress policy) are permitted. */
    REMOTE,
}

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
    /** Locality ceiling enforced when no per-call override is provided. */
    val enforcedLocality: EnforcedLocality = EnforcedLocality.REMOTE,
    /** Optional default preferred model ID across locality tiers. */
    val preferredModelId: String? = null,
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
    private val clusterAdapters: List<ModelAdapter> = emptyList(),
    private val modelRuntime: ModelRuntime? = null,
    private val admissionPolicy: ModelAdmissionPolicy? = null,
    private val deviceSignalsProvider: (() -> DeviceSignals)? = null,
    private val availableMemoryBytesProvider: (() -> Long)? = null,
    private val modelWorkingSetBytes: Map<String, Long> = emptyMap(),
) : InferenceRouter {

    private fun isLocalModelAdmitted(modelId: String): Boolean {
        val admission = admissionPolicy ?: return true
        val signals = deviceSignalsProvider?.invoke() ?: return true
        val availableMem = availableMemoryBytesProvider?.invoke() ?: return true
        val workingSet = modelWorkingSetBytes[modelId] ?: 0L
        return admission.evaluate(workingSet, availableMem, signals) is ModelAdmissionPolicy.AdmissionDecision.Admitted
    }

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

    override suspend fun select(kind: ModelKind, context: RoutingContext): RoutingDecision =
        selectWithOverride(kind = kind, context = context)

    /**
     * Selects an adapter for [kind] while optionally overriding the preferred model ID and/or
     * enforced locality ceiling for this specific Run, worker, or Guardian call.
     */
    suspend fun selectWithOverride(
        kind: ModelKind,
        context: RoutingContext,
        preferredModelId: String? = null,
        enforcedLocality: EnforcedLocality? = null,
    ): RoutingDecision {
        // MOBILE foreground service check (D24): a local inference requires foreground.
        if (context.profile == PlatformProfile.MOBILE &&
            !context.executionWindow.permitsLocalInference()
        ) {
            return RoutingDecision.ForegroundRequired
        }

        val effectiveLocality = enforcedLocality ?: policy.enforcedLocality
        val effectivePreferredModel = (preferredModelId ?: policy.preferredModelId)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        val minCtx = context.minimumContextWindow

        // 1. Candidates per locality tier filtered by ModelKind, minimum context window, and admission (RFC-0045).
        val localCandidates = localAdapters
            .filter { matchesKind(it, kind) }
            .filter { minCtx == null || it.contextWindow >= minCtx }
            .filter { isLocalModelAdmitted(it.modelId) }

        val runtimeInstalledLocal = modelRuntime?.installed()
            .orEmpty()
            .filter { it.isLocal && it.kind == kind && (minCtx == null || it.contextWindow >= minCtx) }
            .filter { isLocalModelAdmitted(it.id) }

        val clusterCandidates = clusterAdapters
            .filter { matchesKind(it, kind) }
            .filter { minCtx == null || it.contextWindow >= minCtx }

        val allRemoteKindCandidates = remoteAdapters
            .filter { matchesKind(it, kind) }
            .filter { minCtx == null || it.contextWindow >= minCtx }

        val remoteCandidates = allRemoteKindCandidates.filter { adapter ->
            policy.allowedRemoteModelIds.isEmpty() || adapter.modelId in policy.allowedRemoteModelIds
        }

        // 2. Explicit model preference across allowed locality tiers.
        if (effectivePreferredModel != null) {
            localCandidates.find { it.modelId == effectivePreferredModel }?.let {
                return RoutingDecision.Local(it)
            }
            runtimeInstalledLocal.find { it.id == effectivePreferredModel }?.let { descriptor ->
                modelRuntime?.load(descriptor.id)?.getOrNull()?.let { adapter ->
                    return RoutingDecision.Local(adapter)
                }
            }

            clusterCandidates.find { it.modelId == effectivePreferredModel }?.let { clusterAdapter ->
                return if (effectiveLocality == EnforcedLocality.LOCAL) {
                    RoutingDecision.DisabledByPolicy(
                        "Model locality enforced to LOCAL; preferred model '$effectivePreferredModel' is on cluster"
                    )
                } else if (!context.networkAvailable) {
                    RoutingDecision.UnavailableOffline(kind)
                } else {
                    RoutingDecision.RemoteApproved(clusterAdapter)
                }
            }

            allRemoteKindCandidates.find { it.modelId == effectivePreferredModel }?.let { remoteAdapter ->
                if (effectiveLocality != EnforcedLocality.REMOTE) {
                    return RoutingDecision.DisabledByPolicy(
                        "Model locality enforced to $effectiveLocality; preferred model '$effectivePreferredModel' requires remote egress"
                    )
                }
                if (policy.allowedRemoteModelIds.isNotEmpty() &&
                    remoteAdapter.modelId !in policy.allowedRemoteModelIds
                ) {
                    return RoutingDecision.DisabledByPolicy(
                        "Preferred model '$effectivePreferredModel' is not in allowedRemoteModelIds"
                    )
                }
                return selectFromRemoteCandidates(kind, context, listOf(remoteAdapter))
            }
        }

        // 3. Local tier (permitted under LOCAL, CLUSTER, and REMOTE).
        if (localCandidates.isNotEmpty()) {
            val preferred = policy.preferredLocal[kind]
            val adapter = if (preferred != null) {
                localCandidates.find { it.modelId == preferred } ?: localCandidates.first()
            } else {
                localCandidates.first()
            }
            return RoutingDecision.Local(adapter)
        }
        if (runtimeInstalledLocal.isNotEmpty() && modelRuntime != null) {
            val preferred = policy.preferredLocal[kind]
            val loadedIds = modelRuntime.loaded().toSet()
            val descriptor = (preferred?.let { p -> runtimeInstalledLocal.find { it.id == p } })
                ?: runtimeInstalledLocal.firstOrNull { it.id in loadedIds }
                ?: runtimeInstalledLocal.first()
            modelRuntime.load(descriptor.id).getOrNull()?.let { adapter ->
                return RoutingDecision.Local(adapter)
            }
        }

        if (effectiveLocality == EnforcedLocality.LOCAL) {
            return RoutingDecision.DisabledByPolicy("Model locality enforced to LOCAL")
        }

        // 4. Trusted Aidos cluster tier (permitted under CLUSTER and REMOTE).
        if (clusterCandidates.isNotEmpty()) {
            if (!context.networkAvailable) {
                return RoutingDecision.UnavailableOffline(kind)
            }
            return RoutingDecision.RemoteApproved(clusterCandidates.first())
        }

        if (effectiveLocality == EnforcedLocality.CLUSTER) {
            return RoutingDecision.DisabledByPolicy("Model locality enforced to CLUSTER")
        }

        // 5. Cloud remote tier (permitted only under REMOTE).
        return selectFromRemoteCandidates(kind, context, remoteCandidates)
    }

    private fun selectFromRemoteCandidates(
        kind: ModelKind,
        context: RoutingContext,
        remoteCandidates: List<ModelAdapter>,
    ): RoutingDecision {
        // Policy forbids remote entirely (M23: NEVER, or the ASK default).
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

        // Remote allowed by policy, but network is unavailable.
        if (!context.networkAvailable) {
            return RoutingDecision.UnavailableOffline(kind)
        }

        if (remoteCandidates.isEmpty()) {
            return RoutingDecision.DisabledByPolicy("No allowed remote adapter available for kind $kind")
        }

        // RFC-0028: Budget-aware routing. When budget is constrained, prefer cheaper models.
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

        // Tainted run crossing network boundary requires user confirmation (RFC-0027).
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
