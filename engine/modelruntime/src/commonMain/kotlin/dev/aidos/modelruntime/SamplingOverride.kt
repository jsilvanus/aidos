package dev.aidos.modelruntime

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * The sampling a single request asked for (RFC-0022 amendment, point 9).
 *
 * `ModelRequest` belongs to the kernel, which is frozen and has no sampling fields, so these
 * travel beside the request in the coroutine context: the caller wraps the invocation in
 * `withContext(SamplingOverride(...))` and the adapter reads it where it builds the native
 * parameters. A null field means the client did not say, and the model's own run settings apply.
 */
class SamplingOverride(
    val temperature: Float? = null,
    val topP: Float? = null,
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<SamplingOverride>
}
