package dev.aidos.cli

import dev.aidos.portability.CliIo
import dev.aidos.storage.AidosStorage
import dev.aidos.storage.DesktopPaths
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InstructionsCliTest {
    private val now = "2026-11-15T00:00:00Z"

    private class Io : CliIo {
        val out = mutableListOf<String>()
        val err = mutableListOf<String>()
        override fun out(line: String) { out += line }
        override fun err(line: String) { err += line }
        override fun password(prompt: String): CharArray? = null
        override fun env(name: String): String? = null
    }

    @Test
    fun `status adopt and revoke round trip and recognition carries across projects`() {
        val a = Files.createTempDirectory("ia").toFile().also { it.resolve("AGENTS.md").writeText("Be kind.") }
        val b = Files.createTempDirectory("ib").toFile().also { it.resolve("AGENTS.md").writeText("Be kind.") }
        val home = Files.createTempDirectory("ih").toFile()
        for (r in listOf(a, b)) {
            val d = AidosStorage.openProject(DesktopPaths.stateDb(r.path), "t") { now }.driver
            d.execute(
                null,
                "INSERT INTO projects (id, name, root_path, project_type, created_at, updated_at, state_updated_at) " +
                    "VALUES ('${r.name}','${r.name}','/','generic','$now','$now','$now')",
                0,
            )
            d.close()
        }
        fun cli(io: Io) = InstructionsCli(
            openProjectDriver = { AidosStorage.openProject(DesktopPaths.stateDb(it), "t") { now }.driver },
            openUserDriver = { AidosStorage.openUser(DesktopPaths.userDb(home.path), "t") { now }.driver },
            io = io,
            nowIso = { now },
        )
        var io = Io()
        assertEquals(0, cli(io).run(listOf("status", a.path)))
        assertTrue(io.out.any { it.contains("adopted: no (unseen") })
        io = Io()
        assertEquals(0, cli(io).run(listOf("adopt", a.path)))
        io = Io()
        cli(io).run(listOf("status", a.path))
        assertTrue(io.out.any { it == "adopted: yes" })
        io = Io()
        cli(io).run(listOf("status", b.path))
        assertTrue(io.out.any { it.contains("accepted this exact text in another project") })
        io = Io()
        assertEquals(0, cli(io).run(listOf("revoke", a.path)))
        io = Io()
        cli(io).run(listOf("status", a.path))
        assertTrue(io.out.any { it.startsWith("adopted: no") })
        assertEquals(2, cli(Io()).run(listOf("bogus", a.path)))
    }
}
