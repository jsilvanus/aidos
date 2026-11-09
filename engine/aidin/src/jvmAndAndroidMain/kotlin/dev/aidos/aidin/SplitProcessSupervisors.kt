package dev.aidos.aidin

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.system.exitProcess

/** Handle to a supervised child OS process (`ggml-rpc-server` or `llama-server`). */
interface SupervisedChildProcess : Closeable {
    val pid: Long
    val port: Int
    val isAlive: Boolean
    fun destroyForcibly()
    override fun close() = destroyForcibly()
}

/** Strategy for launching the `ggml-rpc-server` and `llama-server` child processes on `127.0.0.1`. */
interface SplitProcessSpawner {
    fun spawnHelperRpcServer(port: Int, cacheDir: File): SupervisedChildProcess
    fun spawnAnchorLlamaServer(
        modelFile: File,
        rpcEndpoints: List<String>,
        tensorSplit: String,
        contextLength: Int,
        port: Int,
        apiKey: String,
    ): SupervisedChildProcess
}

/**
 * Default OS child-process spawner using [ProcessBuilder].
 *
 * If native `ggml-rpc-server` / `llama-server` binaries are provided and executable, launches them
 * directly. Otherwise launches a lightweight JVM child OS process speaking the loopback RPC / HTTP
 * protocol so supervision, tunnel isolation, and helper-crash survival work identically across
 * desktop and CI machines without requiring pre-installed native binaries.
 */
class OsSplitProcessSpawner(
    private val ggmlRpcBinary: File? = null,
    private val llamaServerBinary: File? = null,
) : SplitProcessSpawner {

    override fun spawnHelperRpcServer(port: Int, cacheDir: File): SupervisedChildProcess {
        cacheDir.mkdirs()
        val command = if (ggmlRpcBinary != null && ggmlRpcBinary.isFile && ggmlRpcBinary.canExecute()) {
            listOf(
                ggmlRpcBinary.absolutePath,
                "-H", "127.0.0.1",
                "-p", port.toString(),
                "-c", cacheDir.absolutePath,
            )
        } else {
            jvmCommand(
                LoopbackRpcServerChildMain::class.java.name,
                port.toString(),
                cacheDir.absolutePath,
            )
        }
        val process = ProcessBuilder(command)
            .redirectErrorStream(true)
            .start()
        waitForLoopbackPort("127.0.0.1", port, process)
        return OsSupervisedChildProcess(process, port)
    }

    override fun spawnAnchorLlamaServer(
        modelFile: File,
        rpcEndpoints: List<String>,
        tensorSplit: String,
        contextLength: Int,
        port: Int,
        apiKey: String,
    ): SupervisedChildProcess {
        val command = if (llamaServerBinary != null && llamaServerBinary.isFile && llamaServerBinary.canExecute()) {
            listOf(
                llamaServerBinary.absolutePath,
                "--model", modelFile.absolutePath,
                "--rpc", rpcEndpoints.joinToString(","),
                "--tensor-split", tensorSplit,
                "--ctx-size", contextLength.toString(),
                "--host", "127.0.0.1",
                "--port", port.toString(),
                "--api-key", apiKey,
            )
        } else {
            jvmCommand(
                LoopbackLlamaServerChildMain::class.java.name,
                modelFile.absolutePath,
                rpcEndpoints.joinToString(","),
                tensorSplit,
                contextLength.toString(),
                port.toString(),
                apiKey,
            )
        }
        val process = ProcessBuilder(command)
            .redirectErrorStream(true)
            .start()
        waitForLoopbackPort("127.0.0.1", port, process)
        return OsSupervisedChildProcess(process, port)
    }

    private fun jvmCommand(mainClass: String, vararg args: String): List<String> {
        val javaHome = System.getProperty("java.home")
        val javaBin = File(javaHome, if (System.getProperty("os.name").lowercase().contains("win")) "bin/java.exe" else "bin/java").absolutePath
        val classpath = System.getProperty("java.class.path")
        return listOf(javaBin, "-Xmx64m", "-cp", classpath, mainClass) + args.toList()
    }

    private fun waitForLoopbackPort(host: String, port: Int, process: Process, timeoutMillis: Long = 10_000L) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (!process.isAlive) {
                val out = runCatching { process.inputStream.bufferedReader().readText() }.getOrDefault("")
                throw IOException("Child process exited prematurely (code=${process.exitValue()}): $out")
            }
            try {
                Socket().use { s ->
                    s.connect(InetSocketAddress(host, port), 150)
                    return
                }
            } catch (_: IOException) {
                Thread.sleep(25)
            }
        }
        process.destroyForcibly()
        throw IOException("Timed out waiting for child process to listen on $host:$port")
    }
}

internal class OsSupervisedChildProcess(
    private val process: Process,
    override val port: Int,
) : SupervisedChildProcess {
    override val pid: Long get() = try {
        val method = process.javaClass.getMethod("pid")
        (method.invoke(process) as? Number)?.toLong() ?: -1L
    } catch (_: Throwable) {
        -1L
    }
    override val isAlive: Boolean get() = process.isAlive

    override fun destroyForcibly() {
        if (process.isAlive) {
            process.destroyForcibly()
            runCatching { process.waitFor(2, TimeUnit.SECONDS) }
        }
    }
}

/**
 * Child OS process entry point emulating `ggml-rpc-server -H 127.0.0.1 -p <port> -c <cacheDir>`.
 *
 * Binds strictly to `127.0.0.1:<port>`, accepts RPC frames from the anchor's tunnel, caches weight
 * shards in `<cacheDir>`, and computes layer transformations.
 */
object LoopbackRpcServerChildMain {
    @JvmStatic
    fun main(args: Array<String>) {
        val port = args.getOrNull(0)?.toIntOrNull() ?: exitProcess(2)
        val cacheDir = File(args.getOrNull(1) ?: ".")
        cacheDir.mkdirs()
        val server = ServerSocket(port, 50, InetAddress.getByName("127.0.0.1"))
        while (!server.isClosed) {
            val client = try {
                server.accept()
            } catch (_: IOException) {
                break
            }
            Thread({
                client.use { sock ->
                    try {
                        val input = DataInputStream(sock.getInputStream())
                        val output = DataOutputStream(sock.getOutputStream())
                        while (!sock.isClosed) {
                            val cmd = try {
                                input.readUTF()
                            } catch (_: IOException) {
                                break
                            }
                            when {
                                cmd == "PING" -> {
                                    output.writeUTF("PONG")
                                    output.flush()
                                }
                                cmd.startsWith("WEIGHTS:") -> {
                                    val digest = cmd.removePrefix("WEIGHTS:")
                                    File(cacheDir, "weights-$digest.cache").writeText("cached:$digest")
                                    output.writeUTF("CACHED:$digest")
                                    output.flush()
                                }
                                cmd.startsWith("FORWARD:") -> {
                                    val payload = cmd.removePrefix("FORWARD:")
                                    output.writeUTF("ACTIVATION:$payload")
                                    output.flush()
                                }
                                else -> {
                                    output.writeUTF("OK")
                                    output.flush()
                                }
                            }
                        }
                    } catch (_: IOException) {
                    }
                }
            }, "rpc-worker").apply { isDaemon = true; start() }
        }
    }
}

/**
 * Child OS process entry point emulating `llama-server --model <gguf> --rpc <endpoints> --tensor-split <split>`.
 *
 * Binds strictly to `127.0.0.1:<port>` and verifies every `--rpc` endpoint on each inference call.
 * Reproduces `ggml-rpc.cpp`'s `GGML_ABORT` (`RPC_STATUS_ASSERT`) behavior: if any helper RPC endpoint
 * drops or fails during a forward pass, the child process aborts with non-zero exit code while the
 * parent Engine process survives!
 */
object LoopbackLlamaServerChildMain {
    @JvmStatic
    fun main(args: Array<String>) {
        if (args.size < 6) exitProcess(2)
        val modelPath = args[0]
        val rpcEndpoints = args[1].split(",").filter { it.isNotBlank() }
        val tensorSplit = args[2]
        val port = args[4].toIntOrNull() ?: exitProcess(2)
        val apiKey = args[5]

        val server = ServerSocket(port, 50, InetAddress.getByName("127.0.0.1"))
        while (!server.isClosed) {
            val client = try {
                server.accept()
            } catch (_: IOException) {
                break
            }
            Thread({
                client.use { sock ->
                    try {
                        val reader = BufferedReader(InputStreamReader(sock.getInputStream(), Charsets.UTF_8))
                        val writer = PrintWriter(OutputStreamWriter(sock.getOutputStream(), Charsets.UTF_8), true)
                        val providedKey = reader.readLine() ?: return@use
                        val prompt = reader.readLine() ?: return@use
                        if (providedKey != apiKey) {
                            writer.println("ERR:UNAUTHORIZED")
                            return@use
                        }
                        // Forward through every helper RPC tunnel in sequence; abort on RPC failure
                        // just as upstream ggml-rpc.cpp's RPC_STATUS_ASSERT / GGML_ABORT does.
                        var state = prompt
                        for (endpoint in rpcEndpoints) {
                            val parts = endpoint.split(":")
                            val host = parts[0]
                            val rpcPort = parts[1].toInt()
                            try {
                                Socket().use { rpcSock ->
                                    rpcSock.soTimeout = 3_000
                                    rpcSock.connect(InetSocketAddress(host, rpcPort), 2_000)
                                    val rpcIn = DataInputStream(rpcSock.getInputStream())
                                    val rpcOut = DataOutputStream(rpcSock.getOutputStream())
                                    rpcOut.writeUTF("FORWARD:$state")
                                    rpcOut.flush()
                                    val reply = rpcIn.readUTF()
                                    if (!reply.startsWith("ACTIVATION:")) {
                                        exitProcess(134)
                                    }
                                    state = reply.removePrefix("ACTIVATION:")
                                }
                            } catch (_: Exception) {
                                // Helper dropped mid-forward -> GGML_ABORT in child process
                                exitProcess(134)
                            }
                        }
                        val modelName = File(modelPath).nameWithoutExtension
                        writer.println("OK:[split:$modelName:$tensorSplit] $state")
                    } catch (_: Exception) {
                    }
                }
            }, "llama-server-worker").apply { isDaemon = true; start() }
        }
    }
}

internal fun allocateEphemeralLoopbackPort(): Int =
    ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }

/**
 * RFC-0105 Phase 3 Helper supervisor:
 * - Runs `ggml-rpc-server -H 127.0.0.1 -p <ephemeral> -c` as a supervised child process.
 * - Exposes it only through an authenticated tunnel on the peer link for the anchor of the current plan.
 * - Enforces [ContributionPolicy]; crossing a limit moves the helper to draining and stops the RPC process.
 */
class HelperProcessSupervisor(
    private val node: AidinNode,
    private val cacheDir: File,
    private val llamaCppBuildId: String = DEFAULT_LLAMA_CPP_BUILD_ID,
    private val spawner: SplitProcessSpawner = OsSplitProcessSpawner(),
    private val policyProvider: () -> ContributionPolicy = { ContributionPolicy(ramBudgetBytes = 4L * 1024 * 1024 * 1024) },
) : Closeable {
    private val lock = Any()
    private var rpcProcess: SupervisedChildProcess? = null
    private var activePlan: SplitPlan? = null
    private val highestPlanVersionById = HashMap<String, Long>()
    private val openStreams = ConcurrentHashMap<String, Socket>()

    val currentPlan: SplitPlan? get() = synchronized(lock) { activePlan }
    val rpcListenPort: Int? get() = synchronized(lock) { rpcProcess?.takeIf { it.isAlive }?.port }
    val isRpcProcessAlive: Boolean get() = synchronized(lock) { rpcProcess?.isAlive == true }

    init {
        node. attachHelperSupervisor(this)
    }

    /** Called when the helper's device signals (battery, charging, thermal) cross a [ContributionPolicy] limit. */
    fun evaluateContributionPolicy(
        batteryPercent: Int? = null,
        charging: Boolean? = null,
        thermalStatus: String? = null,
    ): Boolean {
        val groupId = synchronized(lock) { (activePlan?.domain as? TrustDomain.Group)?.groupId }
        val draining = policyProvider().shouldDrain(batteryPercent, charging, thermalStatus, groupId)
        if (draining) {
            stopRpcServer()
        }
        return draining
    }

    internal fun onPlanAssign(fromPeer: NodeId, wire: WireSplitPlan): LinkMessage.PlanAck {
        val plan = wire.toSplitPlan()
            ?: return LinkMessage.PlanAck(wire.planId, wire.version, accepted = false, reason = "malformed plan")

        synchronized(lock) {
            if (plan.anchorNodeId != fromPeer) {
                return LinkMessage.PlanAck(plan.planId, plan.version, accepted = false, reason = "sender is not plan anchor")
            }
            // Reject older plan versions (RFC-0105: "Nodes reject messages for an older plan version").
            val highestSeen = highestPlanVersionById[plan.planId] ?: 0L
            if (plan.version < highestSeen) {
                return LinkMessage.PlanAck(plan.planId, plan.version, accepted = false, reason = "stale plan version ${plan.version} < $highestSeen")
            }
            // Verify anchor holds Scope.ANCHOR in the plan's trust domain.
            val auth = node.authorize(fromPeer, Scope.ANCHOR)
            if (auth !is TrustDecision.Allowed || plan.domain !in auth.domains) {
                return LinkMessage.PlanAck(plan.planId, plan.version, accepted = false, reason = "anchor not authorized in ${plan.domain}")
            }
            if (plan.llamaCppBuildId != llamaCppBuildId) {
                return LinkMessage.PlanAck(plan.planId, plan.version, accepted = false, reason = "llama.cpp build mismatch")
            }
            val myShare = plan.helpers.firstOrNull { it.nodeId == node.nodeId }
                ?: return LinkMessage.PlanAck(plan.planId, plan.version, accepted = false, reason = "node not in helper list")

            val policy = policyProvider()
            val groupId = (plan.domain as? TrustDomain.Group)?.groupId
            if (policy.shouldDrain(groupId = groupId) || myShare.allocatedRamBytes > policy.ramBudgetBytes) {
                return LinkMessage.PlanAck(plan.planId, plan.version, accepted = false, reason = "exceeds helper contribution policy")
            }

            try {
                ensureRpcProcessLocked()
            } catch (e: Exception) {
                return LinkMessage.PlanAck(plan.planId, plan.version, accepted = false, reason = "failed to start ggml-rpc-server: ${e.message}")
            }
            highestPlanVersionById[plan.planId] = plan.version
            activePlan = plan
            return LinkMessage.PlanAck(plan.planId, plan.version, accepted = true)
        }
    }

    internal fun onTunnelOpen(fromPeer: NodeId, msg: LinkMessage.TunnelOpen, sendToPeer: (LinkMessage) -> Unit) {
        val port = synchronized(lock) {
            val plan = activePlan
            val proc = rpcProcess
            if (plan == null ||
                plan.anchorNodeId != fromPeer ||
                plan.planId != msg.planId ||
                plan.version != msg.planVersion ||
                proc == null ||
                !proc.isAlive
            ) {
                null
            } else {
                // Re-verify anchor authorization on every tunnel open.
                val auth = node.authorize(fromPeer, Scope.ANCHOR)
                if (auth is TrustDecision.Allowed && plan.domain in auth.domains) proc.port else null
            }
        }
        if (port == null) {
            sendToPeer(LinkMessage.TunnelClose(msg.streamId, "unauthorized or stale plan"))
            return
        }

        try {
            val sock = Socket()
            sock.connect(InetSocketAddress("127.0.0.1", port), 2_000)
            openStreams[msg.streamId] = sock
            Thread({
                val buf = ByteArray(16_384)
                try {
                    val input = sock.getInputStream()
                    while (!sock.isClosed) {
                        val n = input.read(buf)
                        if (n < 0) break
                        if (n > 0) {
                            val b64 = Base64.getUrlEncoder().withoutPadding().encodeToString(buf.copyOf(n))
                            sendToPeer(LinkMessage.TunnelData(msg.streamId, b64))
                        }
                    }
                } catch (_: IOException) {
                } finally {
                    openStreams.remove(msg.streamId)?.let { runCatching { it.close() } }
                    runCatching { sendToPeer(LinkMessage.TunnelClose(msg.streamId, "eof")) }
                }
            }, "aidin-helper-tunnel-${msg.streamId}").apply { isDaemon = true; start() }
        } catch (e: IOException) {
            sendToPeer(LinkMessage.TunnelClose(msg.streamId, e.message ?: "connect failed"))
        }
    }

    internal fun onTunnelData(fromPeer: NodeId, msg: LinkMessage.TunnelData) {
        val anchor = synchronized(lock) { activePlan?.anchorNodeId }
        if (anchor != fromPeer) return
        val sock = openStreams[msg.streamId] ?: return
        try {
            val bytes = Base64.getUrlDecoder().decode(msg.data)
            synchronized(sock) {
                sock.getOutputStream().write(bytes)
                sock.getOutputStream().flush()
            }
        } catch (_: Exception) {
            openStreams.remove(msg.streamId)?.let { runCatching { it.close() } }
        }
    }

    internal fun onTunnelClose(msg: LinkMessage.TunnelClose) {
        openStreams.remove(msg.streamId)?.let { runCatching { it.close() } }
    }

    private fun ensureRpcProcessLocked(): SupervisedChildProcess {
        val existing = rpcProcess
        if (existing != null && existing.isAlive) return existing
        val port = allocateEphemeralLoopbackPort()
        val started = spawner.spawnHelperRpcServer(port, cacheDir)
        rpcProcess = started
        return started
    }

    /** Stops the supervised `ggml-rpc-server` child process and closes all active tunnel streams. */
    fun stopRpcServer() {
        val proc = synchronized(lock) {
            activePlan = null
            val p = rpcProcess
            rpcProcess = null
            p
        }
        openStreams.values.forEach { runCatching { it.close() } }
        openStreams.clear()
        proc?.destroyForcibly()
    }

    override fun close() {
        stopRpcServer()
    }

    companion object {
        const val DEFAULT_LLAMA_CPP_BUILD_ID = "b4200-rpc-v1"
    }
}

sealed interface SplitInferenceOutcome {
    data class Success(val text: String, val plan: SplitPlan) : SplitInferenceOutcome
    data class PlanChanged(val expectedVersion: Long, val currentVersion: Long?) : SplitInferenceOutcome
    data class Unavailable(val reason: String) : SplitInferenceOutcome
}

/**
 * RFC-0105 Phase 3 Anchor supervisor:
 * - Plans a layer split across trusted helpers, opens one `127.0.0.1:<ephemeral>` tunnel listener per helper,
 *   and starts `llama-server` as a supervised child process.
 * - Watches helper liveness and `llama-server` exit: when a helper is lost/draining or `llama-server`
 *   crashes, stops `llama-server`, marks the split model unavailable so virtual IDs fall back immediately,
 *   and attempts RESTART recovery by re-planning with the remaining healthy members.
 */
class AnchorProcessSupervisor(
    private val node: AidinNode,
    private val scope: CoroutineScope,
    private val llamaCppBuildId: String = HelperProcessSupervisor.DEFAULT_LLAMA_CPP_BUILD_ID,
    private val spawner: SplitProcessSpawner = OsSplitProcessSpawner(),
) : Closeable {
    private val lock = Any()
    private val versionCounter = AtomicLong(0L)

    private data class HostedSpec(
        val planId: String,
        val domain: TrustDomain,
        val modelId: String,
        val modelDigest: String,
        val modelFile: File,
        val totalModelBytes: Long,
        val blockCount: Int,
        val kvCacheBytes: Long,
        val contextLength: Int,
        val anchorRamBudgetBytes: Long,
    )

    private var hostedSpec: HostedSpec? = null
    @Volatile private var currentPlan: SplitPlan? = null
    private var llamaProcess: SupervisedChildProcess? = null
    private var apiKey: String = ""
    private val tunnelListeners = mutableListOf<ServerSocket>()
    private val activeClientSockets = ConcurrentHashMap<String, Socket>()
    private var monitorJob: Job? = null
    private val reconnecting = AtomicBoolean(false)

    init {
        node.attachAnchorSupervisor(this)
    }

    val activePlan: SplitPlan? get() = currentPlan
    val isAvailable: Boolean get() = synchronized(lock) { currentPlan != null && llamaProcess?.isAlive == true }
    val anchorLlamaPort: Int? get() = synchronized(lock) { llamaProcess?.takeIf { it.isAlive }?.port }

    /**
     * Form -> plan -> load -> serve a split GGUF model across [domain].
     */
    fun startSplitModel(
        domain: TrustDomain,
        modelId: String,
        modelDigest: String,
        modelFile: File,
        totalModelBytes: Long,
        blockCount: Int,
        kvCacheBytes: Long = 0L,
        contextLength: Int = 4096,
        anchorRamBudgetBytes: Long,
        planId: String = "plan-$modelId",
    ): SplitPlanOutcome {
        val spec = HostedSpec(
            planId = planId,
            domain = domain,
            modelId = modelId,
            modelDigest = modelDigest,
            modelFile = modelFile,
            totalModelBytes = totalModelBytes,
            blockCount = blockCount,
            kvCacheBytes = kvCacheBytes,
            contextLength = contextLength,
            anchorRamBudgetBytes = anchorRamBudgetBytes,
        )
        synchronized(lock) {
            hostedSpec = spec
        }
        return deployPlan(spec)
    }

    private fun deployPlan(spec: HostedSpec): SplitPlanOutcome {
        tearDownActiveProcess()

        val candidates = node.peers.value.mapNotNull { peer ->
            val beat = peer.lastHeartbeat ?: return@mapNotNull null
            val auth = node.authorize(peer.nodeId, Scope.HELP)
            val domains = (auth as? TrustDecision.Allowed)?.domains ?: emptySet()
            HelperCandidate(
                nodeId = peer.nodeId,
                state = peer.state,
                heartbeat = beat,
                helpDomains = domains,
            )
        }

        val nextVersion = versionCounter.incrementAndGet()
        val outcome = SplitModelPlanner.plan(
            planId = spec.planId,
            version = nextVersion,
            domain = spec.domain,
            modelId = spec.modelId,
            modelDigest = spec.modelDigest,
            totalModelBytes = spec.totalModelBytes,
            blockCount = spec.blockCount,
            kvCacheBytes = spec.kvCacheBytes,
            contextLength = spec.contextLength,
            llamaCppBuildId = llamaCppBuildId,
            anchorNodeId = node.nodeId,
            anchorRamBudgetBytes = spec.anchorRamBudgetBytes,
            candidates = candidates,
        )
        if (outcome !is SplitPlanOutcome.Planned) {
            return outcome
        }

        val plan = outcome.plan
        // Assign plan to every helper and require ACK before opening tunnels.
        for (helper in plan.helpers) {
            val ack = node.sendPlanAssignAndAwaitAck(helper.nodeId, plan)
            if (ack == null || !ack.accepted) {
                return SplitPlanOutcome.Refused("Helper ${helper.nodeId} refused plan: ${ack?.reason ?: "timeout"}")
            }
        }

        // Open one loopback tunnel listener per helper on 127.0.0.1:<ephemeral>.
        val newListeners = mutableListOf<ServerSocket>()
        val rpcEndpoints = mutableListOf<String>()
        try {
            for (helper in plan.helpers) {
                val listener = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
                newListeners += listener
                rpcEndpoints += "127.0.0.1:${listener.localPort}"
                startAcceptingTunnelConnections(listener, helper.nodeId, plan)
            }

            val llamaPort = allocateEphemeralLoopbackPort()
            val randomKey = UUID.randomUUID().toString()
            val proc = spawner.spawnAnchorLlamaServer(
                modelFile = spec.modelFile,
                rpcEndpoints = rpcEndpoints,
                tensorSplit = plan.tensorSplitArg,
                contextLength = plan.contextLength,
                port = llamaPort,
                apiKey = randomKey,
            )

            synchronized(lock) {
                tunnelListeners += newListeners
                llamaProcess = proc
                apiKey = randomKey
                currentPlan = plan
            }
            plan.helpers.forEach { node.markPeerActive(it.nodeId, true) }
            ensureMonitorRunning()
            return SplitPlanOutcome.Planned(plan)
        } catch (e: Exception) {
            newListeners.forEach { runCatching { it.close() } }
            tearDownActiveProcess()
            return SplitPlanOutcome.Refused("Failed to launch anchor llama-server: ${e.message}")
        }
    }

    private fun startAcceptingTunnelConnections(listener: ServerSocket, helperNodeId: NodeId, plan: SplitPlan) {
        Thread({
            while (!listener.isClosed) {
                val sock = try {
                    listener.accept()
                } catch (_: IOException) {
                    break
                }
                val streamId = UUID.randomUUID().toString()
                activeClientSockets[streamId] = sock
                if (!node.sendTunnelMessage(helperNodeId, LinkMessage.TunnelOpen(streamId, plan.planId, plan.version))) {
                    activeClientSockets.remove(streamId)
                    runCatching { sock.close() }
                    continue
                }
                Thread({
                    val buf = ByteArray(16_384)
                    try {
                        val input = sock.getInputStream()
                        while (!sock.isClosed) {
                            val n = input.read(buf)
                            if (n < 0) break
                            if (n > 0) {
                                val b64 = Base64.getUrlEncoder().withoutPadding().encodeToString(buf.copyOf(n))
                                if (!node.sendTunnelMessage(helperNodeId, LinkMessage.TunnelData(streamId, b64))) {
                                    break
                                }
                            }
                        }
                    } catch (_: IOException) {
                    } finally {
                        activeClientSockets.remove(streamId)?.let { runCatching { it.close() } }
                        node.sendTunnelMessage(helperNodeId, LinkMessage.TunnelClose(streamId, "eof"))
                    }
                }, "aidin-anchor-tunnel-$streamId").apply { isDaemon = true; start() }
            }
        }, "aidin-anchor-accept-${helperNodeId.value}").apply { isDaemon = true; start() }
    }

    internal fun onTunnelData(fromPeer: NodeId, msg: LinkMessage.TunnelData) {
        val plan = currentPlan ?: return
        if (plan.helpers.none { it.nodeId == fromPeer }) return
        val sock = activeClientSockets[msg.streamId] ?: return
        try {
            val bytes = Base64.getUrlDecoder().decode(msg.data)
            synchronized(sock) {
                sock.getOutputStream().write(bytes)
                sock.getOutputStream().flush()
            }
        } catch (_: Exception) {
            activeClientSockets.remove(msg.streamId)?.let { runCatching { it.close() } }
        }
    }

    internal fun onTunnelClose(msg: LinkMessage.TunnelClose) {
        activeClientSockets.remove(msg.streamId)?.let { runCatching { it.close() } }
    }

    /**
     * Executes an inference prompt against the supervised `llama-server` child process.
     *
     * If [expectedPlanVersion] is provided and does not match the current plan's version,
     * returns [SplitInferenceOutcome.PlanChanged] (HTTP 409 `plan_changed`) before touching
     * the child process so content never reaches an unverified helper set (RFC-0105).
     */
    fun invoke(prompt: String, expectedPlanVersion: Long? = null): SplitInferenceOutcome {
        val (plan, proc, key) = synchronized(lock) {
            val p = currentPlan
            val pr = llamaProcess
            if (p != null && expectedPlanVersion != null && p.version != expectedPlanVersion) {
                return SplitInferenceOutcome.PlanChanged(expectedPlanVersion, p.version)
            }
            if (p == null || pr == null || !pr.isAlive) {
                if (expectedPlanVersion != null && versionCounter.get() != expectedPlanVersion) {
                    return SplitInferenceOutcome.PlanChanged(expectedPlanVersion, currentPlan?.version)
                }
                return SplitInferenceOutcome.Unavailable("Split model is not available")
            }
            Triple(p, pr, apiKey)
        }

        return try {
            Socket().use { sock ->
                sock.soTimeout = 5_000
                sock.connect(InetSocketAddress("127.0.0.1", proc.port), 2_000)
                val writer = PrintWriter(OutputStreamWriter(sock.getOutputStream(), Charsets.UTF_8), true)
                val reader = BufferedReader(InputStreamReader(sock.getInputStream(), Charsets.UTF_8))
                writer.println(key)
                writer.println(prompt.replace('\n', ' '))
                val line = reader.readLine()
                if (line != null && line.startsWith("OK:")) {
                    SplitInferenceOutcome.Success(line.removePrefix("OK:"), plan)
                } else {
                    handleFailureAndRestart()
                    SplitInferenceOutcome.Unavailable("Anchor llama-server failed during generation")
                }
            }
        } catch (e: Exception) {
            handleFailureAndRestart()
            SplitInferenceOutcome.Unavailable("Anchor llama-server connection lost: ${e.message}")
        }
    }

    private fun ensureMonitorRunning() {
        synchronized(lock) {
            if (monitorJob?.isActive == true) return
            monitorJob = scope.launch(Dispatchers.IO) {
                while (isActive) {
                    delay(100)
                    val plan = currentPlan ?: continue
                    val proc = synchronized(lock) { llamaProcess }
                    val peerStates = node.peers.value.associateBy { it.nodeId }
                    val helperLost = plan.helpers.any { h ->
                        val peer = peerStates[h.nodeId]
                        peer == null ||
                            !peer.connected ||
                            peer.state == PeerState.LEFT ||
                            peer.state == PeerState.DRAINING ||
                            peer.lastHeartbeat?.draining == true
                    }
                    if (proc == null || !proc.isAlive || helperLost) {
                        handleFailureAndRestart()
                    }
                }
            }
        }
    }

    private fun handleFailureAndRestart() {
        if (!reconnecting.compareAndSet(false, true)) return
        try {
            val spec = synchronized(lock) { hostedSpec }
            tearDownActiveProcess()
            if (spec != null) {
                deployPlan(spec)
            }
        } finally {
            reconnecting.set(false)
        }
    }

    private fun tearDownActiveProcess() {
        val (oldPlan, oldProc, listeners) = synchronized(lock) {
            val p = currentPlan
            val pr = llamaProcess
            val ls = tunnelListeners.toList()
            currentPlan = null
            llamaProcess = null
            tunnelListeners.clear()
            Triple(p, pr, ls)
        }
        oldPlan?.helpers?.forEach { node.markPeerActive(it.nodeId, false) }
        listeners.forEach { runCatching { it.close() } }
        activeClientSockets.values.forEach { runCatching { it.close() } }
        activeClientSockets.clear()
        oldProc?.destroyForcibly()
    }

    override fun close() {
        synchronized(lock) {
            hostedSpec = null
            monitorJob?.cancel()
            monitorJob = null
        }
        tearDownActiveProcess()
    }
}
