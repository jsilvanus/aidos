package fi.italeino.aidos.engine.control

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** [ApprovalsControl] over a running Engine's loopback admin API (`/v1/admin/approvals`). */
class HttpApprovalsControl(
    private val baseUrl: String,
    token: String,
    private val client: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build(),
    reloadToken: () -> String? = { null },
) : ApprovalsControl {
    private val auth = AdminAuth(token, reloadToken)
    private val json = Json { ignoreUnknownKeys = true }

    private suspend fun send(req: HttpRequest.Builder): HttpResponse<String> = withContext(Dispatchers.IO) {
        auth.send(client, req.timeout(Duration.ofSeconds(10)).header("Authorization", "Bearer ${auth.token}").build())
    }

    override suspend fun pending(): List<PendingApproval> {
        val r = send(HttpRequest.newBuilder(URI("$baseUrl/v1/admin/approvals")).GET())
        if (r.statusCode() != 200) throw EngineControlException(r.statusCode(), "Engine answered ${r.statusCode()}")
        return json.decodeFromString(ListSerializer(PendingApproval.serializer()), r.body())
    }

    override suspend fun decide(id: String, allow: Boolean): Boolean {
        val body = json.encodeToString(ApprovalDecision(allow))
        val r = send(
            HttpRequest.newBuilder(URI("$baseUrl/v1/admin/approvals/" + java.net.URLEncoder.encode(id, Charsets.UTF_8)))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)),
        )
        return when (r.statusCode()) {
            204 -> true
            404 -> false
            else -> throw EngineControlException(r.statusCode(), "Engine answered ${r.statusCode()}")
        }
    }
}
