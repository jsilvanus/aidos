package dev.aidos.daemon

import dev.aidos.api.RuntimeClient
import dev.aidos.api.socket.Methods
import dev.aidos.api.socket.SocketPaths
import dev.aidos.api.socket.Wire
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.Channels
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.security.SecureRandom
import java.util.Base64

/**
 * Socket server exposing [RuntimeClient] over a Unix domain socket (RFC-0052, RFC-0055, M10).
 *
 * Newline-delimited JSON, one connection per CLI invocation for ordinary commands, and one
 * long-lived connection per `events.subscribe` call. Every connection starts with a handshake
 * (RFC-0052 Authentication): the client presents the daemon's minted connection token, and
 * whether it is `user_interactive` (attached to a TTY) — commands in [Methods.REQUIRES_INTERACTIVE]
 * refuse otherwise, so a script cannot approve authority on the user's behalf (RFC-0055).
 *
 * **Scope (M10):** dispatches exactly the methods [Methods] names — project/session/capability
 * commands, the event stream, and runtime info. See `Wire.kt`'s doc comment for what is
 * deliberately not yet on the wire.
 */
class RuntimeSocketServer(
    private val client: RuntimeClient,
    private val socketPath: Path = SocketPaths.defaultSocketPath(),
    private val tokenPath: Path = SocketPaths.defaultTokenPath(socketPath),
) {
    /** Minted fresh per server instance; also written to [tokenPath] (0600) by [start]. */
    val connectionToken: String = mintToken()

    private var serverChannel: ServerSocketChannel? = null
    private var scope: CoroutineScope? = null
    private var acceptJob: Job? = null

    suspend fun start() = withContext(Dispatchers.IO) {
        Files.createDirectories(socketPath.parent)
        Files.deleteIfExists(socketPath)
        writeTokenFile()

        val channel = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
        channel.bind(UnixDomainSocketAddress.of(socketPath))
        serverChannel = channel
        restrictPermissions(socketPath)

        val runScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = runScope
        acceptJob = runScope.launch { acceptLoop(channel, runScope) }
    }

    suspend fun stop() = withContext(Dispatchers.IO) {
        acceptJob?.cancel()
        runCatching { serverChannel?.close() }
        scope?.cancel()
        Files.deleteIfExists(socketPath)
        Files.deleteIfExists(tokenPath)
    }

    private fun mintToken(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun writeTokenFile() {
        Files.deleteIfExists(tokenPath)
        Files.writeString(tokenPath, connectionToken)
        restrictPermissions(tokenPath)
    }

    private fun restrictPermissions(path: Path) {
        // Not all filesystems support POSIX permissions (e.g. some CI containers); best-effort.
        runCatching { Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------")) }
    }

    private suspend fun acceptLoop(channel: ServerSocketChannel, runScope: CoroutineScope) {
        while (true) {
            val connection = try {
                channel.accept()
            } catch (e: IOException) {
                return // channel closed by stop() -- normal shutdown
            }
            runScope.launch { handleConnection(connection) }
        }
    }

    private suspend fun handleConnection(connection: SocketChannel) = withContext(Dispatchers.IO) {
        connection.use { ch ->
            val reader = BufferedReader(InputStreamReader(Channels.newInputStream(ch), StandardCharsets.UTF_8))
            val output = Channels.newOutputStream(ch)

            val interactive = handshake(reader, output) ?: return@withContext

            while (true) {
                val line = reader.readLine() ?: break
                val request = runCatching { Json.parseToJsonElement(line).jsonObject }.getOrNull() ?: break
                val id = request["id"]?.jsonPrimitive?.content ?: ""
                val method = request["method"]?.jsonPrimitive?.content ?: ""
                val params = request["params"]?.jsonObject ?: JsonObject(emptyMap())

                if (method in Methods.REQUIRES_INTERACTIVE && !interactive) {
                    writeLine(output, errorResponse(id, "runtime.not_interactive",
                        "$method requires an interactive connection (RFC-0055)"))
                    continue
                }

                if (method == Methods.EVENTS_SUBSCRIBE) {
                    writeLine(output, buildJsonObject { put("id", id); put("ok", true) })
                    streamEvents(params, output)
                    break // events.subscribe owns the connection until the client disconnects
                }

                val response = try {
                    buildJsonObject { put("id", id); put("ok", true); put("result", dispatch(method, params)) }
                } catch (e: UnsupportedOperationException) {
                    errorResponse(id, "runtime.not_wired", e.message ?: method)
                } catch (e: Exception) {
                    errorResponse(id, "runtime.dispatch_failed", e.message ?: e::class.simpleName.orEmpty())
                }
                writeLine(output, response)
            }
        }
    }

    /** Returns the connection's `user_interactive` flag on success, null if the handshake failed. */
    private fun handshake(reader: BufferedReader, output: OutputStream): Boolean? {
        val helloLine = reader.readLine() ?: return null
        val hello = runCatching { Json.parseToJsonElement(helloLine).jsonObject }.getOrNull()
        val presentedToken = hello?.get("token")?.jsonPrimitive?.content
        val interactive = hello?.get("interactive")?.jsonPrimitive?.boolean ?: false

        if (presentedToken != connectionToken) {
            writeLine(output, buildJsonObject { put("ok", false); put("error", "unauthorized") })
            return null
        }
        writeLine(output, buildJsonObject { put("ok", true) })
        return interactive
    }

    private suspend fun dispatch(method: String, params: JsonObject): JsonElement = when (method) {
        Methods.PROJECTS_CREATE ->
            Wire.encodeProjectResult(client.projects.create(Wire.decodeCreateProjectRequest(params)))
        Methods.PROJECTS_LIST ->
            Wire.encodeProjectSummaryList(client.projects.list())
        Methods.SESSIONS_CREATE ->
            Wire.encodeSessionResult(client.sessions.create(Wire.decodeCreateSessionRequest(params)))
        Methods.SESSIONS_LIST ->
            Wire.encodeSessionSummaryList(client.sessions.list(params["projectId"]!!.jsonPrimitive.content))
        Methods.SESSIONS_SEND -> Wire.encodeRunResult(
            client.sessions.send(
                params["sessionId"]!!.jsonPrimitive.content,
                Wire.decodeUserMessage(params["message"]!!.jsonObject),
            )
        )
        Methods.CAPABILITIES_GRANT ->
            Wire.encodeCapabilityResult(client.capabilities.grant(Wire.decodeGrantCapabilityRequest(params)))
        Methods.CAPABILITIES_LIST_PENDING ->
            Wire.encodePendingCapabilityList(client.capabilities.listPending())
        Methods.CAPABILITIES_APPROVE ->
            Wire.encodeCapabilityResult(client.capabilities.approve(params["requestId"]!!.jsonPrimitive.content))
        Methods.RUNTIME_PING ->
            JsonPrimitive(client.runtime.ping())
        Methods.RUNTIME_VERSION ->
            Wire.encodeRuntimeVersion(client.runtime.version())
        else -> throw UnsupportedOperationException("method not wired over socket transport (M10 scope): $method")
    }

    private suspend fun streamEvents(params: JsonObject, output: OutputStream) {
        val filter = Wire.decodeEventFilter(params["filter"]?.jsonObject ?: JsonObject(emptyMap()))
        try {
            client.events.subscribe(filter).collect { event ->
                writeLine(output, buildJsonObject { put("type", "event"); put("event", Wire.encodeRuntimeEvent(event)) })
            }
        } catch (e: IOException) {
            // client disconnected mid-stream -- normal termination, not an error to log
        }
    }

    private fun errorResponse(id: String, code: String, message: String): JsonObject = buildJsonObject {
        put("id", id)
        put("ok", false)
        put("error", buildJsonObject { put("code", code); put("message", message) })
    }

    private fun writeLine(output: OutputStream, json: JsonObject) {
        output.write((json.toString() + "\n").toByteArray(StandardCharsets.UTF_8))
        output.flush()
    }
}
