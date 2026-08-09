package dev.aidos.androidapp.content

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.aidos.kernel.ActorKind
import dev.aidos.kernel.ContentKind
import dev.aidos.kernel.ContentNode
import dev.aidos.kernel.ContentNodeId
import dev.aidos.kernel.ContentNodeState
import dev.aidos.kernel.EgressEligibility
import dev.aidos.kernel.MutabilityPolicy
import dev.aidos.kernel.ProjectId
import dev.aidos.kernel.ProvenanceEdge
import dev.aidos.kernel.ProvenanceEdgeKind
import dev.aidos.kernel.SensitivityLevel
import dev.aidos.kernel.StorageLocation
import dev.aidos.kernel.TrustLevel
import dev.aidos.storage.AidosStorage
import dev.aidos.storage.DesktopPaths
import java.nio.file.Files
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * RFC-0024 MVP scope: content_nodes CRUD, provenance_edges limited to DERIVED_FROM/VERSION_OF,
 * acyclicity enforced on insert. See SqliteContentNodeStore's class doc for what's out of scope.
 */
class SqliteContentNodeStoreTest {

    private fun openDriver(): JdbcSqliteDriver {
        val root = Files.createTempDirectory("content-store-test").toFile()
        val db = AidosStorage.openProject(DesktopPaths.stateDb(root.path), "test-1.0") { "2026-08-09T00:00:00Z" }
        val driver = db.driver as JdbcSqliteDriver
        driver.execute(
            identifier = null,
            sql = "INSERT INTO projects (id, name, root_path, created_at, updated_at, state_updated_at) VALUES ('proj-1', 'proj-1', '/projects/proj-1', ?, ?, ?)",
            parameters = 3,
        ) {
            bindString(0, "2026-08-09T00:00:00Z")
            bindString(1, "2026-08-09T00:00:00Z")
            bindString(2, "2026-08-09T00:00:00Z")
        }
        return driver
    }

    private fun node(
        id: String = "content-1",
        kind: ContentKind = ContentKind.NOTE,
        name: String = "notes.md",
    ) = ContentNode(
        id = ContentNodeId(id),
        projectId = ProjectId("proj-1"),
        kind = kind,
        name = name,
        description = "A description",
        mutabilityPolicy = MutabilityPolicy.MUTABLE_LATEST,
        sensitivityLevel = SensitivityLevel.INTERNAL,
        egressEligibility = EgressEligibility.REQUIRES_APPROVAL,
        trustLevel = TrustLevel.TRUSTED,
        storageLocation = StorageLocation.FilesystemPath(relativePath = "notes.md", gitTracked = true),
        contentHash = "deadbeef",
        contentType = "text/markdown",
        sizeBytes = 42L,
        createdAt = Instant.parse("2026-08-09T00:00:00Z"),
        createdByKind = ActorKind.USER,
        createdById = "user-1",
        updatedAt = Instant.parse("2026-08-09T00:00:00Z"),
        updatedByKind = null,
        updatedById = null,
        contentVersion = 1,
        rowVersion = 1,
        state = ContentNodeState.ACTIVE,
        tags = listOf("journal", "personal"),
    )

    @Test
    fun `created node round-trips through get`() {
        val store = SqliteContentNodeStore(openDriver())
        store.create(node())

        val fetched = store.get(ContentNodeId("content-1"))
        assertEquals(node(), fetched)
    }

    @Test
    fun `unknown id returns null`() {
        val store = SqliteContentNodeStore(openDriver())
        assertNull(store.get(ContentNodeId("no-such-id")))
    }

    @Test
    fun `listByProject and listByKind filter correctly`() {
        val store = SqliteContentNodeStore(openDriver())
        store.create(node(id = "a", kind = ContentKind.NOTE))
        store.create(node(id = "b", kind = ContentKind.CODE_FILE, name = "main.kt"))

        assertEquals(setOf("a", "b"), store.listByProject(ProjectId("proj-1")).map { it.id.value }.toSet())
        assertEquals(listOf("a"), store.listByKind(ProjectId("proj-1"), ContentKind.NOTE).map { it.id.value })
        assertEquals(listOf("b"), store.listByKind(ProjectId("proj-1"), ContentKind.CODE_FILE).map { it.id.value })
    }

    @Test
    fun `storage location and tags round-trip through JSON columns`() {
        val store = SqliteContentNodeStore(openDriver())
        val gitObject = node(id = "git-1").copy(
            storageLocation = StorageLocation.GitObject("commit1", "blob1", "src/Main.kt"),
            tags = listOf("a", "b", "c"),
        )
        store.create(gitObject)

        val fetched = store.get(ContentNodeId("git-1"))!!
        assertEquals(StorageLocation.GitObject("commit1", "blob1", "src/Main.kt"), fetched.storageLocation)
        assertEquals(listOf("a", "b", "c"), fetched.tags)
    }

    @Test
    fun `DERIVED_FROM edge is accepted and stored`() {
        val store = SqliteContentNodeStore(openDriver())
        store.create(node(id = "a"))
        store.create(node(id = "b"))

        val result = store.addProvenanceEdge(
            ProvenanceEdge(
                id = "edge-1",
                fromNodeId = ContentNodeId("a"),
                toNodeId = ContentNodeId("b"),
                edgeKind = ProvenanceEdgeKind.DERIVED_FROM,
                createdAt = Instant.parse("2026-08-09T00:00:00Z"),
                createdByRunId = null,
            )
        )
        assertTrue(result.isSuccess)
    }

    @Test
    fun `post-MVP edge kinds are rejected`() {
        val store = SqliteContentNodeStore(openDriver())
        store.create(node(id = "a"))
        store.create(node(id = "b"))

        val result = store.addProvenanceEdge(
            ProvenanceEdge(
                id = "edge-1",
                fromNodeId = ContentNodeId("a"),
                toNodeId = ContentNodeId("b"),
                edgeKind = ProvenanceEdgeKind.REFERENCED_BY,
                createdAt = Instant.parse("2026-08-09T00:00:00Z"),
                createdByRunId = null,
            )
        )
        assertTrue(result.isFailure)
    }

    @Test
    fun `an edge that would close a cycle is rejected`() {
        val store = SqliteContentNodeStore(openDriver())
        store.create(node(id = "a"))
        store.create(node(id = "b"))

        // a -DERIVED_FROM-> b (b was derived from a)
        assertTrue(
            store.addProvenanceEdge(
                ProvenanceEdge("edge-1", ContentNodeId("a"), ContentNodeId("b"), ProvenanceEdgeKind.DERIVED_FROM, Instant.parse("2026-08-09T00:00:00Z"), null)
            ).isSuccess
        )

        // b -VERSION_OF-> a would close the loop (a already reaches b)
        val result = store.addProvenanceEdge(
            ProvenanceEdge("edge-2", ContentNodeId("b"), ContentNodeId("a"), ProvenanceEdgeKind.VERSION_OF, Instant.parse("2026-08-09T00:00:01Z"), null)
        )
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("content.cycle_rejected"))
    }

    @Test
    fun `a longer chain still rejects a closing edge`() {
        val store = SqliteContentNodeStore(openDriver())
        store.create(node(id = "a"))
        store.create(node(id = "b"))
        store.create(node(id = "c"))

        // a -> b -> c
        store.addProvenanceEdge(ProvenanceEdge("e1", ContentNodeId("a"), ContentNodeId("b"), ProvenanceEdgeKind.DERIVED_FROM, Instant.parse("2026-08-09T00:00:00Z"), null))
        store.addProvenanceEdge(ProvenanceEdge("e2", ContentNodeId("b"), ContentNodeId("c"), ProvenanceEdgeKind.DERIVED_FROM, Instant.parse("2026-08-09T00:00:01Z"), null))

        // c -> a would close a -> b -> c -> a
        val result = store.addProvenanceEdge(
            ProvenanceEdge("e3", ContentNodeId("c"), ContentNodeId("a"), ProvenanceEdgeKind.DERIVED_FROM, Instant.parse("2026-08-09T00:00:02Z"), null)
        )
        assertTrue(result.isFailure)
    }
}
