package dev.aidos.executor

/**
 * Matches event topics against subscription patterns (RFC-0004, "Topics and Filtering").
 *
 * A pattern segment is delimited by a forward slash, per the RFC's own examples: a single
 * trailing wildcard after `src` + slash matches files directly in `src` but not deeper; a
 * doubled wildcard after `project` + slash matches any depth below it. A lone wildcard used as
 * the entire pattern is a special case meaning "all events" (the RFC's own gloss), so it is not
 * run through the segment-bounded translation below.
 */
object TopicMatcher {

    /** Whether [topic] is matched by [pattern]. A null [topic] matches only the literal `*` pattern. */
    fun matches(pattern: String, topic: String?): Boolean {
        if (pattern == "*") return true
        if (topic == null) return false
        return patternToRegex(pattern).matches(topic)
    }

    /** Whether [topic] is matched by any pattern in [patterns]. An empty pattern list matches everything. */
    fun matchesAny(patterns: List<String>, topic: String?): Boolean =
        patterns.isEmpty() || patterns.any { matches(it, topic) }

    private fun patternToRegex(pattern: String): Regex {
        val sb = StringBuilder()
        var i = 0
        while (i < pattern.length) {
            val c = pattern[i]
            when {
                c == '*' && pattern.startsWith("**", i) -> {
                    sb.append(".*")
                    i += 2
                }
                c == '*' -> {
                    sb.append("[^/]*")
                    i += 1
                }
                else -> {
                    sb.append(Regex.escape(c.toString()))
                    i += 1
                }
            }
        }
        return Regex(sb.toString())
    }
}
