package dev.aidos.daemon

import dev.aidos.kernel.CapabilityId
import dev.aidos.kernel.CapabilityManager
import dev.aidos.kernel.Permission
import kotlinx.datetime.Instant

/**
 * M19 (RFC-0008 step 8c): resolves a model-emitted tool call to a capability the subject already
 * holds for the tool's required permission — the mapping `AgentLoopTaskRunner`'s own doc comment
 * named as "a separate, not-yet-built subsystem" until now.
 *
 * **Design confirmed with the project owner before being built**, per CLAUDE.md's "humans keep
 * this kind of decision" principle — see PIPELINE.md's M19 entry for the discussion. The picked
 * shape: match by `(subjectId, permission)`, prefer the most recently issued grant among unexpired,
 * unrevoked matches, no error if more than one exists.
 *
 * **Why this can be this simple without being unsafe.** This class does not decide authority —
 * it looks an existing grant up. Whatever it returns still goes through
 * `CapabilityManager.validate()`, called next by `ToolBroker.invoke()`, which is what actually
 * checks scope (a filesystem grant's path prefix, say), expiry, revocation, and taint
 * attenuation. A resolution that picks the "wrong" capability (e.g. one whose scope doesn't cover
 * the call) can only under-grant — `validate()` denies it exactly as it denies today's
 * unconditional `null`. There is no path from a wrong pick here to an over-grant: `validate()` is
 * still the authority, this is only the lookup.
 */
class CapabilityResolver(
    private val capabilityManager: CapabilityManager,
    private val nowIso: () -> String,
) {
    suspend fun resolve(subjectId: String, permission: Permission): CapabilityId? {
        val now = Instant.parse(nowIso())
        return capabilityManager.loadForSubject(subjectId)
            .asSequence()
            .filter { it.permission == permission }
            .filter { it.revokedAt == null }
            .filter { val expiresAt = it.expiresAt; expiresAt == null || expiresAt > now }
            .maxByOrNull { it.issuedAt }
            ?.id
    }
}
