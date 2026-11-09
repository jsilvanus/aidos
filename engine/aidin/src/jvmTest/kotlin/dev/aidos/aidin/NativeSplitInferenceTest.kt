package dev.aidos.aidin

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assume.assumeTrue
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * RFC-0105 phase 3 against real llama.cpp: the pinned `llama-server` and `ggml-rpc-server` from the
 * `llama-rpc-native` CI build, with the helper's layers reached only through the AIDIN tunnel.
 *
 * [SplitInferenceTest] covers planning and supervision with the JVM stand-ins; this one proves the
 * same supervisors drive the real programs. It needs `AIDOS_LLAMA_RPC_DIR` pointing at a directory
 * holding both programs and is skipped (reported as skipped) without it.
 */
class NativeSplitInferenceTest {
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

    private fun rot13Fixture(): File {
        var dir: File? = File(System.getProperty("user.dir")).canonicalFile
        while (dir != null) {
            val candidate = File(dir, "models/rot13-gguf/rot13.gguf")
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        error("models/rot13-gguf/rot13.gguf not found")
    }

    private fun node(beat: () -> Heartbeat) = AidinNode(
        key = SigningKey.generate(),
        stateStore = InMemoryAidinStateStore(),
        scope = scope,
        engineVersion = "test",
        tracker = PeerTracker(heartbeatIntervalMillis = 80, leaveAfterMillis = 2_000),
        heartbeatContent = beat,
    ).also {
        it.start("127.0.0.1")
        nodes += it
    }

    @Test
    fun aRealLayerSplitAnswersThroughTheTunnelAndStopsWhenTheHelperDies() {
        val binDir = System.getenv("AIDOS_LLAMA_RPC_DIR")?.let(::File)
        val logs = Files.createTempDirectory("aidin-native-logs").toFile().also { tempDirs += it }
        val spawner = OsSplitProcessSpawner.fromDirectory(binDir, logDir = logs)
        assumeTrue("AIDOS_LLAMA_RPC_DIR does not hold llama-server and ggml-rpc-server", spawner.usesNativeBinaries)

        val buildId = HelperProcessSupervisor.DEFAULT_LLAMA_CPP_BUILD_ID
        val beat = {
            Heartbeat(
                protocolVersion = AIDIN_PROTOCOL_VERSION,
                engineVersion = "test",
                llamaCppBuild = buildId,
                contributionRamBytes = 4_000_000_000L,
                batteryPercent = 90,
                charging = true,
                draining = false,
            )
        }
        val anchor = node(beat)
        val helper = node(beat)
        val group = anchor.createGroup("Lab")
        anchor.enrol(helper.createInvite(listOf("127.0.0.1")), group)
        eventually("enrolment offered") { helper.pairingViews.value.isNotEmpty() }
        anchor.confirmPairing(anchor.pairingViews.value.last().id, "helper")
        helper.confirmPairing(helper.pairingViews.value.single().id, "Lab")
        eventually("anchor sees the helper's heartbeat") {
            anchor.peers.value.any { it.nodeId == helper.nodeId && it.connected && it.lastHeartbeat != null }
        }
        // The heartbeat can arrive before the group certificate does; plan only once the anchor
        // grants the helper HELP in the group.
        eventually("anchor authorizes the helper to help in the group") {
            (anchor.authorize(helper.nodeId, Scope.HELP) as? TrustDecision.Allowed)?.domains?.contains(TrustDomain.Group(group)) == true
        }

        val cache = Files.createTempDirectory("aidin-native-cache").toFile().also { tempDirs += it }
        val helperSup = HelperProcessSupervisor(
            node = helper,
            cacheDir = cache,
            llamaCppBuildId = buildId,
            spawner = spawner,
            policyProvider = { ContributionPolicy(ramBudgetBytes = 4_000_000_000L) },
        ).also { closables += it }
        val anchorSup = AnchorProcessSupervisor(
            node = anchor,
            scope = scope,
            llamaCppBuildId = buildId,
            spawner = spawner,
        ).also { closables += it }

        // Declared too big for the anchor alone, so the planner must give the helper layers.
        val outcome = anchorSup.startSplitModel(
                domain = TrustDomain.Group(group),
                modelId = "rot13-split",
                modelDigest = "sha256:rot13",
                modelFile = rot13Fixture(),
                totalModelBytes = 3_000_000_000L,
                // The fixture has one transformer block; planning it as two gives each node a
                // share, and offloading the helper's share puts that block on the helper over RPC
                // while the anchor keeps the rest.
                blockCount = 2,
                contextLength = 512,
                anchorRamBudgetBytes = 1_000_000_000L,
            )
        val planned = assertIs<SplitPlanOutcome.Planned>(outcome, "plan refused: $outcome; logs: ${logs.listFiles()?.joinToString { it.name + ": " + it.readText().takeLast(1500) }}")
        assertEquals(listOf(helper.nodeId), planned.plan.helpers.map { it.nodeId })
        assertTrue(helperSup.isRpcProcessAlive, "the helper's ggml-rpc-server is not running")

        // The fixture answers rot13 of the prompt's last byte: 'o' -> 'b'. Getting it means the
        // helper's layers really computed over the tunnel.
        val answer = anchorSup.invoke("Hello", planned.plan.version, maxTokens = 1, temperature = 0.0)
        assertEquals("b", assertIs<SplitInferenceOutcome.Success>(answer, "logs in $logs").text)

        // Losing the only helper takes the model down; it must not answer from a partial model.
        helperSup.stopRpcServer()
        val after = anchorSup.invoke("Hello", planned.plan.version, maxTokens = 1, temperature = 0.0)
        assertFalse(after is SplitInferenceOutcome.Success && after.text == "b", "answered after its helper died: $after")
    }
}
