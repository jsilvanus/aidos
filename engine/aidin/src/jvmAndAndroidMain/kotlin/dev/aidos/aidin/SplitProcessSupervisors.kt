package dev.aidos.aidin

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
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
    /** Where child output goes; a pipe nobody reads fills up and stalls a chatty `llama-server`. */
    private val logDir: File = File(System.getProperty("java.io.tmpdir"), "aidin-split-logs"),
) : SplitProcessSpawner {

    /** True when both pinned native programs are present, so splits run real llama.cpp. */
    val usesNativeBinaries: Boolean
        get() = ggmlRpcBinary.isRunnable() && llamaServerBinary.isRunnable()

    override fun spawnHelperRpcServer(port: Int, cacheDir: File): SupervisedChildProcess {
        cacheDir.mkdirs()
        val native = ggmlRpcBinary.isRunnable()
        val command = if (native) {
            // `-c` only switches the weight cache on; its location comes from LLAMA_CACHE.
            listOf(
                ggmlRpcBinary!!.absolutePath,
                "-H", "127.0.0.1",
                "-p", port.toString(),
                "-c",
            )
        } else {
            jvmCommand(
                LoopbackRpcServerChildMain::class.java.name,
                port.toString(),
                cacheDir.absolutePath,
            )
        }
        val log = logFile("ggml-rpc-server-$port.log")
        val process = ProcessBuilder(command)
            .redirectErrorStream(true)
            .redirectOutput(log)
            .apply { environment()["LLAMA_CACHE"] = cacheDir.absolutePath }
            .start()
        waitForLoopbackPort("127.0.0.1", port, process, log)
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
        val native = llamaServerBinary.isRunnable()
        val command = if (native) {
            val offload = nativeOffload(tensorSplit)
            listOf(
                llamaServerBinary!!.absolutePath,
                "--model", modelFile.absolutePath,
                "--rpc", rpcEndpoints.joinToString(","),
                "--n-gpu-layers", offload.layers.toString(),
                "--tensor-split", offload.split,
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
        val log = logFile("llama-server-$port.log")
        val process = ProcessBuilder(command)
            // With two RPC devices llama.cpp has the servers connect to each other directly; that
            // path bypasses the AIDIN tunnel and its admission, so it stays off.
            .apply { environment()["GGML_RPC_NO_COMM"] = "1" }
            .redirectErrorStream(true)
            .redirectOutput(log)
            .start()
        waitForLoopbackPort("127.0.0.1", port, process, log, timeoutMillis = if (native) 300_000L else 10_000L)
        // The real server listens before the model is loaded and answers 503 until then; the
        // helpers' layers travel through the tunnel first, so this can take a while.
        waitForHealth(port, process, log, timeoutMillis = if (native) 300_000L else 10_000L)
        return OsSupervisedChildProcess(process, port)
    }

    private fun logFile(name: String): File = File(logDir.apply { mkdirs() }, name)

    private fun jvmCommand(mainClass: String, vararg args: String): List<String> {
        val javaHome = System.getProperty("java.home")
        val javaBin = File(javaHome, if (System.getProperty("os.name").lowercase().contains("win")) "bin/java.exe" else "bin/java").absolutePath
        val classpath = System.getProperty("java.class.path")
        return listOf(javaBin, "-Xmx64m", "-cp", classpath, mainClass) + args.toList()
    }

    private fun waitForLoopbackPort(host: String, port: Int, process: Process, log: File, timeoutMillis: Long = 10_000L) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (!process.isAlive) {
                throw IOException("Child process exited prematurely (code=${process.exitValue()}): ${log.tail()}")
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

    private fun waitForHealth(port: Int, process: Process, log: File, timeoutMillis: Long) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (!process.isAlive) {
                throw IOException("llama-server exited while loading (code=${process.exitValue()}): ${log.tail()}")
            }
            val status = runCatching {
                val conn = URL("http://127.0.0.1:$port/health").openConnection() as HttpURLConnection
                conn.connectTimeout = 1_000
                conn.readTimeout = 2_000
                try { conn.responseCode } finally { conn.disconnect() }
            }.getOrDefault(-1)
            if (status == 200) return
            Thread.sleep(100)
        }
        process.destroyForcibly()
        throw IOException("llama-server did not become healthy within ${timeoutMillis / 1000} s: ${log.tail()}")
    }

    companion object {
        /**
         * The pinned programs from the `llama-rpc-native` CI build, looked up in [dir]
         * (`llama-server` and `ggml-rpc-server`, `.exe` on Windows). Missing ones fall back to the
         * JVM stand-ins.
         */
        fun fromDirectory(dir: File?, logDir: File = File(System.getProperty("java.io.tmpdir"), "aidin-split-logs")): OsSplitProcessSpawner {
            val ext = if (System.getProperty("os.name").lowercase().contains("win")) ".exe" else ""
            return OsSplitProcessSpawner(
                ggmlRpcBinary = dir?.let { File(it, "ggml-rpc-server$ext") },
                llamaServerBinary = dir?.let { File(it, "llama-server$ext") },
                logDir = logDir,
            )
        }

        internal data class NativeOffload(val layers: Int, val split: String)

        /**
         * Maps the plan's `helper1,...,helperN,anchor` layer counts onto real `llama-server` flags.
         * RPC servers are devices, the anchor's CPU is not: the helpers' layers are offloaded
         * (`--n-gpu-layers`) and divided among them (`--tensor-split`), and whatever is not
         * offloaded stays on the anchor.
         */
        internal fun nativeOffload(tensorSplit: String): NativeOffload {
            val counts = tensorSplit.split(",").map { it.trim().toInt() }
            val helpers = counts.dropLast(1).ifEmpty { counts }
            return NativeOffload(helpers.sum(), helpers.joinToString(","))
        }
    }
}

private fun File?.isRunnable(): Boolean = this != null && isFile && canExecute()

private fun File.tail(maxChars: Int = 2_000): String =
    runCatching { readText().takeLast(maxChars) }.getOrDefault("")

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
                        val request = readHttpRequest(sock) ?: return@use
                        when {
                            request.method == "GET" && request.path == "/health" ->
                                writeHttpResponse(sock, 200, """{"status":"ok"}""")
                            request.method == "POST" && request.path == "/completion" -> {
                                if (request.headers["authorization"] != "Bearer $apiKey") {
                                    writeHttpResponse(sock, 401, """{"error":{"message":"Invalid API Key"}}""")
                                    return@use
                                }
                                val prompt = Json.parseToJsonElement(request.body).jsonObject["prompt"]
                                    ?.jsonPrimitive?.content.orEmpty()
                                // Forward through every helper RPC tunnel in sequence; abort on RPC failure
                                // just as upstream ggml-rpc.cpp's RPC_STATUS_ASSERT / GGML_ABORT does.
                                var state = prompt
                                for (endpoint in rpcEndpoints) {
                                    val parts = endpoint.split(":")
                                    try {
                                        Socket().use { rpcSock ->
                                            rpcSock.soTimeout = 3_000
                                            rpcSock.connect(InetSocketAddress(parts[0], parts[1].toInt()), 2_000)
                                            val rpcIn = DataInputStream(rpcSock.getInputStream())
                                            val rpcOut = DataOutputStream(rpcSock.getOutputStream())
                                            rpcOut.writeUTF("FORWARD:$state")
                                            rpcOut.flush()
                                            val reply = rpcIn.readUTF()
                                            if (!reply.startsWith("ACTIVATION:")) exitProcess(134)
                                            state = reply.removePrefix("ACTIVATION:")
                                        }
                                    } catch (_: Exception) {
                                        // Helper dropped mid-forward -> GGML_ABORT in child process
                                        exitProcess(134)
                                    }
                                }
                                val modelName = File(modelPath).nameWithoutExtension
                                val content = JsonPrimitive("[split:$modelName:$tensorSplit] $state")
                                writeHttpResponse(sock, 200, """{"content":$content}""")
                            }
                            else -> writeHttpResponse(sock, 404, """{"error":{"message":"not found"}}""")
                        }
                    } catch (_: Exception) {
                    }
                }
            }, "llama-server-worker").apply { isDaemon = true; start() }
        }
    }

    private class HttpRequest(val method: String, val path: String, val headers: Map<String, String>, val body: String)

    private fun readHttpRequest(sock: Socket): HttpRequest? {
        val input = sock.getInputStream()
        fun line(): String? {
            val bytes = java.io.ByteArrayOutputStream()
            while (true) {
                val b = input.read()
                if (b < 0) return if (bytes.size() == 0) null else bytes.toString(Charsets.UTF_8.name())
                if (b == '\n'.code) return bytes.toString(Charsets.UTF_8.name()).trimEnd('\r')
                bytes.write(b)
            }
        }
        val (method, path) = line()?.split(" ")?.takeIf { it.size >= 2 }?.let { it[0] to it[1] } ?: return null
        val headers = HashMap<String, String>()
        while (true) {
            val header = line() ?: break
            if (header.isEmpty()) break
            val colon = header.indexOf(':')
            if (colon > 0) headers[header.substring(0, colon).trim().lowercase()] = header.substring(colon + 1).trim()
        }
        val length = headers["content-length"]?.toIntOrNull() ?: 0
        val body = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(body, read, length - read)
            if (n < 0) break
            read += n
        }
        return HttpRequest(method, path, headers, String(body, 0, read, Charsets.UTF_8))
    }

    private fun writeHttpResponse(sock: Socket, status: Int, json: String) {
        val body = json.toByteArray(Charsets.UTF_8)
        val head = "HTTP/1.1 $status X\r\nContent-Type: application/json\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n"
        sock.getOutputStream().apply {
            write(head.toByteArray(Charsets.UTF_8))
            write(body)
            flush()
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
        const val DEFAULT_LLAMA_CPP_BUILD_ID = "b11901"
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

    // The plan whose tunnels are open while its llama-server starts. llama.cpp handshakes with
    // every RPC server and uploads the helpers' tensors during startup, before [currentPlan] is
    // set, so helper replies for this plan must already be delivered.
    @Volatile private var deployingPlan: SplitPlan? = null
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

            deployingPlan = plan
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
            deployingPlan = null
            plan.helpers.forEach { node.markPeerActive(it.nodeId, true) }
            ensureMonitorRunning()
            return SplitPlanOutcome.Planned(plan)
        } catch (e: Exception) {
            deployingPlan = null
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
        val plan = currentPlan ?: deployingPlan ?: return
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
    fun invoke(
        prompt: String,
        expectedPlanVersion: Long? = null,
        maxTokens: Int = 128,
        temperature: Double = 0.8,
    ): SplitInferenceOutcome {
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
            val conn = URL("http://127.0.0.1:${proc.port}/completion").openConnection() as HttpURLConnection
            try {
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.connectTimeout = 2_000
                conn.readTimeout = 120_000
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("Authorization", "Bearer $key")
                val body = buildJsonObject {
                    put("prompt", prompt)
                    put("n_predict", maxTokens)
                    put("temperature", temperature)
                }.toString()
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                if (conn.responseCode == 200) {
                    val text = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                    val content = Json.parseToJsonElement(text).jsonObject["content"]?.jsonPrimitive?.content.orEmpty()
                    SplitInferenceOutcome.Success(content, plan)
                } else {
                    handleFailureAndRestart()
                    SplitInferenceOutcome.Unavailable("Anchor llama-server failed during generation (HTTP ${conn.responseCode})")
                }
            } finally {
                conn.disconnect()
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
