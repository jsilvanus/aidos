package dev.aidos.ui.frontdoors.projects

import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Duration.Companion.minutes

/** Directory names for runtime-managed projects (UI-34). The runtime keeps them under its storage root. */
object ProjectSlugs {
    const val MAX_LENGTH = 40

    /**
     * Lower-case ASCII slug: ä and å become a, ö becomes o, every other run of characters outside
     * a-z and 0-9 becomes one hyphen, and the result is trimmed to [MAX_LENGTH] characters.
     */
    fun from(name: String): String {
        val mapped = name.lowercase()
            .replace('ä', 'a')
            .replace('å', 'a')
            .replace('ö', 'o')
            .map { if (it in 'a'..'z' || it in '0'..'9') it else '-' }
            .joinToString("")
        return mapped
            .replace(Regex("-+"), "-")
            .trim('-')
            .take(MAX_LENGTH)
            .trimEnd('-')
            .ifEmpty { "project" }
    }

    /**
     * [base] if no existing project directory ends with it, otherwise `base-2`, `base-3`, ... The
     * suffix counts toward [MAX_LENGTH], so the base is cut shorter when needed.
     */
    fun unique(base: String, existingProjectPaths: List<String>): String {
        val taken = existingProjectPaths.map { lastSegment(it) }.toSet()
        if (base !in taken) return base
        var n = 2
        while (true) {
            val suffix = "-$n"
            val candidate = base.take(MAX_LENGTH - suffix.length).trimEnd('-') + suffix
            if (candidate !in taken) return candidate
            n++
        }
    }

    private fun lastSegment(path: String): String =
        path.trimEnd('/', '\\').substringAfterLast('/').substringAfterLast('\\')
}

/** "just now", "12 min ago", "3 h ago", "2 d ago", then the date. [now] is passed in so this is testable. */
fun formatLastActive(lastActiveAt: Instant, now: Instant): String {
    val elapsed = now - lastActiveAt
    return when {
        elapsed < 1.minutes -> "just now"
        elapsed < 60.minutes -> "${elapsed.inWholeMinutes} min ago"
        elapsed < (24 * 60).minutes -> "${elapsed.inWholeHours} h ago"
        elapsed < (30 * 24 * 60).minutes -> "${elapsed.inWholeDays} d ago"
        else -> lastActiveAt.toLocalDateTime(TimeZone.UTC).date.toString()
    }
}

