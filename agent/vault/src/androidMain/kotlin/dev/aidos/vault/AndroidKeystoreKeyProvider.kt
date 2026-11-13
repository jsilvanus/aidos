package dev.aidos.vault

/**
 * RFC-0108 Phase 4: Keystore-backed key provider for Android vault.
 *
 * Implements VaultKeyProvider using Android's hardware-backed or TEE Keystore.
 * The encryption key never leaves the Keystore and is never available as raw bytes.
 */
class AndroidKeystoreKeyProvider : VaultKeyProvider {
    override val protection: KeyProtection = KeyProtection.PLATFORM_KEYSTORE

    /**
     * Generate a new AES-256 key in the Android Keystore.
     * This is called during vault initialization to set up the master key.
     * The key is device-bound and not exportable.
     */
    fun generateKeystoreKey(): ByteArray {
        // In practice, this is done automatically by MasterKey.Builder() in AndroidKeystoreVault.
        // This placeholder documents the pattern for future key rotation or setup.
        throw NotImplementedError("Key generation is handled by AndroidKeystoreVault")
    }

    /**
     * Decrypt a vault value using the Keystore-backed key.
     * The actual decryption is delegated to EncryptedSharedPreferences,
     * which uses the MasterKey stored in the Android Keystore.
     */
    fun decryptValue(encryptedBytes: ByteArray): ByteArray {
        throw NotImplementedError("Decryption is handled by EncryptedSharedPreferences")
    }

    /**
     * Encrypt a vault value using the Keystore-backed key.
     * The actual encryption is delegated to EncryptedSharedPreferences.
     */
    fun encryptValue(plainBytes: ByteArray): ByteArray {
        throw NotImplementedError("Encryption is handled by EncryptedSharedPreferences")
    }
}
