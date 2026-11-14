package dev.aidos.capability

import dev.aidos.identity.UuidV7Generator
import dev.aidos.kernel.CapabilityId
import dev.aidos.kernel.RelPath
import dev.aidos.storage.AidosStorage
import dev.aidos.storage.DesktopPaths
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.Path
import java.util.stream.Collectors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class SqliteDirHandleTest {

    private fun newHandle(): Pair<SqliteDirHandle, Path> {
        val dbDir = Files.createTempDirectory("dirhandle-db").toFile()
        val driver = AidosStorage.openProject(DesktopPaths.stateDb(dbDir.path), "test-1.0") {
            "2026-08-05T00:00:00Z"
        }.driver
        val mgr = SqliteCapabilityManager(driver, UuidV7Generator()) { "2026-08-05T00:00:00Z" }
        val projectRoot = Files.createTempDirectory("dirhandle-root")
        return SqliteDirHandle(CapabilityId("cap-dir-1"), projectRoot.toString(), mgr) to projectRoot
    }

    private fun tryCreateSymbolicLink(link: Path, target: Path): Boolean =
        try {
            Files.createSymbolicLink(link, target)
            true
        } catch (_: UnsupportedOperationException) {
            false
        } catch (_: FileSystemException) {
            false
        }

    @Test
    fun `write creates parent directories atomically and leaves no temp sibling files`(): Unit = runBlocking {
        val (handle, root) = newHandle()
        val rel = RelPath.of("nested/dir/hello.txt").getOrThrow()

        assertTrue(handle.write(rel, "v1".encodeToByteArray()).isSuccess)
        assertEquals("v1", handle.read(rel).getOrThrow().decodeToString())

        assertTrue(handle.write(rel, "v2".encodeToByteArray()).isSuccess)
        assertEquals("v2", handle.read(rel).getOrThrow().decodeToString())

        val siblings = Files.list(root.resolve("nested/dir")).use { stream ->
            stream.map { it.fileName.toString() }.collect(Collectors.toList())
        }
        assertEquals(listOf("hello.txt"), siblings)
    }

    @Test
    fun `write fails cleanly without temp leftovers when target is an existing directory`(): Unit = runBlocking {
        val (handle, root) = newHandle()
        val dirRel = RelPath.of("existing-dir").getOrThrow()
        assertTrue(handle.mkdir(dirRel).isSuccess)

        val writeRes = handle.write(dirRel, "data".encodeToByteArray())
        assertTrue(writeRes.isFailure)

        val rootEntries = Files.list(root).use { stream ->
            stream.map { it.fileName.toString() }.collect(Collectors.toList())
        }
        assertEquals(listOf("existing-dir"), rootEntries)
    }

    @Test
    fun `write rejects path through external symlink before creating subdirectories`(): Unit = runBlocking {
        val (handle, root) = newHandle()
        val outside = Files.createTempDirectory("dirhandle-outside")
        val link = root.resolve("escape")
        if (!tryCreateSymbolicLink(link, outside)) return@runBlocking

        val res = handle.write(RelPath.of("escape/sub/pwn.txt").getOrThrow(), "bad".encodeToByteArray())
        assertTrue(res.isFailure)
        assertFalse(Files.exists(outside.resolve("sub")))
    }

    @Test
    fun `move to same normalized path is a safe no-op even with overwrite true`(): Unit = runBlocking {
        val (handle, _) = newHandle()
        val fileRel = RelPath.of("same.txt").getOrThrow()
        assertTrue(handle.write(fileRel, "keep me".encodeToByteArray()).isSuccess)

        val sameOverwriteTrue = handle.move(
            RelPath.of("same.txt").getOrThrow(),
            RelPath.of("./same.txt").getOrThrow(),
            overwrite = true,
        )
        assertTrue(sameOverwriteTrue.isSuccess, "move to same normalized path must succeed: $sameOverwriteTrue")
        assertTrue(handle.exists(fileRel))
        assertEquals("keep me", handle.read(fileRel).getOrThrow().decodeToString())

        val sameOverwriteFalse = handle.move(
            RelPath.of("same.txt").getOrThrow(),
            RelPath.of("same.txt").getOrThrow(),
            overwrite = false,
        )
        assertTrue(sameOverwriteFalse.isSuccess)
        assertEquals("keep me", handle.read(fileRel).getOrThrow().decodeToString())

        val nested = RelPath.of("sub/item.txt").getOrThrow()
        assertTrue(handle.write(nested, "nested".encodeToByteArray()).isSuccess)
        assertTrue(
            handle.move(
                RelPath.of("sub/item.txt").getOrThrow(),
                RelPath.of("sub/./item.txt").getOrThrow(),
                overwrite = true,
            ).isSuccess
        )
        assertEquals("nested", handle.read(nested).getOrThrow().decodeToString())

        val missing = handle.move(
            RelPath.of("missing.txt").getOrThrow(),
            RelPath.of("./missing.txt").getOrThrow(),
            overwrite = true,
        )
        assertTrue(missing.isFailure)
    }

    @Test
    fun `move transfers file and respects overwrite flag`(): Unit = runBlocking {
        val (handle, _) = newHandle()
        val a = RelPath.of("a.txt").getOrThrow()
        val b = RelPath.of("dir/b.txt").getOrThrow()
        val c = RelPath.of("dir/c.txt").getOrThrow()

        assertTrue(handle.write(a, "alpha".encodeToByteArray()).isSuccess)
        assertTrue(handle.move(a, b, overwrite = false).isSuccess)
        assertFalse(handle.exists(a))
        assertEquals("alpha", handle.read(b).getOrThrow().decodeToString())

        assertTrue(handle.write(c, "gamma".encodeToByteArray()).isSuccess)
        assertTrue(handle.move(b, c, overwrite = false).isFailure)
        assertEquals("alpha", handle.read(b).getOrThrow().decodeToString())
        assertEquals("gamma", handle.read(c).getOrThrow().decodeToString())

        assertTrue(handle.move(b, c, overwrite = true).isSuccess)
        assertFalse(handle.exists(b))
        assertEquals("alpha", handle.read(c).getOrThrow().decodeToString())
    }

    @Test
    fun `delete removes files and directories and refuses non-empty non-recursive`(): Unit = runBlocking {
        val (handle, _) = newHandle()
        val f = RelPath.of("dir/file.txt").getOrThrow()
        val dir = RelPath.of("dir").getOrThrow()

        assertTrue(handle.write(f, "hello".encodeToByteArray()).isSuccess)
        assertTrue(handle.delete(dir, recursive = false).isFailure)
        assertTrue(handle.exists(f))

        val delRes = handle.delete(dir, recursive = true)
        assertTrue(delRes.isSuccess)
        assertEquals(2, delRes.getOrThrow())
        assertFalse(handle.exists(dir))
    }

    @Test
    fun `deleteRecursively does not follow symlinked directories`(): Unit = runBlocking {
        val (handle, root) = newHandle()
        val outsideDir = Files.createTempDirectory("dirhandle-outside-target")
        val outsideSecret = outsideDir.resolve("secret.txt")
        Files.writeString(outsideSecret, "do not touch")

        val keepFile = RelPath.of("keep/important.txt").getOrThrow()
        assertTrue(handle.write(keepFile, "stay safe".encodeToByteArray()).isSuccess)

        val subFile = RelPath.of("sub/bye.txt").getOrThrow()
        assertTrue(handle.write(subFile, "bye".encodeToByteArray()).isSuccess)

        val subDir = root.resolve("sub")
        val extLink = subDir.resolve("ext_link")
        val intLink = subDir.resolve("int_link")
        if (!tryCreateSymbolicLink(extLink, outsideDir)) return@runBlocking
        if (!tryCreateSymbolicLink(intLink, root.resolve("keep"))) return@runBlocking

        val delRes = handle.delete(RelPath.of("sub").getOrThrow(), recursive = true)
        assertTrue(delRes.isSuccess, "recursive delete should succeed: $delRes")
        assertFalse(Files.exists(subDir))
        assertTrue(Files.exists(outsideSecret), "external symlink target must remain untouched")
        assertEquals("do not touch", Files.readString(outsideSecret))
        assertTrue(handle.exists(keepFile), "internal symlink target must remain untouched")
        assertEquals("stay safe", handle.read(keepFile).getOrThrow().decodeToString())

        // Also test direct delete of a symlink pointing to an internal directory.
        val topLink = root.resolve("top_link")
        assertTrue(tryCreateSymbolicLink(topLink, root.resolve("keep")))
        val topDel = handle.delete(RelPath.of("top_link").getOrThrow(), recursive = true)
        assertTrue(topDel.isSuccess)
        assertEquals(1, topDel.getOrThrow())
        assertFalse(Files.exists(topLink))
        assertTrue(handle.exists(keepFile))
    }
}
