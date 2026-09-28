package fi.italeino.aidos.engine.http

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Unit tests for TokenManager (RFC-0103).
 * Tests token generation, validation, and expiration.
 */
class TokenManagerTest {

    @Test
    fun generateNewToken_createsValidToken() {
        val manager = TokenManager()
        val token = manager.generateNewToken()

        assertNotNull(token.token)
        assertEquals(64, token.token.length)  // 32 bytes = 64 hex chars
        assertNotNull(token.issuedAt)
        assertNotNull(token.expiresAt)
    }

    @Test
    fun validateToken_acceptsValidToken() {
        val manager = TokenManager()
        val generated = manager.generateNewToken()

        val validated = manager.validateToken(generated.token)
        assertEquals(generated.token, validated)
    }

    @Test
    fun validateToken_rejectsNullToken() {
        val manager = TokenManager()
        manager.generateNewToken()

        assertNull(manager.validateToken(null))
    }

    @Test
    fun validateToken_rejectsWrongToken() {
        val manager = TokenManager()
        manager.generateNewToken()

        assertNull(manager.validateToken("wrong_token_string"))
    }

    @Test
    fun validateToken_rejectsTokenBeforeGeneration() {
        val manager = TokenManager()

        assertNull(manager.validateToken("any_token"))
    }

    @Test
    fun currentValidToken_returnsTokenBeforeExpiry() {
        val manager = TokenManager()
        val generated = manager.generateNewToken()

        val current = manager.currentValidToken()
        assertEquals(generated.token, current)
    }

    @Test
    fun clearTokens_invalidatesAllTokens() {
        val manager = TokenManager()
        manager.generateNewToken()

        manager.clearTokens()

        val current = manager.currentValidToken()
        assertNull(current)
    }
}
