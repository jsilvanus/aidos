package dev.aidos.modelruntime

/*
 * Failures the runtime reports by type, so a host can map them to an API error without reading
 * the message text. They extend IllegalStateException and keep the message prefixes the hosts
 * matched on before these types existed, so older callers keep working.
 */

/** The model has no installed artifact on this engine. */
class ModelNotInstalledException(val modelId: String, detail: String? = null) :
    IllegalStateException("MODEL_NOT_INSTALLED: " + (detail ?: modelId))

/** The model cannot be made resident within the memory the engine may use (RFC-0022). */
class InsufficientMemoryException(detail: String) : IllegalStateException("INSUFFICIENT_MEMORY: $detail")

/** Room could only be made by unloading a model that is serving a request (RFC-0022). */
class ResidentModelsBusyException(detail: String) : IllegalStateException("ENGINE_BUSY: $detail")

/** Optional on a model adapter: the number of tokens the model's own tokenizer makes of [text]. */
interface TokenCountingAdapter {
    fun countTokens(text: String): Int?
}
