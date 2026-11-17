package dev.aidos.vault

/**
 * RFC-0035 "Secrets found in content": what to do with one text before it reaches the model.
 *
 * - [Clean]: nothing matched; the text is passed unchanged.
 * - [Redacted]: credential-like values were replaced with markers; the rest of the text is kept.
 * - [Withheld]: the whole text is credential-shaped and is excluded from the transcript.
 *
 * [kinds] are kind names only (`known_value`, `pattern:<label>`), never a matched value.
 */
sealed interface SecretScreening {
    data object Clean : SecretScreening
    data class Redacted(val text: String, val kinds: List<String>, val count: Int) : SecretScreening
    data class Withheld(val kinds: List<String>) : SecretScreening
}

/** RFC-0035 "Secrets found in content": withheld at this many pattern matches. */
internal const val WITHHOLD_AT_PATTERN_HITS = 4

/** RFC-0035 "Secrets found in content": an assignment-shaped text needs at least this many lines. */
internal const val ASSIGNMENT_MIN_LINES = 3

/** RFC-0035 "Secrets found in content": a PEM private key is withheld even when it is the only match. */
internal const val PRIVATE_KEY_KIND = "pattern:pem-private-key"

/** RFC-0035 "Secrets found in content": a `NAME=value` or `NAME: value` line, as in `.env` or YAML. */
private val ASSIGNMENT_LINE = Regex("""^\s*(export\s+)?[A-Za-z_][A-Za-z0-9_.-]*\s*[=:]\s*\S""")

/**
 * Applies the RFC-0035 rule to the text of one tool result. Replaces only the matched values,
 * and withholds the whole text when it looks like a credentials file. Never returns, logs or
 * records a matched value; the redactor's sink (if any) sees kinds and the boundary only.
 */
class SecretContentPolicy(private val redactor: Redactor) {

    fun screen(text: String): SecretScreening {
        // Tool results enter the prompt, so the redactor's audit records them at PROMPT.
        val r = redactor.redactCounted(text, RedactionBoundary.PROMPT)
        if (r.kinds.isEmpty()) return SecretScreening.Clean

        val patternHits = r.patternHits
        val lines = text.lines().filter { it.isNotBlank() }
        val assignmentLines = lines.count { ASSIGNMENT_LINE.containsMatchIn(it) }
        val assignmentShaped = patternHits >= 1 &&
            lines.size >= ASSIGNMENT_MIN_LINES &&
            assignmentLines * 2 >= lines.size
        // In an assignment-shaped file the patterns catch some secrets and miss others, so keeping
        // the rest would leak the ones missed.
        val withheld = PRIVATE_KEY_KIND in r.kinds || patternHits >= WITHHOLD_AT_PATTERN_HITS || assignmentShaped
        if (withheld) return SecretScreening.Withheld(r.kinds)

        val count = r.knownValueHits + r.patternHits
        val note = "[$count credential-like value(s) in this result were replaced with redaction markers.]"
        return SecretScreening.Redacted(text = r.text + "\n" + note, kinds = r.kinds, count = count)
    }
}
