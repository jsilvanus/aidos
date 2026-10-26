package dev.aidos.amp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Functional interface for HTTP transport to enable seamless unit testing without live sockets.
 */
fun interface HttpTransport {
    suspend fun postJson(url: String, headers: Map<String, String>, bodyJson: String): String
}

/**
 * Default JVM HTTP transport using standard java.net.http.HttpClient.
 */
class DefaultJavaHttpTransport(
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build()
) : HttpTransport {
    override suspend fun postJson(url: String, headers: Map<String, String>, bodyJson: String): String =
        withContext(Dispatchers.IO) {
            val reqBuilder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(bodyJson))
                .timeout(Duration.ofSeconds(45))

            headers.forEach { (k, v) -> reqBuilder.header(k, v) }

            val response = httpClient.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() !in 200..299) {
                throw IllegalStateException("HTTP ${response.statusCode()}: ${response.body()}")
            }
            response.body()
        }
}
