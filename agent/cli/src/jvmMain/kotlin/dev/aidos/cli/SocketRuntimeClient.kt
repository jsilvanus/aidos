package dev.aidos.cli

import dev.aidos.api.ArtifactDetail
import dev.aidos.api.ArtifactFilter
import dev.aidos.api.ArtifactQueries
import dev.aidos.api.ArtifactSummary
import dev.aidos.api.AuditEntry
import dev.aidos.api.CapabilityCommands
import dev.aidos.api.CapabilityResult
import dev.aidos.api.CapabilitySummary
import dev.aidos.api.CommitResult
import dev.aidos.api.CreateProjectRequest
import dev.aidos.api.CreateSessionRequest
import dev.aidos.api.DiffQueries
import dev.aidos.api.DiffRange
import dev.aidos.api.DiffSummary
import dev.aidos.api.EventFilter
import dev.aidos.api.EventSubscriptions
import dev.aidos.api.GrantCapabilityRequest
import dev.aidos.api.HunkId
import dev.aidos.api.IndexStatus
import dev.aidos.api.KnowledgeQueries
import dev.aidos.api.KnowledgeQuery
import dev.aidos.api.KnowledgeResult
import dev.aidos.api.PendingCapabilityRequest
import dev.aidos.api.ProjectCommands
import dev.aidos.api.ProjectDetail
import dev.aidos.api.ProjectResult
import dev.aidos.api.ProjectSummary
import dev.aidos.api.RunResult
import dev.aidos.api.RuntimeClient
import dev.aidos.api.RuntimeEvent
import dev.aidos.api.RuntimeInfo
import dev.aidos.api.RuntimeVersion
import dev.aidos.api.SessionCommands
import dev.aidos.api.SessionDetail
import dev.aidos.api.SessionResult
import dev.aidos.api.SessionSummary
import dev.aidos.api.UserMessage
import dev.aidos.api.socket.Methods
import dev.aidos.api.socket.Wire
import dev.aidos.kernel.FileDiff
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.Channels
import java.nio.channels.SocketChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/** Thrown when the socket handshake or a request fails at the transport or protocol level. */
class SocketConnectionException(message: String) : Exception(message)

/**
 * Socket-transport [RuntimeClient] (RFC-0052, RFC-0055, M10): the CLI's connection to a running
 * `aidos-daemon` over a Unix domain socket, newline-delimited JSON.
 *
 * A new connection is opened per call — matching how the CLI is actually used (one OS process
 * per command) rather than pooling a long-lived connection a short-lived process would only use
 * once. `events.subscribe` is the one long-lived exception: it holds its connection open and
 * streams event lines for as long as the returned [Flow] is collected.
 *
 * **Scope (M10):** wires exactly the methods M10's done-when needs — project/session/capability
 * commands, the event stream, and runtime info. `DiffQueries`, `ArtifactQueries`, and
 * `KnowledgeQueries` are not yet part of the wire protocol (see `Wire.kt`'s doc comment) and
 * throw [UnsupportedOperationException] here; drive those against an in-process `RuntimeClient`
 * until a later link extends the socket coverage.
 */
class SocketRuntimeClient(
    private val socketPath: Path,
    private val tokenPath: Path,
    /** RFC-0055: only a CLI attached to a TTY may claim this — a script or CI run may not. */
    private val interactive: Boolean = System.console() != null,
) : RuntimeClient {

    private fun connect(): SocketChannel {
        val channel = SocketChannel.open(StandardProtocolFamily.UNIX)
        try {
            channel.connect(UnixDomainSocketAddress.of(socketPath))
        } catch (e: Exception) {
            channel.close()
            throw SocketConnectionException("could not connect to daemon at $socketPath: ${e.message}")
        }
        return channel
    }

    private fun token(): String = try {
        Files.readString(tokenPath).trim()
    } catch (e: Exception) {
        throw SocketConnectionException("could not read connection token at $tokenPath: ${e.message}")
    }

    /** Opens a connection and performs the handshake (RFC-0052 Authentication, RFC-0055 Security). */
    private fun handshake(): Triple<SocketChannel, BufferedReader, OutputStream> {
        val channel = connect()
        val reader = BufferedReader(InputStreamReader(Channels.newInputStream(channel), StandardCharsets.UTF_8))
        val output = Channels.newOutputStream(channel)
        writeLine(output, buildJsonObject { put("token", token()); put("interactive", interactive) })
        val ackLine = reader.readLine()
            ?: throw SocketConnectionException("daemon closed the connection during handshake")
        val ack = runCatching { Json.parseToJsonElement(ackLine).jsonObject }
            .getOrElse { throw SocketConnectionException("malformed handshake response: $ackLine") }
        if (ack["ok"]?.jsonPrimitive?.boolean != true) {
            channel.close()
            throw SocketConnectionException("handshake rejected by daemon: $ackLine")
        }
        return Triple(channel, reader, output)
    }

    /** One request, one response, on a fresh connection closed before returning. */
    private fun call(method: String, params: JsonObject): JsonElement {
        val (channel, reader, output) = handshake()
        try {
            val id = UUID.randomUUID().toString()
            writeLine(output, buildJsonObject { put("id", id); put("method", method); put("params", params) })
            val line = reader.readLine() ?: throw SocketConnectionException("daemon closed the connection")
            val response = runCatching { Json.parseToJsonElement(line).jsonObject }
                .getOrElse { throw SocketConnectionException("malformed response: $line") }
            if (response["ok"]?.jsonPrimitive?.boolean != true) {
                val error = response["error"]?.jsonObject
                val code = error?.get("code")?.jsonPrimitive?.content ?: "unknown"
                val message = error?.get("message")?.jsonPrimitive?.content ?: line
                throw SocketConnectionException("$code: $message")
            }
            return response["result"] ?: JsonNull
        } finally {
            channel.close()
        }
    }

    private fun writeLine(output: OutputStream, json: JsonObject) {
        output.write((json.toString() + "\n").toByteArray(StandardCharsets.UTF_8))
        output.flush()
    }

    private fun notWired(method: String): Nothing = throw UnsupportedOperationException(
        "$method is not yet wired over socket transport (M10 scope — see PIPELINE.md's M10 note)"
    )

    override val projects = object : ProjectCommands {
        override suspend fun create(request: CreateProjectRequest): ProjectResult = withContext(Dispatchers.IO) {
            Wire.decodeProjectResult(call(Methods.PROJECTS_CREATE, Wire.encodeCreateProjectRequest(request)).jsonObject)
        }
        override suspend fun open(projectId: String): ProjectResult = notWired("projects.open")
        override suspend fun close(projectId: String): Unit = notWired("projects.close")
        override suspend fun list(): List<ProjectSummary> = withContext(Dispatchers.IO) {
            Wire.decodeProjectSummaryList(call(Methods.PROJECTS_LIST, JsonObject(emptyMap())).jsonArray)
        }
        override suspend fun get(projectId: String): ProjectDetail? = notWired("projects.get")
        override suspend fun delete(projectId: String, confirm: Boolean): Unit = notWired("projects.delete")
    }

    override val sessions = object : SessionCommands {
        override suspend fun create(request: CreateSessionRequest): SessionResult = withContext(Dispatchers.IO) {
            Wire.decodeSessionResult(call(Methods.SESSIONS_CREATE, Wire.encodeCreateSessionRequest(request)).jsonObject)
        }
        override suspend fun send(sessionId: String, message: UserMessage): RunResult = withContext(Dispatchers.IO) {
            val params = buildJsonObject {
                put("sessionId", sessionId)
                put("message", Wire.encodeUserMessage(message))
            }
            Wire.decodeRunResult(call(Methods.SESSIONS_SEND, params).jsonObject)
        }
        override suspend fun cancel(sessionId: String, runId: String): Unit = notWired("sessions.cancel")
        override suspend fun list(projectId: String): List<SessionSummary> = withContext(Dispatchers.IO) {
            Wire.decodeSessionSummaryList(
                call(Methods.SESSIONS_LIST, buildJsonObject { put("projectId", projectId) }).jsonArray
            )
        }
        override suspend fun get(sessionId: String): SessionDetail? = notWired("sessions.get")
        override suspend fun archive(sessionId: String): Unit = notWired("sessions.archive")
        override suspend fun delete(sessionId: String): Unit = notWired("sessions.delete")
    }

    override val capabilities = object : CapabilityCommands {
        override suspend fun grant(request: GrantCapabilityRequest): CapabilityResult = withContext(Dispatchers.IO) {
            Wire.decodeCapabilityResult(call(Methods.CAPABILITIES_GRANT, Wire.encodeGrantCapabilityRequest(request)).jsonObject)
        }
        override suspend fun revoke(capabilityId: String): Unit = notWired("capabilities.revoke")
        override suspend fun list(sessionId: String): List<CapabilitySummary> = notWired("capabilities.list")
        override suspend fun listPending(): List<PendingCapabilityRequest> = withContext(Dispatchers.IO) {
            Wire.decodePendingCapabilityList(call(Methods.CAPABILITIES_LIST_PENDING, JsonObject(emptyMap())).jsonArray)
        }
        override suspend fun approve(requestId: String): CapabilityResult = withContext(Dispatchers.IO) {
            val params = buildJsonObject { put("requestId", requestId) }
            Wire.decodeCapabilityResult(call(Methods.CAPABILITIES_APPROVE, params).jsonObject)
        }
        override suspend fun deny(requestId: String, reason: String): Unit = notWired("capabilities.deny")
        override suspend fun approveEffect(runId: String, taskId: String): CapabilityResult = withContext(Dispatchers.IO) {
            val params = buildJsonObject { put("runId", runId); put("taskId", taskId) }
            Wire.decodeCapabilityResult(call(Methods.CAPABILITIES_APPROVE_EFFECT, params).jsonObject)
        }
        override suspend fun denyEffect(runId: String, taskId: String, reason: String): Unit = withContext(Dispatchers.IO) {
            val params = buildJsonObject { put("runId", runId); put("taskId", taskId); put("reason", reason) }
            call(Methods.CAPABILITIES_DENY_EFFECT, params)
        }
        override suspend fun answerPrompt(runId: String, answer: String): CapabilityResult = withContext(Dispatchers.IO) {
            val params = buildJsonObject { put("runId", runId); put("answer", answer) }
            Wire.decodeCapabilityResult(call(Methods.CAPABILITIES_ANSWER_PROMPT, params).jsonObject)
        }
    }

    override val knowledge = object : KnowledgeQueries {
        override suspend fun search(projectId: String, query: KnowledgeQuery): KnowledgeResult = notWired("knowledge.search")
        override suspend fun indexStatus(projectId: String): IndexStatus = notWired("knowledge.indexStatus")
    }

    override val diff = object : DiffQueries {
        override suspend fun changes(projectId: String, range: DiffRange): DiffSummary = notWired("diff.changes")
        override suspend fun hunks(projectId: String, range: DiffRange, path: String): Result<FileDiff> = notWired("diff.hunks")
        override suspend fun unified(projectId: String, range: DiffRange, path: String): Result<String> = notWired("diff.unified")
        override suspend fun stage(projectId: String, hunks: List<HunkId>): Result<Unit> = notWired("diff.stage")
        override suspend fun revert(projectId: String, hunks: List<HunkId>): Result<Unit> = notWired("diff.revert")
        override suspend fun commit(projectId: String, message: String): CommitResult = notWired("diff.commit")
    }

    override val artifacts = object : ArtifactQueries {
        override suspend fun list(projectId: String, filter: ArtifactFilter): List<ArtifactSummary> = notWired("artifacts.list")
        override suspend fun get(artifactId: String): ArtifactDetail? = notWired("artifacts.get")
        override suspend fun getAuditTrail(artifactId: String): List<AuditEntry> = notWired("artifacts.getAuditTrail")
    }

    override val events = object : EventSubscriptions {
        override fun subscribe(filter: EventFilter): Flow<RuntimeEvent> = callbackFlow {
            val (channel, reader, output) = handshake()
            val id = UUID.randomUUID().toString()
            writeLine(output, buildJsonObject {
                put("id", id)
                put("method", Methods.EVENTS_SUBSCRIBE)
                put("params", buildJsonObject { put("filter", Wire.encodeEventFilter(filter)) })
            })
            // subscribe ack -- {"id":..., "ok":true}, no "result" (the stream itself is the result)
            if (reader.readLine() == null) {
                channel.close()
                close(SocketConnectionException("daemon closed the connection"))
                return@callbackFlow
            }

            // `reader.readLine()` is a blocking java.io call, not a suspension point -- ordinary
            // Flow cancellation (a downstream `take(n)`, a collector's `withTimeout`) does not
            // interrupt it, and `take(n)`'s own abort signal travels back through this producer
            // asynchronously, not synchronously with any one `trySend` call -- confirmed by an
            // end-to-end run where two events were read and sent before a `take(1)` collector's
            // cancellation ever reached this loop, wedging it on a third, never-arriving read.
            // A raw thread plus `awaitClose` (callbackFlow's contract: always runs, exactly once,
            // for every reason a flow's collection can end) is what actually guarantees the read
            // unblocks: closing `channel` from another thread makes a blocked NIO read throw.
            val readerThread = Thread({
                try {
                    while (true) {
                        val line = reader.readLine() ?: break
                        val json = runCatching { Json.parseToJsonElement(line).jsonObject }.getOrNull() ?: continue
                        if (json["type"]?.jsonPrimitive?.content == "event") {
                            val event = Wire.decodeRuntimeEvent(json["event"]!!.jsonObject)
                            if (trySend(event).isFailure) break
                        }
                    }
                } catch (e: Exception) {
                    // channel closed underneath us (awaitClose fired) -- normal termination
                } finally {
                    close()
                }
            }, "aidos-events-subscribe-reader").apply { isDaemon = true }
            readerThread.start()

            awaitClose {
                runCatching { channel.close() }
                readerThread.join(2_000)
            }
        }
    }

    override val runtime = object : RuntimeInfo {
        override suspend fun version(): RuntimeVersion = withContext(Dispatchers.IO) {
            Wire.decodeRuntimeVersion(call(Methods.RUNTIME_VERSION, JsonObject(emptyMap())).jsonObject)
        }
        override suspend fun ping(): Boolean = withContext(Dispatchers.IO) {
            call(Methods.RUNTIME_PING, JsonObject(emptyMap())).jsonPrimitive.boolean
        }
    }
}
