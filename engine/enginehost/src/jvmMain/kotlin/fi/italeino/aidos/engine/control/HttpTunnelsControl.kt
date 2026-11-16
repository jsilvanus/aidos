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

/** [TunnelsControl] over a running Engine's loopback admin API (`/v1/admin/tunnels`). */
class HttpTunnelsControl(
    private val baseUrl: String,
    token: String,
    private val client: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build(),
    reloadToken: () -> String? = { null },
) : TunnelsControl {
    private val auth = AdminAuth(token, reloadToken)
    private val json = Json { ignoreUnknownKeys = true }

    private suspend fun call(method: String, path: String, body: String? = null): String = withContext(Dispatchers.IO) {
        val b = HttpRequest.newBuilder(URI("$baseUrl$path")).timeout(Duration.ofSeconds(30))
            .header("Authorization", "Bearer ${auth.token}").header("Content-Type", "application/json")
        val req = when (method) {
            "GET" -> b.GET()
            "DELETE" -> b.DELETE()
            else -> b.method(method, HttpRequest.BodyPublishers.ofString(body ?: "{}"))
        }.build()
        val r = auth.send(client, req)
        if (r.statusCode() !in 200..299) {
            val msg = Regex("\"message\"\\s*:\\s*\"([^\"]*)\"").find(r.body())?.groupValues?.get(1)
            throw EngineControlException(r.statusCode(), msg ?: "Engine answered ${r.statusCode()}")
        }
        r.body()
    }

    private fun path(id: String, action: String = "") = "/v1/admin/tunnels/${URLEncoder.encode(id, Charsets.UTF_8)}$action"

    override suspend fun list(): TunnelsStatus = json.decodeFromString(call("GET", "/v1/admin/tunnels"))
    override suspend fun create(request: CreateTunnelRequest): String =
        json.decodeFromString<ControlValue>(call("POST", "/v1/admin/tunnels", json.encodeToString(request))).value
    override suspend fun probeHostKey(id: String): String = json.decodeFromString<ControlValue>(call("POST", path(id, "/probe"))).value
    override suspend fun confirmHostKey(id: String) { call("POST", path(id, "/confirm")) }
    override suspend fun start(id: String) { call("POST", path(id, "/start")) }
    override suspend fun stop(id: String) { call("POST", path(id, "/stop")) }
    override suspend fun rotateKey(id: String): String = json.decodeFromString<ControlValue>(call("POST", path(id, "/rotate-key"))).value
    override suspend fun delete(id: String) { call("DELETE", path(id)) }
}
