package fi.italeino.aidos.engine.agent

import dev.aidos.aidin.AidinNode
import dev.aidos.aidin.LinkMessage
import dev.aidos.kernel.ModelAdapter
import fi.italeino.aidos.engine.inference.InferenceRequestManager
import fi.italeino.aidos.engine.inference.Locality
import fi.italeino.aidos.engine.inference.Placement
import fi.italeino.aidos.engine.inference.VirtualModelRouter
import kotlinx.coroutines.CoroutineScope

/**
 * The agent runtime of an Aidos Engine host, wired the same way on desktop and Android (D52: every
 * Engine serves agents). The loop's model goes through the virtual router (RFC-0107 phase 1):
 * `aidos:chat`, `peer:<node>/<m>` and `local:<m>` resolve as they do for `/v1/chat/completions`,
 * and a plain installed-model id keeps the direct in-process path.
 *
 * [router] and [aidin] are read lazily, because the HTTP server that owns the router is built after
 * the agent runtime it mounts.
 */
fun createEngineAgentRuntime(
    scope: CoroutineScope,
    manager: InferenceRequestManager,
    router: () -> VirtualModelRouter,
    aidin: () -> AidinNode?,
): AgentRuntime {
    fun modelAdapterFor(req: AgentTurnRequest, onPlacement: ((Placement) -> Unit)?): ModelAdapter {
        val locality = Locality.parse(req.locality.orEmpty()) ?: Locality.LOCAL_ONLY
        val contextWindow = req.contextWindow ?: 8192
        val r = router()
        return if (r.isVirtualModelId(req.model)) {
            RoutedModelAdapter(
                resolveRoutes = r::resolveRoutes,
                callLocal = { id, loc, request -> ManagedModelAdapter(manager, id, loc, contextWindow).invoke(request) },
                callPeer = { peer, endpoint, body, loc, plan ->
                    aidin()?.delegate(peer, endpoint, body, loc.wire, plan)
                        ?: LinkMessage.DelegateResponse(requestId = "", status = 503, bodyJson = "")
                },
                quarantine = { r.markRouteQuarantined(it) },
                modelId = req.model,
                locality = locality,
                contextWindow = contextWindow,
                onPlacementObserved = onPlacement,
            )
        } else {
            ManagedModelAdapter(manager, req.model, locality, contextWindow, onPlacementObserved = onPlacement)
        }
    }
    return AgentRuntime(
        scope = scope,
        adapterFor = { req -> modelAdapterFor(req, null) },
        adapterWithPlacement = { req, onPlacement -> modelAdapterFor(req, onPlacement) },
    )
}
