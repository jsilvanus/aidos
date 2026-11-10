package dev.aidos.filesystem

import dev.aidos.kernel.TrustLevel

/**
 * Origin-based trust for project files (RFC-0027 "Trust levels").
 *
 * A file in the project is [TrustLevel.PROJECT] — reading it is the normal case and never
 * attenuates a Run. A file under one of [untrustedPatterns] (`trust.untrusted_paths`, default
 * [DEFAULT_UNTRUSTED_PATHS] (`node_modules`, `vendor`, ... each followed by slash-star-star)) is [TrustLevel.UNTRUSTED]: vendored dependencies are the likeliest
 * indirect-injection vector in a coding agent.
 *
 * Pattern syntax: `*` matches within one path segment, `**` across segments, `?` one character.
 * A pattern matches at any depth (a node_modules pattern also matches `web/node_modules/x/y.js`)
 * unless it starts with `/`, which anchors it at the project root. Matching is on the lexical
 * path; a symlink inside the project that points into an untrusted directory is not followed.
 */
class PathTrustPolicy(untrustedPatterns: List<String> = DEFAULT_UNTRUSTED_PATHS) {

    private val regexes: List<Regex> = untrustedPatterns
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .map { globToRegex(it) }

    fun classify(path: String): TrustLevel {
        val normalized = path.replace('\\', '/').trimStart('/').let { if (it.isEmpty()) "." else it }
        return if (regexes.any { it.matches(normalized) }) TrustLevel.UNTRUSTED else TrustLevel.PROJECT
    }

    companion object {
        /** RFC-0027 "Vendored dependencies default to UNTRUSTED". */
        val DEFAULT_UNTRUSTED_PATHS: List<String> = listOf(
            "node_modules/**", "vendor/**", "third_party/**", ".venv/**", "target/**", "build/**",
        )

        internal fun globToRegex(glob: String): Regex {
            val anchored = glob.startsWith("/")
            var g = glob.trimStart('/')
            // A trailing slash-star-star also covers `dir` itself (listing the vendored directory).
            val coversDir = g.endsWith("/**")
            if (coversDir) g = g.removeSuffix("/**")
            val sb = StringBuilder()
            if (!anchored) sb.append("(?:.*/)?")
            var i = 0
            while (i < g.length) {
                val c = g[i]
                when {
                    c == '*' && i + 1 < g.length && g[i + 1] == '*' -> {
                        // a star-star-slash matches zero or more whole segments; other `**` matches anything.
                        if (i + 2 < g.length && g[i + 2] == '/') { sb.append("(?:.*/)?"); i += 3; continue }
                        sb.append(".*"); i += 2; continue
                    }
                    c == '*' -> sb.append("[^/]*")
                    c == '?' -> sb.append("[^/]")
                    c == '/' -> sb.append('/')
                    else -> sb.append(Regex.escape(c.toString()))
                }
                i++
            }
            if (coversDir) sb.append("(?:/.*)?")
            return Regex(sb.toString())
        }
    }
}
