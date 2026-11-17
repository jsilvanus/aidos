package dev.aidos.executor

/**
 * RFC-0035 "Secrets found in content": what a screener decided for one tool result. The vault's
 * SecretContentPolicy produces it; the executor only acts on it. Never carries a matched value:
 * [Redacted.text] already has every value replaced by its marker.
 */
sealed interface SecretScreeningResult {
    data object Clean : SecretScreeningResult
    data class Redacted(val text: String, val kinds: List<String>, val count: Int) : SecretScreeningResult
    data class Withheld(val kinds: List<String>) : SecretScreeningResult
}
