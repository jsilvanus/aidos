package fi.italeino.aidos.service

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.DeadObjectException
import android.os.IBinder
import dev.aidos.androidapp.service.NativeInferenceConnectionMonitor
import dev.aidos.ui.system.NativeProcessHealth
import kotlinx.coroutines.flow.StateFlow

/**
 * Android Binder connector to `:native_inference` (`fi.italeino.aidos.engine.HANDSHAKE`).
 *
 * Registers an [IBinder.DeathRecipient] and catches [DeadObjectException] to drive
 * [NativeInferenceConnectionMonitor] and warm-restart the isolated native inference process
 * while keeping the UI process alive (D49, RFC-0111).
 */
class AndroidNativeInferenceConnector(
    private val context: Context,
) {
    private val monitor = NativeInferenceConnectionMonitor(
        onRequestWarmRestart = { bindEngineService() }
    )

    val health: StateFlow<NativeProcessHealth> = monitor.health

    private var activeBinder: IBinder? = null
    private var isBound: Boolean = false

    private val deathRecipient = IBinder.DeathRecipient {
        activeBinder = null
        monitor.onBinderDied()
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            activeBinder = service
            try {
                service?.linkToDeath(deathRecipient, 0)
            } catch (_: DeadObjectException) {
                monitor.onDeadObjectException()
                return
            } catch (_: Throwable) {
                // Ignore if binder does not support death linking
            }
            monitor.onConnected()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            activeBinder = null
            monitor.onBinderDied()
        }
    }

    fun start() {
        bindEngineService()
    }

    fun stop() {
        if (isBound) {
            try {
                activeBinder?.unlinkToDeath(deathRecipient, 0)
            } catch (_: Throwable) {
            }
            try {
                context.unbindService(connection)
            } catch (_: Throwable) {
            }
            isBound = false
            activeBinder = null
        }
    }

    private fun bindEngineService() {
        val intent = Intent(ENGINE_HANDSHAKE_ACTION).apply {
            setPackage(ENGINE_PACKAGE)
        }
        try {
            isBound = context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        } catch (_: SecurityException) {
            // Handshake permission or package not installed yet
        }
    }

    companion object {
        const val ENGINE_HANDSHAKE_ACTION = "fi.italeino.aidos.engine.HANDSHAKE"
        const val ENGINE_PACKAGE = "fi.italeino.aidos.engine"
    }
}
