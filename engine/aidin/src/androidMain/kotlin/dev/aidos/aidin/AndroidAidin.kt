package dev.aidos.aidin

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import java.io.File

/**
 * Android wiring for [AidinNode] (RFC-0105 phase 1): node key and trust state as files in the
 * app's private storage, discovery through NsdManager.
 *
 * The node key is a software key in app-private files, not an Android Keystore key: the TLS peer
 * link builds an in-memory key store from the key pair, which a non-exportable Keystore key cannot
 * join. Moving it into the Keystore needs a Keystore-backed KeyManager and is left for later.
 */
object AndroidAidin {
    const val DEFAULT_PORT = 47100

    fun create(context: Context, scope: CoroutineScope, engineVersion: String): AidinNode {
        val dir = File(context.filesDir, "aidin").apply { mkdirs() }
        val key = FileNodeKeyStore(File(dir, "node-key")).loadOrCreate()
        val store = FileAidinStateStore(File(dir, "aidin-state.json"))
        val discovery = runCatching { NsdDiscovery(context.applicationContext) }.getOrNull()
        return AidinNode(key, store, scope, engineVersion, discovery)
    }
}
