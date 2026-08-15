package fi.italeino.aidos.sdk

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * Manages Aidos Engine bearer tokens (RFC-0103).
 *
 * Tokens are short-lived and scoped to one handshake session. The manager:
 * - Caches tokens from successful handshakes
 * - Tracks expiration and validity
 * - Detects stale tokens (e.g., Engine restart)
 * - Triggers re-handshake on expiration
 *
 * RFC-0103: "Tokens are short-lived and scoped to one handshake. A client
 * reconnecting after an Engine restart re-handshakes; it does not reuse a stale token."
 */
class EngineTokenManager {
    private var cachedToken: String? = null
    private var tokenIssuedAt: Instant? = null
    private val tokenLifetime: Duration = 15.minutes  // Conservative default; Engine's actual TTL may differ

    /**
     * Store a new token from a successful handshake.
     *
     * Replaces any previous token. The token's actual lifetime is not known
     * to the SDK (Engine doesn't communicate it), so we use a conservative
     * default and rely on HTTP 401 to signal expiration.
     */
    fun store(token: String) {
        this.cachedToken = token
        this.tokenIssuedAt = Clock.System.now()
    }

    /**
     * Retrieve the cached token if it's still (likely) valid.
     *
     * Returns null if:
     * - No token has been stored yet
     * - Token lifetime has exceeded the estimated expiration
     *
     * HTTP callers should still check for 401 responses and trigger
     * re-handshake if received.
     */
    fun getToken(): String? {
        val token = cachedToken
        val issuedAt = tokenIssuedAt
        if (token == null || issuedAt == null) return null

        val age = Clock.System.now() - issuedAt
        return if (age < tokenLifetime) token else null
    }

    /**
     * Invalidate the cached token (e.g., after receiving HTTP 401).
     *
     * Signals that the token is no longer valid. The next call to
     * [getToken] will return null, forcing a re-handshake.
     */
    fun invalidate() {
        cachedToken = null
        tokenIssuedAt = null
    }

    /**
     * Clear all cached tokens (e.g., on app shutdown).
     */
    fun clear() {
        cachedToken = null
        tokenIssuedAt = null
    }

    /**
     * Check if a cached token is currently available and valid.
     */
    fun hasValidToken(): Boolean = getToken() != null
}
