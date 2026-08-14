package dev.aidos.knowledge

import io.github.jsilvanus.gitsema.GitsemaSemanticIndex
import io.github.jsilvanus.gitsema.git.JGitRepository
import io.github.jsilvanus.gitsema.storage.FlatFileVectorStore
import io.github.jsilvanus.gitsema.storage.SqliteFtsStore
import io.github.jsilvanus.gitsema.storage.SqliteMetadataStore
import io.github.jsilvanus.gitsema.storage.createSqlDriver
import io.github.jsilvanus.gitsema.db.GitsemaDatabase
import java.io.File

/**
 * Constructs a [KnowledgeIndex] for a Git repository at [repoPath] (RFC-0015, M22).
 *
 * Storage layout (D21: index lives outside state.db):
 * ```
 * <repoPath>/.aidos/index/
 *   gitsema.db          — blobs, commits, FTS5 content (SQLite, gitsema-kotlin schema)
 *   vectors/            — flat-file quantized vector store (FlatFileVectorStore, jvmAndroidMain)
 * ```
 *
 * The [provider] is the Aidos-supplied [LocalOnlyEmbeddingProvider]. Until M21 lands an
 * actual model, pass [LocalOnlyEmbeddingProvider.placeholder()] — indexing will throw
 * (no model) and search degrades to FTS-only.
 *
 * [concurrency] controls how many embed calls run in parallel. The default (2) is
 * conservative for mobile, where the embedding model saturates a single core.
 */
fun buildKnowledgeIndex(
    repoPath: String,
    provider: LocalOnlyEmbeddingProvider = LocalOnlyEmbeddingProvider.placeholder(),
    concurrency: Int = 2,
): KnowledgeIndex {
    val indexDir = File(repoPath, ".aidos/index").also { it.mkdirs() }
    val dbPath = File(indexDir, "gitsema.db").absolutePath
    val vectorDir = File(indexDir, "vectors").also { it.mkdirs() }

    val driver = createSqlDriver(dbPath)
    val database = GitsemaDatabase(driver)
    val metadataStore = SqliteMetadataStore(database)
    val ftsStore = SqliteFtsStore(database)
    val vectorStore = FlatFileVectorStore(database, vectorDir)

    val repository = JGitRepository(File(repoPath))

    val semanticIndex = GitsemaSemanticIndex(
        repository = repository,
        provider = provider,
        metadataStore = metadataStore,
        vectorStore = vectorStore,
        ftsStore = ftsStore,
        concurrency = concurrency,
    )

    return GitsemaKnowledgeIndex(semanticIndex)
}
