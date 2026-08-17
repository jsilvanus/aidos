package dev.aidos.api.socket

import dev.aidos.api.CapabilityResult
import dev.aidos.api.CreateProjectRequest
import dev.aidos.api.CreateSessionRequest
import dev.aidos.api.EventFilter
import dev.aidos.api.GrantCapabilityRequest
import dev.aidos.api.PendingCapabilityRequest
import dev.aidos.api.ProjectLocation
import dev.aidos.api.ProjectResult
import dev.aidos.api.ProjectSummary
import dev.aidos.api.RunOptions
import dev.aidos.api.RunResult
import dev.aidos.api.RuntimeEvent
import dev.aidos.api.RuntimeEventType
import dev.aidos.api.RuntimeVersion
import dev.aidos.api.SessionResult
import dev.aidos.api.SessionRole
import dev.aidos.api.SessionState
import dev.aidos.api.SessionSummary
import dev.aidos.api.UserMessage
import kotlinx.datetime.Instant
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

/**
 * Hand-written JSON wire codec for the subset of [dev.aidos.api.RuntimeClient] that the socket
 * transport carries (RFC-0052, M10).
 *
 * The full `RuntimeClient` surface is large (35 methods across 8 sub-interfaces, several with
 * sealed/polymorphic payloads like [dev.aidos.kernel.FileDiff]). Annotating the whole api module
 * `@Serializable` and deriving a generic dispatcher was judged more risk than this milestone's
 * done-when needs: M10 requires project/session/capability/event/runtime-info commands over a
 * real socket, not diff/artifact/knowledge browsing. Those remain in-process-only for now — see
 * PIPELINE.md's M10 note for the explicit list of what is not yet wired here. Hand-written codecs
 * for exactly the methods in scope keep the wire format auditable and avoid reflection on a
 * multiplatform target that does not uniformly support it.
 */
object Wire {

    // ── Project ────────────────────────────────────────────────────────────

    fun encodeCreateProjectRequest(request: CreateProjectRequest): JsonObject = buildJsonObject {
        put("name", request.name)
        put("description", request.description)
        put("initGit", request.initGit)
        request.templateId?.let { put("templateId", it) }
        put("location", when (val loc = request.location) {
            is ProjectLocation.RuntimeManaged -> buildJsonObject {
                put("kind", "runtimeManaged"); put("slug", loc.slug)
            }
            is ProjectLocation.CloneOf -> buildJsonObject {
                put("kind", "cloneOf"); put("remoteUrl", loc.remoteUrl); put("slug", loc.slug)
            }
            is ProjectLocation.LocalPath -> throw UnsupportedOperationException(
                "ProjectLocation.LocalPath is in-process transport only (RFC-0052)"
            )
        })
    }

    fun decodeCreateProjectRequest(json: JsonObject): CreateProjectRequest {
        val loc = json["location"]!!.jsonObject
        val location = when (loc["kind"]!!.jsonPrimitive.content) {
            "runtimeManaged" -> ProjectLocation.RuntimeManaged(loc["slug"]!!.jsonPrimitive.content)
            "cloneOf" -> ProjectLocation.CloneOf(
                loc["remoteUrl"]!!.jsonPrimitive.content,
                loc["slug"]!!.jsonPrimitive.content,
            )
            else -> error("unknown ProjectLocation kind: ${loc["kind"]}")
        }
        return CreateProjectRequest(
            name = json["name"]!!.jsonPrimitive.content,
            description = json["description"]!!.jsonPrimitive.content,
            location = location,
            initGit = json["initGit"]?.jsonPrimitive?.content?.toBoolean() ?: true,
            templateId = json["templateId"]?.jsonPrimitive?.contentOrNull,
        )
    }

    fun encodeProjectSummary(summary: ProjectSummary): JsonObject = buildJsonObject {
        put("id", summary.id)
        put("name", summary.name)
        put("description", summary.description)
        put("projectPath", summary.projectPath)
        put("createdAt", summary.createdAt.toString())
        put("lastActiveAt", summary.lastActiveAt.toString())
        put("sessionCount", summary.sessionCount)
    }

    fun decodeProjectSummary(json: JsonObject): ProjectSummary = ProjectSummary(
        id = json["id"]!!.jsonPrimitive.content,
        name = json["name"]!!.jsonPrimitive.content,
        description = json["description"]!!.jsonPrimitive.content,
        projectPath = json["projectPath"]!!.jsonPrimitive.content,
        createdAt = Instant.parse(json["createdAt"]!!.jsonPrimitive.content),
        lastActiveAt = Instant.parse(json["lastActiveAt"]!!.jsonPrimitive.content),
        sessionCount = json["sessionCount"]!!.jsonPrimitive.int,
    )

    fun encodeProjectResult(result: ProjectResult): JsonObject = when (result) {
        is ProjectResult.Success -> buildJsonObject {
            put("kind", "success"); put("project", encodeProjectSummary(result.project))
        }
        is ProjectResult.Error -> buildJsonObject {
            put("kind", "error"); put("code", result.code); put("message", result.message)
        }
    }

    fun decodeProjectResult(json: JsonObject): ProjectResult = when (json["kind"]!!.jsonPrimitive.content) {
        "success" -> ProjectResult.Success(decodeProjectSummary(json["project"]!!.jsonObject))
        "error" -> ProjectResult.Error(json["code"]!!.jsonPrimitive.content, json["message"]!!.jsonPrimitive.content)
        else -> error("unknown ProjectResult kind: ${json["kind"]}")
    }

    fun encodeProjectSummaryList(list: List<ProjectSummary>): JsonArray = buildJsonArray {
        list.forEach { add(encodeProjectSummary(it)) }
    }

    fun decodeProjectSummaryList(json: JsonArray): List<ProjectSummary> = json.map { decodeProjectSummary(it.jsonObject) }

    // ── Session ────────────────────────────────────────────────────────────

    fun encodeCreateSessionRequest(request: CreateSessionRequest): JsonObject = buildJsonObject {
        put("projectId", request.projectId)
        put("name", request.name)
        put("role", request.role.name)
        request.instructionSetId?.let { put("instructionSetId", it) }
    }

    fun decodeCreateSessionRequest(json: JsonObject): CreateSessionRequest = CreateSessionRequest(
        projectId = json["projectId"]!!.jsonPrimitive.content,
        name = json["name"]!!.jsonPrimitive.content,
        role = SessionRole.valueOf(json["role"]?.jsonPrimitive?.content ?: "DRIVER"),
        instructionSetId = json["instructionSetId"]?.jsonPrimitive?.contentOrNull,
    )

    fun encodeSessionSummary(summary: SessionSummary): JsonObject = buildJsonObject {
        put("id", summary.id)
        put("projectId", summary.projectId)
        put("name", summary.name)
        put("role", summary.role.name)
        put("state", summary.state.name)
        put("createdAt", summary.createdAt.toString())
        put("lastActiveAt", summary.lastActiveAt.toString())
        put("runCount", summary.runCount)
    }

    fun decodeSessionSummary(json: JsonObject): SessionSummary = SessionSummary(
        id = json["id"]!!.jsonPrimitive.content,
        projectId = json["projectId"]!!.jsonPrimitive.content,
        name = json["name"]!!.jsonPrimitive.content,
        role = SessionRole.valueOf(json["role"]!!.jsonPrimitive.content),
        state = SessionState.valueOf(json["state"]!!.jsonPrimitive.content),
        createdAt = Instant.parse(json["createdAt"]!!.jsonPrimitive.content),
        lastActiveAt = Instant.parse(json["lastActiveAt"]!!.jsonPrimitive.content),
        runCount = json["runCount"]!!.jsonPrimitive.int,
    )

    fun encodeSessionResult(result: SessionResult): JsonObject = when (result) {
        is SessionResult.Success -> buildJsonObject {
            put("kind", "success"); put("session", encodeSessionSummary(result.session))
        }
        is SessionResult.Error -> buildJsonObject {
            put("kind", "error"); put("code", result.code); put("message", result.message)
        }
    }

    fun decodeSessionResult(json: JsonObject): SessionResult = when (json["kind"]!!.jsonPrimitive.content) {
        "success" -> SessionResult.Success(decodeSessionSummary(json["session"]!!.jsonObject))
        "error" -> SessionResult.Error(json["code"]!!.jsonPrimitive.content, json["message"]!!.jsonPrimitive.content)
        else -> error("unknown SessionResult kind: ${json["kind"]}")
    }

    fun encodeSessionSummaryList(list: List<SessionSummary>): JsonArray = buildJsonArray {
        list.forEach { add(encodeSessionSummary(it)) }
    }

    fun decodeSessionSummaryList(json: JsonArray): List<SessionSummary> = json.map { decodeSessionSummary(it.jsonObject) }

    fun encodeUserMessage(message: UserMessage): JsonObject = buildJsonObject {
        put("content", message.content)
        put("attachments", buildJsonArray { message.attachments.forEach { add(it) } })
        put("runOptions", buildJsonObject {
            message.runOptions.maxTokens?.let { put("maxTokens", it) }
            put("requireApprovalBeforeToolUse", message.runOptions.requireApprovalBeforeToolUse)
            put("timeoutSeconds", message.runOptions.timeoutSeconds)
        })
    }

    fun decodeUserMessage(json: JsonObject): UserMessage {
        val opts = json["runOptions"]?.jsonObject
        return UserMessage(
            content = json["content"]!!.jsonPrimitive.content,
            attachments = json["attachments"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList(),
            runOptions = RunOptions(
                maxTokens = opts?.get("maxTokens")?.jsonPrimitive?.int,
                requireApprovalBeforeToolUse = opts?.get("requireApprovalBeforeToolUse")?.jsonPrimitive?.content?.toBoolean() ?: false,
                timeoutSeconds = opts?.get("timeoutSeconds")?.jsonPrimitive?.int ?: 300,
            ),
        )
    }

    fun encodeRunResult(result: RunResult): JsonObject = when (result) {
        is RunResult.Accepted -> buildJsonObject { put("kind", "accepted"); put("runId", result.runId) }
        is RunResult.Error -> buildJsonObject {
            put("kind", "error"); put("code", result.code); put("message", result.message)
        }
    }

    fun decodeRunResult(json: JsonObject): RunResult = when (json["kind"]!!.jsonPrimitive.content) {
        "accepted" -> RunResult.Accepted(json["runId"]!!.jsonPrimitive.content)
        "error" -> RunResult.Error(json["code"]!!.jsonPrimitive.content, json["message"]!!.jsonPrimitive.content)
        else -> error("unknown RunResult kind: ${json["kind"]}")
    }

    // ── Capability ─────────────────────────────────────────────────────────

    fun encodeGrantCapabilityRequest(request: GrantCapabilityRequest): JsonObject = buildJsonObject {
        put("sessionId", request.sessionId)
        put("permission", request.permission)
        request.scope?.let { put("scope", it) }
        put("constraints", buildJsonObject { request.constraints.forEach { (k, v) -> put(k, v) } })
        request.expiresAt?.let { put("expiresAt", it.toString()) }
    }

    fun decodeGrantCapabilityRequest(json: JsonObject): GrantCapabilityRequest = GrantCapabilityRequest(
        sessionId = json["sessionId"]!!.jsonPrimitive.content,
        permission = json["permission"]!!.jsonPrimitive.content,
        scope = json["scope"]?.jsonPrimitive?.contentOrNull,
        constraints = json["constraints"]?.jsonObject?.mapValues { it.value.jsonPrimitive.content } ?: emptyMap(),
        expiresAt = json["expiresAt"]?.jsonPrimitive?.contentOrNull?.let { Instant.parse(it) },
    )

    fun encodeCapabilityResult(result: CapabilityResult): JsonObject = when (result) {
        is CapabilityResult.Success -> buildJsonObject { put("kind", "success"); put("capabilityId", result.capabilityId) }
        is CapabilityResult.Error -> buildJsonObject {
            put("kind", "error"); put("code", result.code); put("message", result.message)
        }
    }

    fun decodeCapabilityResult(json: JsonObject): CapabilityResult = when (json["kind"]!!.jsonPrimitive.content) {
        "success" -> CapabilityResult.Success(json["capabilityId"]!!.jsonPrimitive.content)
        "error" -> CapabilityResult.Error(json["code"]!!.jsonPrimitive.content, json["message"]!!.jsonPrimitive.content)
        else -> error("unknown CapabilityResult kind: ${json["kind"]}")
    }

    fun encodePendingCapabilityRequest(request: PendingCapabilityRequest): JsonObject = buildJsonObject {
        put("requestId", request.requestId)
        put("sessionId", request.sessionId)
        put("permission", request.permission)
        put("reason", request.reason)
    }

    fun decodePendingCapabilityRequest(json: JsonObject): PendingCapabilityRequest = PendingCapabilityRequest(
        requestId = json["requestId"]!!.jsonPrimitive.content,
        sessionId = json["sessionId"]!!.jsonPrimitive.content,
        permission = json["permission"]!!.jsonPrimitive.content,
        reason = json["reason"]!!.jsonPrimitive.content,
    )

    fun encodePendingCapabilityList(list: List<PendingCapabilityRequest>): JsonArray = buildJsonArray {
        list.forEach { add(encodePendingCapabilityRequest(it)) }
    }

    fun decodePendingCapabilityList(json: JsonArray): List<PendingCapabilityRequest> =
        json.map { decodePendingCapabilityRequest(it.jsonObject) }

    // ── Events ─────────────────────────────────────────────────────────────

    fun encodeEventFilter(filter: EventFilter): JsonObject = buildJsonObject {
        put("projectIds", buildJsonArray { filter.projectIds.forEach { add(it) } })
        put("sessionIds", buildJsonArray { filter.sessionIds.forEach { add(it) } })
        put("types", buildJsonArray { filter.types.forEach { add(it.name) } })
        filter.sinceSequence?.let { put("sinceSequence", it) }
    }

    fun decodeEventFilter(json: JsonObject): EventFilter = EventFilter(
        projectIds = json["projectIds"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList(),
        sessionIds = json["sessionIds"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList(),
        types = json["types"]?.jsonArray?.map { RuntimeEventType.valueOf(it.jsonPrimitive.content) } ?: emptyList(),
        sinceSequence = json["sinceSequence"]?.jsonPrimitive?.contentOrNull?.let { json["sinceSequence"]!!.jsonPrimitive.long },
    )

    /** Encodes one [RuntimeEvent] as a tagged JSON object — the tag is the sealed subtype's simple name. */
    fun encodeRuntimeEvent(event: RuntimeEvent): JsonObject = buildJsonObject {
        put("eventId", event.eventId)
        put("timestamp", event.timestamp.toString())
        // NOT `event.projectId?.let { put(...) } ?: put(..., JsonNull)` -- put() itself returns
        // the *previous* value at that key (always null, first write), so that chain's elvis
        // fired every time and silently clobbered every non-null id with JsonNull.
        val projectId = event.projectId
        if (projectId != null) put("projectId", projectId) else put("projectId", JsonNull)
        val sessionId = event.sessionId
        if (sessionId != null) put("sessionId", sessionId) else put("sessionId", JsonNull)
        when (event) {
            is RuntimeEvent.SessionCreated -> {
                put("type", "SessionCreated"); put("name", event.name); put("role", event.role.name)
            }
            is RuntimeEvent.SessionStateChanged -> {
                put("type", "SessionStateChanged"); put("from", event.from.name); put("to", event.to.name)
            }
            is RuntimeEvent.RunStarted -> {
                put("type", "RunStarted"); put("runId", event.runId)
            }
            is RuntimeEvent.RunCompleted -> {
                put("type", "RunCompleted"); put("runId", event.runId)
                put("artifactIds", buildJsonArray { event.artifactIds.forEach { add(it) } })
            }
            is RuntimeEvent.RunFailed -> {
                put("type", "RunFailed"); put("runId", event.runId)
                put("errorCode", event.errorCode); put("errorMessage", event.errorMessage)
            }
            is RuntimeEvent.RunStepCompleted -> {
                put("type", "RunStepCompleted"); put("runId", event.runId); put("taskId", event.taskId)
                put("stepIndex", event.stepIndex); put("taskState", event.taskState)
            }
            is RuntimeEvent.AiResponseDelta -> {
                put("type", "AiResponseDelta"); put("runId", event.runId)
                put("delta", event.delta); put("isFinal", event.isFinal)
            }
            is RuntimeEvent.ToolApprovalRequired -> {
                put("type", "ToolApprovalRequired"); put("runId", event.runId); put("taskId", event.taskId)
                put("toolName", event.toolName); put("previewDescription", event.previewDescription)
            }
        }
    }

    fun decodeRuntimeEvent(json: JsonObject): RuntimeEvent {
        val eventId = json["eventId"]!!.jsonPrimitive.content
        val timestamp = Instant.parse(json["timestamp"]!!.jsonPrimitive.content)
        val projectId = json["projectId"]?.takeIf { it != JsonNull }?.jsonPrimitive?.content
            ?: error("event $eventId has no projectId")
        val sessionId = json["sessionId"]?.takeIf { it != JsonNull }?.jsonPrimitive?.content
            ?: error("event $eventId has no sessionId")
        return when (json["type"]!!.jsonPrimitive.content) {
            "SessionCreated" -> RuntimeEvent.SessionCreated(
                eventId, timestamp, projectId, sessionId,
                name = json["name"]!!.jsonPrimitive.content,
                role = SessionRole.valueOf(json["role"]!!.jsonPrimitive.content),
            )
            "SessionStateChanged" -> RuntimeEvent.SessionStateChanged(
                eventId, timestamp, projectId, sessionId,
                from = SessionState.valueOf(json["from"]!!.jsonPrimitive.content),
                to = SessionState.valueOf(json["to"]!!.jsonPrimitive.content),
            )
            "RunStarted" -> RuntimeEvent.RunStarted(eventId, timestamp, projectId, sessionId, json["runId"]!!.jsonPrimitive.content)
            "RunCompleted" -> RuntimeEvent.RunCompleted(
                eventId, timestamp, projectId, sessionId,
                runId = json["runId"]!!.jsonPrimitive.content,
                artifactIds = json["artifactIds"]!!.jsonArray.map { it.jsonPrimitive.content },
            )
            "RunFailed" -> RuntimeEvent.RunFailed(
                eventId, timestamp, projectId, sessionId,
                runId = json["runId"]!!.jsonPrimitive.content,
                errorCode = json["errorCode"]!!.jsonPrimitive.content,
                errorMessage = json["errorMessage"]!!.jsonPrimitive.content,
            )
            "RunStepCompleted" -> RuntimeEvent.RunStepCompleted(
                eventId, timestamp, projectId, sessionId,
                runId = json["runId"]!!.jsonPrimitive.content,
                taskId = json["taskId"]!!.jsonPrimitive.content,
                stepIndex = json["stepIndex"]!!.jsonPrimitive.int,
                taskState = json["taskState"]!!.jsonPrimitive.content,
            )
            "AiResponseDelta" -> RuntimeEvent.AiResponseDelta(
                eventId, timestamp, projectId, sessionId,
                runId = json["runId"]!!.jsonPrimitive.content,
                delta = json["delta"]!!.jsonPrimitive.content,
                isFinal = json["isFinal"]!!.jsonPrimitive.content.toBoolean(),
            )
            "ToolApprovalRequired" -> RuntimeEvent.ToolApprovalRequired(
                eventId, timestamp, projectId, sessionId,
                runId = json["runId"]!!.jsonPrimitive.content,
                taskId = json["taskId"]!!.jsonPrimitive.content,
                toolName = json["toolName"]!!.jsonPrimitive.content,
                previewDescription = json["previewDescription"]!!.jsonPrimitive.content,
            )
            else -> error("unknown RuntimeEvent type: ${json["type"]}")
        }
    }

    // ── Runtime info ───────────────────────────────────────────────────────

    fun encodeRuntimeVersion(version: RuntimeVersion): JsonObject = buildJsonObject {
        put("version", version.version)
        put("apiVersion", version.apiVersion)
        put("profile", version.profile)
    }

    fun decodeRuntimeVersion(json: JsonObject): RuntimeVersion = RuntimeVersion(
        version = json["version"]!!.jsonPrimitive.content,
        apiVersion = json["apiVersion"]!!.jsonPrimitive.int,
        profile = json["profile"]!!.jsonPrimitive.content,
    )
}
