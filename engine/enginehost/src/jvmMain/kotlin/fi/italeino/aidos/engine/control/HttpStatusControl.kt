package fi.italeino.aidos.engine.control

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** [StatusControl] over a running Engine's loopback admin API (`/v1/admin/status`). */
class HttpStatusControl(
    private val baseUrl: String,
    token: String,
    private val client: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build(),
    reloadToken: () -> String? = { null },
) : StatusControl {
    private val auth = AdminAuth(token, reloadToken)
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun status(): EngineStatusInfo = withContext(Dispatchers.IO) {
        val r = auth.send(
            client,
            HttpRequest.newBuilder(URI("$baseUrl/v1/admin/status")).timeout(Duration.ofSeconds(10)).header("Authorization", "Bearer ${auth.token}").GET().build(),
        )
        if (r.statusCode() != 200) throw EngineControlException(r.statusCode(), "Engine answered ${r.statusCode()}")
        json.decodeFromString(r.body())
    }
}
