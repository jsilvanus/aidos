package fi.italeino.aidos.engine.control

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** [ModelsControl] over a running Engine's loopback admin API (`/v1/admin/models`). */
class HttpModelsControl(
    private val baseUrl: String,
    token: String,
    private val client: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build(),
    reloadToken: () -> String? = { null },
) : ModelsControl {
    private val auth = AdminAuth(token, reloadToken)
    private val json = Json { ignoreUnknownKeys = true }

    private suspend fun call(method: String, path: String, body: String? = null, timeoutSeconds: Long = 30): String = withContext(Dispatchers.IO) {
        val b = HttpRequest.newBuilder(URI("$baseUrl$path")).timeout(Duration.ofSeconds(timeoutSeconds))
            .header("Authorization", "Bearer ${auth.token}").header("Content-Type", "application/json")
        val req = when (method) {
            "GET" -> b.GET().build()
            "DELETE" -> b.DELETE().build()
            else -> b.method(method, HttpRequest.BodyPublishers.ofString(body ?: "{}")).build()
        }
        val r = auth.send(client, req)
        if (r.statusCode() !in 200..299) {
            val msg = Regex("\"message\"\\s*:\\s*\"([^\"]*)\"").find(r.body())?.groupValues?.get(1)
            throw EngineControlException(r.statusCode(), msg ?: "Engine answered ${r.statusCode()}")
        }
        r.body()
    }

    override suspend fun installed(): List<ModelInfo> = json.decodeFromString(ListSerializer(ModelInfo.serializer()), call("GET", "/v1/admin/models"))

    override suspend fun searchRemote(query: String?, kind: String?, minContext: Int?, filters: ModelSearchFiltersInfo): List<ModelInfo> =
        json.decodeFromString(
            ListSerializer(ModelInfo.serializer()),
            // A Hub search reads per-result metadata; give it room.
            call("POST", "/v1/admin/models/search", json.encodeToString(ModelSearchRequest(query, kind, minContext, filters)), timeoutSeconds = 120),
        )

    override suspend fun startInstall(suggestionId: String) { call("POST", "/v1/admin/models/install", json.encodeToString(ModelInstallRequest(suggestionId))) }

    override suspend fun installs(): List<InstallStatus> = json.decodeFromString(ListSerializer(InstallStatus.serializer()), call("GET", "/v1/admin/models/installs"))

    override suspend fun delete(modelId: String) { call("DELETE", "/v1/admin/models/" + modelId.split("/").joinToString("/") { URLEncoder.encode(it, Charsets.UTF_8).replace("+", "%20") }) }
}
