package dev.aidos.tunnel

import dev.aidos.vault.SecretRequest
import dev.aidos.vault.SecretsVault
import fi.italeino.sshtunnel.KeyProvider
import kotlinx.coroutines.CancellationException
import java.nio.ByteBuffer
import java.nio.CharBuffer

/**
 * RFC-0108 Phase 3: Vault-backed [KeyProvider] for tunnel management.
 * The vault holds the private key encrypted. This provider asks the vault for it
 * on every connection attempt, never stores it, and zeros intermediate key buffers
 * immediately after producing the OpenSSH key bytes for [fi.italeino.sshtunnel.SshTunnel].
 */
class VaultKeyProvider(
    private val vault: SecretsVault,
    private val keyId: String,
    private val request: SecretRequest? = null,
) : KeyProvider {
    override suspend fun privateKey(): ByteArray? {
        if (keyId.isBlank()) return null

        val resolved = try {
            val targetId = if (vault.get(keyId).isSuccess) {
                keyId
            } else {
                vault.getByName(keyId).getOrNull()?.id ?: keyId
            }
            if (request != null) {
                vault.resolveFor(targetId, request)
            } else {
                vault.resolve(targetId)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return null
        }

        val chars = resolved.getOrNull() ?: return null
        return try {
            charsToUtf8Bytes(chars)?.takeIf { it.isNotEmpty() }
        } finally {
            chars.fill('\u0000')
        }
    }
}

internal fun charsToUtf8Bytes(chars: CharArray): ByteArray? {
    if (chars.isEmpty()) return null
    val charBuf = CharBuffer.wrap(chars)
    val byteBuf = Charsets.UTF_8.encode(charBuf)
    return try {
        val bytes = ByteArray(byteBuf.remaining())
        byteBuf.get(bytes)
        bytes
    } finally {
        if (byteBuf.hasArray()) {
            byteBuf.array().fill(0)
        }
    }
}

internal fun utf8BytesToChars(bytes: ByteArray): CharArray {
    if (bytes.isEmpty()) return CharArray(0)
    val byteBuf = ByteBuffer.wrap(bytes)
    val charBuf = Charsets.UTF_8.decode(byteBuf)
    return try {
        val chars = CharArray(charBuf.remaining())
        charBuf.get(chars)
        chars
    } finally {
        if (charBuf.hasArray()) {
            charBuf.array().fill('\u0000')
        }
    }
}
