package dev.aidos.prompt

import java.io.File
import java.security.MessageDigest

/**
 * Discovers and composes instruction files at the project root (RFC-0016, M15).
 *
 * MVP reads AGENTS.md then CLAUDE.md, root-only. Nested files are detected and reported
 * (not silently ignored) — the user must know if per-subdirectory instructions are not being
 * read. Identity is the hash of the ordered (filename, blobHash) pairs.
 */
object InstructionDiscovery {

    private val MVP_FILES = listOf("AGENTS.md", "CLAUDE.md")

    /**
     * Discover instruction files at [projectRoot].
     *
     * Returns an [InstructionSet] with [InstructionSet.adopted] = false if the hash differs
     * from [adoptedHash] (or adoptedHash is null). The adoption check is the caller's
     * responsibility; the runtime stores the adopted hash in `projects` or `settings`.
     *
     * [nestedCount] receives the count of per-subdirectory instruction files found but
     * not read — surfaced in the project status line (RFC-0016).
     */
    fun discover(
        projectRoot: File,
        adoptedHash: String? = null,
        nestedCountOut: ((Int) -> Unit)? = null,
    ): InstructionSet? {
        val sources = mutableListOf<InstructionSource>()
        for (name in MVP_FILES) {
            val file = File(projectRoot, name)
            if (file.exists() && file.isFile) {
                val content = file.readText()
                val blobHash = sha256(content)
                sources.add(InstructionSource(filename = name, blobHash = blobHash, content = content))
            }
        }
        if (sources.isEmpty()) return null

        // Count nested instruction files not being read.
        val nested = projectRoot.walkTopDown()
            .filter { it.isFile && it.parentFile != projectRoot && it.name in MVP_FILES }
            .count()
        nestedCountOut?.invoke(nested)

        val hash = computeHash(sources)
        val composedText = sources.joinToString("\n\n") { src ->
            "# Instructions from ${src.filename}\n\n${src.content}"
        }
        return InstructionSet(
            hash = hash,
            sources = sources,
            composedText = composedText,
            adopted = hash == adoptedHash,
        )
    }

    /** Hash of the ordered list of (filename, blobHash) pairs — identity for the set. */
    fun computeHash(sources: List<InstructionSource>): String {
        val input = sources.joinToString("|") { "${it.filename}:${it.blobHash}" }
        return sha256(input)
    }

    private fun sha256(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}
