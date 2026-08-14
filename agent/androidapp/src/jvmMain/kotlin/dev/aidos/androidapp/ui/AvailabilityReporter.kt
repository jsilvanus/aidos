package dev.aidos.androidapp.ui

import dev.aidos.api.RuntimeClient

/**
 * Availability reporting (M29, RFC-0049).
 *
 * Degraded and unavailable tools are shown at project open.
 * They are NEVER discovered mid-run — they are never offered and then failed.
 *
 * This class computes the availability report on project open, before any run is started.
 * The report is shown in the UI as part of the project open screen.
 */

/** Tool availability state at project open time. */
sealed interface ToolAvailabilityState {
    /** Tool is fully available. */
    data object Available : ToolAvailabilityState
    /** Tool is available but degraded (e.g., local-only, no remote). */
    data class Degraded(val reason: String) : ToolAvailabilityState
    /** Tool is not available. Will not be offered in runs. */
    data class Unavailable(val reason: String) : ToolAvailabilityState
}

data class ToolAvailabilityEntry(
    val toolName: String,
    val state: ToolAvailabilityState,
)

data class ProjectAvailabilityReport(
    val tools: List<ToolAvailabilityEntry>,
    val networkAvailable: Boolean,
    val localModelAvailable: Boolean,
    val remoteModelAllowed: Boolean,
) {
    /** True if all tools are fully available. */
    val allAvailable: Boolean
        get() = tools.all { it.state is ToolAvailabilityState.Available }

    /** Tools that need attention (degraded or unavailable). */
    val needsAttention: List<ToolAvailabilityEntry>
        get() = tools.filter { it.state !is ToolAvailabilityState.Available }
}

/**
 * Computes availability report from known facts at project open.
 * This is a pure function — no network calls, no side effects.
 */
object AvailabilityReporter {

    fun report(
        registeredTools: List<String>,
        networkAvailable: Boolean,
        localModelAvailable: Boolean,
        remoteModelAllowed: Boolean,
    ): ProjectAvailabilityReport {
        val entries = registeredTools.map { tool ->
            val state = when {
                tool.startsWith("mcp_http") && !networkAvailable ->
                    ToolAvailabilityState.Unavailable("Network not available")
                tool.startsWith("mcp_stdio") && !isShellAvailable() ->
                    ToolAvailabilityState.Unavailable("Shell execution not permitted")
                tool == "model_query" && !localModelAvailable && !remoteModelAllowed ->
                    ToolAvailabilityState.Unavailable("No model available (offline, no local model loaded)")
                tool == "model_query" && !localModelAvailable && remoteModelAllowed ->
                    ToolAvailabilityState.Degraded("Local model not loaded; will use remote (requires network)")
                else -> ToolAvailabilityState.Available
            }
            ToolAvailabilityEntry(tool, state)
        }

        return ProjectAvailabilityReport(
            tools = entries,
            networkAvailable = networkAvailable,
            localModelAvailable = localModelAvailable,
            remoteModelAllowed = remoteModelAllowed,
        )
    }

    // On Android, shell availability depends on capability grants (RFC-0003).
    // For the test stub, we assume it is always available on JVM.
    private fun isShellAvailable() = true
}
