package dev.aidos.settings

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

/**
 * A reference to a vault entry (RFC-0036 "Settings are not secrets", RFC-0035).
 *
 * A setting never holds a credential: it holds one of these. The stored/serialised form is
 * `secret:<name>`, so a plain API key typed into `aidos.toml` fails validation instead of being
 * accepted, written to the cache and exported. The value is resolved at the point of use via
 * [SecretResolver] and is never written back, logged or audited.
 */
data class SecretRef(val name: String) {
    init {
        require(NAME.matches(name)) { "secret_ref name must match ${NAME.pattern}: '$name'" }
    }

    override fun toString(): String = "secret:$name"

    companion object {
        private val NAME = Regex("[A-Za-z0-9_.-]{1,128}")
        const val PREFIX = "secret:"
    }
}

object SecretRefCodec : SettingCodec<SecretRef> {
    override fun encode(value: SecretRef): JsonElement = JsonPrimitive(value.toString())
    override fun decode(element: JsonElement): Result<SecretRef> = runCatching {
        val text = element.jsonPrimitive.content
        require(text.startsWith(SecretRef.PREFIX)) {
            "must be a reference of the form '${SecretRef.PREFIX}<vault-entry-name>', not a value"
        }
        SecretRef(text.removePrefix(SecretRef.PREFIX))
    }
}

/** Resolves a [SecretRef] to the secret at the point of use. The result is never persisted. */
fun interface SecretResolver {
    fun resolve(ref: SecretRef): Result<CharArray>
}

/** Resolve a `secret_ref` setting's value at the point of use. */
fun Resolved<SecretRef>.resolveSecret(resolver: SecretResolver): Result<CharArray> =
    resolver.resolve(value)
