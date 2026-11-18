package dev.aidos.ui.workspace

import dev.aidos.api.CreateProjectRequest
import dev.aidos.api.ProjectLocation
import dev.aidos.api.ProjectResult
import dev.aidos.api.RuntimeClient

/** The two projects the workspace opens by default (RFC-0124 amendment, decision U5). */
enum class WorkspaceKind(val projectName: String, val slug: String, val projectType: String) {
    WORKSPACE("Workspace", "workspace", "generic"),
    LIFE("Life", "life", "personal"),
}

/**
 * RFC-0124 decision U5: no magic project ids. Finds the project by name or creates it, so the
 * intent graph lives in a real project database instead of the in-memory fallback.
 */
object WorkspaceBootstrap {

    /** Returns the real id of the project for [kind], creating it if none exists. */
    suspend fun ensureProject(client: RuntimeClient, kind: WorkspaceKind): String {
        // ProjectSummary carries no type field, so the name is the match key.
        client.projects.list().firstOrNull { it.name == kind.projectName }?.let { return it.id }
        val result = client.projects.create(
            CreateProjectRequest(
                name = kind.projectName,
                description = "",
                location = ProjectLocation.RuntimeManaged(kind.slug),
                initGit = false,
                projectType = kind.projectType,
            )
        )
        return when (result) {
            is ProjectResult.Success -> result.project.id
            is ProjectResult.Error ->
                throw IllegalStateException("Could not create the ${kind.projectName} project: ${result.message}")
        }
    }

    /**
     * Resolves a caller-supplied id. A real project id is returned as is; anything else
     * (legacy "default-workspace", "life", null) maps to [ensureProject].
     */
    suspend fun resolve(client: RuntimeClient, requestedId: String?, fallback: WorkspaceKind): String {
        if (!requestedId.isNullOrBlank() && client.projects.get(requestedId) != null) return requestedId
        val kind = if (requestedId == "life") WorkspaceKind.LIFE else fallback
        return ensureProject(client, kind)
    }
}
