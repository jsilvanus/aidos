package dev.aidos.vault

import java.util.concurrent.CopyOnWriteArrayList
import java.util.regex.Pattern

/**
 * Where a string was about to go when the redactor looked at it (RFC-0035 "Where it runs").
 * The audit records the boundary, never the content.
 */
enum class RedactionBoundary(val wire: String) {
    PROMPT("prompt"), EVENT("event"), LOG("log"), EXPORT("export"), MEMORY("memory"),
    /** Attempt input/output snapshots: RFC-0035 lists them as their own boundary. */
    ATTEMPT("attempt"),
    UNSPECIFIED("unspecified"),
}

/**
 * Receives "something was found" — the kind and the boundary, never the match (RFC-0035
 * "Detection is reported without recording the value"). `kind` is `known_value` or
 * `pattern:<label>`.
 */
fun interface RedactionSink {
    fun record(boundary: RedactionBoundary, detectionKind: String)
}

/**
 * The redactor (RFC-0035).
 *
 * Every string that crosses a persistence or transmission boundary passes through [redact].
 * Two strategies run in order:
 *
 * 1. **Known values** — vault values registered via [register]. Any occurrence is replaced with
 *    `«redacted:NAME»`. The vault registers every value when it opens and on every store/rotate,
 *    and calls [unregister] on delete.
 * 2. **Pattern detection** — API-key shapes, JWTs, PEM blocks, connection strings, `KEY=value`
 *    assignments. Patterns are imperfect and biased toward false positives over false negatives.
 *
 * It runs unconditionally: there is no level or flag that turns it off. [detect] reports
 * whether a string *contains* secrets without redacting — used to label content as SECRET
 * (RFC-0024).
 *
 * Rotation: [retire] keeps a replaced value registered for a grace period so a leaked old value
 * is still filtered (RFC-0035 "Rotation and revocation").
 */
class Redactor(
    /** Optional audit of detections (kind + boundary only). */
    private val sink: RedactionSink? = null,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {

    data class Registration(
        val id: String,
        val name: String,
        val value: CharArray,
        /** Epoch millis after which a retired value is dropped; null for live values. */
        val expiresAtMillis: Long? = null,
    ) {
        override fun equals(other: Any?): Boolean = other is Registration && id == other.id && expiresAtMillis == other.expiresAtMillis
        override fun hashCode() = id.hashCode()
    }

    private val registered = CopyOnWriteArrayList<Registration>()

    /** Called by the vault when a secret is loaded. Re-registering an id replaces its value. */
    fun register(id: String, name: String, value: CharArray) {
        if (value.size < MIN_KNOWN_VALUE_LENGTH) return
        removeLive(id)
        registered.add(Registration(id, name, value.copyOf()))
    }

    /** Called by the vault when a secret is deleted. */
    fun unregister(id: String) {
        removeLive(id)
    }

    /**
     * Keeps [oldValue] filtered for [graceMillis] after the secret under [id] was rotated.
     * The vault no longer holds it; only the redactor does, and only until the grace ends.
     */
    fun retire(id: String, name: String, oldValue: CharArray, graceMillis: Long) {
        if (oldValue.size < MIN_KNOWN_VALUE_LENGTH) return
        registered.add(Registration(id, name, oldValue.copyOf(), nowMillis() + graceMillis))
    }

    private fun removeLive(id: String) {
        for (r in registered) {
            if (r.id == id && r.expiresAtMillis == null) {
                r.value.fill('\u0000')
                registered.remove(r)
            }
        }
    }

    private fun active(): List<Registration> {
        val now = nowMillis()
        val live = ArrayList<Registration>(registered.size)
        for (r in registered) {
            val exp = r.expiresAtMillis
            if (exp != null && exp <= now) {
                r.value.fill('\u0000'); registered.remove(r)
            } else live.add(r)
        }
        return live
    }

    /** Number of values currently registered (live + within grace). Never exposes them. */
    fun registeredCount(): Int = active().size

    /**
     * Redact known values and suspicious patterns from [input]. Returns [input] itself when
     * nothing was found. Each kind found is reported to the sink once per call.
     */
    fun redact(input: String, boundary: RedactionBoundary = RedactionBoundary.UNSPECIFIED): String =
        redactCounted(input, boundary).text

    /**
     * Outcome of [redactCounted]: the redacted text, the kinds found, and how many matches were
     * replaced, known values and pattern matches counted apart (RFC-0035 "Secrets found in content").
     */
    data class Redaction(
        val text: String,
        val kinds: List<String>,
        val knownValueHits: Int,
        val patternHits: Int,
    )

    /**
     * [redact], also counting the matches it replaced. Returns the text as given when nothing was
     * found. Never returns or records a match.
     */
    fun redactCounted(input: String, boundary: RedactionBoundary = RedactionBoundary.UNSPECIFIED): Redaction {
        var result = input
        val found = LinkedHashSet<String>()
        var knownHits = 0
        for (reg in active()) {
            // Searched as chars: making a String of the value on every call left a copy of every
            // secret on the heap for each string that crossed a boundary.
            val hits = countOf(result, reg.value)
            if (hits > 0) {
                result = replaceAll(result, reg.value, "«redacted:${reg.name}»")
                found += "known_value"
                knownHits += hits
            }
        }
        var patternHits = 0
        // One pass decides whether any pattern can match; the twelve are only run when one does.
        if (ANY_PATTERN.matcher(result).find()) {
            for ((pattern, label) in PATTERNS) {
                val m = pattern.matcher(result)
                var hits = 0
                while (m.find()) hits++
                if (hits > 0) {
                    result = m.reset().replaceAll(java.util.regex.Matcher.quoteReplacement("«redacted:$label»"))
                    found += "pattern:$label"
                    patternHits += hits
                }
            }
        }
        if (sink != null) for (kind in found) sink.record(boundary, kind)
        return Redaction(result, found.toList(), knownHits, patternHits)
    }

    /** The kinds of secret [input] contains, without redacting or reporting. Never the match. */
    fun detections(input: String): List<String> {
        val found = LinkedHashSet<String>()
        for (reg in active()) {
            if (indexOf(input, reg.value, 0) >= 0) found += "known_value"
        }
        if (ANY_PATTERN.matcher(input).find()) {
            for ((pattern, label) in PATTERNS) {
                if (pattern.matcher(input).find()) found += "pattern:$label"
            }
        }
        return found.toList()
    }

    private fun indexOf(haystack: String, needle: CharArray, from: Int): Int {
        val last = haystack.length - needle.size
        var i = from
        while (i <= last) {
            i = haystack.indexOf(needle[0], i)
            if (i < 0 || i > last) return -1
            var j = 1
            while (j < needle.size && haystack[i + j] == needle[j]) j++
            if (j == needle.size) return i
            i++
        }
        return -1
    }

    private fun countOf(haystack: String, needle: CharArray): Int {
        var hits = 0
        var from = 0
        while (true) {
            val at = indexOf(haystack, needle, from)
            if (at < 0) return hits
            hits++
            from = at + needle.size
        }
    }

    private fun replaceAll(haystack: String, needle: CharArray, replacement: String): String {
        val out = StringBuilder(haystack.length)
        var from = 0
        while (true) {
            val at = indexOf(haystack, needle, from)
            if (at < 0) break
            out.append(haystack, from, at).append(replacement)
            from = at + needle.size
        }
        return out.append(haystack, from, haystack.length).toString()
    }

    /** True if [input] contains a known value or matches a pattern. */
    fun detect(input: String): Boolean = detections(input).isNotEmpty()

    companion object {
        /** A shorter "secret" would corrupt ordinary text more often than it would protect anything. */
        const val MIN_KNOWN_VALUE_LENGTH = 4

        private val PATTERNS: List<Pair<Pattern, String>> = listOf(
            Pattern.compile("eyJ[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}") to "jwt",
            Pattern.compile("-----BEGIN ([A-Z ]+ )?PRIVATE KEY-----[\\s\\S]*?-----END ([A-Z ]+ )?PRIVATE KEY-----") to "pem-private-key",
            Pattern.compile("sk-ant-[A-Za-z0-9\\-_]{20,}") to "anthropic-api-key",
            Pattern.compile("sk-[A-Za-z0-9_\\-]{20,}") to "openai-api-key",
            Pattern.compile("\\bgh[pousr]_[A-Za-z0-9]{30,}\\b") to "github-token",
            Pattern.compile("\\bgithub_pat_[A-Za-z0-9_]{40,}\\b") to "github-token",
            Pattern.compile("\\bhf_[A-Za-z0-9]{30,}\\b") to "huggingface-token",
            Pattern.compile("\\bAKIA[0-9A-Z]{16}\\b") to "aws-access-key",
            Pattern.compile("\\bxox[abprs]-[A-Za-z0-9-]{10,}\\b") to "slack-token",
            Pattern.compile("\\bAIza[0-9A-Za-z_\\-]{35}\\b") to "google-api-key",
            // scheme://user:password@host
            Pattern.compile("\\b[a-zA-Z][a-zA-Z0-9+.-]*://[^\\s:/@]+:[^\\s/@]+@[^\\s/]+") to "connection-string",
            Pattern.compile("(?i)(api[_-]?key|secret|token|password|passwd|credential)\\s*[=:]\\s*['\"]?[A-Za-z0-9+/._-]{16,}") to "credential-assignment",
        )

        /** Any of [PATTERNS]: a string this does not match needs none of them run. */
        private val ANY_PATTERN: Pattern = Pattern.compile(PATTERNS.joinToString("|") { "(?:" + it.first.pattern() + ")" })
    }
}
