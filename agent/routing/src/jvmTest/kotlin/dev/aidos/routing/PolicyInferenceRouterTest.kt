package dev.aidos.routing

import dev.aidos.kernel.Budget
import dev.aidos.kernel.EmbeddingModelAdapter
import dev.aidos.kernel.ExecutionWindow
import dev.aidos.kernel.ModelAdapter
import dev.aidos.kernel.ModelDescriptor
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
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

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

    private fun fakeAdapter(
        id: String,
        contextWindow: Int = 4096,
        isLocal: Boolean = false,
    ) = object : ModelAdapter {
        override val providerId = "test"
        override val modelId = id
        override val modelVersion = "1.0"
        override val contextWindow = contextWindow
        override val isLocal = isLocal
        override fun supportsNativeToolCalls() = false
        override suspend fun invoke(request: ModelRequest) = Result.success(ModelResponse(
            outputs = listOf(TextOutput("ok")),
            stopReason = StopReason.END_TURN,
            usage = Usage(inputTokens = 0, outputTokens = 0, totalTokens = 0),
            model = ModelRef(id = id, version = "1.0"),
        ))
    }

    private fun fakeEmbeddingAdapter(
        id: String,
        contextWindow: Int = 2048,
        isLocal: Boolean = true,
    ) = object : EmbeddingModelAdapter {
        override val providerId = "test"
        override val modelId = id
        override val modelVersion = "1.0"
        override val contextWindow = contextWindow
        override val isLocal = isLocal
        override fun supportsNativeToolCalls() = false
        override suspend fun invoke(request: ModelRequest) = Result.success(ModelResponse(
            outputs = listOf(TextOutput("ok")),
            stopReason = StopReason.END_TURN,
            usage = Usage(inputTokens = 0, outputTokens = 0, totalTokens = 0),
            model = ModelRef(id = id, version = "1.0"),
        ))
        override suspend fun embed(text: String) = Result.success(floatArrayOf(0.1f, 0.2f))
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
        budgetRemaining: Budget? = null,
    ) = RoutingContext(
        profile = profile,
        networkAvailable = networkAvailable,
        budgetRemaining = budgetRemaining,
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
    fun `PROJECT-tainted run (read ordinary project files) is not attenuated`() = runTest {
        val router = PolicyInferenceRouter(
            policy = RoutingPolicy(allowRemote = true),
            localAdapters = emptyList(),
            remoteAdapters = listOf(fakeAdapter("remote-claude")),
        )
        val result = router.select(ModelKind.LLM, ctx(runTaint = TrustLevel.PROJECT))
        assertIs<RoutingDecision.RemoteApproved>(result)
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

    // ─── ModelKind filtering ──────────────────────────────────────────────────────────────

    @Test
    fun `select filters local and remote adapters by ModelKind`() = runTest {
        val visionLocal = fakeAdapter("smolvlm", isLocal = true)
        val sttRemote = fakeAdapter("whisper-remote")
        val llmRemote = fakeAdapter("claude-3-5-haiku")
        val descriptors = mapOf(
            "smolvlm" to ModelDescriptor(
                id = "smolvlm",
                name = "SmolVLM",
                kind = ModelKind.VISION,
                providerId = "local",
                isLocal = true,
                contextWindow = 4096,
                sizeBytes = 512L * 1024 * 1024,
                digest = null,
            ),
            "whisper-remote" to ModelDescriptor(
                id = "whisper-remote",
                name = "Whisper",
                kind = ModelKind.STT,
                providerId = "openai",
                isLocal = false,
                contextWindow = 2048,
                sizeBytes = null,
                digest = null,
            ),
            "claude-3-5-haiku" to ModelDescriptor(
                id = "claude-3-5-haiku",
                name = "Haiku",
                kind = ModelKind.LLM,
                providerId = "anthropic",
                isLocal = false,
                contextWindow = 200_000,
                sizeBytes = null,
                digest = null,
            ),
        )
        val router = PolicyInferenceRouter(
            policy = RoutingPolicy(allowRemote = true),
            localAdapters = listOf(visionLocal),
            remoteAdapters = listOf(sttRemote, llmRemote),
            adapterDescriptors = descriptors,
        )

        val visionDecision = assertIs<RoutingDecision.Local>(router.select(ModelKind.VISION, ctx()))
        assertEquals("smolvlm", visionDecision.adapter.modelId)

        val sttDecision = assertIs<RoutingDecision.RemoteApproved>(router.select(ModelKind.STT, ctx()))
        assertEquals("whisper-remote", sttDecision.adapter.modelId)

        val llmDecision = assertIs<RoutingDecision.RemoteApproved>(router.select(ModelKind.LLM, ctx()))
        assertEquals("claude-3-5-haiku", llmDecision.adapter.modelId)

        val ocrDecision = assertIs<RoutingDecision.DisabledByPolicy>(router.select(ModelKind.OCR, ctx()))
        assertTrue(ocrDecision.reason.contains("OCR"))
    }

    @Test
    fun `EmbeddingModelAdapter matches EMBEDDING and is excluded from LLM`() = runTest {
        val embedder = fakeEmbeddingAdapter("nomic-embed")
        val router = PolicyInferenceRouter(
            policy = RoutingPolicy(allowRemote = false),
            localAdapters = listOf(embedder),
        )

        val llm = assertIs<RoutingDecision.UnavailableOffline>(router.select(ModelKind.LLM, ctx()))
        assertEquals(ModelKind.LLM, llm.kind)

        val emb = assertIs<RoutingDecision.Local>(router.select(ModelKind.EMBEDDING, ctx()))
        assertEquals("nomic-embed", emb.adapter.modelId)
    }

    @Test
    fun `preferredLocal kind isolates adapter from unrelated ModelKind requests`() = runTest {
        val decisionAdapter = fakeAdapter("router-decision", isLocal = true)
        val router = PolicyInferenceRouter(
            policy = RoutingPolicy(
                allowRemote = false,
                preferredLocal = mapOf(ModelKind.DECISION to "router-decision"),
            ),
            localAdapters = listOf(decisionAdapter),
        )

        assertIs<RoutingDecision.UnavailableOffline>(router.select(ModelKind.LLM, ctx()))
        val decision = assertIs<RoutingDecision.Local>(router.select(ModelKind.DECISION, ctx()))
        assertEquals("router-decision", decision.adapter.modelId)
    }

    @Test
    fun `ASK does not name wrong-kind remote adapter as approval candidate`() = runTest {
        val visionOnly = fakeAdapter("remote-vision")
        val router = PolicyInferenceRouter(
            policy = RoutingPolicy(allowRemote = false, remoteRequiresApproval = true),
            remoteAdapters = listOf(visionOnly),
            adapterKinds = mapOf("remote-vision" to ModelKind.VISION),
        )

        val llm = assertIs<RoutingDecision.UnavailableOffline>(router.select(ModelKind.LLM, ctx()))
        assertEquals(ModelKind.LLM, llm.kind)
    }

    // ─── Cost estimation & budget-aware routing ───────────────────────────────────────────

    @Test
    fun `estimateAdapterCost uses local zero, metadata, sizeBytes, and model tier instead of contextWindow`() {
        val local = fakeAdapter("local-huge-ctx", contextWindow = 128_000, isLocal = true)
        val cheapLargeCtx = fakeAdapter("gpt-4o-mini", contextWindow = 128_000, isLocal = false)
        val expensiveSmallCtx = fakeAdapter("gpt-4", contextWindow = 8_192, isLocal = false)
        val sizedA = fakeAdapter("model-a", contextWindow = 32_768, isLocal = false)
        val sizedB = fakeAdapter("model-b", contextWindow = 32_768, isLocal = false)
        val customPriced = fakeAdapter("custom-priced", contextWindow = 200_000, isLocal = false)

        val router = PolicyInferenceRouter(
            policy = RoutingPolicy(allowRemote = true),
            adapterDescriptors = mapOf(
                "model-a" to ModelDescriptor(
                    id = "model-a",
                    name = "A",
                    kind = ModelKind.LLM,
                    providerId = "test",
                    isLocal = false,
                    contextWindow = 32_768,
                    sizeBytes = 2L * 1024 * 1024 * 1024, // 2 GiB
                    digest = null,
                ),
                "model-b" to ModelDescriptor(
                    id = "model-b",
                    name = "B",
                    kind = ModelKind.LLM,
                    providerId = "test",
                    isLocal = false,
                    contextWindow = 32_768,
                    sizeBytes = 70L * 1024 * 1024 * 1024, // 70 GiB
                    digest = null,
                ),
                "custom-priced" to ModelDescriptor(
                    id = "custom-priced",
                    name = "Custom",
                    kind = ModelKind.LLM,
                    providerId = "test",
                    isLocal = false,
                    contextWindow = 200_000,
                    sizeBytes = null,
                    digest = null,
                    metadata = mapOf("cost_units" to "42"),
                ),
            ),
        )

        assertEquals(0L, router.estimateAdapterCost(local))
        assertTrue(
            router.estimateAdapterCost(cheapLargeCtx) < router.estimateAdapterCost(expensiveSmallCtx),
            "gpt-4o-mini (128k ctx) must cost less than gpt-4 (8k ctx)",
        )
        assertTrue(router.estimateAdapterCost(sizedA) < router.estimateAdapterCost(sizedB))
        assertEquals(42L, router.estimateAdapterCost(customPriced))
    }

    @Test
    fun `tight budget selects genuinely cheaper remote model even if it has larger context window`() = runTest {
        val expensive = fakeAdapter("gpt-4", contextWindow = 8_192)
        val cheap = fakeAdapter("gpt-4o-mini", contextWindow = 128_000)

        val router = PolicyInferenceRouter(
            policy = RoutingPolicy(allowRemote = true),
            remoteAdapters = listOf(expensive, cheap),
        )

        val ample = assertIs<RoutingDecision.RemoteApproved>(
            router.select(ModelKind.LLM, ctx(budgetRemaining = Budget(costUnits = 50_000L)))
        )
        assertEquals("gpt-4", ample.adapter.modelId)

        val tight = assertIs<RoutingDecision.RemoteApproved>(
            router.select(ModelKind.LLM, ctx(budgetRemaining = Budget(costUnits = 500L)))
        )
        assertEquals("gpt-4o-mini", tight.adapter.modelId)
    }

    @Test
    fun `explicit adapterCosts overrides heuristic during tight budget selection`() = runTest {
        val first = fakeAdapter("claude-3-5-haiku", contextWindow = 200_000)
        val second = fakeAdapter("partner-custom", contextWindow = 200_000)

        val router = PolicyInferenceRouter(
            policy = RoutingPolicy(allowRemote = true),
            remoteAdapters = listOf(first, second),
            adapterCosts = mapOf(
                "claude-3-5-haiku" to 500L,
                "partner-custom" to 10L,
            ),
        )

        val tight = assertIs<RoutingDecision.RemoteApproved>(
            router.select(ModelKind.LLM, ctx(budgetRemaining = Budget(costUnits = 100L)))
        )
        assertEquals("partner-custom", tight.adapter.modelId)
    }
}
