package dev.aidos.storage

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InstructionAdoptionStoreTest {
    private val now = "2026-11-15T00:00:00Z"

    @Test fun `adoption is per project and also recognised at user scope`() {
        val root = Files.createTempDirectory("adopt").toFile()
        val project = AidosStorage.openProject(DesktopPaths.stateDb(root.path), "t") { now }.driver
        val user = AidosStorage.openUser(root.resolve("user.db").path, "t") { now }.driver
        project.execute(null, "INSERT INTO projects (id, name, root_path, project_type, created_at, updated_at, state_updated_at) VALUES ('p1','p','/','generic','$now','$now','$now')", 0)
        val store = InstructionAdoptionStore(project, user)

        assertFalse(store.isAdopted("p1", "h1"))
        assertFalse(store.isKnown("h1"))
        store.adopt("p1", "h1", "[]", now, projectName = "p")
        assertTrue(store.isAdopted("p1", "h1"))
        assertTrue(store.isKnown("h1"))
        assertFalse(store.isAdopted("p1", "h2"))

        store.adopt("p1", "h1", "[]", now) // re-adoption is idempotent
        store.revoke("p1", "h1")
        assertFalse(store.isAdopted("p1", "h1"))
        assertTrue(store.isKnown("h1")) // recognition outlives a project's revocation
    }
}
