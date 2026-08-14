package dev.aidos.kernel

/**
 * The Knowledge Engine's entire public surface (RFC-0015, RFC-0025).
 *
 * A query broker, not a monolith: narrow and stable here, with providers evolving behind it.
 * This is the cleanest parallel workstream in the system precisely because the surface is this
 * small.
 */
interface KnowledgeContextProvider {
    suspend fun query(
        projectId: ProjectId,
        query: KnowledgeQuery,
        tokenBudget: Int,
        excludeNodeIds: List<ContentNodeId> = emptyList(),
    ): List<ContextItem>

    suspend fun indexStatus(projectId: ProjectId): IndexStatus
}

data class KnowledgeQuery(
    val userMessage: String,
    val intentSummary: String? = null,
    val recentToolOperations: List<String> = emptyList(),
    val preferredKinds: List<String>? = null,
)

data class ContextItem(
    val contentNodeId: ContentNodeId?,
    val kind: ContextItemKind,
    val content: String,
    val relevanceScore: Float?,
    val tokenCount: Int,

    /** Carried into the Run's taint computation (RFC-0027). */
    val trustLevel: TrustLevel,

    /** Considered but not included — recorded for provenance (RFC-0025). */
    val dropped: Boolean = false,
)

enum class ContextItemKind { CODE_SNIPPET, DOCUMENT_SECTION, GIT_HISTORY, SEARCH_RESULT, TOOL_RESULT }

data class IndexStatus(
    val indexedBlobs: Long,
    val pendingBlobs: Long,
    val lastIndexedAt: String?,

    /** Queries never block on indexing; results from unindexed content are marked. */
    val degraded: Boolean,
)
