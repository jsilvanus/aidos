package dev.aidos.testing

import dev.aidos.kernel.AvailabilityTier
import dev.aidos.kernel.PlatformProfile
import dev.aidos.kernel.ToolAvailability
import java.io.File
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/** RFC-0038: the shared fakes, the fixtures tree, and the MOBILE/DESKTOP profile matrix. */
class TestingKitTest {

    private fun fixture(rel: String): File =
        listOf("../../tests/fixtures/$rel", "tests/fixtures/$rel").map(::File).first { it.exists() }

    @Test fun `test clock only moves when told to`() {
        val clock = TestClock()
        val t0 = clock.now()
        assertEquals(t0, clock.now())
        clock.advanceBy(5.minutes)
        assertEquals(5L * 60, (clock.now() - t0).inWholeSeconds)
    }

    @Test fun `seeded ids are reproducible and unique`() {
        val a = SeededIdGenerator(); val b = SeededIdGenerator()
        val xs = (1..50).map { a.next() }
        assertEquals(xs, (1..50).map { b.next() })
        assertEquals(50, xs.toSet().size)
    }

    @Test fun `injectable filesystem raises each fault once`() {
        InjectableFilesystem().use { fs ->
            fs.injectNextWriteFault(InjectableFilesystem.Fault.ENOSPC)
            assertFailsWith<IOException> { fs.writeText("a.txt", "hello") }
            fs.writeText("a.txt", "hello")
            assertEquals("hello", fs.readText("a.txt"))
            fs.injectNextWriteFault(InjectableFilesystem.Fault.PARTIAL_WRITE)
            assertFailsWith<IOException> { fs.writeText("b.txt", "abcdef") }
            assertEquals("abc", fs.readText("b.txt"))
        }
    }

    @Test fun `every path escape vector in the fixtures tree is refused by the filesystem`() {
        val vectors = fixture("paths/escape-vectors.txt").readLines().filter { it.isNotBlank() }
        assertTrue(vectors.isNotEmpty())
        InjectableFilesystem().use { fs ->
            for (v in vectors) {
                // Absolute or traversing vectors must never write outside the root.
                val outcome = runCatching { fs.writeText(v, "x") }
                if (outcome.isSuccess) {
                    // Accepted only if it stayed inside the root (e.g. a literal odd filename).
                    val resolved = fs.root.resolve(v).normalize()
                    assertTrue(resolved.startsWith(fs.root.normalize()), "escaped via $v")
                }
            }
        }
    }

    @Test fun `fixtures tree is complete`() {
        listOf(
            "golden/prompt-basic.txt", "injection/prompt-injection-corpus.txt",
            "paths/escape-vectors.txt", "scenarios/basic-run.json", "schemas/v1.sql",
        ).forEach { assertTrue(fixture(it).length() > 0, "empty fixture $it") }
    }

    @Test fun `profile matrix gates tools by profile network and sidecar`() {
        val shell = ToolAvailability(setOf(PlatformProfile.DESKTOP), AvailabilityTier.PLATFORM)
        val fs = ToolAvailability(PlatformProfile.values().toSet(), AvailabilityTier.UNIVERSAL)
        val remote = ToolAvailability(setOf(PlatformProfile.MOBILE, PlatformProfile.DESKTOP), AvailabilityTier.NETWORKED, requiresNetwork = true)
        val sidecar = ToolAvailability(setOf(PlatformProfile.MOBILE), AvailabilityTier.SIDECAR)

        for (profile in listOf(PlatformProfile.MOBILE, PlatformProfile.DESKTOP)) {
            assertTrue(fs.availableOn(profile, networkAvailable = false))
            assertTrue(remote.availableOn(profile, networkAvailable = true))
            assertFalse(remote.availableOn(profile, networkAvailable = false))
        }
        assertFalse(shell.availableOn(PlatformProfile.MOBILE, true))
        assertTrue(shell.availableOn(PlatformProfile.DESKTOP, false))
        assertFalse(sidecar.availableOn(PlatformProfile.MOBILE, true, sidecarAvailable = false))
        assertTrue(sidecar.availableOn(PlatformProfile.MOBILE, true, sidecarAvailable = true))
        assertFalse(sidecar.availableOn(PlatformProfile.DESKTOP, true, sidecarAvailable = true))
    }
}
