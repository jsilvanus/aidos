package dev.aidos.routing

import dev.aidos.kernel.ExecutionWindow
import dev.aidos.kernel.ModelAdapter
import dev.aidos.kernel.ModelKind
import dev.aidos.kernel.ModelRequest
import dev.aidos.kernel.ModelResponse
import dev.aidos.kernel.PlatformProfile
import dev.aidos.kernel.RoutingContext
import dev.aidos.kernel.RoutingDecision
import dev.aidos.kernel.StopReason
import dev.aidos.kernel.ModelRef
import dev.aidos.kernel.TextOutput
import dev.aidos.kernel.Usage
import dev.aidos.kernel.TrustLevel
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertIs

/**
 * M23 done-when (RFC-0020, RFC-0049, RFC-0023):
 *
 * 1. Routing is user-owned policy: crossing network boundary is never automatic unless
 *    the user said so.
 * 2. UnavailableOffline names the missing model kind and is not an error.
 * 3. Tainted Run requesting remote requires approval.
 * 4. Policy allowlist enforced: non-listed remote adapters are rejected.
 * 5. MOBILE without foreground service parks with ForegroundRequired (D24).
 */
class PolicyInferenceRouterTest {

    private fun fakeAdapter(id: String, contextWindow: Int = 4096) = object : ModelAdapter {
        override val providerId = "test"
        override val modelId = id
        override val modelVersion = "1.0"
        override val contextWindow = contextWindow
        override val isLocal = false
        override fun supportsNativeToolCalls() = false
        override suspend fun invoke(request: ModelRequest) = Result.success(ModelResponse(
            outputs = listOf(TextOutput("ok")),
            stopReason = StopReason.END_TURN,
            usage = Usage(inputTokens = 0, outputTokens = 0, totalTokens = 0),
            model = ModelRef(id = id, version = "1.0"),
        ))
    }

    private val unbounded = object : ExecutionWindow {
        override fun remainingMillis() = null
        override fun permitsLocalInference() = true
    }

    private val mobileWindow = object : ExecutionWindow {
        override fun remainingMillis() = null
        override fun permitsLocalInference() = false  // no foreground service
    }

    private fun ctx(
        profile: PlatformProfile = PlatformProfile.DESKTOP,
        networkAvailable: Boolean = true,
        runTaint: TrustLevel = TrustLevel.TRUSTED,
        window: ExecutionWindow = unbounded,
    ) = RoutingContext(
        profile = profile,
        networkAvailable = networkAvailable,
        budgetRemaining = null,
        runTaint = runTaint,
        executionWindow = window,
    )

    @Test
    fun `UnavailableOffline when no local model and allowRemote is false`() = runTest {
        val router = PolicyInferenceRouter(
            policy = RoutingPolicy(allowRemote = false),
            localAdapters = emptyList(),
        )
        val result = router.select(ModelKind.LLM, ctx())
        assertIs<RoutingDecision.UnavailableOffline>(result)
    }

    @Test
    fun `UnavailableOffline when allowRemote but network is off`() = runTest {
        val router = PolicyInferenceRouter(
            policy = RoutingPolicy(allowRemote = true),
            localAdapters = emptyList(),
            remoteAdapters = listOf(fakeAdapter("remote-a")),
        )
        val result = router.select(ModelKind.LLM, ctx(networkAvailable = false))
        assertIs<RoutingDecision.UnavailableOffline>(result)
    }

    @Test
    fun `local adapter selected when available`() = runTest {
        val local = fakeAdapter("local-7b")
        val router = PolicyInferenceRouter(
            policy = RoutingPolicy(allowRemote = false),
            localAdapters = listOf(local),
        )
        val result = router.select(ModelKind.LLM, ctx())
        assertIs<RoutingDecision.Local>(result)
    }

    @Test
    fun `remote approved when policy allows and network up and run is trusted`() = runTest {
        val router = PolicyInferenceRouter(
            policy = RoutingPolicy(allowRemote = true),
            localAdapters = emptyList(),
            remoteAdapters = listOf(fakeAdapter("remote-claude")),
        )
        val result = router.select(ModelKind.LLM, ctx(runTaint = TrustLevel.TRUSTED))
        assertIs<RoutingDecision.RemoteApproved>(result)
    }

    @Test
    fun `tainted run requests remote pending approval`() = runTest {
        val router = PolicyInferenceRouter(
            policy = RoutingPolicy(allowRemote = true),
            localAdapters = emptyList(),
            remoteAdapters = listOf(fakeAdapter("remote-claude")),
        )
        val result = router.select(
            ModelKind.LLM,
            ctx(runTaint = TrustLevel.UNTRUSTED),
        )
        assertIs<RoutingDecision.RemotePendingApproval>(result)
    }

    @Test
    fun `allowedRemoteModelIds allowlist enforced`() = runTest {
        val router = PolicyInferenceRouter(
            policy = RoutingPolicy(
                allowRemote = true,
                allowedRemoteModelIds = setOf("allowed-model"),
            ),
            localAdapters = emptyList(),
            remoteAdapters = listOf(fakeAdapter("not-allowed"), fakeAdapter("allowed-model")),
        )
        val result = router.select(ModelKind.LLM, ctx())
        assertIs<RoutingDecision.RemoteApproved>(result)
        // The selected adapter should be the allowed one.
        assert(result.adapter.modelId == "allowed-model")
    }

    @Test
    fun `disabledByPolicy when no allowed remote matches`() = runTest {
        val router = PolicyInferenceRouter(
            policy = RoutingPolicy(
                allowRemote = true,
                allowedRemoteModelIds = setOf("specific-model"),
            ),
            localAdapters = emptyList(),
            remoteAdapters = listOf(fakeAdapter("other-model")),
        )
        val result = router.select(ModelKind.LLM, ctx())
        assertIs<RoutingDecision.DisabledByPolicy>(result)
    }

    // ─── M23: ASK is distinguishable from NEVER, not identical ────────────────────────────────

    @Test
    fun `remoteRequiresApproval (ASK) returns RemotePendingApproval naming the missing approval flow`() = runTest {
        val router = PolicyInferenceRouter(
            policy = RoutingPolicy(allowRemote = false, remoteRequiresApproval = true),
            localAdapters = emptyList(),
            remoteAdapters = listOf(fakeAdapter("remote-claude")),
        )
        val result = router.select(ModelKind.LLM, ctx())
        val pending = assertIs<RoutingDecision.RemotePendingApproval>(result)
        assert(pending.adapter.modelId == "remote-claude")
        assert(pending.reason.contains("approval", ignoreCase = true)) { pending.reason }
    }

    @Test
    fun `NEVER (allowRemote false, remoteRequiresApproval false) stays UnavailableOffline even with a remote adapter present`() = runTest {
        // Same remote adapter available as the ASK test above -- the only difference is the
        // policy flag -- proving the two are genuinely distinguished, not just both denied.
        val router = PolicyInferenceRouter(
            policy = RoutingPolicy(allowRemote = false, remoteRequiresApproval = false),
            localAdapters = emptyList(),
            remoteAdapters = listOf(fakeAdapter("remote-claude")),
        )
        val result = router.select(ModelKind.LLM, ctx())
        assertIs<RoutingDecision.UnavailableOffline>(result)
    }

    @Test
    fun `remoteRequiresApproval with no remote adapter at all still falls back to UnavailableOffline`() = runTest {
        // ASK can't name an approval candidate that doesn't exist.
        val router = PolicyInferenceRouter(
            policy = RoutingPolicy(allowRemote = false, remoteRequiresApproval = true),
            localAdapters = emptyList(),
            remoteAdapters = emptyList(),
        )
        val result = router.select(ModelKind.LLM, ctx())
        assertIs<RoutingDecision.UnavailableOffline>(result)
    }

    @Test
    fun `MOBILE without foreground service returns ForegroundRequired`() = runTest {
        val local = fakeAdapter("local-7b")
        val router = PolicyInferenceRouter(
            policy = RoutingPolicy(allowRemote = false),
            localAdapters = listOf(local),
        )
        val result = router.select(
            ModelKind.LLM,
            ctx(profile = PlatformProfile.MOBILE, window = mobileWindow),
        )
        assertIs<RoutingDecision.ForegroundRequired>(result)
    }
}
