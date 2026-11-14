package dev.aidos.vault

import android.content.Context
import android.content.SharedPreferences
import java.security.GeneralSecurityException
import java.util.Base64

/**
 * RFC-0108 Phase 4: Encrypted Android storage for SSH reverse-tunnel keys backed by
 * [AndroidKeystoreKeyProvider] (`AES/GCM/NoPadding`, randomized 12-byte IV, 128-bit GCM tag,
 * and preference-key AAD binding).
 *
 * Fails closed with [GeneralSecurityException] on corrupt, tampered, or unencrypted legacy entries.
 */
class AndroidKeystoreVault @JvmOverloads constructor(
    context: Context,
    private val keyProvider: AndroidKeystoreKeyProvider = AndroidKeystoreKeyProvider(),
) {
    private val prefs: SharedPreferences =
        (context.applicationContext ?: context).getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val prefsLock = Any()

    val protection: KeyProtection
        get() = keyProvider.protection

    fun storeSshTunnelKey(keyId: String, privateKeyPem: String, publicKeyLine: String) {
        validateKeyId(keyId)
        val privPrefKey = PRIVATE_KEY_PREFIX + keyId
        val pubPrefKey = PUBLIC_KEY_PREFIX + keyId

        val encryptedPriv = encryptString(privPrefKey, privateKeyPem)
        val encryptedPub = encryptString(pubPrefKey, publicKeyLine)

        synchronized(prefsLock) {
            val committed = prefs.edit()
                .putString(privPrefKey, encryptedPriv)
                .putString(pubPrefKey, encryptedPub)
                .commit()
            if (!committed) {
                throw GeneralSecurityException("Failed to persist encrypted vault entry for '$keyId'")
            }
        }
    }

    fun getSshTunnelKey(keyId: String): String? {
        validateKeyId(keyId)
        val prefKey = PRIVATE_KEY_PREFIX + keyId
        synchronized(prefsLock) {
            return readDecryptedString(prefKey)
        }
    }

    fun getSshTunnelPublicKey(keyId: String): String? {
        validateKeyId(keyId)
        val prefKey = PUBLIC_KEY_PREFIX + keyId
        synchronized(prefsLock) {
            return readDecryptedString(prefKey)
        }
    }

    fun deleteSshTunnelKey(keyId: String): Boolean {
        validateKeyId(keyId)
        val privPrefKey = PRIVATE_KEY_PREFIX + keyId
        val pubPrefKey = PUBLIC_KEY_PREFIX + keyId
        synchronized(prefsLock) {
            val existed = prefs.contains(privPrefKey) || prefs.contains(pubPrefKey)
            if (!existed) {
                return false
            }
            val committed = prefs.edit()
                .remove(privPrefKey)
                .remove(pubPrefKey)
                .commit()
            if (!committed) {
                throw GeneralSecurityException("Failed to delete encrypted vault entry for '$keyId'")
            }
            return true
        }
    }

    fun listSshTunnelKeys(): List<String> = synchronized(prefsLock) {
        prefs.all.keys
            .asSequence()
            .filter { it.startsWith(PRIVATE_KEY_PREFIX) }
            .map { it.removePrefix(PRIVATE_KEY_PREFIX) }
            .filter { isValidKeyId(it) }
            .distinct()
            .sorted()
            .toList()
    }

    private fun encryptString(prefKey: String, plaintext: String): String {
        val plainBytes = plaintext.toByteArray(Charsets.UTF_8)
        val cipherBytes = try {
            keyProvider.encryptValue(
                plainBytes = plainBytes,
                associatedData = prefKey.toByteArray(Charsets.UTF_8),
            )
        } finally {
            plainBytes.fill(0)
        }
        return Base64.getEncoder().encodeToString(cipherBytes)
    }

    private fun readDecryptedString(prefKey: String): String? {
        val raw = prefs.all[prefKey] ?: return null
        val encoded = raw as? String
            ?: throw GeneralSecurityException("Corrupt vault entry type for '$prefKey'")
        val cipherBytes = try {
            Base64.getDecoder().decode(encoded)
        } catch (e: IllegalArgumentException) {
            throw GeneralSecurityException("Corrupt Base64 vault entry for '$prefKey'", e)
        }
        val plainBytes = keyProvider.decryptValue(
            encryptedBytes = cipherBytes,
            associatedData = prefKey.toByteArray(Charsets.UTF_8),
        )
        return try {
            String(plainBytes, Charsets.UTF_8)
        } finally {
            plainBytes.fill(0)
        }
    }

    companion object {
        const val PREFS_NAME = "aidos_vault_secrets"
        private const val PRIVATE_KEY_PREFIX = "ssh_tunnel_key_"
        private const val PUBLIC_KEY_PREFIX = "ssh_tunnel_public_"
        const val MAX_KEY_ID_LENGTH = 128
        private val VALID_KEY_ID = Regex("^[A-Za-z0-9._-]+$")

        fun isValidKeyId(keyId: String): Boolean =
            keyId.isNotBlank() &&
                keyId.length <= MAX_KEY_ID_LENGTH &&
                keyId != "." &&
                keyId != ".." &&
                VALID_KEY_ID.matches(keyId)

        private fun validateKeyId(keyId: String) {
            require(isValidKeyId(keyId)) {
                "Invalid SSH tunnel keyId: must be 1..$MAX_KEY_ID_LENGTH chars matching [A-Za-z0-9._-]"
            }
        }
    }
}
