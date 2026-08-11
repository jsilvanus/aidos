package dev.aidos.daemon

import app.cash.sqldelight.db.SqlDriver
import dev.aidos.api.EffectApprovalGateway
import dev.aidos.api.EffectResolution
import dev.aidos.executor.CapabilityApprovalResolution
import dev.aidos.identity.UuidV7Generator
import dev.aidos.kernel.RunId

/**
 * The real [EffectApprovalGateway] (RFC-0008 step 8d): resolves the approval via
 * [RuntimeCompositionRoot.resolveAnyApproval] — `CAPABILITY_APPROVAL` (M23's `RemotePendingApproval`)
 * and `TOOL_CALL` (`DenialReason.REQUIRES_APPROVAL`) both, dispatched by whichever continuation
 * kind is actually parked. Composed here rather than living in `api` because `api` cannot depend
 * on `executor` without a module cycle — see [EffectApprovalGateway]'s own doc comment. Mirrors
 * [SqliteRunExecutor]'s own composition style (a thin `api`-shaped wrapper over a call into
 * [RuntimeCompositionRoot]).
 */
class SqliteEffectApprovalGateway(
    private val compositionRoot: RuntimeCompositionRoot,
    private val idGen: () -> String = { UuidV7Generator().next() },
    private val nowIso: () -> String,
) : EffectApprovalGateway {

    override suspend fun resolve(
        projectDriver: SqlDriver,
        runId: String,
        approved: Boolean,
        denialReason: String?,
    ): EffectResolution {
        val resolution = compositionRoot.resolveAnyApproval(
            projectDriver = projectDriver,
            runId = RunId(runId),
            approved = approved,
            denialReason = denialReason,
            idGen = idGen,
            nowIso = nowIso,
        )
        return when (resolution) {
            is CapabilityApprovalResolution.Resumed -> EffectResolution.Resumed
            is CapabilityApprovalResolution.Denied -> EffectResolution.Denied
            is CapabilityApprovalResolution.NotFound -> EffectResolution.NotFound
            is CapabilityApprovalResolution.WrongKind -> EffectResolution.NotFound
        }
    }

    override suspend fun answer(
        projectDriver: SqlDriver,
        runId: String,
        answer: String,
    ): EffectResolution {
        val resolution = compositionRoot.resolveUserPromptAnswer(
            projectDriver = projectDriver,
            runId = RunId(runId),
            answer = answer,
            idGen = idGen,
            nowIso = nowIso,
        )
        return when (resolution) {
            is CapabilityApprovalResolution.Resumed -> EffectResolution.Resumed
            is CapabilityApprovalResolution.Denied -> EffectResolution.Denied
            is CapabilityApprovalResolution.NotFound -> EffectResolution.NotFound
            is CapabilityApprovalResolution.WrongKind -> EffectResolution.NotFound
        }
    }
}
