package fi.italeino.aidos.engine.control

import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * The admin token a client presents to a running Engine, and what to do when the Engine says it is stale
 * (RFC-0115: the token is minted at every Engine start, so a restart invalidates the one a client holds).
 *
 * [reload] re-reads `engine.json`; it is called only after a 401, and the request is retried once, and only when the
 * file holds a different token. That way a service restart needs no UI restart, and a genuinely wrong token still
 * fails fast instead of looping.
 */
class AdminAuth(token: String, private val reload: () -> String? = { null }) {
    @Volatile
    var token: String = token
        private set

    /** Sends [request] (built with this auth's current token); on a 401, re-reads the token and retries once. */
    fun send(client: HttpClient, request: HttpRequest): HttpResponse<String> {
        val handler = HttpResponse.BodyHandlers.ofString()
        val first = client.send(request, handler)
        if (first.statusCode() != 401) return first
        val stale = request.headers().firstValue("Authorization").orElse("").removePrefix("Bearer ")
        val fresh = runCatching { reload() }.getOrNull()
        if (fresh == null || fresh == stale) return first
        token = fresh
        val retry = HttpRequest.newBuilder(request) { name, _ -> !name.equals("Authorization", ignoreCase = true) }
            .header("Authorization", "Bearer $fresh")
            .build()
        return client.send(retry, handler)
    }
}
