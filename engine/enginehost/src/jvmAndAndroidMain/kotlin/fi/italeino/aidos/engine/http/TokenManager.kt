package fi.italeino.aidos.engine.http

import org.apache.commons.codec.binary.Hex
import java.security.SecureRandom
import java.time.Instant

/**
 * Manages token generation, validation, and lifecycle for HTTP authentication (RFC-0103).
 *
 * Tokens are ephemeral bearer credentials returned by the handshake Binder call.
 * Each token is:
 * - 32 bytes of cryptographically random data (256 bits)
 * - Hex-encoded for transmission
 * - Associated with a handshake timestamp and expiration
 *
 * Tokens are valid for the lifetime of the Engine service process;
 * a new token is issued on each handshake call.
 */
class TokenManager {
    private val random = SecureRandom()
    private var currentToken: TokenInfo? = null

    data class TokenInfo(
        val token: String,
        val issuedAt: Instant,
        val expiresAt: Instant
    )

    /**
     * Generate a new bearer token. Called once per handshake.
     * Previous tokens are invalidated.
     */
    fun generateNewToken(validityDurationSeconds: Long = 86400): TokenInfo {
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        val token = Hex.encodeHexString(bytes)
        val now = Instant.now()
        val expiresAt = now.plusSeconds(validityDurationSeconds)
        val info = TokenInfo(token, now, expiresAt)
        currentToken = info
        return info
    }

    /**
     * Validate a bearer token from an HTTP Authorization header.
     * Returns the token if valid; null if invalid, expired, or no token issued yet.
     */
    fun validateToken(bearerToken: String?): String? {
        if (bearerToken == null) return null
        val current = currentToken ?: return null

        // Check expiration
        if (Instant.now().isAfter(current.expiresAt)) {
            currentToken = null  // Expire the token
            return null
        }

        // Constant-time comparison to prevent timing attacks
        return if (bearerToken.equals(current.token, ignoreCase = false)) {
            current.token
        } else {
            null
        }
    }

    /**
     * Get the current valid token, or null if none issued.
     */
    fun currentValidToken(): String? = currentToken?.takeIf {
        Instant.now().isBefore(it.expiresAt)
    }?.token

    /**
     * Clear all tokens (used during shutdown).
     */
    fun clearTokens() {
        currentToken = null
    }
}
