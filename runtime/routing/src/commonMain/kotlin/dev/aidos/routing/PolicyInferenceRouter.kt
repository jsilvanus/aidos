package dev.aidos.routing

import dev.aidos.kernel.InferenceRouter
import dev.aidos.kernel.ModelAdapter
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
    /** Non-empty = allowlist; empty = allow any remote provider. */
    val allowedRemoteModelIds: Set<String> = emptySet(),
    /** Per-kind preferred local model ID. If null, the runtime picks the first loaded. */
    val preferredLocal: Map<ModelKind, String> = emptyMap(),
)

/**
 * [InferenceRouter] implementation driven by [RoutingPolicy] (RFC-0020, M23).
 *
 * Candidate resolution order:
 * 1. If a local model is loaded and matches the request, return [RoutingDecision.Local].
 * 2. If no local model exists AND allowRemote is false: [RoutingDecision.UnavailableOffline].
 * 3. If allowRemote is true and network is available: check taint and policy, then select remote.
 * 4. If taint is UNTRUSTED and the only option is egress: [RoutingDecision.RemotePendingApproval].
 * 5. MOBILE without a foreground service: [RoutingDecision.ForegroundRequired].
 */
class PolicyInferenceRouter(
    private val policy: RoutingPolicy,
    private val localAdapters: List<ModelAdapter> = emptyList(),
    private val remoteAdapters: List<ModelAdapter> = emptyList(),
) : InferenceRouter {

    override suspend fun select(kind: ModelKind, context: RoutingContext): RoutingDecision {
        // MOBILE foreground service check (D24): a local inference requires foreground.
        if (context.profile == PlatformProfile.MOBILE &&
            !context.executionWindow.permitsLocalInference()
        ) {
            return RoutingDecision.ForegroundRequired
        }

        // 1. Local candidates — filter by kind and minimumContextWindow.
        val localCandidates = localAdapters.filter { adapter ->
            adapter.modelId in (policy.preferredLocal.values.toSet()) ||
                    policy.preferredLocal[kind] == adapter.modelId ||
                    (policy.preferredLocal[kind] == null)
        }.filter { context.minimumContextWindow == null || it.contextWindow >= context.minimumContextWindow!! }

        if (localCandidates.isNotEmpty()) {
            // Prefer the user's preferred model for this kind if loaded.
            val preferred = policy.preferredLocal[kind]
            val adapter = if (preferred != null) {
                localCandidates.find { it.modelId == preferred } ?: localCandidates.first()
            } else {
                localCandidates.first()
            }
            return RoutingDecision.Local(adapter)
        }

        // 2. No local candidate available.
        if (!policy.allowRemote) {
            return RoutingDecision.UnavailableOffline(kind)
        }

        // 3. Remote, but network is unavailable.
        if (!context.networkAvailable) {
            return RoutingDecision.UnavailableOffline(kind)
        }

        // 4. Remote candidates — filtered by allowedRemoteModelIds (if non-empty = allowlist).
        val remoteCandidates = remoteAdapters
            .filter { context.minimumContextWindow == null || it.contextWindow >= context.minimumContextWindow!! }
            .filter { adapter ->
                policy.allowedRemoteModelIds.isEmpty() || adapter.modelId in policy.allowedRemoteModelIds
            }

        if (remoteCandidates.isEmpty()) {
            return RoutingDecision.DisabledByPolicy("No allowed remote adapter available for kind $kind")
        }

        val remoteAdapter = remoteCandidates.first()

        // 5. Tainted run crossing network boundary requires user confirmation (RFC-0027).
        return if (context.runTaint != TrustLevel.TRUSTED) {
            RoutingDecision.RemotePendingApproval(
                adapter = remoteAdapter,
                reason = "Run is tainted (${context.runTaint}); sending data off-device requires approval."
            )
        } else {
            RoutingDecision.RemoteApproved(remoteAdapter)
        }
    }
}
