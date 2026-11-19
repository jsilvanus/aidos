package fi.italeino.aidos.engine.vault

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import dev.aidos.vault.KeyProtection
import dev.aidos.vault.VaultKeyProvider
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * RFC-0035 "Android Keystore, hardware-backed where available": the vault key of the Android host.
 *
 * A non-exportable AES-256-GCM key under [keyAlias]. The returned [SecretKey] is a handle; the key
 * material stays in the Keystore (TEE or StrongBox where the device has one) and every encryption
 * runs there. Randomized encryption is required, so the Keystore chooses each IV, which is what
 * [dev.aidos.vault.ValueSealer] expects. No user authentication is required: the foreground service
 * resolves tokens with the screen off (model downloads, provider calls).
 */
class AndroidKeystoreKeyProvider(private val keyAlias: String = DEFAULT_KEY_ALIAS) : VaultKeyProvider {

    init {
        require(keyAlias.isNotBlank()) { "keyAlias must not be blank" }
    }

    override val protection: KeyProtection = KeyProtection.PLATFORM_KEYSTORE

    override fun loadOrCreate(): SecretKey = synchronized(LOCK) {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        if (keyStore.containsAlias(keyAlias)) {
            return (keyStore.getKey(keyAlias, null) as? SecretKey)
                ?: throw GeneralSecurityException("Keystore alias '$keyAlias' does not hold a secret key")
        }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(keyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .setUserAuthenticationRequired(false)
                .build(),
        )
        return generator.generateKey()
    }

    companion object {
        const val DEFAULT_KEY_ALIAS = "aidos_vault_master_key"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private val LOCK = Any()
    }
}
