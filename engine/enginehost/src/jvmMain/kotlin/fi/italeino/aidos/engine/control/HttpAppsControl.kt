package fi.italeino.aidos.engine.control

import fi.italeino.aidos.engine.approval.AppApprovalRecord
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

/** [AppsControl] over a running Engine's loopback admin API (`/v1/admin/apps`). */
class HttpAppsControl(
    private val baseUrl: String,
    token: String,
    private val client: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build(),
    reloadToken: () -> String? = { null },
) : AppsControl {
    private val auth = AdminAuth(token, reloadToken)
    private val json = Json { ignoreUnknownKeys = true }

    private suspend fun call(method: String, path: String, body: String? = null): HttpResponse<String> = withContext(Dispatchers.IO) {
        val b = HttpRequest.newBuilder(URI("$baseUrl$path")).timeout(Duration.ofSeconds(10))
            .header("Authorization", "Bearer ${auth.token}").header("Content-Type", "application/json")
        auth.send(client, if (method == "GET") b.GET().build() else b.method(method, HttpRequest.BodyPublishers.ofString(body ?: "{}")).build())
    }

    /** 204 is done, 404 is "no such app"; anything else is an error. */
    private suspend fun decide(method: String, path: String, body: String? = null): Boolean {
        val r = call(method, path, body)
        return when (r.statusCode()) {
            204 -> true
            404 -> false
            else -> throw EngineControlException(r.statusCode(), Regex("\"message\"\\s*:\\s*\"([^\"]*)\"").find(r.body())?.groupValues?.get(1) ?: "Engine answered ${r.statusCode()}")
        }
    }

    private fun path(id: String, tail: String) = "/v1/admin/apps/${URLEncoder.encode(id, Charsets.UTF_8)}/$tail"

    override suspend fun list(): List<AppApprovalRecord> {
        val r = call("GET", "/v1/admin/apps")
        if (r.statusCode() != 200) throw EngineControlException(r.statusCode(), "Engine answered ${r.statusCode()}")
        return json.decodeFromString(ListSerializer(AppApprovalRecord.serializer()), r.body())
    }

    override suspend fun approve(id: String) = decide("POST", path(id, "approve"))
    override suspend fun deny(id: String) = decide("POST", path(id, "deny"))
    override suspend fun revoke(id: String) = decide("POST", path(id, "revoke"))
    override suspend fun undoDeny(id: String) = decide("POST", path(id, "undo-deny"))
    override suspend fun setAgentAllowed(id: String, allowed: Boolean) = decide("PUT", path(id, "agent-allowed"), json.encodeToString(AppAgentAllowedRequest(allowed)))
    override suspend fun setLocalityLimit(id: String, limit: String?) = decide("PUT", path(id, "locality-limit"), json.encodeToString(AppLocalityLimitRequest(limit)))
}
