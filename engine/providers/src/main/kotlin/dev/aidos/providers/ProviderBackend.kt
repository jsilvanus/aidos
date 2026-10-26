package dev.aidos.providers

import dev.aidos.kernel.ModelAdapter
import dev.aidos.kernel.ModelDescriptor
import dev.aidos.kernel.ModelKind
import dev.aidos.modelruntime.InferenceBackend

/**
 * Adds configured HTTP providers' models to an existing JVM [InferenceBackend] (RFC-0021): their
 * ids are `<providerId>:<model>`; every other id is delegated unchanged. Stack it with the other
 * decorators. Providers are read per call, so editing the config applies without a restart.
 *
 * Locality (RFC-0104): a provider model is `remote` unless its provider is [ProviderConfig.servedLocally].
 * The descriptor says so explicitly, and so does the adapter, so both claims agree.
 */
class ProviderBackend(
    private val delegate: InferenceBackend,
    private val providers: () -> List<ProviderConfig>,
    private val transport: HttpTransport = DefaultJavaHttpTransport(),
) : InferenceBackend {

    private fun find(modelId: String): Pair<ProviderConfig, ProviderModel>? =
        providers().firstNotNullOfOrNull { p ->
            p.models.firstOrNull { p.publicId(it) == modelId }?.let { p to it }
        }

    override suspend fun catalog(): List<ModelDescriptor> = delegate.catalog()

    // A provider's models are "installed" in the sense that matters here: configured and callable.
    override suspend fun installed(): List<ModelDescriptor> =
        delegate.installed() + providers().flatMap { p -> p.models.map { descriptor(p, it) } }

    override suspend fun computeDigest(modelId: String): String =
        if (find(modelId) != null) "provider:$modelId" else delegate.computeDigest(modelId)

    override suspend fun computeDigest(modelId: String, artifactPath: String?): String =
        if (find(modelId) != null) "provider:$modelId" else delegate.computeDigest(modelId, artifactPath)

    /** Nothing to delete for a provider model: remove it from the config. */
    override suspend fun delete(modelId: String) {
        if (find(modelId) == null) delegate.delete(modelId)
    }

    override suspend fun load(modelId: String): Result<ModelAdapter> {
        val (provider, model) = find(modelId) ?: return delegate.load(modelId)
        return Result.success(adapter(provider, model))
    }

    override suspend fun load(modelId: String, artifactPath: String?): Result<ModelAdapter> =
        if (find(modelId) != null) load(modelId) else delegate.load(modelId, artifactPath)

    override suspend fun unload(modelId: String) {
        if (find(modelId) == null) delegate.unload(modelId)
    }

    private fun descriptor(p: ProviderConfig, m: ProviderModel) = ModelDescriptor(
        id = p.publicId(m),
        name = "${m.id} (${p.id})",
        kind = ModelKind.LLM,
        providerId = p.id,
        isLocal = p.servedLocally,
        contextWindow = m.contextWindow,
        sizeBytes = null,
        digest = null,
        // Never the API key: metadata is listed by /v1/models.
        metadata = buildMap {
            put("placement", if (p.servedLocally) "local" else "remote")
            put("provider_base_url", p.baseUrl)
            if (p.treatAsLocal) put("treat_as_local", if (p.servedLocally) "honoured" else "ignored: host is not loopback")
        },
    )

    private fun adapter(p: ProviderConfig, m: ProviderModel) = OpenAiCompatibleModelAdapter(
        baseUrl = p.baseUrl,
        modelId = m.id,
        providerId = p.id,
        isLocal = p.servedLocally,
        apiKey = p.apiKey,
        contextWindow = m.contextWindow,
        transport = transport,
    )
}
