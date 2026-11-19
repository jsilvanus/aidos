package fi.italeino.aidos.engine.vault

import android.content.Context
import android.util.Log
import dev.aidos.vault.FileSecretRecordStore
import dev.aidos.vault.RecordSecretsVault
import dev.aidos.vault.Redactor
import dev.aidos.vault.SecretKind
import java.io.File

/**
 * The Android host's vault (RFC-0035, amendment 2026-11-19; D49: Engine and Agent are one app here,
 * so this one vault serves both). Sealed values live in `files/secrets/vault.json`, app-private;
 * the key lives in the Android Keystore ([AndroidKeystoreKeyProvider]).
 *
 * One [redactor] per process: the vault registers every value with it, and the agent loop screens
 * tool results with it, so a stored token is caught by value and not only by its shape.
 *
 * Fails closed. When the Keystore or the vault file cannot be opened, [vault] returns null: a token
 * then reads as absent and is not saved, and nothing falls back to plaintext preferences.
 */
object EngineVault {
    private const val TAG = "EngineVault"

    /** Names of the values the host keeps here. */
    const val HF_TOKEN = "huggingface_token"
    fun providerKey(providerId: String) = "provider:${providerId.lowercase()}:api_key"

    val redactor: Redactor = Redactor()

    @Volatile private var opened: RecordSecretsVault? = null
    private val lock = Any()

    fun vault(context: Context): RecordSecretsVault? {
        opened?.let { return it }
        synchronized(lock) {
            opened?.let { return it }
            return try {
                val file = File(File(context.applicationContext.filesDir, "secrets"), "vault.json")
                RecordSecretsVault(FileSecretRecordStore(file), AndroidKeystoreKeyProvider(), redactor).also {
                    opened = it
                    migratePlaintext(context, it)
                }
            } catch (e: Exception) {
                // The message names the failing step only; no value is ever in it.
                Log.e(TAG, "The vault could not be opened: ${e::class.java.simpleName}: ${e.message}")
                null
            }
        }
    }

    /** The value stored under [name], or null when it is absent or the vault cannot be opened. */
    fun read(context: Context, name: String): String? {
        val v = vault(context) ?: return null
        return try {
            v.valueOf(name)?.let { chars -> String(chars).also { chars.fill('\u0000') } }
        } catch (e: Exception) {
            Log.e(TAG, "Could not read '$name' from the vault: ${e::class.java.simpleName}")
            null
        }
    }

    /** Stores [value] under [name], or deletes it when [value] is null or blank. False when it was not saved. */
    fun write(context: Context, name: String, kind: SecretKind, value: String?): Boolean {
        val v = vault(context) ?: return false
        return try {
            if (value.isNullOrBlank()) v.removeByName(name) else v.putValue(name, kind, value.trim().toCharArray())
            true
        } catch (e: Exception) {
            Log.e(TAG, "Could not save '$name' to the vault: ${e::class.java.simpleName}")
            false
        }
    }

    /**
     * Before this vault, the Hugging Face token and provider API keys were kept in plain
     * SharedPreferences. Each one found is moved here and then removed from the preferences.
     */
    private fun migratePlaintext(context: Context, vault: RecordSecretsVault) {
        val prefs = context.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE)
        prefs.getString(LEGACY_HF_TOKEN, null)?.let { token ->
            if (vault.adoptPlaintext(HF_TOKEN, SecretKind.TOKEN, token.trim().toCharArray())) {
                prefs.edit().remove(LEGACY_HF_TOKEN).commit()
            }
        }
        val providers = context.getSharedPreferences(LEGACY_PROVIDER_PREFS, Context.MODE_PRIVATE)
        val keys = providers.all.keys.filter { it.endsWith(LEGACY_API_KEY_SUFFIX) }
        for (k in keys) {
            val id = k.removeSuffix(LEGACY_API_KEY_SUFFIX)
            val value = providers.getString(k, null)
            if (vault.adoptPlaintext(providerKey(id), SecretKind.API_KEY, value?.trim()?.toCharArray()) || value.isNullOrBlank()) {
                providers.edit().remove(k).commit()
            }
        }
    }

    private const val LEGACY_PREFS = "aidos_prefs"
    private const val LEGACY_HF_TOKEN = "hf_token"
    private const val LEGACY_PROVIDER_PREFS = "aidos_engine_provider_state"
    private const val LEGACY_API_KEY_SUFFIX = ":api_key"
}
