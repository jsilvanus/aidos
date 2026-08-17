package dev.aidos.engine.cli

import dev.aidos.kernel.ModelDescriptor
import dev.aidos.kernel.ModelRuntime

/**
 * Testable command layer for the Aidos Engine CLI.
 *
 * This class deliberately contains no process/terminal concerns. Main.kt is the process
 * boundary; EngineCli can be exercised directly from JVM tests and later reused by scripts.
 */
class EngineCli(
    private val runtime: ModelRuntime,
) {
    suspend fun catalog(): List<ModelDescriptor> = runtime.catalog()

    suspend fun installed(): List<ModelDescriptor> = runtime.installed()

    fun loaded(): List<String> = runtime.loaded()

    suspend fun load(modelId: String): Result<Unit> =
        runtime.load(modelId).map { Unit }

    suspend fun unload(modelId: String) {
        runtime.unload(modelId)
    }

    fun version(): String = VERSION

    companion object {
        const val VERSION = "0.1.0"
    }
}
