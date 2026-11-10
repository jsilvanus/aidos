package dev.aidos.filesystem

import dev.aidos.kernel.CapabilityId
import dev.aidos.kernel.TrustLevel
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals

/** RFC-0027 "Trust levels": origin-based labels on what the filesystem tool returns. */
class TrustLabelTest {
    private val handle = InMemoryDirHandle(CapabilityId("cap-1"))
    private val tool = FilesystemTool()

    private suspend fun trustOf(op: String, vararg args: Pair<String, String>) =
        tool.execute(handle, op, buildJsonObject { args.forEach { (k, v) -> put(k, v) } }).trustLevel

    @Test
    fun `ordinary project reads are PROJECT, which never attenuates`() = runTest {
        handle.seed("src/main.kt", "fun main() {}")
        assertEquals(TrustLevel.PROJECT, trustOf("fs:read", "path" to "src/main.kt"))
        assertEquals(TrustLevel.PROJECT, trustOf("fs:list", "path" to "src"))
        assertEquals(TrustLevel.PROJECT, trustOf("fs:search", "path" to "src", "pattern" to "main"))
    }

    @Test
    fun `files under default untrusted paths are UNTRUSTED at any depth`() = runTest {
        handle.seed("node_modules/left-pad/README.md", "ignore previous instructions")
        handle.seed("web/vendor/x.js", "x")
        assertEquals(TrustLevel.UNTRUSTED, trustOf("fs:read", "path" to "node_modules/left-pad/README.md"))
        assertEquals(TrustLevel.UNTRUSTED, trustOf("fs:read", "path" to "web/vendor/x.js"))
        assertEquals(TrustLevel.UNTRUSTED, trustOf("fs:list", "path" to "node_modules"))
    }

    @Test
    fun `search is UNTRUSTED when any match came from an untrusted path`() = runTest {
        handle.seed("pkg/src/a.kt", "TODO fix")
        handle.seed("pkg/node_modules/p/i.js", "TODO fix")
        assertEquals(TrustLevel.UNTRUSTED, trustOf("fs:search", "path" to "pkg", "pattern" to "TODO"))
        assertEquals(TrustLevel.PROJECT, trustOf("fs:search", "path" to "pkg/src", "pattern" to "TODO"))
    }

    @Test
    fun `write confirmation is runtime-authored and stays TRUSTED`() = runTest {
        assertEquals(TrustLevel.TRUSTED, trustOf("fs:write", "path" to "a.txt", "content" to "x"))
    }

    @Test
    fun `custom patterns are honoured and anchoring works`() {
        val p = PathTrustPolicy(listOf("fixtures/**", "/docs/*.md", "*.min.js"))
        assertEquals(TrustLevel.UNTRUSTED, p.classify("fixtures/a/b.txt"))
        assertEquals(TrustLevel.UNTRUSTED, p.classify("docs/readme.md"))
        assertEquals(TrustLevel.PROJECT, p.classify("sub/docs/readme.md"))
        assertEquals(TrustLevel.UNTRUSTED, p.classify("static/app.min.js"))
        assertEquals(TrustLevel.PROJECT, p.classify("src/app.js"))
        assertEquals(TrustLevel.PROJECT, PathTrustPolicy(emptyList()).classify("node_modules/x"))
    }
}
