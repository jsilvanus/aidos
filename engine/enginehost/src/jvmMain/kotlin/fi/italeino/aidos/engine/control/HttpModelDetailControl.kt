package fi.italeino.aidos.engine.control

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** [ModelDetailControl] over a running Engine's loopback admin API (`/v1/admin/model-detail/...?model=<id>`). */
class HttpModelDetailControl(
    private val baseUrl: String,
    token: String,
    private val client: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build(),
    reloadToken: () -> String? = { null },
) : ModelDetailControl {
    private val auth = AdminAuth(token, reloadToken)
    private val json = Json { ignoreUnknownKeys = true }

    private suspend fun call(method: String, action: String, modelId: String, body: String? = null, timeoutSeconds: Long = 60): HttpResponse<String> = withContext(Dispatchers.IO) {
        val b = HttpRequest.newBuilder(URI("$baseUrl/v1/admin/model-detail/$action?model=" + URLEncoder.encode(modelId, Charsets.UTF_8)))
            .timeout(Duration.ofSeconds(timeoutSeconds)).header("Authorization", "Bearer ${auth.token}").header("Content-Type", "application/json")
        val r = auth.send(client, if (method == "GET") b.GET().build() else b.method(method, HttpRequest.BodyPublishers.ofString(body ?: "{}")).build())
        if (r.statusCode() !in 200..299) {
            val msg = Regex("\"message\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(r.body())?.groupValues?.get(1)?.replace("\\\"", "\"")
            throw EngineControlException(r.statusCode(), msg ?: "Engine answered ${r.statusCode()}")
        }
        r
    }

    private suspend inline fun <reified T> get(action: String, modelId: String): T = json.decodeFromString(call("GET", action, modelId).body())

    override suspend fun detail(modelId: String): ModelDetailInfo = get("detail", modelId)
    override suspend fun installedState(modelId: String): InstalledModelInfo = get("installed", modelId)
    override suspend fun licenseAccepted(modelId: String): Boolean = get<ModelFlag>("license", modelId).value
    override suspend fun acceptLicense(modelId: String) { call("POST", "license/accept", modelId) }
    override suspend fun startDownload(modelId: String) { call("POST", "download/start", modelId) }

    override suspend fun downloadStatus(modelId: String): DownloadInfo? {
        val r = call("GET", "download", modelId)
        return if (r.statusCode() == 204) null else json.decodeFromString(r.body())
    }

    override suspend fun cancelDownload(modelId: String) { call("POST", "download/cancel", modelId) }
    override suspend fun residency(modelId: String): ResidencyInfo = get("residency", modelId)
    override suspend fun load(modelId: String) { call("POST", "load", modelId, timeoutSeconds = 120) }
    override suspend fun unload(modelId: String) { call("POST", "unload", modelId, timeoutSeconds = 60) }
    override suspend fun runSettings(modelId: String): RunSettingsInfo = get("run-settings", modelId)
    override suspend fun setRunSettings(modelId: String, settings: RunSettingsInfo) { call("PUT", "run-settings", modelId, json.encodeToString(settings)) }

    override suspend fun embed(modelId: String, input: String): List<Float> =
        json.decodeFromString<ModelVectorResponse>(call("POST", "embed", modelId, json.encodeToString(ModelTextRequest(input)), timeoutSeconds = 120).body()).vector

    override suspend fun selfTest(modelId: String, text: String, file: String): SelfTestInfo =
        json.decodeFromString(call("POST", "self-test", modelId, json.encodeToString(ModelTextRequest(text, file)), timeoutSeconds = 300).body())
}
