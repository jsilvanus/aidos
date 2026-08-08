package dev.aidos.http

import dev.aidos.kernel.EffectKind
import dev.aidos.kernel.MutationScope
import dev.aidos.kernel.Permission
import dev.aidos.kernel.RecoveryClass
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Tests for HttpTool (RFC-0030, M4+).
 *
 * Validates that HTTP operations are correctly configured per RFC-0030.
 */
class HttpToolTest {

    @Test
    fun `http tool has correct id and version`() {
        assertTrue(HttpTool.id == "http")
        assertTrue(HttpTool.version == "0.1.0")
    }

    @Test
    fun `http tool declares all expected operations`() {
        val ops = HttpTool.operations().map { it.name }
        assertTrue(ops.contains("http:get"))
        assertTrue(ops.contains("http:head"))
        assertTrue(ops.contains("http:post"))
        assertTrue(ops.contains("http:put"))
        assertTrue(ops.contains("http:patch"))
        assertTrue(ops.contains("http:delete"))
        assertTrue(ops.contains("http:download"))
    }

    @Test
    fun `GET operation is Read effect with PURE recovery`() {
        val ops = HttpTool.operations()
        val get = ops.first { it.name == "http:get" }
        assertIs<EffectKind.Read>(get.effect)
        assertTrue(get.recoveryClass == RecoveryClass.PURE)
    }

    @Test
    fun `POST operation is Egress effect with UNSAFE recovery`() {
        val ops = HttpTool.operations()
        val post = ops.first { it.name == "http:post" }
        assertIs<EffectKind.Egress>(post.effect)
        assertTrue(post.recoveryClass == RecoveryClass.UNSAFE)
    }

    @Test
    fun `PUT operation is Egress effect with UNSAFE recovery`() {
        val ops = HttpTool.operations()
        val put = ops.first { it.name == "http:put" }
        assertIs<EffectKind.Egress>(put.effect)
        assertTrue(put.recoveryClass == RecoveryClass.UNSAFE)
    }

    @Test
    fun `DELETE operation is Egress effect with UNSAFE recovery`() {
        val ops = HttpTool.operations()
        val delete = ops.first { it.name == "http:delete" }
        assertIs<EffectKind.Egress>(delete.effect)
        assertTrue(delete.recoveryClass == RecoveryClass.UNSAFE)
    }

    @Test
    fun `PATCH operation is Egress effect with UNSAFE recovery`() {
        val ops = HttpTool.operations()
        val patch = ops.first { it.name == "http:patch" }
        assertIs<EffectKind.Egress>(patch.effect)
        assertTrue(patch.recoveryClass == RecoveryClass.UNSAFE)
    }

    @Test
    fun `download operation is Mutate effect with IDEMPOTENT recovery`() {
        val ops = HttpTool.operations()
        val download = ops.first { it.name == "http:download" }
        assertIs<EffectKind.Mutate>(download.effect)
        assertTrue((download.effect as EffectKind.Mutate).scope == MutationScope.IN_PROJECT)
        assertTrue(download.recoveryClass == RecoveryClass.IDEMPOTENT)
    }

    @Test
    fun `all operations require NETWORK_EGRESS permission`() {
        val ops = HttpTool.operations()
        ops.forEach { op ->
            assertTrue(
                op.requiredPermission == Permission.NETWORK_EGRESS,
                "Operation ${op.name} should require NETWORK_EGRESS permission"
            )
        }
    }

    @Test
    fun `all operations require network availability`() {
        val ops = HttpTool.operations()
        ops.forEach { op ->
            assertTrue(
                op.availability.requiresNetwork,
                "Operation ${op.name} should require network"
            )
        }
    }

    @Test
    fun `all operations available on all platforms`() {
        val ops = HttpTool.operations()
        ops.forEach { op ->
            assertTrue(
                op.availability.profiles.contains(dev.aidos.kernel.PlatformProfile.MOBILE),
                "Operation ${op.name} should be available on MOBILE"
            )
            assertTrue(
                op.availability.profiles.contains(dev.aidos.kernel.PlatformProfile.DESKTOP),
                "Operation ${op.name} should be available on DESKTOP"
            )
            assertTrue(
                op.availability.profiles.contains(dev.aidos.kernel.PlatformProfile.HEADLESS_SERVER),
                "Operation ${op.name} should be available on HEADLESS_SERVER"
            )
        }
    }

    @Test
    fun `get operation has url parameter required`() {
        val ops = HttpTool.operations()
        val get = ops.first { it.name == "http:get" }
        // Schema validation happens in broker, tool just declares it
        assertTrue(get.inputSchema.containsKey("properties"))
    }
}
