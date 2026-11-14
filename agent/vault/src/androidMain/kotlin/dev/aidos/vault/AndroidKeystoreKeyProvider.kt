package dev.aidos.vault

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * RFC-0108 Phase 4: Android Keystore-backed [VaultKeyProvider] using non-exportable AES-256-GCM keys.
 *
 * Each encryption uses [Cipher] initialized in [Cipher.ENCRYPT_MODE] against the Keystore key with
 * [KeyGenParameterSpec.Builder.setRandomizedEncryptionRequired] enabled so Android Keystore supplies
 * a fresh 12-byte random IV per encryption. Ciphertext wire format:
 * `[version: 1 byte = 0x01] || [iv: 12 bytes] || [ciphertext + 128-bit GCM auth tag]`.
 */
class AndroidKeystoreKeyProvider(
    private val keyAlias: String = DEFAULT_KEY_ALIAS,
) : VaultKeyProvider {

    init {
        require(keyAlias.isNotBlank()) { "keyAlias must not be blank" }
    }

    override val protection: KeyProtection = KeyProtection.PLATFORM_KEYSTORE

    private val keyLock = Any()

    override fun loadOrCreate(): SecretKey = synchronized(keyLock) {
        val keyStore = loadAndroidKeyStore()
        if (keyStore.containsAlias(keyAlias)) {
            return (keyStore.getKey(keyAlias, null) as? SecretKey)
                ?: throw GeneralSecurityException("Keystore alias '$keyAlias' does not hold a SecretKey")
        }
        return generateKeystoreKeyLocked()
    }

    /**
     * Generates (or replaces) the AES-256-GCM master key in the Android Keystore under [keyAlias].
     * The raw key material stays inside Android Keystore hardware/TEE and cannot be exported.
     */
    fun generateKeystoreKey(): SecretKey = synchronized(keyLock) {
        generateKeystoreKeyLocked()
    }

    /**
     * Encrypts [plainBytes] using AES/GCM/NoPadding with a Keystore-generated random 12-byte IV
     * and an optional [associatedData] binding.
     */
    @JvmOverloads
    fun encryptValue(plainBytes: ByteArray, associatedData: ByteArray? = null): ByteArray {
        val key = loadOrCreate()
        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)

        val iv = cipher.iv
            ?: throw GeneralSecurityException("Keystore cipher did not generate an IV")
        if (iv.size != GCM_IV_BYTES) {
            throw GeneralSecurityException("Unexpected GCM IV length: ${iv.size} bytes")
        }
        if (associatedData != null && associatedData.isNotEmpty()) {
            cipher.updateAAD(associatedData)
        }

        val cipherTextAndTag = cipher.doFinal(plainBytes)
        if (cipherTextAndTag.size < GCM_TAG_BYTES) {
            throw GeneralSecurityException(
                "AES-GCM produced truncated output: ${cipherTextAndTag.size} bytes",
            )
        }

        val payload = ByteArray(1 + GCM_IV_BYTES + cipherTextAndTag.size)
        payload[0] = FORMAT_VERSION
        iv.copyInto(payload, destinationOffset = 1)
        cipherTextAndTag.copyInto(payload, destinationOffset = 1 + GCM_IV_BYTES)
        return payload
    }

    /**
     * Decrypts and authenticates an [encryptedBytes] payload produced by [encryptValue], verifying
     * the format version, 12-byte IV, optional [associatedData], and 128-bit GCM authentication tag.
     * Fails closed with [GeneralSecurityException] on malformed, legacy, or tampered payloads.
     */
    @JvmOverloads
    fun decryptValue(encryptedBytes: ByteArray, associatedData: ByteArray? = null): ByteArray {
        if (encryptedBytes.size < MIN_CIPHERTEXT_BYTES) {
            throw GeneralSecurityException(
                "Encrypted payload too short (${encryptedBytes.size} bytes, minimum $MIN_CIPHERTEXT_BYTES)",
            )
        }
        val version = encryptedBytes[0]
        if (version != FORMAT_VERSION) {
            throw GeneralSecurityException(
                "Unsupported encrypted payload version: ${version.toInt() and 0xFF}",
            )
        }

        val iv = encryptedBytes.copyOfRange(1, 1 + GCM_IV_BYTES)
        val cipherTextOffset = 1 + GCM_IV_BYTES
        val cipherTextLength = encryptedBytes.size - cipherTextOffset
        val key = loadExistingKeyOrThrow()

        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        if (associatedData != null && associatedData.isNotEmpty()) {
            cipher.updateAAD(associatedData)
        }
        return cipher.doFinal(encryptedBytes, cipherTextOffset, cipherTextLength)
    }

    private fun loadExistingKeyOrThrow(): SecretKey = synchronized(keyLock) {
        val keyStore = loadAndroidKeyStore()
        if (!keyStore.containsAlias(keyAlias)) {
            throw GeneralSecurityException("Keystore alias '$keyAlias' does not exist")
        }
        return (keyStore.getKey(keyAlias, null) as? SecretKey)
            ?: throw GeneralSecurityException("Keystore alias '$keyAlias' does not hold a SecretKey")
    }

    private fun generateKeystoreKeyLocked(): SecretKey {
        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            keyAlias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(AES_KEY_BITS)
            .setRandomizedEncryptionRequired(true)
            .setUserAuthenticationRequired(false) // Allow non-interactive access for tunnel keepalive.
            .build()
        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }

    private fun loadAndroidKeyStore(): KeyStore =
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    companion object {
        const val DEFAULT_KEY_ALIAS = "aidos_vault_master_key"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val AES_GCM_TRANSFORMATION = "AES/GCM/NoPadding"
        const val AES_KEY_BITS = 256
        const val GCM_IV_BYTES = 12
        const val GCM_TAG_BITS = 128
        const val GCM_TAG_BYTES = GCM_TAG_BITS / 8
        const val FORMAT_VERSION: Byte = 0x01
        const val MIN_CIPHERTEXT_BYTES = 1 + GCM_IV_BYTES + GCM_TAG_BYTES
    }
}
