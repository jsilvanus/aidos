package dev.aidos.aidin

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * RFC-0105 phase 3, the parts a person using a split model meets: chat turns reach the model with
 * their roles, a refused request does not take the model down, and a model whose helper went away
 * comes back when the helper does. Runs on the JVM stand-ins; [NativeSplitInferenceTest] covers the
 * same chat path on real llama.cpp.
 */
class SplitChatAndRecoveryTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val nodes = mutableListOf<AidinNode>()
    private val closables = mutableListOf<java.io.Closeable>()
    private val tempDirs = mutableListOf<File>()

    @AfterTest
    fun tearDown() {
        closables.forEach { runCatching { it.close() } }
        nodes.forEach { runCatching { it.stop() } }
        tempDirs.forEach { runCatching { it.deleteRecursively() } }
        scope.cancel()
    }

    private fun eventually(what: String, timeoutMillis: Long = 15_000, check: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (check()) return
            Thread.sleep(25)
        }
        throw AssertionError("timed out waiting for: $what")
    }

    private val buildId = HelperProcessSupervisor.DEFAULT_LLAMA_CPP_BUILD_ID

    private fun node() = AidinNode(
        key = SigningKey.generate(),
        stateStore = InMemoryAidinStateStore(),
        scope = scope,
        engineVersion = "test",
        tracker = PeerTracker(heartbeatIntervalMillis = 80, leaveAfterMillis = 2_000),
        heartbeatContent = {
            Heartbeat(
                protocolVersion = AIDIN_PROTOCOL_VERSION,
                engineVersion = "test",
                llamaCppBuild = buildId,
                contributionRamBytes = 4_000_000_000L,
                batteryPercent = 90,
                charging = true,
            )
        },
    ).also {
        it.start("127.0.0.1")
        nodes += it
    }

    private class Split(
        val anchorSup: AnchorProcessSupervisor,
        val helperSup: HelperProcessSupervisor,
        val plan: SplitPlan,
    )

    /** An anchor and one helper in a group, serving a split model on the stand-in programs. */
    private fun startSplit(helperBudget: () -> Long = { 4_000_000_000L }): Split {
        val anchor = node()
        val helper = node()
        val group = anchor.createGroup("Lab")
        anchor.enrol(helper.createInvite(listOf("127.0.0.1")), group)
        eventually("enrolment offered") { helper.pairingViews.value.isNotEmpty() }
        anchor.confirmPairing(anchor.pairingViews.value.last().id, "helper")
        helper.confirmPairing(helper.pairingViews.value.single().id, "Lab")
        eventually("anchor authorizes the helper to help in the group") {
            anchor.peers.value.any { it.nodeId == helper.nodeId && it.connected && it.lastHeartbeat != null } &&
                (anchor.authorize(helper.nodeId, Scope.HELP) as? TrustDecision.Allowed)?.domains?.contains(TrustDomain.Group(group)) == true
        }
        val helperSup = HelperProcessSupervisor(
            node = helper,
            cacheDir = Files.createTempDirectory("aidin-chat-cache").toFile().also { tempDirs += it },
            llamaCppBuildId = buildId,
            policyProvider = { ContributionPolicy(ramBudgetBytes = helperBudget()) },
        ).also { closables += it }
        val anchorSup = AnchorProcessSupervisor(anchor, scope, buildId).also { closables += it }
        val model = File.createTempFile("split-chat", ".gguf").also { tempDirs += it }
        val outcome = anchorSup.startSplitModel(
            domain = TrustDomain.Group(group),
            modelId = "split-chat",
            modelDigest = "sha256:split-chat",
            modelFile = model,
            totalModelBytes = 3_000_000_000L,
            blockCount = 24,
            anchorRamBudgetBytes = 2_000_000_000L,
        )
        return Split(anchorSup, helperSup, assertIs<SplitPlanOutcome.Planned>(outcome).plan)
    }

    @Test
    fun chatTurnsReachTheSplitModelWithTheirRolesAndTheAnswerCarriesUsage() {
        val split = startSplit()

        val outcome = split.anchorSup.chat(
            messages = listOf(
                SplitChatMessage("system", "Be brief."),
                SplitChatMessage("user", "Hello"),
            ),
            expectedPlanVersion = split.plan.version,
            maxTokens = 16,
        )

        val success = assertIs<SplitInferenceOutcome.Success>(outcome)
        // The stand-in echoes what it was given after the helper's pass: both turns, each with its role.
        assertTrue(success.text.endsWith("system:Be brief.|user:Hello"), "got: ${success.text}")
        assertTrue(success.promptTokens > 0 && success.completionTokens > 0, "usage missing: $success")
        assertEquals("stop", success.finishReason)

        assertIs<SplitInferenceOutcome.PlanChanged>(
            split.anchorSup.chat(listOf(SplitChatMessage("user", "Hello")), expectedPlanVersion = 999L)
        )
    }

    @Test
    fun aRequestTheServerRefusesDoesNotRestartTheSplit() {
        val split = startSplit()

        // No turns: llama-server answers 400. One bad request must not unload a healthy model.
        val refused = split.anchorSup.chat(emptyList(), expectedPlanVersion = split.plan.version)

        assertEquals(400, assertIs<SplitInferenceOutcome.Rejected>(refused).status)
        assertTrue(split.anchorSup.isAvailable)
        assertEquals(split.plan.version, split.anchorSup.activePlan?.version, "the split was redeployed")
        assertIs<SplitInferenceOutcome.Success>(
            split.anchorSup.chat(listOf(SplitChatMessage("user", "still here?")), expectedPlanVersion = split.plan.version)
        )
    }

    @Test
    fun aSplitWhoseHelperStoppedContributingComesBackWhenTheHelperDoes() {
        val helperBudget = AtomicLong(4_000_000_000L)
        val split = startSplit(helperBudget = { helperBudget.get() })

        // The helper stops contributing (say, unplugged): its RPC server goes, and it refuses new plans.
        helperBudget.set(0L)
        split.helperSup.stopRpcServer()
        assertFalse(
            split.anchorSup.chat(listOf(SplitChatMessage("user", "Hello")), split.plan.version) is SplitInferenceOutcome.Success
        )
        eventually("the split is reported unavailable") { !split.anchorSup.isAvailable }

        // RFC-0105: "re-plan with the remaining members". Nobody asks again; the anchor must notice.
        helperBudget.set(4_000_000_000L)
        eventually("the anchor redeploys once the helper contributes again") {
            split.anchorSup.isAvailable && (split.anchorSup.activePlan?.version ?: 0L) > split.plan.version
        }
        val version = split.anchorSup.activePlan!!.version
        assertIs<SplitInferenceOutcome.Success>(split.anchorSup.chat(listOf(SplitChatMessage("user", "Hello")), version))
    }
}
