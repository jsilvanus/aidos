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

/** [VaultControl] over a running Engine's loopback admin API (`/v1/admin/vault`). */
class HttpVaultControl(
    private val baseUrl: String,
    token: String,
    private val client: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build(),
    reloadToken: () -> String? = { null },
) : VaultControl {
    private val auth = AdminAuth(token, reloadToken)
    private val json = Json { ignoreUnknownKeys = true }

    private suspend fun call(method: String, path: String, body: String? = null): String = withContext(Dispatchers.IO) {
        val b = HttpRequest.newBuilder(URI("$baseUrl$path")).timeout(Duration.ofSeconds(30))
            .header("Authorization", "Bearer ${auth.token}").header("Content-Type", "application/json")
        val r = auth.send(client, if (method == "GET") b.GET().build() else b.method(method, HttpRequest.BodyPublishers.ofString(body ?: "{}")).build())
        if (r.statusCode() !in 200..299) {
            val msg = Regex("\"message\"\\s*:\\s*\"([^\"]*)\"").find(r.body())?.groupValues?.get(1)
            throw EngineControlException(r.statusCode(), msg ?: "Engine answered ${r.statusCode()}")
        }
        r.body()
    }

    override suspend fun status(): VaultStatus = json.decodeFromString(call("GET", "/v1/admin/vault"))

    override suspend fun unlock(passphrase: CharArray) {
        call("POST", "/v1/admin/vault/unlock", json.encodeToString(VaultUnlockRequest(String(passphrase))))
    }

    override suspend fun lock() { call("POST", "/v1/admin/vault/lock") }
}
