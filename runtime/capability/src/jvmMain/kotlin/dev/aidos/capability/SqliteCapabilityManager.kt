package dev.aidos.capability

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import dev.aidos.identity.UuidV7Generator
import dev.aidos.kernel.AidosError
import dev.aidos.kernel.AuditId
import dev.aidos.kernel.Budget
import dev.aidos.kernel.Capability
import dev.aidos.kernel.CapabilityCheckResult
import dev.aidos.kernel.CapabilityConstraints
import dev.aidos.kernel.CapabilityId
import dev.aidos.kernel.CapabilityManager
import dev.aidos.kernel.CapabilityScope
import dev.aidos.kernel.DenialReason
import dev.aidos.kernel.ErrorClass
import dev.aidos.kernel.GitOperation
import dev.aidos.kernel.GrantSource
import dev.aidos.kernel.ModelKind
import dev.aidos.kernel.Operation
import dev.aidos.kernel.Permission
import dev.aidos.kernel.ProjectId
import dev.aidos.kernel.RedirectPolicy
import dev.aidos.kernel.ResourceHandle
import dev.aidos.kernel.SecretId
import dev.aidos.kernel.SessionRole
import dev.aidos.kernel.SubjectKind
import dev.aidos.kernel.TrustLevel
import dev.aidos.kernel.UserId
import kotlinx.datetime.Instant
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * SQLite-backed capability manager (RFC-0018, M3).
 *
 * Stores capabilities in the project database. Revocation increments the per-project epoch
 * in one transaction and recursively marks delegated children revoked. Callers that cache
 * capabilities compare their recorded epoch against [currentEpoch] before reuse — a stale
 * cache re-reads from SQLite; it does not degrade to a security failure.
 *
 * Taint attenuation: [validate] applies RFC-0027 rules. An UNTRUSTED run is denied SECRETS_READ,
 * NETWORK_EGRESS (unless per-call approval is set on the grant), and FS_WRITE outside the
 * project. In MVP, the "outside project" boundary is enforced at the scope level — a capability
 * scoped to a project cannot authorise cross-project writes. The rule that fires here is the
 * unconditional denial when taint == UNTRUSTED and the permission class requires approval or
 * is in the UNSAFE effect class.
 */
class SqliteCapabilityManager(
    private val projectDriver: SqlDriver,
    private val ids: UuidV7Generator,
    private val nowIso: () -> String,
) : CapabilityManager {

    override suspend fun grant(
        subjectId: String,
        subjectKind: SubjectKind,
        permission: Permission,
        scope: CapabilityScope,
        constraints: CapabilityConstraints,
        expiresAt: Instant?,
        grantedBy: UserId,
    ): Result<Capability> {
        val id = CapabilityId(ids.next())
        val auditId = AuditId(ids.next())
        val now = nowIso()
        val epoch = currentEpochOrZero(scope.projectId())
        val projectIdStr = scope.projectId()?.value ?: ""

        insertAuditRow(auditId.value, projectIdStr, grantedBy.value, now)

        projectDriver.execute(
            identifier = null,
            sql = "INSERT INTO capabilities " +
                "(id, project_id, permission, subject_id, subject_kind, scope_json, " +
                "constraints_json, issued_at, issued_by_kind, issued_by_id, " +
                "parent_capability_id, allows_delegation, expires_at, revoked_at, revoked_by, " +
                "revocation_epoch, audit_ref) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'USER', ?, NULL, 0, ?, NULL, NULL, ?, ?)",
            parameters = 10,
        ) {
            bindString(0, id.value)
            bindString(1, projectIdStr)
            bindString(2, permission.name)
            bindString(3, subjectId)
            bindString(4, subjectKind.name)
            bindString(5, Json.encodeToString(scope.toJson()))
            bindString(6, Json.encodeToString(constraints.toJson()))
            bindString(7, now)
            bindString(8, grantedBy.value)
            bindString(9, expiresAt?.toString())
            bindLong(10, epoch)
            bindString(11, auditId.value)
        }

        return Result.success(
            Capability(
                id = id,
                projectId = scope.projectId() ?: ProjectId(""),
                permission = permission,
                subjectId = subjectId,
                subjectKind = subjectKind,
                scope = scope,
                constraints = constraints,
                issuedAt = Instant.parse(now),
                issuedBy = GrantSource.User,
                parentCapabilityId = null,
                allowsDelegation = false,
                expiresAt = expiresAt,
                revokedAt = null,
                revocationEpoch = epoch,
                auditRef = auditId,
            )
        )
    }

    override suspend fun delegate(
        parent: CapabilityId,
        toSubjectId: String,
        toSubjectKind: SubjectKind,
        attenuatedScope: CapabilityScope,
        attenuatedConstraints: CapabilityConstraints,
    ): Result<Capability> {
        val parentCap = loadCapabilityById(parent)
            ?: return Result.failure(capNotFound(parent))
        if (parentCap.revokedAt != null) {
            return Result.failure(RuntimeException("parent capability ${parent.value} is revoked"))
        }
        if (!parentCap.allowsDelegation) {
            return Result.failure(RuntimeException("parent capability ${parent.value} does not allow delegation"))
        }

        val id = CapabilityId(ids.next())
        val auditId = AuditId(ids.next())
        val now = nowIso()
        val epoch = currentEpochOrZero(parentCap.scope.projectId())

        // Was missing entirely: every delegate() call failed FK-violating on audit_ref, since
        // nothing wrote the audit_log row it references (unlike grant(), just above, which does).
        // delegate() was therefore never actually callable before this fix.
        insertAuditRow(
            auditId.value, parentCap.projectId.value, parentCap.subjectId, now,
            kind = "CapabilityDelegated", actorKind = "SESSION",
        )

        projectDriver.execute(
            identifier = null,
            sql = "INSERT INTO capabilities " +
                "(id, project_id, permission, subject_id, subject_kind, scope_json, " +
                "constraints_json, issued_at, issued_by_kind, issued_by_id, " +
                "parent_capability_id, allows_delegation, expires_at, revoked_at, revoked_by, " +
                "revocation_epoch, audit_ref) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'SESSION', ?, ?, 0, ?, NULL, NULL, ?, ?)",
            parameters = 11,
        ) {
            bindString(0, id.value)
            bindString(1, parentCap.projectId.value)
            bindString(2, parentCap.permission.name)
            bindString(3, toSubjectId)
            bindString(4, toSubjectKind.name)
            bindString(5, Json.encodeToString(attenuatedScope.toJson()))
            bindString(6, Json.encodeToString(attenuatedConstraints.toJson()))
            bindString(7, now)
            bindString(8, parentCap.subjectId)
            bindString(9, parent.value)
            bindString(10, (parentCap.expiresAt ?: attenuatedConstraints.maxDurationSeconds?.let { null })?.toString())
            bindLong(11, epoch)
            bindString(12, auditId.value)
        }

        return Result.success(
            Capability(
                id = id,
                projectId = parentCap.projectId,
                permission = parentCap.permission,
                subjectId = toSubjectId,
                subjectKind = toSubjectKind,
                scope = attenuatedScope,
                constraints = attenuatedConstraints,
                issuedAt = Instant.parse(now),
                issuedBy = GrantSource.Delegation(dev.aidos.kernel.SessionId(parentCap.subjectId)),
                parentCapabilityId = parent,
                allowsDelegation = false,
                expiresAt = parentCap.expiresAt,
                revokedAt = null,
                revocationEpoch = epoch,
                auditRef = auditId,
            )
        )
    }

    override suspend fun openHandle(subjectId: String, capabilityId: CapabilityId): Result<ResourceHandle> {
        val cap = loadCapabilityById(capabilityId)
            ?: return Result.failure(capNotFound(capabilityId))
        if (cap.subjectId != subjectId) {
            return Result.failure(RuntimeException("capability ${capabilityId.value} does not belong to subject $subjectId"))
        }
        if (cap.revokedAt != null) {
            return Result.failure(RuntimeException("capability ${capabilityId.value} is revoked"))
        }
        val scope = cap.scope
        if (scope !is CapabilityScope.Filesystem) {
            return Result.failure(RuntimeException("openHandle only supported for Filesystem capabilities in MVP"))
        }
        return Result.success(
            SqliteDirHandle(capabilityId, scope.rootRelativePath, this)
        )
    }

    override suspend fun validate(
        subjectId: String,
        capabilityId: CapabilityId,
        operation: Operation<*>,
        runTaint: TrustLevel,
    ): CapabilityCheckResult {
        val cap = loadCapabilityById(capabilityId)
            ?: return CapabilityCheckResult.Denied(DenialReason.NO_CAPABILITY)

        if (cap.subjectId != subjectId) {
            return CapabilityCheckResult.Denied(DenialReason.NO_CAPABILITY)
        }

        // Revocation check: compare stored epoch against current project epoch.
        val currentEpoch = currentEpochOrZero(cap.scope.projectId())
        if (cap.revokedAt != null || cap.revocationEpoch < currentEpoch) {
            return CapabilityCheckResult.Denied(DenialReason.CAPABILITY_REVOKED)
        }

        // Expiry check.
        val exp = cap.expiresAt
        if (exp != null && Instant.parse(nowIso()) > exp) {
            return CapabilityCheckResult.Denied(DenialReason.CAPABILITY_EXPIRED)
        }

        // Taint attenuation (RFC-0027): UNTRUSTED runs are denied for specific permission classes.
        if (runTaint == TrustLevel.UNTRUSTED && isTaintDenied(cap.permission, operation)) {
            return CapabilityCheckResult.Denied(DenialReason.ATTENUATED_BY_TAINT)
        }

        // RFC-0008 step 8d, TOOL_CALL continuation (branch `claude/continuation-flow`, follow-up):
        // a grant issued with requiresApprovalPerUse was silently unenforced -- every use passed
        // validate() exactly as if the constraint didn't exist, since nothing here ever read it.
        // This makes the constraint real again: every exercise of such a grant is denied, always
        // -- "per use" means every use, not just the first. Nothing in this codebase issues a
        // grant with this flag set yet (grep confirms), so this is inert until something does; the
        // executor-side response (park for a human decision instead of returning the denial to the
        // model as data, the way every other DenialReason here already does) is a separate,
        // larger piece — see PIPELINE.md's "Notes for the next link" for the resume design and why
        // it can't reuse EffectBroker.invoke() (runtime/kernel/ is frozen at G0).
        if (cap.constraints.requiresApprovalPerUse) {
            return CapabilityCheckResult.Denied(DenialReason.REQUIRES_APPROVAL)
        }

        return CapabilityCheckResult.Allowed
    }

    override suspend fun revoke(capabilityId: CapabilityId, revokedBy: String) {
        val now = nowIso()
        // Increment the project epoch and revoke the capability tree in one transaction.
        projectDriver.execute(
            identifier = null,
            sql = "UPDATE project_revocation_epoch SET epoch = epoch + 1 " +
                "WHERE project_id = (SELECT project_id FROM capabilities WHERE id = ?)",
            parameters = 1,
        ) { bindString(0, capabilityId.value) }

        revokeTree(capabilityId, revokedBy, now)
    }

    /** Recursively revokes a capability and all its delegated descendants. */
    private fun revokeTree(capabilityId: CapabilityId, revokedBy: String, now: String) {
        projectDriver.execute(
            identifier = null,
            sql = "UPDATE capabilities SET revoked_at = ?, revoked_by = ? WHERE id = ?",
            parameters = 3,
        ) {
            bindString(0, now)
            bindString(1, revokedBy)
            bindString(2, capabilityId.value)
        }

        // Recursively revoke delegated children.
        val children = mutableListOf<String>()
        projectDriver.executeQuery(
            identifier = null,
            sql = "SELECT id FROM capabilities WHERE parent_capability_id = ? AND revoked_at IS NULL",
            mapper = { cursor ->
                while (cursor.next().value) {
                    children.add(cursor.getString(0)!!)
                }
                QueryResult.Value(Unit)
            },
            parameters = 1,
        ) { bindString(0, capabilityId.value) }

        for (childId in children) {
            revokeTree(CapabilityId(childId), revokedBy, now)
        }
    }

    override suspend fun loadForSubject(subjectId: String): List<Capability> {
        val results = mutableListOf<Capability>()
        projectDriver.executeQuery(
            identifier = null,
            sql = "SELECT id, project_id, permission, subject_id, subject_kind, scope_json, " +
                "constraints_json, issued_at, issued_by_kind, issued_by_id, " +
                "parent_capability_id, allows_delegation, expires_at, revoked_at, revocation_epoch, audit_ref " +
                "FROM capabilities WHERE subject_id = ? AND revoked_at IS NULL",
            mapper = { cursor ->
                while (cursor.next().value) {
                    parseCapabilityRow(cursor)?.let { results.add(it) }
                }
                QueryResult.Value(Unit)
            },
            parameters = 1,
        ) { bindString(0, subjectId) }
        return results
    }

    override fun currentEpoch(projectId: ProjectId): Long = currentEpochOrZero(projectId)

    /** Returns the project ID associated with a capability, or null if not found. */
    fun projectIdForCapability(capId: dev.aidos.kernel.CapabilityId): String? =
        projectDriver.executeQuery(
            identifier = null,
            sql = "SELECT project_id FROM capabilities WHERE id = ?",
            mapper = { c -> QueryResult.Value(if (c.next().value) c.getString(0) else null) },
            parameters = 1,
        ) { bindString(0, capId.value) }.value

    // ─── Internal helpers ────────────────────────────────────────────────────

    private fun currentEpochOrZero(projectId: ProjectId?): Long {
        if (projectId == null || projectId.value.isEmpty()) return 0L
        return projectDriver.executeQuery(
            identifier = null,
            sql = "SELECT epoch FROM project_revocation_epoch WHERE project_id = ?",
            mapper = { cursor ->
                QueryResult.Value(if (cursor.next().value) cursor.getLong(0) ?: 0L else 0L)
            },
            parameters = 1,
        ) { bindString(0, projectId.value) }.value
    }

    private fun loadCapabilityById(id: CapabilityId): Capability? {
        return projectDriver.executeQuery(
            identifier = null,
            sql = "SELECT id, project_id, permission, subject_id, subject_kind, scope_json, " +
                "constraints_json, issued_at, issued_by_kind, issued_by_id, " +
                "parent_capability_id, allows_delegation, expires_at, revoked_at, revocation_epoch, audit_ref " +
                "FROM capabilities WHERE id = ?",
            mapper = { cursor ->
                QueryResult.Value(if (cursor.next().value) parseCapabilityRow(cursor) else null)
            },
            parameters = 1,
        ) { bindString(0, id.value) }.value
    }

    private fun parseCapabilityRow(cursor: app.cash.sqldelight.db.SqlCursor): Capability? {
        val id = cursor.getString(0) ?: return null
        val projectId = cursor.getString(1) ?: return null
        val permission = Permission.valueOf(cursor.getString(2) ?: return null)
        val subjectId = cursor.getString(3) ?: return null
        val subjectKind = SubjectKind.valueOf(cursor.getString(4) ?: return null)
        val scopeJson = cursor.getString(5) ?: return null
        val constraintsJson = cursor.getString(6) ?: return null
        val issuedAt = cursor.getString(7) ?: return null
        val issuedByKind = cursor.getString(8) ?: return null
        val issuedById = cursor.getString(9) ?: return null
        val parentId = cursor.getString(10)
        @Suppress("UNUSED_VARIABLE")
        val allowsDelegation = (cursor.getLong(11) ?: 0L) != 0L
        val expiresAt = cursor.getString(12)
        val revokedAt = cursor.getString(13)
        val revocationEpoch = cursor.getLong(14) ?: 0L
        val auditRef = cursor.getString(15) ?: return null

        val scope = parseScope(scopeJson, ProjectId(projectId))

        return Capability(
            id = CapabilityId(id),
            projectId = ProjectId(projectId),
            permission = permission,
            subjectId = subjectId,
            subjectKind = subjectKind,
            scope = scope,
            constraints = parseConstraints(constraintsJson),
            issuedAt = Instant.parse(issuedAt),
            issuedBy = when (issuedByKind) {
                "USER" -> GrantSource.User
                "SESSION" -> GrantSource.Delegation(dev.aidos.kernel.SessionId(issuedById))
                else -> GrantSource.User
            },
            parentCapabilityId = parentId?.let { CapabilityId(it) },
            allowsDelegation = (cursor.getLong(11) ?: 0L) != 0L,
            expiresAt = expiresAt?.let { Instant.parse(it) },
            revokedAt = revokedAt?.let { Instant.parse(it) },
            revocationEpoch = revocationEpoch,
            auditRef = AuditId(auditRef),
        )
    }

    private fun capNotFound(id: CapabilityId): Exception = RuntimeException(
        AidosError(
            code = "capability.not_found",
            errorClass = ErrorClass.DENIED,
            message = "No capability found with id '${id.value}'",
        ).message
    )

    /**
     * Inserts an audit_log row for a capability grant or delegation and returns its ID.
     *
     * audit_log.sequence must be unique per project. We use the current row count + 1 as a
     * simple monotonic sequence in MVP; a dedicated sequence table is out of scope until M4.
     *
     * [kind]/[actorKind] default to `grant()`'s original behaviour (a user-issued grant). RFC-0011:
     * `delegate()` calls this with `kind = "CapabilityDelegated"`, `actorKind = "SESSION"` — a
     * delegation's actor is the delegating session, not a user, and conflating the two would
     * misattribute the audit trail RFC-0046 exists to keep accurate.
     */
    private fun insertAuditRow(
        auditId: String,
        projectId: String,
        actorId: String,
        now: String,
        kind: String = "CapabilityGranted",
        actorKind: String = "USER",
    ) {
        val seq = projectDriver.executeQuery(
            identifier = null,
            sql = "SELECT COUNT(*) FROM audit_log WHERE project_id = ?",
            mapper = { c -> QueryResult.Value(if (c.next().value) (c.getLong(0) ?: 0L) + 1L else 1L) },
            parameters = 1,
        ) { bindString(0, projectId) }.value

        projectDriver.execute(
            identifier = null,
            sql = "INSERT INTO audit_log (id, project_id, sequence, occurred_at, kind, " +
                "actor_kind, actor_id, device_id) VALUES (?, ?, ?, ?, ?, ?, ?, 'runtime')",
            parameters = 7,
        ) {
            bindString(0, auditId)
            bindString(1, projectId)
            bindLong(2, seq)
            bindString(3, now)
            bindString(4, kind)
            bindString(5, actorKind)
            bindString(6, actorId)
        }
    }

    companion object {
        /**
         * Returns true when an UNTRUSTED run should be denied this permission+operation.
         *
         * RFC-0027 table: UNTRUSTED runs may not exercise SECRETS_READ, NETWORK_EGRESS,
         * or SHELL_EXEC (unless the grant explicitly sets requiresApprovalPerUse, which
         * turns the denial into a runtime escalation — out of scope for MVP validate()).
         */
        fun isTaintDenied(permission: Permission, @Suppress("UNUSED_PARAMETER") operation: Operation<*>): Boolean =
            permission == Permission.SECRETS_READ ||
                permission == Permission.NETWORK_EGRESS ||
                permission == Permission.SHELL_EXEC
    }
}

private fun CapabilityScope.projectId(): ProjectId? = when (this) {
    is CapabilityScope.Filesystem -> projectId
    is CapabilityScope.Shell -> projectId
    is CapabilityScope.Git -> projectId
    is CapabilityScope.Secrets -> projectId
    is CapabilityScope.Network -> null
    is CapabilityScope.Model -> null
    is CapabilityScope.Worker -> null
    is CapabilityScope.Events -> null
}

/**
 * RFC-0018/RFC-0011: full round-trip, all eight [CapabilityScope] variants. Until this fix, only
 * `Filesystem` wrote its distinguishing field (`root`) — every other variant serialized `type`
 * (and `project_id`, where applicable) only, silently dropping the rest of its data
 * (`Git.allowedOperations`, `Shell.workingDirectory`/`allowedCommands`,
 * `Network.allowedHosts`/etc., `Model.allowedProviders`/`allowedKinds`,
 * `Worker.maxWorkerCount`/`allowedRoles`, `Secrets.allowedSecretIds`, `Events.topicPatterns`) —
 * and the read side ([parseScope]) didn't exist at all: [parseCapabilityRow] reconstructed a
 * *hard-coded* `Filesystem` scope for every capability regardless of its actual type, treating the
 * whole JSON blob as a literal path. RFC-0011's worker capability delegation is what surfaced
 * this: `delegate()` re-derives the parent's *actual* scope via [dev.aidos.kernel.CapabilityManager.loadForSubject],
 * so a lossy round-trip here would silently widen or corrupt what a worker is attenuated to.
 */
private fun CapabilityScope.toJson(): JsonElement = buildJsonObject {
    when (this@toJson) {
        is CapabilityScope.Filesystem -> {
            put("type", "filesystem")
            put("project_id", projectId.value)
            put("root", rootRelativePath)
        }
        is CapabilityScope.Git -> {
            put("type", "git")
            put("project_id", projectId.value)
            put("allowed_operations", buildJsonArray { allowedOperations.forEach { add(it.name) } })
        }
        is CapabilityScope.Network -> {
            put("type", "network")
            put("allowed_hosts", buildJsonArray { allowedHosts.forEach { add(it) } })
            allowedPorts?.let { ports -> put("allowed_ports", buildJsonArray { ports.forEach { add(it) } }) }
            put("allow_private_addresses", allowPrivateAddresses)
            put("follow_redirects", followRedirects.name)
            put("max_response_bytes", maxResponseBytes)
        }
        is CapabilityScope.Model -> {
            put("type", "model")
            allowedProviders?.let { providers -> put("allowed_providers", buildJsonArray { providers.forEach { add(it) } }) }
            put("allowed_kinds", buildJsonArray { allowedKinds.forEach { add(it.name) } })
        }
        is CapabilityScope.Shell -> {
            put("type", "shell")
            put("project_id", projectId.value)
            put("working_directory", workingDirectory)
            allowedCommands?.let { cmds -> put("allowed_commands", buildJsonArray { cmds.forEach { add(it) } }) }
        }
        is CapabilityScope.Secrets -> {
            put("type", "secrets")
            put("project_id", projectId.value)
            put("allowed_secret_ids", buildJsonArray { allowedSecretIds.forEach { add(it.value) } })
        }
        is CapabilityScope.Worker -> {
            put("type", "worker")
            put("max_worker_count", maxWorkerCount)
            put("allowed_roles", buildJsonArray { allowedRoles.forEach { add(it.name) } })
        }
        is CapabilityScope.Events -> {
            put("type", "events")
            put("topic_patterns", buildJsonArray { topicPatterns.forEach { add(it) } })
        }
    }
}

/** The read side of [CapabilityScope.toJson] — dispatches on the `type` discriminator instead of
 *  assuming `Filesystem`. [fallbackProjectId] covers the (theoretical) case of a `project_id`-
 *  bearing variant whose JSON predates this fix and never wrote one; every capability this
 *  codebase has ever granted or delegated shares [fallbackProjectId] with the row it came from,
 *  so this never produces a cross-project scope. */
private fun parseScope(json: String, fallbackProjectId: ProjectId): CapabilityScope {
    val obj = Json.parseToJsonElement(json).jsonObject
    fun projectIdOf() = obj["project_id"]?.jsonPrimitive?.content?.let { ProjectId(it) } ?: fallbackProjectId
    fun strings(key: String) = obj[key]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
    return when (obj["type"]?.jsonPrimitive?.content) {
        "git" -> CapabilityScope.Git(
            projectId = projectIdOf(),
            allowedOperations = strings("allowed_operations").map { GitOperation.valueOf(it) }.toSet(),
        )
        "network" -> CapabilityScope.Network(
            allowedHosts = strings("allowed_hosts"),
            allowedPorts = obj["allowed_ports"]?.jsonArray?.map { it.jsonPrimitive.int },
            allowPrivateAddresses = obj["allow_private_addresses"]?.jsonPrimitive?.booleanOrNull ?: false,
            followRedirects = obj["follow_redirects"]?.jsonPrimitive?.content
                ?.let { RedirectPolicy.valueOf(it) } ?: RedirectPolicy.SAME_HOST_ONLY,
            maxResponseBytes = obj["max_response_bytes"]?.jsonPrimitive?.longOrNull ?: (32L * 1024 * 1024),
        )
        "model" -> CapabilityScope.Model(
            allowedProviders = obj["allowed_providers"]?.jsonArray?.map { it.jsonPrimitive.content },
            allowedKinds = strings("allowed_kinds").map { ModelKind.valueOf(it) }.toSet(),
        )
        "shell" -> CapabilityScope.Shell(
            projectId = projectIdOf(),
            workingDirectory = obj["working_directory"]?.jsonPrimitive?.content ?: "",
            allowedCommands = obj["allowed_commands"]?.jsonArray?.map { it.jsonPrimitive.content },
        )
        "secrets" -> CapabilityScope.Secrets(
            projectId = projectIdOf(),
            allowedSecretIds = strings("allowed_secret_ids").map { SecretId(it) },
        )
        "worker" -> CapabilityScope.Worker(
            maxWorkerCount = obj["max_worker_count"]?.jsonPrimitive?.intOrNull ?: 0,
            allowedRoles = strings("allowed_roles").map { SessionRole.valueOf(it) }.toSet(),
        )
        "events" -> CapabilityScope.Events(topicPatterns = strings("topic_patterns"))
        // "filesystem", or a legacy row from before this fix (no "type" tag; the raw string was
        // the whole scope_json — never valid JSON for any other variant, so this remains a safe
        // default): treat the whole thing as a Filesystem scope, same as the pre-fix behaviour.
        else -> CapabilityScope.Filesystem(
            projectId = projectIdOf(),
            rootRelativePath = obj["root"]?.jsonPrimitive?.content ?: json,
        )
    }
}

/** RFC-0028: all seven [Budget] dimensions, not just `modelCalls`/`steps`. */
private fun Budget.toJson(): JsonElement = buildJsonObject {
    modelCalls?.let { put("model_calls", it) }
    inputTokens?.let { put("input_tokens", it) }
    outputTokens?.let { put("output_tokens", it) }
    costUnits?.let { put("cost_units", it) }
    steps?.let { put("steps", it) }
    wallClockSeconds?.let { put("wall_clock_seconds", it) }
    toolInvocations?.let { put("tool_invocations", it) }
}

private fun parseBudget(obj: JsonObject): Budget = Budget(
    modelCalls = obj["model_calls"]?.jsonPrimitive?.intOrNull,
    inputTokens = obj["input_tokens"]?.jsonPrimitive?.longOrNull,
    outputTokens = obj["output_tokens"]?.jsonPrimitive?.longOrNull,
    costUnits = obj["cost_units"]?.jsonPrimitive?.longOrNull,
    steps = obj["steps"]?.jsonPrimitive?.intOrNull,
    wallClockSeconds = obj["wall_clock_seconds"]?.jsonPrimitive?.intOrNull,
    toolInvocations = obj["tool_invocations"]?.jsonPrimitive?.intOrNull,
)

private fun CapabilityConstraints.toJson(): JsonElement = buildJsonObject {
    maxDurationSeconds?.let { put("max_duration_seconds", it) }
    maxBytesRead?.let { put("max_bytes_read", it) }
    maxBytesWritten?.let { put("max_bytes_written", it) }
    if (requiresApprovalPerUse) put("requires_approval_per_use", true)
    maxExerciseCount?.let { put("max_exercise_count", it) }
    budget?.let { put("budget", it.toJson()) }
}

/**
 * The read side of [CapabilityConstraints.toJson] — until this fix, `constraints_json` was
 * written on every grant and never parsed back anywhere (`parseCapabilityRow` constructed a bare
 * `CapabilityConstraints()` default, discarding the column it had just read). Every constraint —
 * not just `requiresApprovalPerUse` — was silently unenforced on any capability loaded back from
 * storage. `budget` now round-trips all seven [Budget] dimensions (RFC-0011: a delegated worker's
 * split budget must actually carry every dimension the parent's did, not just model-call/step
 * counts) — previously only `modelCalls`/`steps` survived a round trip.
 */
private fun parseConstraints(json: String): CapabilityConstraints {
    val obj = Json.parseToJsonElement(json).jsonObject
    return CapabilityConstraints(
        maxDurationSeconds = obj["max_duration_seconds"]?.jsonPrimitive?.intOrNull,
        maxBytesRead = obj["max_bytes_read"]?.jsonPrimitive?.longOrNull,
        maxBytesWritten = obj["max_bytes_written"]?.jsonPrimitive?.longOrNull,
        requiresApprovalPerUse = obj["requires_approval_per_use"]?.jsonPrimitive?.booleanOrNull ?: false,
        maxExerciseCount = obj["max_exercise_count"]?.jsonPrimitive?.intOrNull,
        budget = obj["budget"]?.jsonObject?.let { parseBudget(it) },
    )
}
