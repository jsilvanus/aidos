package dev.aidos.settings

import dev.aidos.kernel.AidosError
import dev.aidos.kernel.ErrorClass
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/**
 * RFC-0036: The three scope classes that decide who may write a setting.
 *
 * The rule: settings that can loosen authority may not come from a project.
 * Settings that can only tighten (more cautious) may.
 */
enum class ScopeClass {
    /** User-scope only. Any project attempting this is a visible error + audit row. */
    SECURITY,

    /** User-scope only. Any project attempting this is a visible error + audit row. */
    SPEND,

    /** User, workspace, project — but not session. A project may only tighten, never loosen. */
    PROJECT_SAFE,

    /** Any scope including session. */
    PREFERENCE,
}

/** Where a resolved value came from (RFC-0036). */
enum class SettingOrigin { SESSION, PROJECT, WORKSPACE, USER, DEFAULT }

/** Who wrote a settings row (RFC-0036). */
enum class SettingSetByKind { USER, RUNTIME }

/**
 * A setting value together with full provenance (RFC-0036).
 *
 * The origin is not a debugging nicety — "why is this project sending my code to a remote model?"
 * must be answerable exactly: this value, from this scope, at this line.
 */
data class Resolved<T>(
    val value: T,
    val origin: SettingOrigin,

    /** e.g. "aidos.toml:14" or "user.db" */
    val originPath: String?,

    /**
     * Set when a lower scope attempted to override a SECURITY or SPEND setting and was refused.
     * The refused scope class is here so the UI can explain the refusal.
     */
    val overriddenBy: ScopeClass? = null,
)

/**
 * A setting descriptor: the compile-time declaration of a single setting (RFC-0036).
 *
 * Declaration in code means the compiler enforces that readers and writers agree on the type,
 * and the full set of settings is enumerable — which is what makes a settings UI and a
 * diagnostic dump possible at all.
 */
class SettingDescriptor<T>(
    val key: String,
    val scopeClass: ScopeClass,
    val default: T,
    val description: String,
    val codec: SettingCodec<T>,

    /**
     * For SECURITY settings with an invalid value, fail closed to the most restrictive valid
     * value (RFC-0036). Null for non-SECURITY settings (fall back to default instead).
     */
    val mostRestrictive: T? = null,
)

/** Bidirectional mapping between a setting value and its JSON representation (RFC-0039). */
interface SettingCodec<T> {
    fun encode(value: T): JsonElement
    fun decode(element: JsonElement): Result<T>
}

// ─── Built-in codecs ────────────────────────────────────────────────────────

object StringCodec : SettingCodec<String> {
    override fun encode(value: String): JsonElement = JsonPrimitive(value)
    override fun decode(element: JsonElement): Result<String> = runCatching {
        element.jsonPrimitive.content
    }
}

object IntCodec : SettingCodec<Int> {
    override fun encode(value: Int): JsonElement = JsonPrimitive(value)
    override fun decode(element: JsonElement): Result<Int> = runCatching {
        element.jsonPrimitive.int
    }
}

object BooleanCodec : SettingCodec<Boolean> {
    override fun encode(value: Boolean): JsonElement = JsonPrimitive(value)
    override fun decode(element: JsonElement): Result<Boolean> = runCatching {
        element.jsonPrimitive.boolean
    }
}

class RangedIntCodec(private val range: IntRange) : SettingCodec<Int> {
    override fun encode(value: Int): JsonElement = JsonPrimitive(value)
    override fun decode(element: JsonElement): Result<Int> = runCatching {
        val v = element.jsonPrimitive.int
        require(v in range) { "value $v is outside allowed range $range" }
        v
    }
}

class StringListCodec : SettingCodec<List<String>> {
    override fun encode(value: List<String>): JsonElement =
        kotlinx.serialization.json.JsonArray(value.map { JsonPrimitive(it) })
    override fun decode(element: JsonElement): Result<List<String>> = runCatching {
        element.jsonArray.map { it.jsonPrimitive.content }
    }
}

class EnumCodec<T : Enum<T>>(private val values: Array<T>) : SettingCodec<T> {
    override fun encode(value: T): JsonElement = JsonPrimitive(value.name)
    override fun decode(element: JsonElement): Result<T> = runCatching {
        val name = element.jsonPrimitive.content
        values.firstOrNull { it.name == name }
            ?: error("unknown value '$name'; expected one of ${values.map { it.name }}")
    }
}

// ─── DSL ────────────────────────────────────────────────────────────────────

class SettingBuilder<T>(private val key: String) {
    private var scopeClass: ScopeClass = ScopeClass.PREFERENCE
    private var default: T? = null
    private var description: String = ""
    private var codec: SettingCodec<T>? = null
    private var mostRestrictive: T? = null

    fun scopeClass(c: ScopeClass) { scopeClass = c }
    fun default(v: T) { default = v }
    fun description(d: String) { description = d }
    fun codec(c: SettingCodec<T>) { codec = c }
    fun mostRestrictive(v: T) { mostRestrictive = v }

    @Suppress("UNCHECKED_CAST")
    fun build(): SettingDescriptor<T> {
        val d = checkNotNull(default) { "setting '$key' has no default" }
        val c = checkNotNull(codec) { "setting '$key' has no codec" }
        return SettingDescriptor(key, scopeClass, d, description, c, mostRestrictive)
    }
}

fun <T> setting(key: String, block: SettingBuilder<T>.() -> Unit): SettingDescriptor<T> =
    SettingBuilder<T>(key).apply(block).build()

/**
 * A parse error from `aidos.toml` or a settings write (RFC-0036).
 */
data class SettingError(
    val key: String,
    val originPath: String?,
    val line: Int?,
    val message: String,
    val errorClass: SettingErrorClass,
)

enum class SettingErrorClass {
    /** The key is not declared. Preserved in storage; not interpreted. */
    UNKNOWN_KEY,

    /** A SECURITY or SPEND setting was attempted at project scope. Audit row required. */
    SCOPE_VIOLATION,

    /** Type or range check failed; failed closed to default or mostRestrictive. */
    VALIDATION_FAILED,
}

fun SettingError.toAidosError(): AidosError = AidosError(
    code = when (errorClass) {
        SettingErrorClass.UNKNOWN_KEY -> "settings.unknown_key"
        SettingErrorClass.SCOPE_VIOLATION -> "settings.scope_violation"
        SettingErrorClass.VALIDATION_FAILED -> "settings.validation_failed"
    },
    errorClass = ErrorClass.INVALID_INPUT,
    message = message,
    detail = buildMap {
        put("key", key)
        originPath?.let { put("origin", it) }
        line?.let { put("line", it.toString()) }
    },
)
