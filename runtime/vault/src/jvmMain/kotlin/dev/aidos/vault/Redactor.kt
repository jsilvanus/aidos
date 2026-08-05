package dev.aidos.vault

import java.util.concurrent.CopyOnWriteArrayList
import java.util.regex.Pattern

/**
 * The redactor (RFC-0035).
 *
 * Every string that crosses a persistence or transmission boundary passes through [redact].
 * Two strategies run in order:
 *
 * 1. **Known values** — vault values registered via [register]. Any occurrence is replaced with
 *    `«redacted:NAME»`. The vault calls [register] on load and [unregister] on delete.
 * 2. **Pattern detection** — API-key shapes, JWTs, PEM blocks, `KEY=value` assignments.
 *    Patterns are imperfect and biased toward false positives over false negatives.
 *
 * [detect] returns whether a string *contains* secrets without redacting — used to label
 * ContentNodes as SECRET (RFC-0024).
 */
class Redactor {

    data class Registration(val id: String, val name: String, val value: CharArray) {
        override fun equals(other: Any?): Boolean {
            if (other !is Registration) return false
            return id == other.id
        }
        override fun hashCode() = id.hashCode()
    }

    private val registered = CopyOnWriteArrayList<Registration>()

    /** Called by the vault when a secret is loaded. */
    fun register(id: String, name: String, value: CharArray) {
        registered.add(Registration(id, name, value.copyOf()))
    }

    /** Called by the vault when a secret is deleted or rotated (old value). */
    fun unregister(id: String) {
        val iter = registered.iterator()
        while (iter.hasNext()) {
            val r = iter.next()
            if (r.id == id) {
                r.value.fill('\u0000')
                registered.remove(r)
            }
        }
    }

    /**
     * Redact known values and suspicious patterns from [input].
     *
     * Returns the redacted string. If nothing was found, returns [input] unchanged (same
     * object if no substitution occurred).
     */
    fun redact(input: String): String {
        var result = input
        // Known values first — most important.
        for (reg in registered) {
            val secret = String(reg.value)
            if (secret.isNotEmpty() && result.contains(secret)) {
                result = result.replace(secret, "«redacted:${reg.name}»")
            }
        }
        // Pattern detection — common credential shapes.
        for ((pattern, label) in PATTERNS) {
            result = pattern.matcher(result).replaceAll("«redacted:$label»")
        }
        return result
    }

    /** Returns true if [input] contains a known value or matches a pattern. */
    fun detect(input: String): Boolean {
        for (reg in registered) {
            val secret = String(reg.value)
            if (secret.isNotEmpty() && input.contains(secret)) return true
        }
        for ((pattern, _) in PATTERNS) {
            if (pattern.matcher(input).find()) return true
        }
        return false
    }

    companion object {
        private val PATTERNS: List<Pair<Pattern, String>> = listOf(
            // JWTs
            Pair(
                Pattern.compile("eyJ[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}"),
                "jwt"
            ),
            // PEM private keys
            Pair(
                Pattern.compile("-----BEGIN (RSA |EC |OPENSSH |DSA )?PRIVATE KEY-----[\\s\\S]*?-----END [\\s\\S]*?PRIVATE KEY-----"),
                "pem-private-key"
            ),
            // Anthropic API keys
            Pair(
                Pattern.compile("sk-ant-[A-Za-z0-9\\-_]{20,}"),
                "anthropic-api-key"
            ),
            // OpenAI API keys
            Pair(
                Pattern.compile("sk-[A-Za-z0-9]{20,}"),
                "openai-api-key"
            ),
            // Generic KEY=value assignments
            Pair(
                Pattern.compile("(?i)(api[_-]?key|secret|token|password|passwd|credential)\\s*[=:]\\s*['\"]?[A-Za-z0-9+/._-]{16,}['\"]?"),
                "credential-assignment"
            ),
        )
    }
}
