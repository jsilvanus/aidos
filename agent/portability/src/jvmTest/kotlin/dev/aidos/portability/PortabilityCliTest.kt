package dev.aidos.portability

import dev.aidos.storage.AidosStorage
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** RFC-0041 `aidos export` / `aidos import` command surface. */
class PortabilityCliTest {
    private val tmp = Files.createTempDirectory("aidos-cli").toFile()
    @AfterTest fun cleanup() { tmp.deleteRecursively() }

    private class Io(val pw: String? = null, val envPw: String? = null) : CliIo {
        val out = mutableListOf<String>(); val err = mutableListOf<String>()
        override fun out(line: String) { out += line }
        override fun err(line: String) { err += line }
        override fun password(prompt: String) = pw?.toCharArray()
        override fun env(name: String) = if (name == "AIDOS_ARCHIVE_PASSWORD") envPw else null
    }

    private val reg = object : ProjectRegistryPort {
        val m = mutableMapOf<String, String>()
        override fun isRegistered(projectId: String) = projectId in m
        override fun register(projectId: String, path: String, nowIso: String) { m[projectId] = path }
    }

    private fun project(): File {
        val root = File(tmp, "demo").apply { mkdirs() }
        File(root, ".aidos").mkdirs()
        val db = File(root, ".aidos/state.db")
        AidosStorage.openProject(db.absolutePath, "t") { "2026-11-10T00:00:00Z" }.driver.close()
        Sqlite.use(db) { c -> c.createStatement().use { it.execute("INSERT INTO projects (id,name,root_path,created_at,updated_at,state_updated_at) VALUES ('p9','demo','x','t','t','t')") } }
        File(root, "notes.md").writeText("hi")
        return root
    }

    private fun run(vararg a: String, io: Io = Io()) = PortabilityCli.run(a.toList(), reg, io, cwd = tmp, exportedBy = "juha", kdfIterations = 1000) to io

    @Test fun `export then import via the command line`() {
        project()
        val (c1, io1) = run("export", "demo", "--output", "d.aidos-project")
        assertEquals(0, c1, io1.err.toString()); assertTrue(File(tmp, "d.aidos-project").isFile)
        val (c2, io2) = run("import", "d.aidos-project", "--into", "restored")
        assertEquals(0, c2, io2.err.toString())
        assertEquals("hi", File(tmp, "restored/notes.md").readText())
        assertEquals(File(tmp, "restored").absolutePath, File(reg.m.getValue("p9")).absolutePath)
    }

    @Test fun `second import is refused with exit 1 and a stable code, rename succeeds`() {
        project(); run("export", "demo", "--output", "d.aidos-project")
        run("import", "d.aidos-project", "--into", "r")
        val (c, io) = run("import", "d.aidos-project", "--into", "r")
        assertEquals(1, c); assertTrue(io.err.single().contains("import.conflict"))
        assertEquals(0, run("import", "d.aidos-project", "--into", "r", "--rename").first)
    }

    @Test fun `encrypted and signed round trip with env password and keygen key`() {
        project()
        assertEquals(0, run("keygen", "me").first)
        val fp = File(tmp, "me.pub").readText().trim().let { ArchiveCrypto.fingerprint(ArchiveCrypto.decodePublic(it)) }
        val (c1, io1) = run("export", "demo", "--output", "s.aidos-project", "--encrypt", "--sign", "me.key", io = Io(envPw = "pw"))
        assertEquals(0, c1, io1.err.toString())
        val (c2, io2) = run("import", "s.aidos-project", "--into", "s", "--require-signature", "--trust", fp, io = Io(envPw = "pw"))
        assertEquals(0, c2, io2.err.toString()); assertTrue(io2.out.any { it.contains(fp) })
        assertEquals(1, run("import", "s.aidos-project", "--into", "s2", io = Io(envPw = "wrong")).first)
    }

    @Test fun `keygen refuses to overwrite and bad usage exits 2`() {
        run("keygen", "k"); assertEquals(1, run("keygen", "k").first)
        assertEquals(2, run("export").first); assertEquals(2, run("import", "x", "--bogus").first)
        assertEquals(2, run("import", "x", "--rename", "--replace").first)
    }

    @Test fun `exporting something that is not a project fails cleanly`() {
        File(tmp, "plain").mkdirs()
        val (c, io) = run("export", "plain"); assertEquals(1, c); assertTrue(io.err.single().contains("export.not_a_project"))
    }
}
