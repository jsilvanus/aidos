package fi.italeino.aidos.engine.http

import org.apache.commons.codec.binary.Hex
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages token generation, validation, and lifecycle for HTTP authentication (RFC-0103).
 *
 * Tokens are ephemeral bearer credentials returned by the handshake Binder call.
 * Each token is:
 * - 32 bytes of cryptographically random data (256 bits)
 * - Hex-encoded for transmission
 * - Associated with a handshake timestamp and expiration
 *
 * There is one live token **per subject** (the calling app's package name, or [ENGINE_SUBJECT]
 * for Engine's own in-process callers). A new handshake by an app replaces only that app's
 * previous token — otherwise two approved apps (say Dictator and Aidos Agent) would each
 * invalidate the other's session on every handshake, and each client's 401 → re-handshake retry
 * would evict the other in turn.
 *
 * A token says who it was issued to, not that they are still allowed: whether an app is still
 * approved is the approval store's call, made per request by `EngineHttpServer`'s approval check
 * so a revocation takes effect immediately rather than when the token expires.
 */
class TokenManager {
    private val random = SecureRandom()
    private val tokens = ConcurrentHashMap<String, TokenInfo>()

    data class TokenInfo(
        val token: String,
        val issuedAt: Instant,
        val expiresAt: Instant,
        val subject: String = ENGINE_SUBJECT
    )

    /**
     * Generate a new bearer token for [subject]. Called once per handshake.
     * That subject's previous token is invalidated; other subjects' tokens are untouched.
     */
    fun generateNewToken(
        validityDurationSeconds: Long = 86400,
        subject: String = ENGINE_SUBJECT
    ): TokenInfo {
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        val token = Hex.encodeHexString(bytes)
        val now = Instant.now()
        val info = TokenInfo(token, now, now.plusSeconds(validityDurationSeconds), subject)
        tokens[subject] = info
        return info
    }

    /**
     * Validate a bearer token from an HTTP Authorization header.
     * Returns the token if valid; null if invalid, expired, or no token issued yet.
     */
    fun validateToken(bearerToken: String?): String? = authenticate(bearerToken)?.token

    /**
     * Like [validateToken] but returns the whole [TokenInfo], including who the token was issued
     * to. Expired tokens are dropped as a side effect.
     */
    fun authenticate(bearerToken: String?): TokenInfo? {
        if (bearerToken == null) return null
        val now = Instant.now()
        for ((subject, info) in tokens) {
            if (now.isAfter(info.expiresAt)) {
                tokens.remove(subject, info)
                continue
            }
            if (MessageDigest.isEqual(bearerToken.toByteArray(), info.token.toByteArray())) return info
        }
        return null
    }

    /**
     * Get [subject]'s current valid token, or null if none issued (or it has expired).
     */
    fun currentValidToken(subject: String = ENGINE_SUBJECT): String? = tokens[subject]?.takeIf {
        Instant.now().isBefore(it.expiresAt)
    }?.token

    /** Drop [subject]'s token (e.g. its approval was revoked). */
    fun revoke(subject: String) {
        tokens.remove(subject)
    }

    /**
     * Clear all tokens (used during shutdown).
     */
    fun clearTokens() {
        tokens.clear()
    }

    companion object {
        /** Subject for Engine's own in-process callers (the Test Chat screen), which need no approval. */
        const val ENGINE_SUBJECT = "\u0000engine"
    }
}
