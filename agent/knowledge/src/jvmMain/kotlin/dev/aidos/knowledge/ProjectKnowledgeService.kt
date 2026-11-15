package dev.aidos.knowledge

import dev.aidos.kernel.ContextItem
import dev.aidos.kernel.ContextItemKind
import dev.aidos.kernel.ContentNodeId
import dev.aidos.kernel.IndexStatus
import dev.aidos.kernel.KnowledgeContextProvider
import dev.aidos.kernel.KnowledgeQuery
import dev.aidos.kernel.ModelKind
import dev.aidos.kernel.ModelRuntime
import dev.aidos.kernel.ProjectId
import dev.aidos.kernel.TrustLevel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * RFC-0015 production adapter: one [KnowledgeIndex] per project, searched through the
 * [KnowledgeContextProvider] seam (RFC-0025) and indexed as a cancellable background job under a
 * resource budget (at most `maxConcurrentIndexing` projects index at once).
 *
 * Coverage is part of the answer: the first context item states how much of the repository has
 * vectors and whether the results came from the FTS-only degraded path, so the model and the UI
 * see the same caveat (D29). Search never waits for indexing.
 */
class ProjectKnowledgeService(
    private val rootFor: (projectId: String) -> String?,
    private val scope: CoroutineScope,
    /**
     * RFC-0015 item 4 / D28: the local embedding model comes from the model runtime (an installed
     * `EMBEDDING` model, loaded on first use). No network path. Without one, indexing cannot
     * embed and search stays text-match only.
     */
    private val modelRuntime: ModelRuntime? = null,
    private val indexFactory: ((repoPath: String) -> KnowledgeIndex)? = null,
    maxConcurrentIndexing: Int = 1,
    private val maxSnippetLines: Int = 40,
) : KnowledgeContextProvider {

    /** Shared by every project's index; its delegate resolves the installed embedding model lazily. */
    internal val embeddingProvider: LocalOnlyEmbeddingProvider = LocalOnlyEmbeddingProvider.placeholder()
    private val embedderLock = kotlinx.coroutines.sync.Mutex()
    private var embedder: ModelAdapterEmbeddingProvider? = null

    private suspend fun resolveEmbedder(): ModelAdapterEmbeddingProvider = embedderLock.withLock {
        embedder?.let { return@withLock it }
        val runtime = modelRuntime ?: throw IllegalStateException("No model runtime: cannot embed")
        val descriptor = runtime.installed().firstOrNull { it.kind == ModelKind.EMBEDDING }
            ?: throw IllegalStateException("No installed embedding model")
        val adapter = runtime.load(descriptor.id).getOrThrow()
        ModelAdapterEmbeddingProvider.fromModelAdapter(adapter, dimensions = embeddingProvider.dimensions)
            .also { embedder = it }
    }

    init {
        if (modelRuntime != null) {
            scope.launch { embeddingProvider.setEmbeddingDelegate { texts -> resolveEmbedder().embed(texts) } }
        }
    }

    private val indexes = ConcurrentHashMap<String, KnowledgeIndex>()
    private val jobs = ConcurrentHashMap<String, Job>()
    private val progress = ConcurrentHashMap<String, IndexingProgress>()
    private val budget = Semaphore(maxConcurrentIndexing)

    private fun indexFor(projectId: String): KnowledgeIndex? {
        indexes[projectId]?.let { return it }
        val root = rootFor(projectId) ?: return null
        if (!File(root, ".git").exists()) return null
        return indexes.computeIfAbsent(projectId) { indexFactory?.invoke(root) ?: buildKnowledgeIndex(root, embeddingProvider) }
    }

    /** Starts (or keeps running) the background indexing job for [projectId]. Returns false when it cannot index. */
    fun ensureIndexing(projectId: String, ref: String = "HEAD"): Boolean {
        val index = runCatching { indexFor(projectId) }.getOrNull() ?: return false
        val running = jobs[projectId]
        if (running != null && running.isActive) return true
        jobs[projectId] = scope.launch {
            budget.withPermit {
                // A missing embedding model throws; search stays FTS-only until one is loaded.
                runCatching { index.startIndexing(ref) { progress[projectId] = it } }
            }
        }
        return true
    }

    /** Cancels the indexing job; the next [ensureIndexing] resumes from the last checkpoint. */
    fun cancelIndexing(projectId: String) {
        jobs.remove(projectId)?.cancel()
    }

    fun isIndexing(projectId: String): Boolean = jobs[projectId]?.isActive == true

    fun lastProgress(projectId: String): IndexingProgress? = progress[projectId]

    suspend fun coverage(projectId: String): IndexCoverage? =
        runCatching { indexFor(projectId) }.getOrNull()?.let { runCatching { it.coverage() }.getOrNull() }

    override suspend fun query(
        projectId: ProjectId,
        query: KnowledgeQuery,
        tokenBudget: Int,
        excludeNodeIds: List<ContentNodeId>,
    ): List<ContextItem> {
        var used = 0
        // The coverage notice always survives; snippets are dropped lowest-ranked first once the budget is spent.
        return contextFor(projectId.value, query.userMessage, 8).filterIndexed { i, item ->
            if (i == 0) { used += item.tokenCount; true }
            else if (used + item.tokenCount <= tokenBudget) { used += item.tokenCount; true } else false
        }.let { if (it.size == 1) emptyList() else it }
    }

    override suspend fun indexStatus(projectId: ProjectId): IndexStatus {
        val c = coverage(projectId.value)
        return IndexStatus(
            indexedBlobs = c?.blobsEmbedded ?: 0L,
            pendingBlobs = ((c?.blobsKnown ?: 0L) - (c?.blobsEmbedded ?: 0L)).coerceAtLeast(0L),
            lastIndexedAt = null,
            degraded = c == null || !c.isComplete,
        )
    }

    suspend fun contextFor(projectId: String, query: String, topK: Int): List<ContextItem> {
        if (query.isBlank()) return emptyList()
        val index = runCatching { indexFor(projectId) }.getOrNull() ?: return emptyList()
        val root = rootFor(projectId) ?: return emptyList()
        val response = runCatching { index.search(query, topK) }.getOrNull() ?: return emptyList()
        if (response.matches.isEmpty()) return emptyList()

        val pct = (response.coverage.fraction * 100).toInt()
        val notice = "[knowledge index: $pct% of ${response.coverage.blobsKnown} blobs embedded" +
            (if (response.degraded) "; results are text-match only (no vectors yet)" else "") + "]"
        val items = mutableListOf(
            ContextItem(
                contentNodeId = null,
                kind = ContextItemKind.SEARCH_RESULT,
                content = notice,
                relevanceScore = 1.0f,
                tokenCount = (notice.length / 4).coerceAtLeast(1),
                trustLevel = TrustLevel.TRUSTED,
            )
        )
        for (m in response.matches) {
            val path = m.paths.firstOrNull() ?: continue
            val snippet = readSnippet(root, path, m.startLine, m.endLine) ?: continue
            val text = "$path:${m.startLine}-${m.endLine}\n$snippet"
            items += ContextItem(
                contentNodeId = null,
                kind = ContextItemKind.CODE_SNIPPET,
                content = text,
                relevanceScore = m.score.toFloat(),
                tokenCount = (text.length / 4).coerceAtLeast(1),
                trustLevel = TrustLevel.PROJECT,
            )
        }
        return if (items.size == 1) emptyList() else items
    }

    private fun readSnippet(root: String, path: String, start: Int, end: Int): String? {
        val rootFile = File(root).canonicalFile
        val file = File(rootFile, path).canonicalFile
        // Index paths are repo-relative; never read outside the project root.
        if (!file.path.startsWith(rootFile.path + File.separator) || !file.isFile) return null
        val lines = runCatching { file.readLines() }.getOrNull() ?: return null
        val from = (start - 1).coerceIn(0, lines.size)
        val to = end.coerceIn(from, minOf(lines.size, from + maxSnippetLines))
        return lines.subList(from, to).joinToString("\n").takeIf { it.isNotBlank() }
    }
}
