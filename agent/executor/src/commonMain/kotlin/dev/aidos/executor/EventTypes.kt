package dev.aidos.executor

/**
 * Event bus type names for RFC-0004's MVP item 2 ("Event Types").
 *
 * `EventStore.publish`'s `type` is a plain `String`, not a closed enum — deliberately. RFC-0004's
 * own text: *"Readers must tolerate unknown versions of a known type by falling back to the event
 * header"* and the Future Work section's "Event Source Plugins" both treat the type vocabulary as
 * open-ended (new sources, including ones outside this codebase, add new types over time). A
 * closed `enum class` would fight that; these are named constants instead, giving call sites a
 * single source of truth for spelling without closing the string space.
 *
 * This is exactly RFC-0004's own MVP-scoped list — *"UserCommand, TimerFired,
 * FileModified/Created/Deleted, GitCommit, ToolCompleted, PermissionRequested/Granted/Denied,
 * SessionWoken/Sleeping, ArtifactCreated, Error"* — not the larger set the RFC's "Event Types"
 * design section also describes (UserInput/UserApproval, ScheduledTask, DirectoryCreated,
 * GitPush/Pull/BranchCreated/Merge, ToolFailed, MCP*, ModelQuery*, PermissionRevoked,
 * ArtifactUpdated/Published, SystemStarted/ShuttingDown, SessionCreated/Archived/Error). Those
 * remain real RFC-0004 vocabulary a caller may still use as a plain string; this object only
 * names the subset the MVP section itself commits to.
 *
 * [ERROR]'s exact spelling is genuinely ambiguous in the RFC: the MVP line says bare "Error", but
 * the fuller "Event Types" section never defines a type by that literal name — only
 * `SessionError` (Session category) and `ErrorOccurred` (System category). This constant uses the
 * MVP line's literal spelling rather than silently picking one of the two more-specific names;
 * whoever wires real error-event emission should settle which concept "Error" was meant to name
 * before depending on this constant's value, rather than assuming it already resolved the
 * ambiguity.
 */
object EventTypes {
    const val USER_COMMAND = "UserCommand"
    const val TIMER_FIRED = "TimerFired"
    const val FILE_MODIFIED = "FileModified"
    const val FILE_CREATED = "FileCreated"
    const val FILE_DELETED = "FileDeleted"
    const val GIT_COMMIT = "GitCommit"
    const val TOOL_COMPLETED = "ToolCompleted"
    const val PERMISSION_REQUESTED = "PermissionRequested"
    const val PERMISSION_GRANTED = "PermissionGranted"
    const val PERMISSION_DENIED = "PermissionDenied"
    const val SESSION_WOKEN = "SessionWoken"
    const val SESSION_SLEEPING = "SessionSleeping"
    const val ARTIFACT_CREATED = "ArtifactCreated"
    const val ERROR = "Error"
}
