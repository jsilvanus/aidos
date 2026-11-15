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
import dev.aidos.api.CreateIntentNodeRequest
import dev.aidos.api.CreateIntentProposalRequest
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
import dev.aidos.api.IntentCommands
import dev.aidos.api.KnowledgeQueries
import dev.aidos.api.KnowledgeQuery
import dev.aidos.api.KnowledgeResult
import dev.aidos.api.ParkedEgressRequest
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
import dev.aidos.api.UpdateIntentNodeRequest
import dev.aidos.api.UserMessage
import dev.aidos.api.socket.Methods
import dev.aidos.api.socket.Wire
import dev.aidos.api.socket.WireExtended
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import dev.aidos.intent.EffectiveDispatchPolicy
import dev.aidos.intent.IntentActorKind
import dev.aidos.intent.IntentEdgeKind
import dev.aidos.intent.IntentEdgeRecord
import dev.aidos.intent.IntentMinLocality
import dev.aidos.intent.IntentNodeRecord
import dev.aidos.intent.IntentNodeStatusView
import dev.aidos.intent.IntentOperation
import dev.aidos.intent.IntentProposal
import dev.aidos.intent.ProposalResolutionDecision
import dev.aidos.intent.SnapshotReconcileResult
import dev.aidos.kernel.FileDiff
import dev.aidos.kernel.PlatformProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
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
    private val minApiVersion: Int = Methods.CURRENT_API_VERSION,
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
        writeLine(
            output,
            buildJsonObject {
                put("token", token())
                put("interactive", interactive)
                put("minApiVersion", minApiVersion)
            }
        )
        val ackLine = reader.readLine()
            ?: throw SocketConnectionException("daemon closed the connection during handshake")
        val ack = runCatching { Json.parseToJsonElement(ackLine).jsonObject }
            .getOrElse { throw SocketConnectionException("malformed handshake response: $ackLine") }
        if (ack["ok"]?.jsonPrimitive?.boolean != true) {
            channel.close()
            throw SocketConnectionException("handshake rejected by daemon: $ackLine")
        }
        val serverApi = ack["apiVersion"]?.jsonPrimitive?.content?.toIntOrNull()
        if (serverApi != null && serverApi < minApiVersion) {
            channel.close()
            throw SocketConnectionException("api.version_incompatible: server API $serverApi < required $minApiVersion")
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

    /** RFC-0115: the daemon's vault. Returns (mode, locked); `mode` is `os` or `passphrase`. */
    fun vaultStatus(): Pair<String, Boolean> = vaultState(call(Methods.VAULT_STATUS, JsonObject(emptyMap())))

    /** Unlocks a passphrase vault. A wrong passphrase raises [SocketConnectionException] with the daemon's message. */
    fun vaultUnlock(passphrase: CharArray): Pair<String, Boolean> =
        vaultState(call(Methods.VAULT_UNLOCK, buildJsonObject { put("passphrase", String(passphrase)) })).also { passphrase.fill('\u0000') }

    fun vaultLock(): Pair<String, Boolean> = vaultState(call(Methods.VAULT_LOCK, JsonObject(emptyMap())))

    private fun vaultState(e: JsonElement): Pair<String, Boolean> =
        e.jsonObject.let { it["mode"]!!.jsonPrimitive.content to it["locked"]!!.jsonPrimitive.boolean }

    private fun writeLine(output: OutputStream, json: JsonObject) {
        output.write((json.toString() + "\n").toByteArray(StandardCharsets.UTF_8))
        output.flush()
    }

    override val projects = object : ProjectCommands {
        override suspend fun create(request: CreateProjectRequest): ProjectResult = withContext(Dispatchers.IO) {
            Wire.decodeProjectResult(call(Methods.PROJECTS_CREATE, Wire.encodeCreateProjectRequest(request)).jsonObject)
        }
        override suspend fun open(projectId: String): ProjectResult = withContext(Dispatchers.IO) {
            Wire.decodeProjectResult(call(Methods.PROJECTS_OPEN, buildJsonObject { put("projectId", projectId) }).jsonObject)
        }
        override suspend fun close(projectId: String): Unit = withContext(Dispatchers.IO) {
            call(Methods.PROJECTS_CLOSE, buildJsonObject { put("projectId", projectId) }).let { }
        }
        override suspend fun list(): List<ProjectSummary> = withContext(Dispatchers.IO) {
            Wire.decodeProjectSummaryList(call(Methods.PROJECTS_LIST, JsonObject(emptyMap())).jsonArray)
        }
        override suspend fun get(projectId: String): ProjectDetail? = withContext(Dispatchers.IO) {
            val res = call(Methods.PROJECTS_GET, buildJsonObject { put("projectId", projectId) })
            if (res is JsonNull) null else WireExtended.decodeProjectDetail(res.jsonObject)
        }
        override suspend fun delete(projectId: String, confirm: Boolean): Unit = withContext(Dispatchers.IO) {
            call(
                Methods.PROJECTS_DELETE,
                buildJsonObject {
                    put("projectId", projectId)
                    put("confirm", confirm)
                },
            ).let { }
        }
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
        override suspend fun cancel(sessionId: String, runId: String): Unit = withContext(Dispatchers.IO) {
            call(Methods.SESSIONS_CANCEL, buildJsonObject { put("sessionId", sessionId); put("runId", runId) }).let { }
        }
        override suspend fun list(projectId: String): List<SessionSummary> = withContext(Dispatchers.IO) {
            Wire.decodeSessionSummaryList(
                call(Methods.SESSIONS_LIST, buildJsonObject { put("projectId", projectId) }).jsonArray
            )
        }
        override suspend fun get(sessionId: String): SessionDetail? = withContext(Dispatchers.IO) {
            val res = call(Methods.SESSIONS_GET, buildJsonObject { put("sessionId", sessionId) })
            if (res is JsonNull) null else WireExtended.decodeSessionDetail(res.jsonObject)
        }
        override suspend fun archive(sessionId: String): Unit = withContext(Dispatchers.IO) {
            call(Methods.SESSIONS_ARCHIVE, buildJsonObject { put("sessionId", sessionId) }).let { }
        }
        override suspend fun delete(sessionId: String): Unit = withContext(Dispatchers.IO) {
            call(Methods.SESSIONS_DELETE, buildJsonObject { put("sessionId", sessionId) }).let { }
        }
    }

    override val capabilities = object : CapabilityCommands {
        override suspend fun grant(request: GrantCapabilityRequest): CapabilityResult = withContext(Dispatchers.IO) {
            Wire.decodeCapabilityResult(call(Methods.CAPABILITIES_GRANT, Wire.encodeGrantCapabilityRequest(request)).jsonObject)
        }
        override suspend fun revoke(capabilityId: String): Unit = withContext(Dispatchers.IO) {
            call(Methods.CAPABILITIES_REVOKE, buildJsonObject { put("capabilityId", capabilityId) }).let { }
        }
        override suspend fun list(sessionId: String): List<CapabilitySummary> = withContext(Dispatchers.IO) {
            WireExtended.decodeCapabilitySummaryList(
                call(Methods.CAPABILITIES_LIST, buildJsonObject { put("sessionId", sessionId) }).jsonArray
            )
        }
        override suspend fun listPending(): List<PendingCapabilityRequest> = withContext(Dispatchers.IO) {
            Wire.decodePendingCapabilityList(call(Methods.CAPABILITIES_LIST_PENDING, JsonObject(emptyMap())).jsonArray)
        }
        override suspend fun listParkedEgress(): List<ParkedEgressRequest> = withContext(Dispatchers.IO) {
            Wire.decodeParkedEgressList(call(Methods.CAPABILITIES_LIST_PARKED_EGRESS, JsonObject(emptyMap())).jsonArray)
        }
        override suspend fun approve(requestId: String): CapabilityResult = withContext(Dispatchers.IO) {
            val params = buildJsonObject { put("requestId", requestId) }
            Wire.decodeCapabilityResult(call(Methods.CAPABILITIES_APPROVE, params).jsonObject)
        }
        override suspend fun deny(requestId: String, reason: String): Unit = withContext(Dispatchers.IO) {
            call(Methods.CAPABILITIES_DENY, buildJsonObject { put("requestId", requestId); put("reason", reason) }).let { }
        }
        override suspend fun approveEffect(runId: String, taskId: String): CapabilityResult =
            approveEffect(runId, taskId, approvalChannel = "tap", approvalPhrase = null)
        override suspend fun approveEffect(
            runId: String,
            taskId: String,
            approvalChannel: String,
            approvalPhrase: String?,
        ): CapabilityResult = withContext(Dispatchers.IO) {
            val params = buildJsonObject {
                put("runId", runId)
                put("taskId", taskId)
                put("approvalChannel", approvalChannel)
                approvalPhrase?.let { put("approvalPhrase", it) }
            }
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
        override suspend fun search(projectId: String, query: KnowledgeQuery): KnowledgeResult = withContext(Dispatchers.IO) {
            val params = buildJsonObject {
                put("projectId", projectId)
                put("query", WireExtended.encodeKnowledgeQuery(query))
            }
            WireExtended.decodeKnowledgeResult(call(Methods.KNOWLEDGE_SEARCH, params).jsonObject)
        }
        override suspend fun indexStatus(projectId: String): IndexStatus = withContext(Dispatchers.IO) {
            WireExtended.decodeIndexStatus(
                call(Methods.KNOWLEDGE_INDEX_STATUS, buildJsonObject { put("projectId", projectId) }).jsonObject
            )
        }
    }

    override val diff = object : DiffQueries {
        override suspend fun changes(projectId: String, range: DiffRange): DiffSummary = withContext(Dispatchers.IO) {
            val params = buildJsonObject {
                put("projectId", projectId)
                put("range", WireExtended.encodeDiffRange(range))
            }
            WireExtended.decodeDiffSummary(call(Methods.DIFF_CHANGES, params).jsonObject)
        }
        override suspend fun hunks(projectId: String, range: DiffRange, path: String): Result<FileDiff> = withContext(Dispatchers.IO) {
            val params = buildJsonObject {
                put("projectId", projectId)
                put("range", WireExtended.encodeDiffRange(range))
                put("path", path)
            }
            val res = call(Methods.DIFF_HUNKS, params).jsonObject
            if (res["ok"]?.jsonPrimitive?.boolean == true) {
                Result.success(WireExtended.decodeFileDiff(res["diff"]!!.jsonObject))
            } else {
                Result.failure(IllegalStateException(res["error"]?.jsonPrimitive?.content ?: "diff.hunks failed"))
            }
        }
        override suspend fun unified(projectId: String, range: DiffRange, path: String): Result<String> = withContext(Dispatchers.IO) {
            val params = buildJsonObject {
                put("projectId", projectId)
                put("range", WireExtended.encodeDiffRange(range))
                put("path", path)
            }
            val res = call(Methods.DIFF_UNIFIED, params).jsonObject
            if (res["ok"]?.jsonPrimitive?.boolean == true) {
                Result.success(res["text"]!!.jsonPrimitive.content)
            } else {
                Result.failure(IllegalStateException(res["error"]?.jsonPrimitive?.content ?: "diff.unified failed"))
            }
        }
        override suspend fun stage(projectId: String, hunks: List<HunkId>): Result<Unit> = withContext(Dispatchers.IO) {
            val params = buildJsonObject {
                put("projectId", projectId)
                put("hunks", WireExtended.encodeHunkIds(hunks))
            }
            val res = call(Methods.DIFF_STAGE, params).jsonObject
            if (res["ok"]?.jsonPrimitive?.boolean == true) {
                Result.success(Unit)
            } else {
                Result.failure(IllegalStateException(res["error"]?.jsonPrimitive?.content ?: "diff.stage failed"))
            }
        }
        override suspend fun revert(projectId: String, hunks: List<HunkId>): Result<Unit> = withContext(Dispatchers.IO) {
            val params = buildJsonObject {
                put("projectId", projectId)
                put("hunks", WireExtended.encodeHunkIds(hunks))
            }
            val res = call(Methods.DIFF_REVERT, params).jsonObject
            if (res["ok"]?.jsonPrimitive?.boolean == true) {
                Result.success(Unit)
            } else {
                Result.failure(IllegalStateException(res["error"]?.jsonPrimitive?.content ?: "diff.revert failed"))
            }
        }
        override suspend fun commit(projectId: String, message: String): CommitResult = withContext(Dispatchers.IO) {
            val params = buildJsonObject {
                put("projectId", projectId)
                put("message", message)
            }
            WireExtended.decodeCommitResult(call(Methods.DIFF_COMMIT, params).jsonObject)
        }
    }

    override val artifacts = object : ArtifactQueries {
        override suspend fun list(projectId: String, filter: ArtifactFilter): List<ArtifactSummary> = withContext(Dispatchers.IO) {
            val params = buildJsonObject {
                put("projectId", projectId)
                put("filter", WireExtended.encodeArtifactFilter(filter))
            }
            call(Methods.ARTIFACTS_LIST, params).jsonArray.map { WireExtended.decodeArtifactSummary(it.jsonObject) }
        }
        override suspend fun get(artifactId: String): ArtifactDetail? = withContext(Dispatchers.IO) {
            val res = call(Methods.ARTIFACTS_GET, buildJsonObject { put("artifactId", artifactId) })
            if (res is JsonNull) null else WireExtended.decodeArtifactDetail(res.jsonObject)
        }
        override suspend fun getAuditTrail(artifactId: String): List<AuditEntry> = withContext(Dispatchers.IO) {
            WireExtended.decodeAuditEntryList(
                call(Methods.ARTIFACTS_AUDIT_TRAIL, buildJsonObject { put("artifactId", artifactId) }).jsonArray
            )
        }
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

    override val intents = object : IntentCommands {
        override suspend fun createNode(request: CreateIntentNodeRequest): IntentNodeRecord = withContext(Dispatchers.IO) {
            WireExtended.decodeSerializable(
                call(Methods.INTENTS_CREATE_NODE, WireExtended.encodeCreateIntentNodeRequest(request))
            )
        }

        override suspend fun updateNode(request: UpdateIntentNodeRequest): IntentNodeRecord = withContext(Dispatchers.IO) {
            WireExtended.decodeSerializable(
                call(Methods.INTENTS_UPDATE_NODE, WireExtended.encodeUpdateIntentNodeRequest(request))
            )
        }

        override suspend fun archiveNode(projectId: String, nodeId: String): IntentNodeRecord = withContext(Dispatchers.IO) {
            val params = buildJsonObject { put("projectId", projectId); put("nodeId", nodeId) }
            WireExtended.decodeSerializable(call(Methods.INTENTS_ARCHIVE_NODE, params))
        }

        override suspend fun getNodeStatus(
            projectId: String,
            nodeId: String,
            projectDefaultPolicy: EffectiveDispatchPolicy,
        ): IntentNodeStatusView? = withContext(Dispatchers.IO) {
            val params = buildJsonObject {
                put("projectId", projectId)
                put("nodeId", nodeId)
                put("projectDefaultPolicy", projectDefaultPolicy.name)
            }
            val res = call(Methods.INTENTS_GET_NODE_STATUS, params)
            if (res is JsonNull) null else WireExtended.decodeSerializable(res)
        }

        override suspend fun listNodes(
            projectId: String,
            includeArchived: Boolean,
            actorKind: IntentActorKind?,
            parentId: String?,
            filterByParent: Boolean,
        ): List<IntentNodeRecord> = withContext(Dispatchers.IO) {
            val params = buildJsonObject {
                put("projectId", projectId)
                put("includeArchived", includeArchived)
                actorKind?.let { put("actorKind", it.name) }
                parentId?.let { put("parentId", it) }
                put("filterByParent", filterByParent)
            }
            WireExtended.decodeSerializable(call(Methods.INTENTS_LIST_NODES, params))
        }

        override suspend fun listActionableForHuman(
            projectId: String,
            projectDefaultPolicy: EffectiveDispatchPolicy,
        ): List<IntentNodeStatusView> = withContext(Dispatchers.IO) {
            val params = buildJsonObject {
                put("projectId", projectId)
                put("projectDefaultPolicy", projectDefaultPolicy.name)
            }
            WireExtended.decodeSerializable(call(Methods.INTENTS_LIST_ACTIONABLE, params))
        }

        override suspend fun listReadyForAgent(
            projectId: String,
            profile: PlatformProfile,
            locality: IntentMinLocality,
            projectDefaultPolicy: EffectiveDispatchPolicy,
            onlyAutoDispatch: Boolean,
        ): List<IntentNodeStatusView> = withContext(Dispatchers.IO) {
            val params = buildJsonObject {
                put("projectId", projectId)
                put("profile", profile.name)
                put("locality", locality.name)
                put("projectDefaultPolicy", projectDefaultPolicy.name)
                put("onlyAutoDispatch", onlyAutoDispatch)
            }
            WireExtended.decodeSerializable(call(Methods.INTENTS_LIST_READY, params))
        }

        override suspend fun completeHumanNode(
            projectId: String,
            nodeId: String,
            userId: String,
            note: String?,
        ): IntentNodeRecord = withContext(Dispatchers.IO) {
            val params = buildJsonObject {
                put("projectId", projectId)
                put("nodeId", nodeId)
                put("userId", userId)
                note?.let { put("note", it) }
            }
            WireExtended.decodeSerializable(call(Methods.INTENTS_COMPLETE_HUMAN_NODE, params))
        }

        override suspend fun addEdge(
            projectId: String,
            fromNodeId: String,
            toNodeId: String,
            edgeKind: IntentEdgeKind,
        ): IntentEdgeRecord = withContext(Dispatchers.IO) {
            val params = buildJsonObject {
                put("projectId", projectId)
                put("fromNodeId", fromNodeId)
                put("toNodeId", toNodeId)
                put("edgeKind", edgeKind.name)
            }
            WireExtended.decodeSerializable(call(Methods.INTENTS_ADD_EDGE, params))
        }

        override suspend fun removeEdge(
            projectId: String,
            fromNodeId: String,
            toNodeId: String,
            edgeKind: IntentEdgeKind,
        ): Boolean = withContext(Dispatchers.IO) {
            val params = buildJsonObject {
                put("projectId", projectId)
                put("fromNodeId", fromNodeId)
                put("toNodeId", toNodeId)
                put("edgeKind", edgeKind.name)
            }
            call(Methods.INTENTS_REMOVE_EDGE, params).jsonPrimitive.boolean
        }

        override suspend fun listEdges(
            projectId: String,
            fromNodeId: String?,
            toNodeId: String?,
            edgeKind: IntentEdgeKind?,
        ): List<IntentEdgeRecord> = withContext(Dispatchers.IO) {
            val params = buildJsonObject {
                put("projectId", projectId)
                fromNodeId?.let { put("fromNodeId", it) }
                toNodeId?.let { put("toNodeId", it) }
                edgeKind?.let { put("edgeKind", it.name) }
            }
            WireExtended.decodeSerializable(call(Methods.INTENTS_LIST_EDGES, params))
        }

        override suspend fun propose(request: CreateIntentProposalRequest): String = withContext(Dispatchers.IO) {
            call(Methods.INTENTS_PROPOSE, WireExtended.encodeCreateIntentProposalRequest(request)).jsonPrimitive.content
        }

        override suspend fun listProposals(projectId: String, state: String?): List<IntentProposal> = withContext(Dispatchers.IO) {
            val params = buildJsonObject {
                put("projectId", projectId)
                state?.let { put("state", it) }
            }
            WireExtended.decodeSerializable(call(Methods.INTENTS_LIST_PROPOSALS, params))
        }

        override suspend fun resolveProposal(
            projectId: String,
            proposalId: String,
            decision: ProposalResolutionDecision,
            resolvedByUserId: String,
            editedOperations: List<IntentOperation>?,
        ): IntentProposal = withContext(Dispatchers.IO) {
            val params = buildJsonObject {
                put("projectId", projectId)
                put("proposalId", proposalId)
                put("decision", decision.name)
                put("resolvedByUserId", resolvedByUserId)
                editedOperations?.let { put("editedOperations", WireExtended.encodeSerializable(it)) }
            }
            WireExtended.decodeSerializable(call(Methods.INTENTS_RESOLVE_PROPOSAL, params))
        }

        override suspend fun confirmImplementation(
            projectId: String,
            nodeId: String,
            confirmedByKind: String,
            confirmedById: String,
            criterionIds: List<String>?,
        ) = withContext(Dispatchers.IO) {
            val params = buildJsonObject {
                put("projectId", projectId)
                put("nodeId", nodeId)
                put("confirmedByKind", confirmedByKind)
                put("confirmedById", confirmedById)
                criterionIds?.let { ids -> put("criterionIds", buildJsonArray { ids.forEach { add(it) } }) }
            }
            call(Methods.INTENTS_CONFIRM_IMPLEMENTATION, params).let { }
        }

        override suspend fun exportSnapshotJson(projectId: String): String = withContext(Dispatchers.IO) {
            call(Methods.INTENTS_EXPORT_SNAPSHOT, buildJsonObject { put("projectId", projectId) }).jsonPrimitive.content
        }

        override suspend fun importAndReconcileSnapshot(
            projectId: String,
            snapshotJson: String,
        ): SnapshotReconcileResult = withContext(Dispatchers.IO) {
            val params = buildJsonObject {
                put("projectId", projectId)
                put("snapshotJson", snapshotJson)
            }
            WireExtended.decodeSerializable(call(Methods.INTENTS_IMPORT_SNAPSHOT, params))
        }
    }

    override val runtime = object : RuntimeInfo {
        override suspend fun version(): RuntimeVersion = withContext(Dispatchers.IO) {
            Wire.decodeRuntimeVersion(call(Methods.RUNTIME_VERSION, JsonObject(emptyMap())).jsonObject)
        }
        override suspend fun ping(): Boolean = withContext(Dispatchers.IO) {
            call(Methods.RUNTIME_PING, JsonObject(emptyMap())).jsonPrimitive.boolean
        }
        override suspend fun profile(): dev.aidos.kernel.PlatformProfile = withContext(Dispatchers.IO) {
            dev.aidos.kernel.PlatformProfile.valueOf(
                call(Methods.RUNTIME_PROFILE, JsonObject(emptyMap())).jsonPrimitive.content
            )
        }
        override suspend fun availability(projectId: String): dev.aidos.kernel.AvailabilityReport = withContext(Dispatchers.IO) {
            val params = buildJsonObject { put("projectId", projectId) }
            Wire.decodeAvailabilityReport(call(Methods.RUNTIME_AVAILABILITY, params).jsonObject)
        }
    }
}
