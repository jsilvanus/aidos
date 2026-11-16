package dev.aidos.api.socket

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class OwnerOnlyFilesTest {
    @Test
    fun `a secret file is owner-only on whatever file system this is`() {
        val dir = Files.createTempDirectory("owner-only")
        val f = dir.resolve("runtime.token")
        OwnerOnlyFiles.writeSecret(f, "s3cr3t")
        assertEquals("s3cr3t", Files.readString(f))
        assertTrue(OwnerOnlyFiles.isOwnerOnly(f), "only the owner may read the token")
    }

    @Test
    fun `an existing readable file is tightened and rewriting replaces it`() {
        val dir = Files.createTempDirectory("owner-only2")
        val f = dir.resolve("t")
        Files.writeString(f, "old")
        OwnerOnlyFiles.writeSecret(f, "new")
        assertEquals("new", Files.readString(f))
        assertTrue(OwnerOnlyFiles.isOwnerOnly(f))
    }

    @Test
    fun `restricting a missing file is an error, not a silent no-op`() {
        val dir = Files.createTempDirectory("owner-only3")
        assertFailsWith<java.io.IOException> { OwnerOnlyFiles.restrict(dir.resolve("absent")) }
    }

    @Test
    fun `rewriting is atomic and leaves no temp file behind`() {
        val dir = Files.createTempDirectory("owner-only4")
        val f = dir.resolve("engine.json")
        OwnerOnlyFiles.writeSecret(f, "first")
        OwnerOnlyFiles.writeSecret(f, "second")
        assertEquals("second", Files.readString(f))
        assertTrue(OwnerOnlyFiles.isOwnerOnly(f))
        Files.list(dir).use { assertEquals(listOf("engine.json"), it.map { p -> p.fileName.toString() }.toList()) }
    }

    @Test
    fun `a failed write leaves the previous secret in place`() {
        val dir = Files.createTempDirectory("owner-only5")
        val f = dir.resolve("t")
        OwnerOnlyFiles.writeSecret(f, "keep")
        // The target's directory is gone, so the temp file cannot even be created: the old file is untouched elsewhere.
        assertFailsWith<java.io.IOException> { OwnerOnlyFiles.writeSecret(dir.resolve("missing/t"), "x") }
        assertEquals("keep", Files.readString(f))
    }
}
