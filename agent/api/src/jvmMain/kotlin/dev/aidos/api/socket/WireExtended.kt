package dev.aidos.api.socket

import dev.aidos.api.ArtifactDetail
import dev.aidos.api.ArtifactFilter
import dev.aidos.api.ArtifactSummary
import dev.aidos.api.AuditEntry
import dev.aidos.api.CapabilitySummary
import dev.aidos.api.ChangeOrigin
import dev.aidos.api.CommitResult
import dev.aidos.api.CreateIntentNodeRequest
import dev.aidos.api.CreateIntentProposalRequest
import dev.aidos.api.DiffRange
import dev.aidos.api.DiffSummary
import dev.aidos.api.FileChange
import dev.aidos.api.FileChangeKind
import dev.aidos.api.HunkId
import dev.aidos.api.IndexStatus
import dev.aidos.api.KnowledgeItem
import dev.aidos.api.KnowledgeQuery
import dev.aidos.api.KnowledgeResult
import dev.aidos.api.ProjectDetail
import dev.aidos.api.ReviewState
import dev.aidos.api.RunSummary
import dev.aidos.api.SessionDetail
import dev.aidos.api.StartNodeRunResult
import dev.aidos.api.UpdateIntentNodeRequest
import dev.aidos.intent.IntentActorKind
import dev.aidos.intent.IntentCheckKind
import dev.aidos.intent.IntentEdgeRecord
import dev.aidos.intent.IntentGranularity
import dev.aidos.intent.IntentMinLocality
import dev.aidos.intent.IntentNodeRecord
import dev.aidos.intent.IntentNodeStatusView
import dev.aidos.intent.IntentNodeType
import dev.aidos.intent.IntentOperation
import dev.aidos.intent.IntentProposal
import dev.aidos.intent.IntentTargetProfile
import dev.aidos.intent.NodeDispatchPolicy
import dev.aidos.intent.SnapshotReconcileResult
import dev.aidos.kernel.ActorKind
import dev.aidos.kernel.ActorRef
import dev.aidos.kernel.ChangeOrigin as KernelChangeOrigin
import dev.aidos.kernel.DiffHunk
import dev.aidos.kernel.DiffLine
import dev.aidos.kernel.DiffLineKind
import dev.aidos.kernel.FileChange as KernelFileChange
import dev.aidos.kernel.FileChangeKind as KernelFileChangeKind
import dev.aidos.kernel.FileDiff
import dev.aidos.kernel.HunkId as KernelHunkId
import dev.aidos.kernel.ReviewState as KernelReviewState
import kotlinx.datetime.Instant
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

/**
 * Extended JSON wire codec for the remaining [dev.aidos.api.RuntimeClient] surface
 * (projects.open/get, sessions.*, capabilities.*, knowledge.*, diff.*, artifacts.*, intents.*).
 */
object WireExtended {

    val jsonFormat = Json { ignoreUnknownKeys = true }

    // ── Projects & Sessions ───────────────────────────────────────────────

    fun encodeProjectDetail(d: ProjectDetail): JsonObject = buildJsonObject {
        put("summary", Wire.encodeProjectSummary(d.summary))
        put("rootPath", d.rootPath)
        put("projectType", d.projectType)
    }

    fun decodeProjectDetail(json: JsonObject): ProjectDetail = ProjectDetail(
        summary = Wire.decodeProjectSummary(json["summary"]!!.jsonObject),
        rootPath = json["rootPath"]!!.jsonPrimitive.content,
        projectType = json["projectType"]!!.jsonPrimitive.content,
    )

    fun encodeRunSummary(r: RunSummary): JsonObject = buildJsonObject {
        put("id", r.id)
        put("state", r.state)
        put("startedAt", r.startedAt.toString())
        put("endedAt", r.endedAt?.toString()?.let(::JsonPrimitiveOrNull) ?: JsonNull)
        put("stepCount", r.stepCount)
    }

    fun decodeRunSummary(json: JsonObject): RunSummary = RunSummary(
        id = json["id"]!!.jsonPrimitive.content,
        state = json["state"]!!.jsonPrimitive.content,
        startedAt = Instant.parse(json["startedAt"]!!.jsonPrimitive.content),
        endedAt = json["endedAt"]?.jsonPrimitive?.contentOrNull?.let(Instant::parse),
        stepCount = json["stepCount"]!!.jsonPrimitive.int,
    )

    fun encodeSessionDetail(d: SessionDetail): JsonObject = buildJsonObject {
        put("summary", Wire.encodeSessionSummary(d.summary))
        put("recentRuns", buildJsonArray { d.recentRuns.forEach { add(encodeRunSummary(it)) } })
    }

    fun decodeSessionDetail(json: JsonObject): SessionDetail = SessionDetail(
        summary = Wire.decodeSessionSummary(json["summary"]!!.jsonObject),
        recentRuns = json["recentRuns"]!!.jsonArray.map { decodeRunSummary(it.jsonObject) },
    )

    // ── Capabilities ──────────────────────────────────────────────────────

    fun encodeCapabilitySummaryList(list: List<CapabilitySummary>): JsonArray = buildJsonArray {
        list.forEach { c ->
            add(
                buildJsonObject {
                    put("id", c.id)
                    put("permission", c.permission)
                    put("scope", c.scope?.let(::JsonPrimitiveOrNull) ?: JsonNull)
                    put("expiresAt", c.expiresAt?.toString()?.let(::JsonPrimitiveOrNull) ?: JsonNull)
                }
            )
        }
    }

    fun decodeCapabilitySummaryList(arr: JsonArray): List<CapabilitySummary> = arr.map { elem ->
        val obj = elem.jsonObject
        CapabilitySummary(
            id = obj["id"]!!.jsonPrimitive.content,
            permission = obj["permission"]!!.jsonPrimitive.content,
            scope = obj["scope"]?.jsonPrimitive?.contentOrNull,
            expiresAt = obj["expiresAt"]?.jsonPrimitive?.contentOrNull?.let(Instant::parse),
        )
    }

    // ── Knowledge ─────────────────────────────────────────────────────────

    fun encodeKnowledgeQuery(q: KnowledgeQuery): JsonObject = buildJsonObject {
        put("text", q.text)
        put("limit", q.limit)
        put("semanticSearch", q.semanticSearch)
    }

    fun decodeKnowledgeQuery(json: JsonObject): KnowledgeQuery = KnowledgeQuery(
        text = json["text"]!!.jsonPrimitive.content,
        limit = json["limit"]?.jsonPrimitive?.intOrNull ?: 20,
        semanticSearch = json["semanticSearch"]?.jsonPrimitive?.booleanOrNull ?: false,
    )

    fun encodeKnowledgeResult(r: KnowledgeResult): JsonObject = buildJsonObject {
        put("totalMatches", r.totalMatches)
        put("indexedAt", r.indexedAt?.toString()?.let(::JsonPrimitiveOrNull) ?: JsonNull)
        put(
            "items",
            buildJsonArray {
                r.items.forEach { item ->
                    add(
                        buildJsonObject {
                            put("id", item.id)
                            put("kind", item.kind)
                            put("title", item.title)
                            put("snippet", item.snippet)
                            put("score", item.score)
                        }
                    )
                }
            }
        )
    }

    fun decodeKnowledgeResult(json: JsonObject): KnowledgeResult = KnowledgeResult(
        items = json["items"]!!.jsonArray.map { el ->
            val o = el.jsonObject
            KnowledgeItem(
                id = o["id"]!!.jsonPrimitive.content,
                kind = o["kind"]!!.jsonPrimitive.content,
                title = o["title"]!!.jsonPrimitive.content,
                snippet = o["snippet"]!!.jsonPrimitive.content,
                score = o["score"]!!.jsonPrimitive.float,
            )
        },
        totalMatches = json["totalMatches"]!!.jsonPrimitive.int,
        indexedAt = json["indexedAt"]?.jsonPrimitive?.contentOrNull?.let(Instant::parse),
    )

    fun encodeIndexStatus(s: IndexStatus): JsonObject = buildJsonObject {
        put("projectId", s.projectId)
        put("indexedAt", s.indexedAt?.toString()?.let(::JsonPrimitiveOrNull) ?: JsonNull)
        put("nodeCount", s.nodeCount)
        put("isIndexing", s.isIndexing)
    }

    fun decodeIndexStatus(json: JsonObject): IndexStatus = IndexStatus(
        projectId = json["projectId"]!!.jsonPrimitive.content,
        indexedAt = json["indexedAt"]?.jsonPrimitive?.contentOrNull?.let(Instant::parse),
        nodeCount = json["nodeCount"]!!.jsonPrimitive.int,
        isIndexing = json["isIndexing"]!!.jsonPrimitive.boolean,
    )

    // ── Diff ──────────────────────────────────────────────────────────────

    fun encodeDiffRange(range: DiffRange): JsonObject = buildJsonObject {
        when (range) {
            is DiffRange.WorkingTree -> put("kind", "working_tree")
            is DiffRange.Staged -> put("kind", "staged")
            is DiffRange.Refs -> {
                put("kind", "refs")
                put("base", range.base)
                put("head", range.head)
            }
        }
    }

    fun decodeDiffRange(json: JsonObject): DiffRange = when (json["kind"]!!.jsonPrimitive.content) {
        "working_tree" -> DiffRange.WorkingTree
        "staged" -> DiffRange.Staged
        "refs" -> DiffRange.Refs(json["base"]!!.jsonPrimitive.content, json["head"]!!.jsonPrimitive.content)
        else -> DiffRange.WorkingTree
    }

    fun encodeDiffSummary(s: DiffSummary): JsonObject = buildJsonObject {
        put("range", encodeDiffRange(s.range))
        put("filesChanged", s.filesChanged)
        put("linesAdded", s.linesAdded)
        put("linesRemoved", s.linesRemoved)
        put(
            "files",
            buildJsonArray {
                s.files.forEach { f ->
                    add(
                        buildJsonObject {
                            put("path", f.path)
                            put("previousPath", f.previousPath?.let(::JsonPrimitiveOrNull) ?: JsonNull)
                            put("kind", f.kind.name)
                            put("baseBlobHash", f.baseBlobHash?.let(::JsonPrimitiveOrNull) ?: JsonNull)
                            put("headBlobHash", f.headBlobHash?.let(::JsonPrimitiveOrNull) ?: JsonNull)
                            put("binary", f.binary)
                            put("modeChanged", f.modeChanged)
                            put("hunkCount", f.hunkCount)
                            put("linesAdded", f.linesAdded)
                            put("linesRemoved", f.linesRemoved)
                            put("review", f.review.name)
                            put("origin", f.origin.name)
                        }
                    )
                }
            }
        )
    }

    fun decodeDiffSummary(json: JsonObject): DiffSummary = DiffSummary(
        range = decodeDiffRange(json["range"]!!.jsonObject),
        files = json["files"]!!.jsonArray.map { el ->
            val o = el.jsonObject
            FileChange(
                path = o["path"]!!.jsonPrimitive.content,
                previousPath = o["previousPath"]?.jsonPrimitive?.contentOrNull,
                kind = FileChangeKind.valueOf(o["kind"]!!.jsonPrimitive.content),
                baseBlobHash = o["baseBlobHash"]?.jsonPrimitive?.contentOrNull,
                headBlobHash = o["headBlobHash"]?.jsonPrimitive?.contentOrNull,
                binary = o["binary"]!!.jsonPrimitive.boolean,
                modeChanged = o["modeChanged"]!!.jsonPrimitive.boolean,
                hunkCount = o["hunkCount"]!!.jsonPrimitive.int,
                linesAdded = o["linesAdded"]!!.jsonPrimitive.int,
                linesRemoved = o["linesRemoved"]!!.jsonPrimitive.int,
                review = ReviewState.valueOf(o["review"]!!.jsonPrimitive.content),
                origin = ChangeOrigin.valueOf(o["origin"]!!.jsonPrimitive.content),
            )
        },
        filesChanged = json["filesChanged"]!!.jsonPrimitive.int,
        linesAdded = json["linesAdded"]!!.jsonPrimitive.int,
        linesRemoved = json["linesRemoved"]!!.jsonPrimitive.int,
    )

    fun encodeFileDiff(fd: FileDiff): JsonObject = buildJsonObject {
        put(
            "file",
            buildJsonObject {
                put("path", fd.file.path)
                put("previousPath", fd.file.previousPath)
                put("kind", fd.file.kind.name)
                put("baseBlobHash", fd.file.baseBlobHash)
                put("headBlobHash", fd.file.headBlobHash)
                put("binary", fd.file.binary)
                put("modeChanged", fd.file.modeChanged)
                put("hunkCount", fd.file.hunkCount)
                put("linesAdded", fd.file.linesAdded)
                put("linesRemoved", fd.file.linesRemoved)
                put("review", fd.file.review.name)
                put("origin", fd.file.origin.name)
            }
        )
        put(
            "hunks",
            buildJsonArray {
                fd.hunks.forEach { h ->
                    add(
                        buildJsonObject {
                            put("path", h.id.path)
                            put("baseBlobHash", h.id.baseBlobHash)
                            put("index", h.id.index)
                            put("baseStart", h.baseStart)
                            put("baseLines", h.baseLines)
                            put("headStart", h.headStart)
                            put("headLines", h.headLines)
                            put(
                                "lines",
                                buildJsonArray {
                                    h.lines.forEach { l ->
                                        add(
                                            buildJsonObject {
                                                put("kind", l.kind.name)
                                                put("text", l.text)
                                                put("noNewlineAtEof", l.noNewlineAtEof)
                                            }
                                        )
                                    }
                                }
                            )
                        }
                    )
                }
            }
        )
    }

    fun decodeFileDiff(json: JsonObject): FileDiff {
        val f = json["file"]!!.jsonObject
        val fileChange = KernelFileChange(
            path = f["path"]!!.jsonPrimitive.content,
            previousPath = f["previousPath"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.contentOrNull,
            kind = KernelFileChangeKind.valueOf(f["kind"]!!.jsonPrimitive.content),
            baseBlobHash = f["baseBlobHash"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.contentOrNull,
            headBlobHash = f["headBlobHash"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.contentOrNull,
            binary = f["binary"]!!.jsonPrimitive.boolean,
            modeChanged = f["modeChanged"]?.jsonPrimitive?.booleanOrNull ?: false,
            hunkCount = f["hunkCount"]!!.jsonPrimitive.int,
            linesAdded = f["linesAdded"]!!.jsonPrimitive.int,
            linesRemoved = f["linesRemoved"]!!.jsonPrimitive.int,
            review = KernelReviewState.valueOf(f["review"]?.jsonPrimitive?.contentOrNull ?: KernelReviewState.NOT_REVIEWED.name),
            origin = KernelChangeOrigin.valueOf(f["origin"]?.jsonPrimitive?.contentOrNull ?: KernelChangeOrigin.UNKNOWN.name),
        )
        val hunks = json["hunks"]!!.jsonArray.map { el ->
            val h = el.jsonObject
            DiffHunk(
                id = KernelHunkId(
                    path = h["path"]!!.jsonPrimitive.content,
                    baseBlobHash = h["baseBlobHash"]!!.jsonPrimitive.content,
                    index = h["index"]!!.jsonPrimitive.int,
                ),
                baseStart = h["baseStart"]!!.jsonPrimitive.int,
                baseLines = h["baseLines"]!!.jsonPrimitive.int,
                headStart = h["headStart"]!!.jsonPrimitive.int,
                headLines = h["headLines"]!!.jsonPrimitive.int,
                lines = h["lines"]!!.jsonArray.map { le ->
                    val lo = le.jsonObject
                    DiffLine(
                        kind = DiffLineKind.valueOf(lo["kind"]!!.jsonPrimitive.content),
                        text = lo["text"]!!.jsonPrimitive.content,
                        noNewlineAtEof = lo["noNewlineAtEof"]?.jsonPrimitive?.booleanOrNull ?: false,
                    )
                },
            )
        }
        return FileDiff(file = fileChange, hunks = hunks)
    }

    fun encodeHunkIds(hunks: List<HunkId>): JsonArray = buildJsonArray {
        hunks.forEach { h ->
            add(
                buildJsonObject {
                    put("path", h.path)
                    put("baseBlobHash", h.baseBlobHash)
                    put("index", h.index)
                }
            )
        }
    }

    fun decodeHunkIds(arr: JsonArray): List<HunkId> = arr.map { el ->
        val o = el.jsonObject
        HunkId(
            path = o["path"]!!.jsonPrimitive.content,
            baseBlobHash = o["baseBlobHash"]!!.jsonPrimitive.content,
            index = o["index"]!!.jsonPrimitive.int,
        )
    }

    fun encodeCommitResult(r: CommitResult): JsonObject = buildJsonObject {
        when (r) {
            is CommitResult.Success -> {
                put("status", "success")
                put("commitHash", r.commitHash)
                put("shortMessage", r.shortMessage)
            }
            is CommitResult.NothingStaged -> put("status", "nothing_staged")
            is CommitResult.Error -> {
                put("status", "error")
                put("code", r.code)
                put("message", r.message)
            }
        }
    }

    fun decodeCommitResult(json: JsonObject): CommitResult = when (json["status"]!!.jsonPrimitive.content) {
        "success" -> CommitResult.Success(
            commitHash = json["commitHash"]!!.jsonPrimitive.content,
            shortMessage = json["shortMessage"]!!.jsonPrimitive.content,
        )
        "nothing_staged" -> CommitResult.NothingStaged
        else -> CommitResult.Error(
            code = json["code"]!!.jsonPrimitive.content,
            message = json["message"]!!.jsonPrimitive.content,
        )
    }

    // ── Artifacts ─────────────────────────────────────────────────────────

    fun encodeArtifactFilter(f: ArtifactFilter): JsonObject = buildJsonObject {
        put("sessionId", f.sessionId?.let(::JsonPrimitiveOrNull) ?: JsonNull)
        put("contentType", f.contentType?.let(::JsonPrimitiveOrNull) ?: JsonNull)
        put("since", f.since?.toString()?.let(::JsonPrimitiveOrNull) ?: JsonNull)
        put("limit", f.limit)
    }

    fun decodeArtifactFilter(json: JsonObject): ArtifactFilter = ArtifactFilter(
        sessionId = json["sessionId"]?.jsonPrimitive?.contentOrNull,
        contentType = json["contentType"]?.jsonPrimitive?.contentOrNull,
        since = json["since"]?.jsonPrimitive?.contentOrNull?.let(Instant::parse),
        limit = json["limit"]?.jsonPrimitive?.intOrNull ?: 50,
    )

    fun encodeArtifactSummary(a: ArtifactSummary): JsonObject = buildJsonObject {
        put("id", a.id)
        put("contentType", a.contentType)
        put("label", a.label?.let(::JsonPrimitiveOrNull) ?: JsonNull)
        put("createdAt", a.createdAt.toString())
    }

    fun decodeArtifactSummary(json: JsonObject): ArtifactSummary = ArtifactSummary(
        id = json["id"]!!.jsonPrimitive.content,
        contentType = json["contentType"]!!.jsonPrimitive.content,
        label = json["label"]?.jsonPrimitive?.contentOrNull,
        createdAt = Instant.parse(json["createdAt"]!!.jsonPrimitive.content),
    )

    fun encodeArtifactDetail(a: ArtifactDetail): JsonObject = buildJsonObject {
        put("summary", encodeArtifactSummary(a.summary))
        put("payloadSize", a.payloadSize)
    }

    fun decodeArtifactDetail(json: JsonObject): ArtifactDetail = ArtifactDetail(
        summary = decodeArtifactSummary(json["summary"]!!.jsonObject),
        payloadSize = json["payloadSize"]!!.jsonPrimitive.long,
    )

    fun encodeAuditEntryList(list: List<AuditEntry>): JsonArray = buildJsonArray {
        list.forEach { e ->
            add(
                buildJsonObject {
                    put("id", e.id)
                    put("kind", e.kind)
                    put("occurredAt", e.occurredAt.toString())
                    put("actorKind", e.actorKind)
                    put("actorId", e.actorId)
                    put("subjectRef", e.subjectRef?.let(::JsonPrimitiveOrNull) ?: JsonNull)
                }
            )
        }
    }

    fun decodeAuditEntryList(arr: JsonArray): List<AuditEntry> = arr.map { el ->
        val o = el.jsonObject
        AuditEntry(
            id = o["id"]!!.jsonPrimitive.content,
            kind = o["kind"]!!.jsonPrimitive.content,
            occurredAt = Instant.parse(o["occurredAt"]!!.jsonPrimitive.content),
            actorKind = o["actorKind"]!!.jsonPrimitive.content,
            actorId = o["actorId"]!!.jsonPrimitive.content,
            subjectRef = o["subjectRef"]?.jsonPrimitive?.contentOrNull,
        )
    }

    // ── Intents ───────────────────────────────────────────────────────────

    fun encodeCreateIntentNodeRequest(r: CreateIntentNodeRequest): JsonObject = buildJsonObject {
        put("projectId", r.projectId)
        put("title", r.title)
        put("type", r.type.name)
        r.id?.let { put("id", it) }
        r.description?.let { put("description", it) }
        put("priority", r.priority)
        r.parentId?.let { put("parentId", it) }
        put("actorKind", r.actorKind.name)
        put("dispatchPolicy", r.dispatchPolicy.name)
        put("granularity", r.granularity.name)
        r.targetProfile?.let { put("targetProfile", it.name) }
        r.minLocality?.let { put("minLocality", it.name) }
        r.estimatedSteps?.let { put("estimatedSteps", it) }
        put("scopePaths", buildJsonArray { r.scopePaths.forEach { add(it) } })
        r.dueAt?.let { put("dueAt", it) }
        r.recurrenceJson?.let { put("recurrenceJson", it) }
        r.checkKind?.let { put("checkKind", it.name) }
        r.checkSpec?.let { put("checkSpec", it) }
        put("tags", buildJsonArray { r.tags.forEach { add(it) } })
    }

    fun decodeCreateIntentNodeRequest(json: JsonObject): CreateIntentNodeRequest {
        val type = IntentNodeType.valueOf(json["type"]!!.jsonPrimitive.content)
        return CreateIntentNodeRequest(
            projectId = json["projectId"]!!.jsonPrimitive.content,
            title = json["title"]!!.jsonPrimitive.content,
            type = type,
            id = json["id"]?.jsonPrimitive?.contentOrNull,
            description = json["description"]?.jsonPrimitive?.contentOrNull,
            priority = json["priority"]?.jsonPrimitive?.intOrNull ?: 100,
            parentId = json["parentId"]?.jsonPrimitive?.contentOrNull,
            actorKind = json["actorKind"]?.jsonPrimitive?.contentOrNull?.let(IntentActorKind::valueOf) ?: IntentActorKind.AGENT,
            dispatchPolicy = json["dispatchPolicy"]?.jsonPrimitive?.contentOrNull?.let(NodeDispatchPolicy::valueOf) ?: NodeDispatchPolicy.INHERIT,
            granularity = json["granularity"]?.jsonPrimitive?.contentOrNull?.let(IntentGranularity::valueOf)
                ?: dev.aidos.intent.IntentGraphRepository.defaultGranularityForType(type),
            targetProfile = json["targetProfile"]?.jsonPrimitive?.contentOrNull?.let(IntentTargetProfile::valueOf),
            minLocality = json["minLocality"]?.jsonPrimitive?.contentOrNull?.let(IntentMinLocality::valueOf),
            estimatedSteps = json["estimatedSteps"]?.jsonPrimitive?.intOrNull,
            scopePaths = json["scopePaths"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty(),
            dueAt = json["dueAt"]?.jsonPrimitive?.contentOrNull,
            recurrenceJson = json["recurrenceJson"]?.jsonPrimitive?.contentOrNull,
            checkKind = json["checkKind"]?.jsonPrimitive?.contentOrNull?.let(IntentCheckKind::valueOf),
            checkSpec = json["checkSpec"]?.jsonPrimitive?.contentOrNull,
            tags = json["tags"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty(),
        )
    }

    fun encodeUpdateIntentNodeRequest(r: UpdateIntentNodeRequest): JsonObject = buildJsonObject {
        put("projectId", r.projectId)
        put("nodeId", r.nodeId)
        r.title?.let { put("title", it) }
        r.description?.let { put("description", it) }
        put("clearDescription", r.clearDescription)
        r.priority?.let { put("priority", it) }
        r.parentId?.let { put("parentId", it) }
        put("clearParentId", r.clearParentId)
        r.actorKind?.let { put("actorKind", it.name) }
        r.dispatchPolicy?.let { put("dispatchPolicy", it.name) }
        r.granularity?.let { put("granularity", it.name) }
        r.targetProfile?.let { put("targetProfile", it.name) }
        put("clearTargetProfile", r.clearTargetProfile)
        r.minLocality?.let { put("minLocality", it.name) }
        put("clearMinLocality", r.clearMinLocality)
        r.estimatedSteps?.let { put("estimatedSteps", it) }
        put("clearEstimatedSteps", r.clearEstimatedSteps)
        r.scopePaths?.let { paths -> put("scopePaths", buildJsonArray { paths.forEach { add(it) } }) }
        r.dueAt?.let { put("dueAt", it) }
        put("clearDueAt", r.clearDueAt)
        r.recurrenceJson?.let { put("recurrenceJson", it) }
        put("clearRecurrenceJson", r.clearRecurrenceJson)
        r.checkKind?.let { put("checkKind", it.name) }
        r.checkSpec?.let { put("checkSpec", it) }
        r.tags?.let { tags -> put("tags", buildJsonArray { tags.forEach { add(it) } }) }
    }

    fun decodeUpdateIntentNodeRequest(json: JsonObject): UpdateIntentNodeRequest = UpdateIntentNodeRequest(
        projectId = json["projectId"]!!.jsonPrimitive.content,
        nodeId = json["nodeId"]!!.jsonPrimitive.content,
        title = json["title"]?.jsonPrimitive?.contentOrNull,
        description = json["description"]?.jsonPrimitive?.contentOrNull,
        clearDescription = json["clearDescription"]?.jsonPrimitive?.booleanOrNull ?: false,
        priority = json["priority"]?.jsonPrimitive?.intOrNull,
        parentId = json["parentId"]?.jsonPrimitive?.contentOrNull,
        clearParentId = json["clearParentId"]?.jsonPrimitive?.booleanOrNull ?: false,
        actorKind = json["actorKind"]?.jsonPrimitive?.contentOrNull?.let(IntentActorKind::valueOf),
        dispatchPolicy = json["dispatchPolicy"]?.jsonPrimitive?.contentOrNull?.let(NodeDispatchPolicy::valueOf),
        granularity = json["granularity"]?.jsonPrimitive?.contentOrNull?.let(IntentGranularity::valueOf),
        targetProfile = json["targetProfile"]?.jsonPrimitive?.contentOrNull?.let(IntentTargetProfile::valueOf),
        clearTargetProfile = json["clearTargetProfile"]?.jsonPrimitive?.booleanOrNull ?: false,
        minLocality = json["minLocality"]?.jsonPrimitive?.contentOrNull?.let(IntentMinLocality::valueOf),
        clearMinLocality = json["clearMinLocality"]?.jsonPrimitive?.booleanOrNull ?: false,
        estimatedSteps = json["estimatedSteps"]?.jsonPrimitive?.intOrNull,
        clearEstimatedSteps = json["clearEstimatedSteps"]?.jsonPrimitive?.booleanOrNull ?: false,
        scopePaths = json["scopePaths"]?.jsonArray?.map { it.jsonPrimitive.content },
        dueAt = json["dueAt"]?.jsonPrimitive?.contentOrNull,
        clearDueAt = json["clearDueAt"]?.jsonPrimitive?.booleanOrNull ?: false,
        recurrenceJson = json["recurrenceJson"]?.jsonPrimitive?.contentOrNull,
        clearRecurrenceJson = json["clearRecurrenceJson"]?.jsonPrimitive?.booleanOrNull ?: false,
        checkKind = json["checkKind"]?.jsonPrimitive?.contentOrNull?.let(IntentCheckKind::valueOf),
        checkSpec = json["checkSpec"]?.jsonPrimitive?.contentOrNull,
        tags = json["tags"]?.jsonArray?.map { it.jsonPrimitive.content },
    )

    fun encodeCreateIntentProposalRequest(r: CreateIntentProposalRequest): JsonObject = buildJsonObject {
        put("projectId", r.projectId)
        put("runId", r.runId)
        put("operations", jsonFormat.encodeToJsonElement(r.operations))
        put("rationale", r.rationale)
        put("runTaint", r.runTaint)
        put("actorKind", r.actor.kind.name)
        put("actorId", r.actor.id)
        put("expiresAtIso", r.expiresAtIso)
    }

    fun decodeCreateIntentProposalRequest(json: JsonObject): CreateIntentProposalRequest = CreateIntentProposalRequest(
        projectId = json["projectId"]!!.jsonPrimitive.content,
        runId = json["runId"]!!.jsonPrimitive.content,
        operations = jsonFormat.decodeFromJsonElement<List<IntentOperation>>(json["operations"]!!),
        rationale = json["rationale"]!!.jsonPrimitive.content,
        runTaint = json["runTaint"]?.jsonPrimitive?.contentOrNull ?: "TRUSTED",
        actor = ActorRef(
            kind = ActorKind.valueOf(json["actorKind"]?.jsonPrimitive?.contentOrNull ?: "SESSION"),
            id = json["actorId"]?.jsonPrimitive?.contentOrNull ?: "runtime",
        ),
        expiresAtIso = json["expiresAtIso"]!!.jsonPrimitive.content,
    )

    fun encodeStartNodeRunResult(r: StartNodeRunResult): JsonObject = buildJsonObject {
        when (r) {
            is StartNodeRunResult.Started -> {
                put("kind", "started")
                put("runId", r.runId)
                put("sessionId", r.sessionId)
            }
            is StartNodeRunResult.Refused -> {
                put("kind", "refused")
                put("code", r.code)
                put("message", r.message)
            }
        }
    }

    fun decodeStartNodeRunResult(json: JsonObject): StartNodeRunResult = when (json["kind"]!!.jsonPrimitive.content) {
        "started" -> StartNodeRunResult.Started(
            runId = json["runId"]!!.jsonPrimitive.content,
            sessionId = json["sessionId"]!!.jsonPrimitive.content,
        )
        else -> StartNodeRunResult.Refused(
            code = json["code"]!!.jsonPrimitive.content,
            message = json["message"]!!.jsonPrimitive.content,
        )
    }

    inline fun <reified T> encodeSerializable(value: T): JsonElement =
        jsonFormat.encodeToJsonElement(value)

    inline fun <reified T> decodeSerializable(element: JsonElement): T =
        jsonFormat.decodeFromJsonElement(element)

    private fun JsonPrimitiveOrNull(value: String) = kotlinx.serialization.json.JsonPrimitive(value)
}
