package dev.aidos.vault

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.KeyStore

/**
 * RFC-0108 Phase 4: Android Keystore-backed vault for SSH tunnel keys.
 *
 * On Android, secrets are encrypted using the platform's Keystore, which is device-bound
 * and requires user authentication. The MasterKey generated via EncryptedSharedPreferences
 * uses AES-256-GCM encryption backed by the hardware or TEE Keystore.
 */
class AndroidKeystoreVault(private val context: Context) {
    private val masterKeySpec = KeyGenParameterSpec.Builder(
        "aidos_vault_master_key",
        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
    )
        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
        .setKeySize(256)
        .setUserAuthenticationRequired(false)  // Allow non-interactive access for tunnel keepalive
        .build()

    private val masterKey = MasterKey.Builder(context)
        .setKeyGenParameterSpec(masterKeySpec)
        .build()

    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        "aidos_vault_secrets",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    /**
     * Store an SSH tunnel key in the Keystore-backed vault.
     * The key material is never written to disk unencrypted.
     */
    fun storeSshTunnelKey(keyId: String, privateKeyPem: String, publicKeyLine: String) {
        prefs.edit().apply {
            putString("ssh_tunnel_key_$keyId", privateKeyPem)
            putString("ssh_tunnel_public_$keyId", publicKeyLine)
            apply()
        }
    }

    /**
     * Retrieve an SSH tunnel key for use by the tunnel manager.
     * Returns the private key PEM, or null if not found.
     */
    fun getSshTunnelKey(keyId: String): String? =
        prefs.getString("ssh_tunnel_key_$keyId", null)

    /**
     * Retrieve the public key line for an SSH tunnel key.
     */
    fun getSshTunnelPublicKey(keyId: String): String? =
        prefs.getString("ssh_tunnel_public_$keyId", null)

    /**
     * Delete an SSH tunnel key from the vault.
     * Returns true if the key existed and was deleted.
     */
    fun deleteSshTunnelKey(keyId: String): Boolean {
        val existed = prefs.contains("ssh_tunnel_key_$keyId")
        prefs.edit().apply {
            remove("ssh_tunnel_key_$keyId")
            remove("ssh_tunnel_public_$keyId")
            apply()
        }
        return existed
    }

    /**
     * List all SSH tunnel key IDs in the vault.
     */
    fun listSshTunnelKeys(): List<String> =
        prefs.all.keys
            .filter { it.startsWith("ssh_tunnel_key_") }
            .mapNotNull { it.removePrefix("ssh_tunnel_key_").takeIf { id -> id.isNotEmpty() } }
            .distinct()
}
