package dev.aidos.modelruntime

/**
 * Factory for [GlobalModelRuntime] with the default JVM backend (M21).
 *
 * `commonMain` code cannot reference [LlamaCppInferenceBackend] -- it's a `jvmMain`-only class
 * (the `de.kherud.llama` JNI binding it wraps has no non-JVM target). [GlobalModelRuntime] itself
 * stays in `commonMain` (it's genuinely platform-agnostic, testable with mock `InferenceBackend`s
 * per its own doc comment); only this convenience constructor needs a JVM home.
 *
 * Usage:
 * ```kotlin
 * val runtime = GlobalModelRuntime.create()  // Uses LlamaCppInferenceBackend
 * ```
 *
 * The underlying InferenceBackend can be tested with mock implementations:
 * ```kotlin
 * val mockBackend = object : InferenceBackend { ... }
 * val runtime = GlobalModelRuntime(mockBackend)
 * ```
 */
fun GlobalModelRuntime.Companion.create(): GlobalModelRuntime =
    GlobalModelRuntime(backend = LlamaCppInferenceBackend())
