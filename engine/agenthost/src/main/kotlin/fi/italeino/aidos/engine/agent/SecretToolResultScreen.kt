package fi.italeino.aidos.engine.agent

import dev.aidos.amp.AmpToolResultScreen
import dev.aidos.vault.Redactor
import dev.aidos.vault.SecretContentPolicy
import dev.aidos.vault.SecretScreening

/**
 * RFC-0035 "Secrets found in content" for the Engine's agent loop (desktop and Android): the same
 * [SecretContentPolicy] the daemon applies. Matched values are replaced by markers; a result that
 * looks like a credentials file is withheld and the model is told so, without the value.
 *
 * [redactor] should be the one the host's vault registers its values with, so a stored key is
 * caught by value and not only by pattern. The default knows patterns only.
 */
class SecretToolResultScreen(redactor: Redactor = Redactor()) : AmpToolResultScreen {
    private val policy = SecretContentPolicy(redactor)

    override fun screen(toolName: String, text: String): String = when (val s = policy.screen(text)) {
        SecretScreening.Clean -> text
        is SecretScreening.Redacted -> s.text
        is SecretScreening.Withheld ->
            "[The result of '$toolName' was withheld because it looks like a credentials file " +
                "(${s.kinds.joinToString()}). Ask the user for what you need without the secret values.]"
    }
}
