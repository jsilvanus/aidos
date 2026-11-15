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

/** [PeersControl] over a running Engine's loopback admin API (`/v1/admin/peers`). */
class HttpPeersControl(
    private val baseUrl: String,
    private val token: String,
    private val client: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build(),
) : PeersControl {
    private val json = Json { ignoreUnknownKeys = true }

    private suspend fun call(method: String, path: String, body: String? = null): String = withContext(Dispatchers.IO) {
        val b = HttpRequest.newBuilder(URI("$baseUrl$path")).timeout(Duration.ofSeconds(20))
            .header("Authorization", "Bearer $token").header("Content-Type", "application/json")
        val req = if (method == "GET") b.GET().build() else b.method(method, HttpRequest.BodyPublishers.ofString(body ?: "{}")).build()
        val r = client.send(req, HttpResponse.BodyHandlers.ofString())
        if (r.statusCode() !in 200..299) {
            val msg = Regex("\"message\"\\s*:\\s*\"([^\"]*)\"").find(r.body())?.groupValues?.get(1)
            throw EngineControlException(r.statusCode(), msg ?: "Engine answered ${r.statusCode()}")
        }
        r.body()
    }

    private fun path(id: String, tail: String) = "/v1/admin/peers/${URLEncoder.encode(id, Charsets.UTF_8)}/$tail"

    override suspend fun status(): PeersStatus = json.decodeFromString(call("GET", "/v1/admin/peers"))
    override suspend fun setHostsAgentLoops(peerId: String, enabled: Boolean) { call("PUT", path(peerId, "hosts-agent-loops"), json.encodeToString(PeerFlagRequest(enabled))) }
    override suspend fun setPreferred(peerId: String, preferred: Boolean) { call("PUT", path(peerId, "preferred"), json.encodeToString(PeerFlagRequest(preferred))) }
    override suspend fun setRemoteAccess(peerId: String, allowed: Boolean) { call("PUT", path(peerId, "remote-access"), json.encodeToString(PeerFlagRequest(allowed))) }
    override suspend fun addHint(peerId: String, host: String, port: Int) { call("POST", path(peerId, "hints"), json.encodeToString(PeerHintRequest(host, port))) }
    override suspend fun removeHint(peerId: String, hint: String) { call("POST", path(peerId, "hints/remove"), json.encodeToString(PeerHintRemoveRequest(hint))) }
    override suspend fun setToolAllowlist(peerId: String, app: String, tools: List<String>) {
        call("PUT", path(peerId, "tool-allowlist"), json.encodeToString(PeerToolAllowlistRequest(app, tools)))
    }
}
