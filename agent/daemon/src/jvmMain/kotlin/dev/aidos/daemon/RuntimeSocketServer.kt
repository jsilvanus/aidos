package dev.aidos.daemon

import dev.aidos.api.RuntimeClient
import dev.aidos.api.socket.Methods
import dev.aidos.api.socket.SocketPaths
import dev.aidos.api.socket.Wire
import dev.aidos.api.socket.WireExtended
import dev.aidos.intent.EffectiveDispatchPolicy
import dev.aidos.intent.IntentActorKind
import dev.aidos.intent.IntentEdgeKind
import dev.aidos.intent.IntentMinLocality
import dev.aidos.intent.IntentOperation
import dev.aidos.intent.ProposalResolutionDecision
import dev.aidos.kernel.PlatformProfile
import kotlinx.coroutines.CompletableDeferred
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
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
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
import java.nio.file.StandardCopyOption
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
    /** RFC-0115: answers `vault.*`; null means this daemon exposes no vault control (the methods are not wired). */
    private val vaultGate: DaemonVaultGate? = null,
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

        // Bind to a sibling path and rename into place: clients (and RealSocketIntegrationTest) treat
        // the socket file appearing as "the daemon is listening", but bind() creates the file a
        // moment before the listen() inside it completes, and a client landing in that gap gets
        // "Connection refused" on a loaded runner. A rename is atomic, so the final path only ever
        // names a socket that is already listening (and already 0600).
        val bindPath = socketPath.resolveSibling(socketPath.fileName.toString() + ".binding")
        Files.deleteIfExists(bindPath)
        val channel = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
        channel.bind(UnixDomainSocketAddress.of(bindPath))
        serverChannel = channel
        restrictPermissions(bindPath)
        Files.move(bindPath, socketPath, StandardCopyOption.ATOMIC_MOVE)

        val runScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = runScope
        // launch() schedules the accept loop but does not wait for it to actually start running
        // -- start() would return, making the socket+token files visible, before any thread is
        // blocked in channel.accept(). A client connecting in that window sees "Connection
        // refused" even though bind() already succeeded (observed: RealSocketIntegrationTest,
        // three different tests failing across independent runs, all at this exact race). The
        // deferred is completed as the very first thing acceptLoop does, before its blocking
        // accept() call, so awaiting it here closes the gap between "launched" and "listening".
        val accepting = CompletableDeferred<Unit>()
        acceptJob = runScope.launch { acceptLoop(channel, runScope, accepting) }
        accepting.await()
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
        // RFC-0115 / D57: restricted before the token is written, and a failure to restrict is an error (on
        // Windows this used to be a silent no-op, leaving the token as readable as its directory).
        dev.aidos.api.socket.OwnerOnlyFiles.writeSecret(tokenPath, connectionToken)
    }

    private fun restrictPermissions(path: Path) {
        // Not all filesystems support POSIX permissions (e.g. some CI containers); best-effort.
        runCatching { Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------")) }
    }

    private suspend fun acceptLoop(channel: ServerSocketChannel, runScope: CoroutineScope, ready: CompletableDeferred<Unit>) {
        ready.complete(Unit)
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
                val request = parseBounded(line) ?: break
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

    /** RFC-0039: external input is size- and depth-checked before it is parsed; null means reject. */
    private fun parseBounded(line: String): JsonObject? = runCatching {
        dev.aidos.storage.VersionedJson.validateLimits(line)
        Json.parseToJsonElement(line).jsonObject
    }.getOrNull()

    /** Returns the connection's `user_interactive` flag on success, null if the handshake failed. */
    private fun handshake(reader: BufferedReader, output: OutputStream): Boolean? {
        val helloLine = reader.readLine() ?: return null
        val hello = parseBounded(helloLine)
        val presentedToken = hello?.get("token")?.jsonPrimitive?.content
        val interactive = hello?.get("interactive")?.jsonPrimitive?.boolean ?: false
        val clientMinApi = hello?.get("minApiVersion")?.jsonPrimitive?.intOrNull ?: 1

        if (presentedToken != connectionToken) {
            writeLine(output, buildJsonObject { put("ok", false); put("error", "unauthorized") })
            return null
        }
        if (clientMinApi > Methods.CURRENT_API_VERSION) {
            writeLine(
                output,
                buildJsonObject {
                    put("ok", false)
                    put("error", "api.version_incompatible")
                    put("message", "Client requires API >= $clientMinApi, runtime supports ${Methods.CURRENT_API_VERSION}")
                }
            )
            return null
        }
        writeLine(
            output,
            buildJsonObject {
                put("ok", true)
                put("apiVersion", Methods.CURRENT_API_VERSION)
                put("minApiVersion", Methods.MIN_SUPPORTED_API_VERSION)
            }
        )
        return interactive
    }

    private fun vaultState(g: DaemonVaultGate): JsonElement = buildJsonObject { put("mode", g.mode()); put("locked", g.locked()) }

    private suspend fun dispatch(method: String, params: JsonObject): JsonElement = when (method) {
        Methods.VAULT_STATUS -> vaultState(vaultGate ?: throw UnsupportedOperationException(method))
        Methods.VAULT_UNLOCK -> {
            val g = vaultGate ?: throw UnsupportedOperationException(method)
            g.unlock((params["passphrase"]?.jsonPrimitive?.content ?: "").toCharArray())
            vaultState(g)
        }
        Methods.VAULT_LOCK -> {
            val g = vaultGate ?: throw UnsupportedOperationException(method)
            g.lock()
            vaultState(g)
        }
        Methods.PROJECTS_CREATE ->
            Wire.encodeProjectResult(client.projects.create(Wire.decodeCreateProjectRequest(params)))
        Methods.PROJECTS_OPEN ->
            Wire.encodeProjectResult(client.projects.open(params["projectId"]!!.jsonPrimitive.content))
        Methods.PROJECTS_LIST ->
            Wire.encodeProjectSummaryList(client.projects.list())
        Methods.PROJECTS_GET ->
            client.projects.get(params["projectId"]!!.jsonPrimitive.content)
                ?.let(WireExtended::encodeProjectDetail) ?: JsonNull
        Methods.PROJECTS_CLOSE -> {
            client.projects.close(params["projectId"]!!.jsonPrimitive.content)
            JsonObject(emptyMap())
        }
        Methods.PROJECTS_DELETE -> {
            client.projects.delete(
                params["projectId"]!!.jsonPrimitive.content,
                params["confirm"]?.jsonPrimitive?.booleanOrNull ?: false,
            )
            JsonObject(emptyMap())
        }
        Methods.SESSIONS_CREATE ->
            Wire.encodeSessionResult(client.sessions.create(Wire.decodeCreateSessionRequest(params)))
        Methods.SESSIONS_LIST ->
            Wire.encodeSessionSummaryList(client.sessions.list(params["projectId"]!!.jsonPrimitive.content))
        Methods.SESSIONS_GET ->
            client.sessions.get(params["sessionId"]!!.jsonPrimitive.content)
                ?.let(WireExtended::encodeSessionDetail) ?: JsonNull
        Methods.SESSIONS_SEND -> Wire.encodeRunResult(
            client.sessions.send(
                params["sessionId"]!!.jsonPrimitive.content,
                Wire.decodeUserMessage(params["message"]!!.jsonObject),
            )
        )
        Methods.SESSIONS_CANCEL -> {
            client.sessions.cancel(
                params["sessionId"]!!.jsonPrimitive.content,
                params["runId"]!!.jsonPrimitive.content,
            )
            JsonObject(emptyMap())
        }
        Methods.SESSIONS_ARCHIVE -> {
            client.sessions.archive(params["sessionId"]!!.jsonPrimitive.content)
            JsonObject(emptyMap())
        }
        Methods.SESSIONS_DELETE -> {
            client.sessions.delete(params["sessionId"]!!.jsonPrimitive.content)
            JsonObject(emptyMap())
        }
        Methods.CAPABILITIES_GRANT ->
            Wire.encodeCapabilityResult(client.capabilities.grant(Wire.decodeGrantCapabilityRequest(params)))
        Methods.CAPABILITIES_REVOKE -> {
            client.capabilities.revoke(params["capabilityId"]!!.jsonPrimitive.content)
            JsonObject(emptyMap())
        }
        Methods.CAPABILITIES_LIST ->
            WireExtended.encodeCapabilitySummaryList(
                client.capabilities.list(params["sessionId"]!!.jsonPrimitive.content)
            )
        Methods.CAPABILITIES_LIST_PENDING ->
            Wire.encodePendingCapabilityList(client.capabilities.listPending())
        Methods.CAPABILITIES_LIST_PARKED_EGRESS ->
            Wire.encodeParkedEgressList(client.capabilities.listParkedEgress())
        Methods.CAPABILITIES_APPROVE ->
            Wire.encodeCapabilityResult(client.capabilities.approve(params["requestId"]!!.jsonPrimitive.content))
        Methods.CAPABILITIES_DENY -> {
            client.capabilities.deny(
                params["requestId"]!!.jsonPrimitive.content,
                params["reason"]?.jsonPrimitive?.content ?: "",
            )
            JsonObject(emptyMap())
        }
        Methods.CAPABILITIES_APPROVE_EFFECT -> Wire.encodeCapabilityResult(
            client.capabilities.approveEffect(
                runId = params["runId"]!!.jsonPrimitive.content,
                taskId = params["taskId"]?.jsonPrimitive?.content ?: "",
                approvalChannel = params["approvalChannel"]?.jsonPrimitive?.contentOrNull ?: "tap",
                approvalPhrase = params["approvalPhrase"]?.jsonPrimitive?.contentOrNull,
            )
        )
        Methods.CAPABILITIES_DENY_EFFECT -> {
            client.capabilities.denyEffect(
                params["runId"]!!.jsonPrimitive.content,
                params["taskId"]?.jsonPrimitive?.content ?: "",
                params["reason"]?.jsonPrimitive?.content ?: "",
            )
            JsonObject(emptyMap())
        }
        Methods.CAPABILITIES_ANSWER_PROMPT -> Wire.encodeCapabilityResult(
            client.capabilities.answerPrompt(
                params["runId"]!!.jsonPrimitive.content,
                params["answer"]!!.jsonPrimitive.content,
            )
        )
        Methods.KNOWLEDGE_SEARCH -> WireExtended.encodeKnowledgeResult(
            client.knowledge.search(
                params["projectId"]!!.jsonPrimitive.content,
                WireExtended.decodeKnowledgeQuery(params["query"]!!.jsonObject),
            )
        )
        Methods.KNOWLEDGE_INDEX_STATUS -> WireExtended.encodeIndexStatus(
            client.knowledge.indexStatus(params["projectId"]!!.jsonPrimitive.content)
        )
        Methods.DIFF_CHANGES -> WireExtended.encodeDiffSummary(
            client.diff.changes(
                params["projectId"]!!.jsonPrimitive.content,
                WireExtended.decodeDiffRange(params["range"]!!.jsonObject),
            )
        )
        Methods.DIFF_HUNKS -> {
            val res = client.diff.hunks(
                params["projectId"]!!.jsonPrimitive.content,
                WireExtended.decodeDiffRange(params["range"]!!.jsonObject),
                params["path"]!!.jsonPrimitive.content,
            )
            res.fold(
                onSuccess = { buildJsonObject { put("ok", true); put("diff", WireExtended.encodeFileDiff(it)) } },
                onFailure = { buildJsonObject { put("ok", false); put("error", it.message ?: "diff.hunks failed") } },
            )
        }
        Methods.DIFF_UNIFIED -> {
            val res = client.diff.unified(
                params["projectId"]!!.jsonPrimitive.content,
                WireExtended.decodeDiffRange(params["range"]!!.jsonObject),
                params["path"]!!.jsonPrimitive.content,
            )
            res.fold(
                onSuccess = { buildJsonObject { put("ok", true); put("text", it) } },
                onFailure = { buildJsonObject { put("ok", false); put("error", it.message ?: "diff.unified failed") } },
            )
        }
        Methods.DIFF_STAGE -> {
            val res = client.diff.stage(
                params["projectId"]!!.jsonPrimitive.content,
                WireExtended.decodeHunkIds(params["hunks"]!!.jsonArray),
            )
            res.fold(
                onSuccess = { buildJsonObject { put("ok", true) } },
                onFailure = { buildJsonObject { put("ok", false); put("error", it.message ?: "diff.stage failed") } },
            )
        }
        Methods.DIFF_REVERT -> {
            val res = client.diff.revert(
                params["projectId"]!!.jsonPrimitive.content,
                WireExtended.decodeHunkIds(params["hunks"]!!.jsonArray),
            )
            res.fold(
                onSuccess = { buildJsonObject { put("ok", true) } },
                onFailure = { buildJsonObject { put("ok", false); put("error", it.message ?: "diff.revert failed") } },
            )
        }
        Methods.DIFF_COMMIT -> WireExtended.encodeCommitResult(
            client.diff.commit(
                params["projectId"]!!.jsonPrimitive.content,
                params["message"]!!.jsonPrimitive.content,
            )
        )
        Methods.ARTIFACTS_LIST -> buildJsonArray {
            client.artifacts.list(
                params["projectId"]!!.jsonPrimitive.content,
                WireExtended.decodeArtifactFilter(params["filter"]!!.jsonObject),
            ).forEach { add(WireExtended.encodeArtifactSummary(it)) }
        }
        Methods.ARTIFACTS_GET -> client.artifacts.get(params["artifactId"]!!.jsonPrimitive.content)
            ?.let(WireExtended::encodeArtifactDetail) ?: JsonNull
        Methods.ARTIFACTS_AUDIT_TRAIL -> WireExtended.encodeAuditEntryList(
            client.artifacts.getAuditTrail(params["artifactId"]!!.jsonPrimitive.content)
        )
        Methods.INTENTS_CREATE_NODE -> WireExtended.encodeSerializable(
            client.intents.createNode(WireExtended.decodeCreateIntentNodeRequest(params))
        )
        Methods.INTENTS_UPDATE_NODE -> WireExtended.encodeSerializable(
            client.intents.updateNode(WireExtended.decodeUpdateIntentNodeRequest(params))
        )
        Methods.INTENTS_ARCHIVE_NODE -> WireExtended.encodeSerializable(
            client.intents.archiveNode(
                params["projectId"]!!.jsonPrimitive.content,
                params["nodeId"]!!.jsonPrimitive.content,
            )
        )
        Methods.INTENTS_GET_NODE_STATUS -> client.intents.getNodeStatus(
            params["projectId"]!!.jsonPrimitive.content,
            params["nodeId"]!!.jsonPrimitive.content,
            params["projectDefaultPolicy"]?.jsonPrimitive?.contentOrNull?.let(EffectiveDispatchPolicy::valueOf)
                ?: EffectiveDispatchPolicy.MANUAL_START,
        )?.let { WireExtended.encodeSerializable(it) } ?: JsonNull
        Methods.INTENTS_LIST_NODES -> WireExtended.encodeSerializable(
            client.intents.listNodes(
                projectId = params["projectId"]!!.jsonPrimitive.content,
                includeArchived = params["includeArchived"]?.jsonPrimitive?.booleanOrNull ?: false,
                actorKind = params["actorKind"]?.jsonPrimitive?.contentOrNull?.let(IntentActorKind::valueOf),
                parentId = params["parentId"]?.jsonPrimitive?.contentOrNull,
                filterByParent = params["filterByParent"]?.jsonPrimitive?.booleanOrNull ?: false,
            )
        )
        Methods.INTENTS_LIST_ACTIONABLE -> WireExtended.encodeSerializable(
            client.intents.listActionableForHuman(
                projectId = params["projectId"]!!.jsonPrimitive.content,
                projectDefaultPolicy = params["projectDefaultPolicy"]?.jsonPrimitive?.contentOrNull?.let(EffectiveDispatchPolicy::valueOf)
                    ?: EffectiveDispatchPolicy.MANUAL_START,
            )
        )
        Methods.INTENTS_LIST_READY -> WireExtended.encodeSerializable(
            client.intents.listReadyForAgent(
                projectId = params["projectId"]!!.jsonPrimitive.content,
                profile = params["profile"]?.jsonPrimitive?.contentOrNull?.let(PlatformProfile::valueOf) ?: PlatformProfile.DESKTOP,
                locality = params["locality"]?.jsonPrimitive?.contentOrNull?.let(IntentMinLocality::valueOf) ?: IntentMinLocality.ANY,
                projectDefaultPolicy = params["projectDefaultPolicy"]?.jsonPrimitive?.contentOrNull?.let(EffectiveDispatchPolicy::valueOf)
                    ?: EffectiveDispatchPolicy.MANUAL_START,
                onlyAutoDispatch = params["onlyAutoDispatch"]?.jsonPrimitive?.booleanOrNull ?: false,
            )
        )
        Methods.INTENTS_COMPLETE_HUMAN_NODE -> WireExtended.encodeSerializable(
            client.intents.completeHumanNode(
                projectId = params["projectId"]!!.jsonPrimitive.content,
                nodeId = params["nodeId"]!!.jsonPrimitive.content,
                userId = params["userId"]?.jsonPrimitive?.contentOrNull ?: "local-user",
                note = params["note"]?.jsonPrimitive?.contentOrNull,
            )
        )
        Methods.INTENTS_ADD_EDGE -> WireExtended.encodeSerializable(
            client.intents.addEdge(
                projectId = params["projectId"]!!.jsonPrimitive.content,
                fromNodeId = params["fromNodeId"]!!.jsonPrimitive.content,
                toNodeId = params["toNodeId"]!!.jsonPrimitive.content,
                edgeKind = IntentEdgeKind.valueOf(params["edgeKind"]!!.jsonPrimitive.content),
            )
        )
        Methods.INTENTS_REMOVE_EDGE -> JsonPrimitive(
            client.intents.removeEdge(
                projectId = params["projectId"]!!.jsonPrimitive.content,
                fromNodeId = params["fromNodeId"]!!.jsonPrimitive.content,
                toNodeId = params["toNodeId"]!!.jsonPrimitive.content,
                edgeKind = IntentEdgeKind.valueOf(params["edgeKind"]!!.jsonPrimitive.content),
            )
        )
        Methods.INTENTS_LIST_EDGES -> WireExtended.encodeSerializable(
            client.intents.listEdges(
                projectId = params["projectId"]!!.jsonPrimitive.content,
                fromNodeId = params["fromNodeId"]?.jsonPrimitive?.contentOrNull,
                toNodeId = params["toNodeId"]?.jsonPrimitive?.contentOrNull,
                edgeKind = params["edgeKind"]?.jsonPrimitive?.contentOrNull?.let(IntentEdgeKind::valueOf),
            )
        )
        Methods.INTENTS_PROPOSE -> JsonPrimitive(
            client.intents.propose(WireExtended.decodeCreateIntentProposalRequest(params))
        )
        Methods.INTENTS_LIST_PROPOSALS -> WireExtended.encodeSerializable(
            client.intents.listProposals(
                projectId = params["projectId"]!!.jsonPrimitive.content,
                state = params["state"]?.jsonPrimitive?.contentOrNull,
            )
        )
        Methods.INTENTS_RESOLVE_PROPOSAL -> WireExtended.encodeSerializable(
            client.intents.resolveProposal(
                projectId = params["projectId"]!!.jsonPrimitive.content,
                proposalId = params["proposalId"]!!.jsonPrimitive.content,
                decision = ProposalResolutionDecision.valueOf(params["decision"]!!.jsonPrimitive.content),
                resolvedByUserId = params["resolvedByUserId"]?.jsonPrimitive?.contentOrNull ?: "local-user",
                editedOperations = params["editedOperations"]?.let {
                    WireExtended.decodeSerializable<List<IntentOperation>>(it)
                },
            )
        )
        Methods.INTENTS_CONFIRM_IMPLEMENTATION -> {
            client.intents.confirmImplementation(
                projectId = params["projectId"]!!.jsonPrimitive.content,
                nodeId = params["nodeId"]!!.jsonPrimitive.content,
                confirmedByKind = params["confirmedByKind"]?.jsonPrimitive?.contentOrNull ?: "USER",
                confirmedById = params["confirmedById"]?.jsonPrimitive?.contentOrNull ?: "local-user",
                criterionIds = params["criterionIds"]?.jsonArray?.map { it.jsonPrimitive.content },
            )
            JsonObject(emptyMap())
        }
        Methods.INTENTS_EXPORT_SNAPSHOT -> JsonPrimitive(
            client.intents.exportSnapshotJson(params["projectId"]!!.jsonPrimitive.content)
        )
        Methods.INTENTS_IMPORT_SNAPSHOT -> WireExtended.encodeSerializable(
            client.intents.importAndReconcileSnapshot(
                projectId = params["projectId"]!!.jsonPrimitive.content,
                snapshotJson = params["snapshotJson"]!!.jsonPrimitive.content,
            )
        )
        Methods.RUNTIME_PING ->
            JsonPrimitive(client.runtime.ping())
        Methods.RUNTIME_VERSION ->
            Wire.encodeRuntimeVersion(client.runtime.version())
        Methods.RUNTIME_PROFILE ->
            JsonPrimitive(client.runtime.profile().name)
        Methods.RUNTIME_AVAILABILITY ->
            Wire.encodeAvailabilityReport(client.runtime.availability(params["projectId"]!!.jsonPrimitive.content))
        else -> throw UnsupportedOperationException("method not wired over socket transport: $method")
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
