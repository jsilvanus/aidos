package fi.italeino.aidos.engine.ui

import android.app.ActivityManager
import android.content.Context
import android.os.Environment
import android.os.StatFs
import dev.aidos.cookbook.DeviceProfile

/**
 * Provides hardware profiling for the current Android device (RFC-0022).
 */
class DeviceProfileProvider(private val context: Context) {

    /**
     * Captures the current device profile.
     */
    fun getProfile(): DeviceProfile {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)

        val internalDir = Environment.getDataDirectory()
        val statFs = StatFs(internalDir.path)
        val availableStorage = statFs.availableBytes

        return DeviceProfile(
            totalRamBytes = memoryInfo.totalMem,
            availableRamBytes = memoryInfo.availMem,
            storageFreeBytes = availableStorage,
            cpuCoreCount = Runtime.getRuntime().availableProcessors(),
            hasAccelerator = false, // Placeholder: check for NPU/GPU in later phases
            thermalHeadroom = 50, // Placeholder
        )
    }
}
