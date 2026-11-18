package dev.aidos.api.socket

/**
 * Method names for the socket wire protocol (RFC-0052 M10).
 *
 * Shared between [dev.aidos.api] socket codec, the daemon's server dispatcher, and the CLI's
 * socket client, so a typo shows up as a compile error on at least one side rather than a
 * silent method-not-found at runtime.
 */
object Methods {
    const val CURRENT_API_VERSION = 1
    const val MIN_SUPPORTED_API_VERSION = 1

    const val PROJECTS_CREATE = "projects.create"
    const val PROJECTS_OPEN = "projects.open"
    const val PROJECTS_LIST = "projects.list"
    const val PROJECTS_GET = "projects.get"
    const val PROJECTS_CLOSE = "projects.close"
    const val PROJECTS_DELETE = "projects.delete"

    const val SESSIONS_CREATE = "sessions.create"
    const val SESSIONS_LIST = "sessions.list"
    const val SESSIONS_GET = "sessions.get"
    const val SESSIONS_SEND = "sessions.send"
    const val SESSIONS_CANCEL = "sessions.cancel"
    const val SESSIONS_ARCHIVE = "sessions.archive"
    const val SESSIONS_DELETE = "sessions.delete"

    const val CAPABILITIES_GRANT = "capabilities.grant"
    const val CAPABILITIES_REVOKE = "capabilities.revoke"
    const val CAPABILITIES_LIST = "capabilities.list"
    const val CAPABILITIES_LIST_PENDING = "capabilities.listPending"
    const val CAPABILITIES_LIST_PARKED_EGRESS = "capabilities.listParkedEgress"
    const val CAPABILITIES_LIST_PARKED_EFFECTS = "capabilities.listParkedEffects"
    const val CAPABILITIES_APPROVE = "capabilities.approve"
    const val CAPABILITIES_DENY = "capabilities.deny"

    /** RFC-0008 step 8d: resolves a Run parked on `RoutingDecision.RemotePendingApproval`. */
    const val CAPABILITIES_APPROVE_EFFECT = "capabilities.approveEffect"
    const val CAPABILITIES_DENY_EFFECT = "capabilities.denyEffect"

    /** RFC-0008 step 8d: answers a Run parked on `USER_PROMPT` (the model's `ask_user` call). */
    const val CAPABILITIES_ANSWER_PROMPT = "capabilities.answerPrompt"

    const val KNOWLEDGE_SEARCH = "knowledge.search"
    const val KNOWLEDGE_INDEX_STATUS = "knowledge.indexStatus"

    const val DIFF_CHANGES = "diff.changes"
    const val DIFF_HUNKS = "diff.hunks"
    const val DIFF_UNIFIED = "diff.unified"
    const val DIFF_STAGE = "diff.stage"
    const val DIFF_REVERT = "diff.revert"
    const val DIFF_COMMIT = "diff.commit"

    const val ARTIFACTS_LIST = "artifacts.list"
    const val ARTIFACTS_GET = "artifacts.get"
    const val ARTIFACTS_AUDIT_TRAIL = "artifacts.getAuditTrail"

    const val INTENTS_CREATE_NODE = "intents.createNode"
    const val INTENTS_UPDATE_NODE = "intents.updateNode"
    const val INTENTS_ARCHIVE_NODE = "intents.archiveNode"
    const val INTENTS_GET_NODE_STATUS = "intents.getNodeStatus"
    const val INTENTS_LIST_NODES = "intents.listNodes"
    const val INTENTS_LIST_ACTIONABLE = "intents.listActionableForHuman"
    const val INTENTS_LIST_READY = "intents.listReadyForAgent"
    const val INTENTS_COMPLETE_HUMAN_NODE = "intents.completeHumanNode"
    const val INTENTS_ADD_EDGE = "intents.addEdge"
    const val INTENTS_REMOVE_EDGE = "intents.removeEdge"
    const val INTENTS_LIST_EDGES = "intents.listEdges"
    const val INTENTS_PROPOSE = "intents.propose"
    const val INTENTS_LIST_PROPOSALS = "intents.listProposals"
    const val INTENTS_RESOLVE_PROPOSAL = "intents.resolveProposal"
    const val INTENTS_CONFIRM_IMPLEMENTATION = "intents.confirmImplementation"
    const val INTENTS_EXPORT_SNAPSHOT = "intents.exportSnapshotJson"
    const val INTENTS_IMPORT_SNAPSHOT = "intents.importAndReconcileSnapshot"

    const val EVENTS_SUBSCRIBE = "events.subscribe"

    const val RUNTIME_PING = "runtime.ping"
    const val RUNTIME_VERSION = "runtime.version"
    const val RUNTIME_PROFILE = "runtime.profile"
    const val RUNTIME_AVAILABILITY = "runtime.availability"

    /**
     * RFC-0115 / D56: the daemon's vault lock state. A machine-wide daemon starts with a passphrase vault locked; these let
     * the owner (a tray icon, the CLI) unlock it. Not in [REQUIRES_INTERACTIVE]: the connection token is already
     * owner-only, and a tray is not a TTY.
     */
    const val VAULT_STATUS = "vault.status"
    const val VAULT_UNLOCK = "vault.unlock"
    const val VAULT_LOCK = "vault.lock"

    /** Methods that require the connection to be [dev.aidos.api.socket.Hello.interactive] (RFC-0055). */
    val REQUIRES_INTERACTIVE = setOf(
        CAPABILITIES_GRANT, CAPABILITIES_REVOKE, CAPABILITIES_APPROVE, CAPABILITIES_DENY,
        CAPABILITIES_APPROVE_EFFECT, CAPABILITIES_DENY_EFFECT, CAPABILITIES_ANSWER_PROMPT,
    )
}

/**
 * First frame on every connection (RFC-0052 Authentication, RFC-0055 Security).
 *
 * [token] must match the daemon's minted connection token. [interactive] is true only when the
 * client is attached to a TTY a human can see — commands that grant or approve authority refuse
 * otherwise, so a script or a spawned child cannot approve on the user's behalf.
 */
data class Hello(
    val token: String,
    val interactive: Boolean,
    val minApiVersion: Int = Methods.CURRENT_API_VERSION,
)
