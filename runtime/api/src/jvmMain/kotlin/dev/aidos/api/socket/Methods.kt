package dev.aidos.api.socket

/**
 * Method names for the socket wire protocol (RFC-0052 M10).
 *
 * Shared between [dev.aidos.api] socket codec, the daemon's server dispatcher, and the CLI's
 * socket client, so a typo shows up as a compile error on at least one side rather than a
 * silent method-not-found at runtime.
 */
object Methods {
    const val PROJECTS_CREATE = "projects.create"
    const val PROJECTS_LIST = "projects.list"

    const val SESSIONS_CREATE = "sessions.create"
    const val SESSIONS_LIST = "sessions.list"
    const val SESSIONS_SEND = "sessions.send"

    const val CAPABILITIES_GRANT = "capabilities.grant"
    const val CAPABILITIES_LIST_PENDING = "capabilities.listPending"
    const val CAPABILITIES_APPROVE = "capabilities.approve"

    /** RFC-0008 step 8d: resolves a Run parked on `RoutingDecision.RemotePendingApproval`. */
    const val CAPABILITIES_APPROVE_EFFECT = "capabilities.approveEffect"
    const val CAPABILITIES_DENY_EFFECT = "capabilities.denyEffect"

    /** RFC-0008 step 8d: answers a Run parked on `USER_PROMPT` (the model's `ask_user` call). */
    const val CAPABILITIES_ANSWER_PROMPT = "capabilities.answerPrompt"

    const val EVENTS_SUBSCRIBE = "events.subscribe"

    const val RUNTIME_PING = "runtime.ping"
    const val RUNTIME_VERSION = "runtime.version"

    /** Methods that require the connection to be [dev.aidos.api.socket.Hello.interactive] (RFC-0055). */
    val REQUIRES_INTERACTIVE = setOf(
        CAPABILITIES_GRANT, CAPABILITIES_APPROVE, CAPABILITIES_APPROVE_EFFECT, CAPABILITIES_DENY_EFFECT,
        CAPABILITIES_ANSWER_PROMPT,
    )
}

/**
 * First frame on every connection (RFC-0052 Authentication, RFC-0055 Security).
 *
 * [token] must match the daemon's minted connection token. [interactive] is true only when the
 * client is attached to a TTY a human can see — commands that grant or approve authority refuse
 * otherwise, so a script or a spawned child cannot approve on the user's behalf.
 */
data class Hello(val token: String, val interactive: Boolean)
