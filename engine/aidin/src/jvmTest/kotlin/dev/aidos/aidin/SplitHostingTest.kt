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
import kotlin.test.assertNull

/** RFC-0105 phase 3 as an Engine host wires it: [SplitHosting] on each node, heartbeats from it. */
class SplitHostingTest {
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

    private fun tempDir(prefix: String) = Files.createTempDirectory(prefix).toFile().also { tempDirs += it }

    private fun eventually(what: String, timeoutMillis: Long = 15_000, check: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (check()) return
            Thread.sleep(25)
        }
        throw AssertionError("timed out waiting for: $what")
    }

    /** A node whose heartbeat comes from its own [SplitHosting], as the desktop host builds it. */
    private fun hostedNode(binDir: File?): Pair<AidinNode, SplitHosting> {
        var hosting: SplitHosting? = null
        val node = AidinNode(
            key = SigningKey.generate(),
            stateStore = InMemoryAidinStateStore(),
            scope = scope,
            engineVersion = "test",
            tracker = PeerTracker(heartbeatIntervalMillis = 80, leaveAfterMillis = 2_000),
            heartbeatContent = {
                val base = Heartbeat(AIDIN_PROTOCOL_VERSION, "test")
                hosting?.heartbeat(base) ?: base
            },
        ).also { nodes += it }
        hosting = SplitHosting(node, scope, binDir, tempDir("aidin-hosting"), { ContributionPolicy(ramBudgetBytes = 1_000_000_000L) })
            .also { closables += it }
        node.start("127.0.0.1")
        return node to hosting
    }

    @Test
    fun withoutTheProgramsTheNodeNeitherAdvertisesNorStartsASplit() {
        val (_, hosting) = hostedNode(binDir = tempDir("empty-bin"))
        assertFalse(hosting.available)
        val base = Heartbeat(AIDIN_PROTOCOL_VERSION, "test")
        assertEquals(base, hosting.heartbeat(base))
        val outcome = hosting.start(GroupId("aaaaaaaaaaaaaaaaaaaaaaaaaa"), "m", "d", File("missing.gguf"), blockCount = 2)
        assertEquals(SplitHosting.NOT_INSTALLED, assertIs<SplitPlanOutcome.Refused>(outcome).reason)
        assertNull(hosting.activeModelId)
    }

    @Test
    fun aHostedSplitServesThroughTheGroupAndStops() {
        val binDir = System.getenv("AIDOS_LLAMA_RPC_DIR")?.let(::File)
        val (anchor, anchorHosting) = hostedNode(binDir)
        assumeTrue("AIDOS_LLAMA_RPC_DIR does not hold llama-server and ggml-rpc-server", anchorHosting.available)
        val (helper, _) = hostedNode(binDir)

        val group = anchor.createGroup("Lab")
        anchor.enrol(helper.createInvite(listOf("127.0.0.1")), group)
        eventually("enrolment offered") { helper.pairingViews.value.isNotEmpty() }
        anchor.confirmPairing(anchor.pairingViews.value.last().id, "helper")
        helper.confirmPairing(helper.pairingViews.value.single().id, "Lab")
        eventually("helper advertises its llama.cpp build") {
            anchor.peers.value.any { it.nodeId == helper.nodeId && it.lastHeartbeat?.llamaCppBuild == HelperProcessSupervisor.DEFAULT_LLAMA_CPP_BUILD_ID }
        }
        eventually("anchor grants the helper HELP in the group") {
            (anchor.authorize(helper.nodeId, Scope.HELP) as? TrustDecision.Allowed)?.domains?.contains(TrustDomain.Group(group)) == true
        }

        val model = rot13Fixture()
        val outcome = anchorHosting.start(group, "rot13", "gguf-bytes:${model.length()}", model, blockCount = 2, contextLength = 512)
        val plan = assertIs<SplitPlanOutcome.Planned>(outcome, "$outcome").plan
        assertEquals(listOf(helper.nodeId), plan.helpers.map { it.nodeId })
        assertEquals("rot13", anchorHosting.activeModelId)

        val answer = anchor.activeAnchorSupervisor!!.invoke("Hello", plan.version, maxTokens = 1, temperature = 0.0)
        assertEquals("b", assertIs<SplitInferenceOutcome.Success>(answer).text)

        anchorHosting.stop()
        assertNull(anchorHosting.activeModelId)
        assertEquals("Stopped rot13", anchorHosting.status.value)
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
}
