package dev.aidos.aidin

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * RFC-0105 Phase 2 & 3 tests:
 * - Split-model planning (`SplitModelPlanner`), build ID matching, RAM headroom, and mobile contribution policy
 * - Group membership certificate renewal over `PeerLink`
 * - Supervised OS child-process layer splitting (`ggml-rpc-server` + `llama-server`) over mTLS tunnel
 *   with mid-run helper child process kill (`stopRpcServer()`) and `RESTART` re-planning
 */
class SplitInferenceTest {
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

    private fun findRot13Gguf(): File {
        var dir: File? = File(System.getProperty("user.dir")).canonicalFile
        while (dir != null) {
            val candidate = File(dir, "models/rot13-gguf/rot13.gguf")
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        val tmp = File.createTempFile("rot13-test", ".gguf").apply { deleteOnExit() }
        tmp.writeBytes("GGUF\u0003\u0000\u0000\u0000".encodeToByteArray() + ByteArray(256))
        return tmp
    }

    @Test
    fun splitPlannerComputesProportionalLayerSharesAndRefusesInsufficientRamOrMismatchedBuild() {
        val anchorId = SigningKey.generate().nodeId
        val h1 = SigningKey.generate().nodeId
        val h2 = SigningKey.generate().nodeId
        val hWrongBuild = SigningKey.generate().nodeId
        val domain = TrustDomain.Group(SigningKey.generate().let { GroupAuthority(it).groupId })

        val outcome = SplitModelPlanner.plan(
            planId = "plan-qwen",
            version = 1L,
            domain = domain,
            modelId = "qwen2.5-32b",
            modelDigest = "sha256:abc",
            totalModelBytes = 10_000_000_000L,
            blockCount = 60,
            kvCacheBytes = 1_000_000_000L,
            contextLength = 8192,
            llamaCppBuildId = "llama-build-1",
            anchorNodeId = anchorId,
            anchorRamBudgetBytes = 4_000_000_000L,
            candidates = listOf(
                HelperCandidate(
                    nodeId = h1,
                    state = PeerState.READY,
                    heartbeat = Heartbeat(
                        protocolVersion = AIDIN_PROTOCOL_VERSION,
                        engineVersion = "test",
                        llamaCppBuild = "llama-build-1",
                        contributionRamBytes = 4_000_000_000L,
                        batteryPercent = 80,
                        charging = false,
                        draining = false,
                    ),
                    helpDomains = setOf(domain),
                ),
                HelperCandidate(
                    nodeId = h2,
                    state = PeerState.READY,
                    heartbeat = Heartbeat(
                        protocolVersion = AIDIN_PROTOCOL_VERSION,
                        engineVersion = "test",
                        llamaCppBuild = "llama-build-1",
                        contributionRamBytes = 6_000_000_000L,
                        batteryPercent = 95,
                        charging = true,
                        draining = false,
                    ),
                    helpDomains = setOf(domain),
                ),
                HelperCandidate(
                    nodeId = hWrongBuild,
                    state = PeerState.READY,
                    heartbeat = Heartbeat(
                        protocolVersion = AIDIN_PROTOCOL_VERSION,
                        engineVersion = "test",
                        llamaCppBuild = "llama-build-DIFFERENT",
                        contributionRamBytes = 8_000_000_000L,
                        batteryPercent = 100,
                        charging = true,
                        draining = false,
                    ),
                    helpDomains = setOf(domain),
                ),
            ),
        )

        val planned = assertIs<SplitPlanOutcome.Planned>(outcome)
        val plan = planned.plan
        // hWrongBuild must be excluded because its llama.cpp build id does not match the anchor
        assertFalse(plan.helpers.any { it.nodeId == hWrongBuild })
        // h2 is charging and has more RAM so it is ordered first among helpers
        assertEquals(listOf(h2, h1), plan.helpers.map { it.nodeId })
        // Total layers assigned across helpers + anchor must equal blockCount (60)
        val anchorLayers = plan.anchorLayerEndExclusive - plan.anchorLayerStart
        assertEquals(60, anchorLayers + plan.helpers.sumOf { it.layerCount })
        assertEquals(3, plan.tensorSplitArg.split(",").size)

        // Insufficient combined RAM must return SplitPlanOutcome.Refused
        val insufficient = SplitModelPlanner.plan(
            planId = "plan-too-big",
            version = 1L,
            domain = domain,
            modelId = "llama-70b",
            modelDigest = "sha256:def",
            totalModelBytes = 40_000_000_000L,
            blockCount = 80,
            kvCacheBytes = 2_000_000_000L,
            contextLength = 4096,
            llamaCppBuildId = "llama-build-1",
            anchorNodeId = anchorId,
            anchorRamBudgetBytes = 1_000_000_000L,
            candidates = listOf(
                HelperCandidate(
                    nodeId = h1,
                    state = PeerState.READY,
                    heartbeat = Heartbeat(
                        protocolVersion = AIDIN_PROTOCOL_VERSION,
                        engineVersion = "test",
                        llamaCppBuild = "llama-build-1",
                        contributionRamBytes = 2_000_000_000L,
                        batteryPercent = 90,
                        charging = true,
                        draining = false,
                    ),
                    helpDomains = setOf(domain),
                ),
            ),
        )
        assertIs<SplitPlanOutcome.Refused>(insufficient)
    }

    @Test
    fun contributionPolicyDrainsOnUnplugLowBatteryOrSevereThermal() {
        val policy = ContributionPolicy(ramBudgetBytes = 2_000_000_000L, chargingOnly = true, minBatteryPercent = 30)
        assertFalse(policy.shouldDrain(batteryPercent = 50, charging = true, thermalStatus = null))
        // Unplugging when chargingOnly = true drains
        assertTrue(policy.shouldDrain(batteryPercent = 80, charging = false, thermalStatus = null))
        // Battery below threshold when not charging drains
        val unpluggedPolicy = ContributionPolicy(ramBudgetBytes = 2_000_000_000L, chargingOnly = false, minBatteryPercent = 30)
        assertTrue(unpluggedPolicy.shouldDrain(batteryPercent = 25, charging = false, thermalStatus = null))
        // THERMAL_STATUS_SEVERE drains immediately even while charging
        assertTrue(policy.shouldDrain(batteryPercent = 90, charging = true, thermalStatus = "THERMAL_STATUS_SEVERE"))
    }

    @Test
    fun groupAdminRenewsMemberCertificateOverPeerLinkBeforeExpiry() {
        val clock = AtomicLong(1_000_000L)
        val admin = AidinNode(
            key = SigningKey.generate(),
            stateStore = InMemoryAidinStateStore(),
            scope = scope,
            engineVersion = "test",
            clock = clock::get,
            tracker = PeerTracker(heartbeatIntervalMillis = 100, leaveAfterMillis = 2_000),
            certificateRenewalThresholdMillis = 25L * 24 * 60 * 60 * 1000,
        ).also {
            it.start("127.0.0.1")
            nodes += it
        }

        val member = AidinNode(
            key = SigningKey.generate(),
            stateStore = InMemoryAidinStateStore(),
            scope = scope,
            engineVersion = "test",
            clock = clock::get,
            tracker = PeerTracker(heartbeatIntervalMillis = 100, leaveAfterMillis = 2_000),
        ).also {
            it.start("127.0.0.1")
            nodes += it
        }

        val group = admin.createGroup("Home Cluster")
        admin.enrol(member.createInvite(listOf("127.0.0.1")), group)
        eventually("enrolment offered") { member.pairingViews.value.isNotEmpty() }
        admin.confirmPairing(admin.pairingViews.value.single().id, "member")
        member.confirmPairing(member.pairingViews.value.single().id, "Home Cluster")
        eventually("member joined") { member.groups().any { it.first == group } }
        eventually("admin connected to member") { admin.peers.value.any { it.nodeId == member.nodeId && it.connected } }

        val originalCert = member.ownCertificate(group)
        assertNotNull(originalCert)

        // Advance clock by 10 days and trigger renewal over PeerLink
        clock.addAndGet(10L * 24 * 60 * 60 * 1000)
        val renewed = admin.renewMemberCertificate(group, member.nodeId)
        assertNotNull(renewed)
        assertTrue(renewed.notAfterMillis > originalCert.notAfterMillis)

        eventually("member receives renewed certificate over PeerLink") {
            val current = member.ownCertificate(group)
            current != null && current.notAfterMillis == renewed.notAfterMillis
        }
    }

    @Test
    fun supervisedChildProcessesExecuteLayerSplitAndRecoverWhenHelperChildIsKilled() {
        val buildId = HelperProcessSupervisor.DEFAULT_LLAMA_CPP_BUILD_ID
        val rot13File = findRot13Gguf()

        val h1Beat = {
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
        val h2Beat = {
            Heartbeat(
                protocolVersion = AIDIN_PROTOCOL_VERSION,
                engineVersion = "test",
                llamaCppBuild = buildId,
                contributionRamBytes = 4_000_000_000L,
                batteryPercent = 95,
                charging = true,
                draining = false,
            )
        }

        val anchor = AidinNode(
            key = SigningKey.generate(),
            stateStore = InMemoryAidinStateStore(),
            scope = scope,
            engineVersion = "test",
            tracker = PeerTracker(heartbeatIntervalMillis = 80, leaveAfterMillis = 2_000),
            heartbeatContent = h1Beat,
        ).also {
            it.start("127.0.0.1")
            nodes += it
        }

        val helper1 = AidinNode(
            key = SigningKey.generate(),
            stateStore = InMemoryAidinStateStore(),
            scope = scope,
            engineVersion = "test",
            tracker = PeerTracker(heartbeatIntervalMillis = 80, leaveAfterMillis = 2_000),
            heartbeatContent = h1Beat,
        ).also {
            it.start("127.0.0.1")
            nodes += it
        }

        val helper2 = AidinNode(
            key = SigningKey.generate(),
            stateStore = InMemoryAidinStateStore(),
            scope = scope,
            engineVersion = "test",
            tracker = PeerTracker(heartbeatIntervalMillis = 80, leaveAfterMillis = 2_000),
            heartbeatContent = h2Beat,
        ).also {
            it.start("127.0.0.1")
            nodes += it
        }

        // Enrol helper1 and helper2 into anchor's group
        val group = anchor.createGroup("Lab")
        for (h in listOf(helper1, helper2)) {
            anchor.enrol(h.createInvite(listOf("127.0.0.1")), group)
            eventually("enrolment offered to ${h.nodeId}") { h.pairingViews.value.isNotEmpty() }
            anchor.confirmPairing(anchor.pairingViews.value.last().id, "helper")
            h.confirmPairing(h.pairingViews.value.single().id, "Lab")
            eventually("anchor connected to ${h.nodeId}") {
                anchor.peers.value.any { it.nodeId == h.nodeId && it.connected && it.lastHeartbeat != null }
            }
        }
        // Connect helper1 <-> helper2 so all group members know each other
        helper1.connect("127.0.0.1", helper2.port!!)
        eventually("helpers linked") {
            helper1.peers.value.any { it.nodeId == helper2.nodeId && it.connected }
        }

        val h1Cache = Files.createTempDirectory("aidin-h1-cache").toFile().also { tempDirs += it }
        val h2Cache = Files.createTempDirectory("aidin-h2-cache").toFile().also { tempDirs += it }

        val h1Sup = HelperProcessSupervisor(
            node = helper1,
            cacheDir = h1Cache,
            llamaCppBuildId = buildId,
            policyProvider = { ContributionPolicy(ramBudgetBytes = 4_000_000_000L, chargingOnly = true, minBatteryPercent = 20) },
        ).also { closables += it }

        val h2Sup = HelperProcessSupervisor(
            node = helper2,
            cacheDir = h2Cache,
            llamaCppBuildId = buildId,
            policyProvider = { ContributionPolicy(ramBudgetBytes = 4_000_000_000L, chargingOnly = true, minBatteryPercent = 20) },
        ).also { closables += it }

        val anchorSup = AnchorProcessSupervisor(
            node = anchor,
            scope = scope,
            llamaCppBuildId = buildId,
        ).also { closables += it }

        // Start a split model requiring 5 GB total (anchor has 2 GB budget, so both helpers are used when total is 7 GB, or 5 GB uses 1-2 helpers)
        val planOutcome = anchorSup.startSplitModel(
            domain = TrustDomain.Group(group),
            modelId = "rot13-split-70b",
            modelDigest = "sha256:rot13",
            modelFile = rot13File,
            totalModelBytes = 6_500_000_000L,
            blockCount = 32,
            kvCacheBytes = 500_000_000L,
            contextLength = 4096,
            anchorRamBudgetBytes = 2_000_000_000L,
        )
        val initialPlanned = assertIs<SplitPlanOutcome.Planned>(planOutcome)
        assertEquals(2, initialPlanned.plan.helpers.size)
        assertTrue(anchorSup.isAvailable)
        assertNotNull(h1Sup.rpcListenPort)
        assertNotNull(h2Sup.rpcListenPort)

        // Non-anchor peer attempting to open a tunnel to helper1 must be rejected
        val rejected = AtomicInteger(0)
        h1Sup.onTunnelOpen(
            fromPeer = helper2.nodeId,
            msg = LinkMessage.TunnelOpen("unauthorized-stream", initialPlanned.plan.planId, initialPlanned.plan.version),
            sendToPeer = { if (it is LinkMessage.TunnelClose) rejected.incrementAndGet() },
        )
        assertEquals(1, rejected.get())

        // Execute inference through the supervised anchor llama-server + helper ggml-rpc-server tunnel
        val firstRun = anchorSup.invoke("Hello World", expectedPlanVersion = initialPlanned.plan.version)
        val success1 = assertIs<SplitInferenceOutcome.Success>(firstRun)
        assertTrue(success1.text.startsWith("[split:rot13:") && success1.text.endsWith("Hello World"), "got: ${success1.text}")
        assertEquals(2, success1.plan.helpers.size)

        // Calling with a stale planVersion returns PlanChanged (409 plan_changed)
        val wrongVersion = anchorSup.invoke("Hello", expectedPlanVersion = 999L)
        assertIs<SplitInferenceOutcome.PlanChanged>(wrongVersion)

        // Now start a 2-node split model (4 GB total, anchor 2 GB + helper 4 GB) so when helper1 is killed,
        // anchor can RESTART with helper2 alone!
        val twoNodeOutcome = anchorSup.startSplitModel(
            domain = TrustDomain.Group(group),
            modelId = "rot13-split-14b",
            modelDigest = "sha256:rot13-14b",
            modelFile = rot13File,
            totalModelBytes = 3_500_000_000L,
            blockCount = 32,
            kvCacheBytes = 500_000_000L,
            contextLength = 4096,
            anchorRamBudgetBytes = 2_000_000_000L,
        )
        val twoNodePlanned = assertIs<SplitPlanOutcome.Planned>(twoNodeOutcome)
        val activeHelperId = twoNodePlanned.plan.helpers.single().nodeId
        val activeHelperNode = if (activeHelperId == helper1.nodeId) helper1 else helper2
        val activeHelperSup = if (activeHelperId == helper1.nodeId) h1Sup else h2Sup
        val remainingHelperNode = if (activeHelperId == helper1.nodeId) helper2 else helper1

        // Kill the active helper's ggml-rpc-server child process and stop that node:
        // Anchor must survive, detect the helper loss, and RESTART with the remaining helper!
        activeHelperSup.stopRpcServer()
        activeHelperNode.stop()

        eventually("anchor detects helper loss and re-plans with remaining helper") {
            val p = anchorSup.activePlan
            anchorSup.isAvailable && p != null && p.version > twoNodePlanned.plan.version &&
                p.helpers.map { it.nodeId } == listOf(remainingHelperNode.nodeId)
        }

        val replanned = anchorSup.activePlan!!
        assertNotEquals(twoNodePlanned.plan.version, replanned.version)
        val secondRun = anchorSup.invoke("Hello World", expectedPlanVersion = replanned.version)
        val success2 = assertIs<SplitInferenceOutcome.Success>(secondRun)
        assertTrue(success2.text.startsWith("[split:rot13:") && success2.text.endsWith("Hello World"), "got: ${success2.text}")
    }
}
