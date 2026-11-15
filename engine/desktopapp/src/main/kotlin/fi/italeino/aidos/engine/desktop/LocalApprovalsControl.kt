package fi.italeino.aidos.engine.desktop

import fi.italeino.aidos.engine.approval.AppApprovalStatus
import fi.italeino.aidos.engine.control.ApprovalsControl
import fi.italeino.aidos.engine.control.PendingApproval

/**
 * [ApprovalsControl] over this process's Engine (RFC-0115, D59): app approvals waiting in the approval store, and
 * effectful tool calls on tainted remote runs waiting in [DesktopRemoteAgent.approvals]. Ids are `app:<package>`
 * and `tool:<uuid>`, so one decide call reaches the right source.
 */
class LocalApprovalsControl(private val host: DesktopEngineHost) : ApprovalsControl {
    override suspend fun pending(): List<PendingApproval> {
        val apps = host.approvalStore.listAllApprovals().filter { it.status == AppApprovalStatus.PENDING }.map {
            PendingApproval("app:${it.packageName}", "app", "${it.displayName} wants to use the Engine", "A new app is asking for access to models. Nothing runs until you approve it.", null)
        }
        val labels = host.aidin.value?.peers?.value?.associate { it.nodeId.value to (it.label ?: it.nodeId.value.take(8)) }.orEmpty()
        val tools = host.remoteAgent?.approvals?.pending?.value.orEmpty().map {
            PendingApproval(
                "tool:${it.id}", "tool", "${labels[it.peer.value] ?: it.peer.value.take(8)} wants ${it.app} to run ${it.tool}",
                "This run has already received tool results, so a tool that changes things needs your approval. No answer counts as no.",
                it.requestedAtMillis,
            )
        }
        return apps + tools
    }

    override suspend fun decide(id: String, allow: Boolean): Boolean = when {
        id.startsWith("app:") -> {
            val pkg = id.removePrefix("app:")
            val current = host.approvalStore.getApproval(pkg)
            if (current?.status != AppApprovalStatus.PENDING) false
            else (if (allow) host.approvalStore.approveApp(pkg) else host.approvalStore.denyApp(pkg)) != null
        }
        id.startsWith("tool:") -> host.remoteAgent?.approvals?.decide(id.removePrefix("tool:"), allow) ?: false
        else -> false
    }
}
