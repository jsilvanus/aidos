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
    private val token: String,
    private val client: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build(),
) : StatusControl {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun status(): EngineStatusInfo = withContext(Dispatchers.IO) {
        val r = client.send(
            HttpRequest.newBuilder(URI("$baseUrl/v1/admin/status")).timeout(Duration.ofSeconds(10)).header("Authorization", "Bearer $token").GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        if (r.statusCode() != 200) throw EngineControlException(r.statusCode(), "Engine answered ${r.statusCode()}")
        json.decodeFromString(r.body())
    }
}
