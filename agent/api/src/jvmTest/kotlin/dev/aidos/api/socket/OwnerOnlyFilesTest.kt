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
}
