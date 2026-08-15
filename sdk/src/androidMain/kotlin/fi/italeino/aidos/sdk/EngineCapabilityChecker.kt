package fi.italeino.aidos.sdk

/**
 * Capability negotiation for Aidos Engine SDK (RFC-0103).
 *
 * Verifies API version compatibility and checks feature availability.
 *
 * RFC-0103: "Version and capability contract: Two mechanisms, doing different jobs:
 * - apiVersion: a strict integer, incremented on any breaking wire-format change.
 *   A client whose required major version does not match the Engine's treats it as
 *   incompatible and degrades.
 * - capabilities: returned in the same handshake response: which endpoints exist
 *   and which model classes are currently loaded or available."
 */
class EngineCapabilityChecker(private val clientRequiredMajor: Int = CURRENT_API_MAJOR) {
    companion object {
        // Increment this on any breaking change to the wire format
        const val CURRENT_API_MAJOR = 1
        const val CURRENT_API_MINOR = 0
    }

    /**
     * Check if the Engine's API version is compatible with the client.
     *
     * Returns compatibility result with detailed error info if incompatible.
     */
    fun checkApiVersion(serverApiVersion: Int): ApiVersionResult {
        val serverMajor = serverApiVersion / 100  // Assume major in first digits, minor after
        return if (serverMajor == clientRequiredMajor) {
            ApiVersionResult.Compatible(serverApiVersion)
        } else {
            ApiVersionResult.Incompatible(clientRequiredMajor, serverApiVersion)
        }
    }

    /**
     * Check if a required endpoint is available on the Engine.
     *
     * Returns true only if the endpoint is explicitly listed in capabilities.
     */
    fun hasEndpoint(capabilities: EngineCapabilities, endpoint: String): Boolean {
        return capabilities.endpoints.contains(endpoint)
    }

    /**
     * Check if a required model is available and enabled on the Engine.
     *
     * Returns the model status if found and enabled, null otherwise.
     *
     * RFC-0103: "capabilities.models lists the enabled set, not the configured set.
     * A calling app may only request a model that appears in some provider's configured
     * list or in the local Cookbook — never an arbitrary string."
     */
    fun getModel(capabilities: EngineCapabilities, modelId: String): ModelStatus? {
        return capabilities.models.firstOrNull { it.modelId == modelId }
    }

    /**
     * Get all models of a specific kind (LLM, EMBEDDING, STT) that are available.
     */
    fun getModelsByKind(capabilities: EngineCapabilities, modelKind: String): List<ModelStatus> {
        return capabilities.models.filter { it.modelKind == modelKind }
    }

    /**
     * Validate that all required endpoints are present.
     */
    fun validateEndpoints(
        capabilities: EngineCapabilities,
        required: List<String>,
    ): EndpointValidationResult {
        val missing = required.filter { !capabilities.endpoints.contains(it) }
        return if (missing.isEmpty()) {
            EndpointValidationResult.AllPresent
        } else {
            EndpointValidationResult.MissingEndpoints(missing)
        }
    }
}

sealed class ApiVersionResult {
    data class Compatible(val apiVersion: Int) : ApiVersionResult()
    data class Incompatible(val clientRequired: Int, val serverHas: Int) : ApiVersionResult()
}

sealed class EndpointValidationResult {
    object AllPresent : EndpointValidationResult()
    data class MissingEndpoints(val missing: List<String>) : EndpointValidationResult()
}
