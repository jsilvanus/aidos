package fi.italeino.aidos.engine.control

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** Raised when the Engine service answers an admin call with an error. */
class EngineControlException(val httpStatus: Int, message: String) : Exception(message)

/**
 * [LanControl] over a running Engine's loopback admin API (`/v1/admin/lan`, RFC-0115 D58), authenticated with
 * the local token from `engine.json`. [baseUrl] is the loopback address; this never talks to anything else.
 */
class HttpLanControl(
    private val baseUrl: String,
    private val token: String,
    private val client: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build(),
) : LanControl {
    private val json = Json { ignoreUnknownKeys = true }

    private suspend fun call(method: String, path: String, body: String? = null): String = withContext(Dispatchers.IO) {
        val b = HttpRequest.newBuilder(URI("$baseUrl$path"))
            .timeout(Duration.ofSeconds(20))
            .header("Authorization", "Bearer $token")
            .header("Content-Type", "application/json")
        val req = when (method) {
            "GET" -> b.GET()
            "DELETE" -> b.DELETE()
            else -> b.method(method, HttpRequest.BodyPublishers.ofString(body ?: "{}"))
        }.build()
        val r = client.send(req, HttpResponse.BodyHandlers.ofString())
        if (r.statusCode() !in 200..299) throw EngineControlException(r.statusCode(), errorMessage(r.body()) ?: "Engine answered ${r.statusCode()}")
        r.body()
    }

    private fun errorMessage(body: String) = Regex("\"message\"\\s*:\\s*\"([^\"]*)\"").find(body)?.groupValues?.get(1)

    override suspend fun status(): LanStatus = json.decodeFromString(call("GET", "/v1/admin/lan"))

    override suspend fun setEnabled(enabled: Boolean, port: Int?): LanStatus =
        json.decodeFromString(call("PUT", "/v1/admin/lan", json.encodeToString(LanEnableRequest(enabled, port))))

    override suspend fun startCodePairing(label: String, lifetimeSeconds: Long): String =
        json.decodeFromString<LanPairingLink>(call("POST", "/v1/admin/lan/pairing-code", json.encodeToString(LanPairingRequest(label, lifetimeSeconds)))).link

    override suspend fun addManually(label: String, lifetimeSeconds: Long): LanManualPairing =
        json.decodeFromString(call("POST", "/v1/admin/lan/devices", json.encodeToString(LanPairingRequest(label, lifetimeSeconds))))

    override suspend fun revoke(deviceId: String) {
        call("DELETE", "/v1/admin/lan/devices/${java.net.URLEncoder.encode(deviceId, Charsets.UTF_8)}")
    }
}
