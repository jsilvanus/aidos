package fi.italeino.aidos.engine.desktop

import dev.aidos.cookbook.DeviceProfile
import fi.italeino.aidos.engine.ui.MemoryBudget
import java.io.File
import java.lang.management.ManagementFactory

/**
 * Where the desktop debug host keeps its files.
 *
 * Models live where the JVM llama.cpp backend and the engine CLI already look
 * (`aidos.models.dir`, default `~/.aidos/models`), so a model fetched with the CLI shows up here
 * and vice versa. Desktop-only state (catalog DB, approvals, preferences) is kept apart under
 * `~/.aidos/engine-desktop` so it never mixes with the Agent's own `~/.aidos` data.
 */
object DesktopPaths {
    val modelsDir: File = File(
        System.getProperty("aidos.models.dir")
            ?: File(System.getProperty("user.home"), ".aidos/models").absolutePath
    )

    val stateDir: File = File(System.getProperty("user.home"), ".aidos/engine-desktop")

    fun ensureCreated() {
        modelsDir.mkdirs()
        stateDir.mkdirs()
    }
}

/** Desktop counterpart of the Android DeviceProfileProvider / ActivityManager reads (RFC-0022). */
object DesktopDevice {
    private val os = ManagementFactory.getOperatingSystemMXBean() as? com.sun.management.OperatingSystemMXBean

    fun profile(): DeviceProfile = DeviceProfile(
        totalRamBytes = os?.totalMemorySize ?: Runtime.getRuntime().maxMemory(),
        availableRamBytes = os?.freeMemorySize ?: Runtime.getRuntime().freeMemory(),
        storageFreeBytes = DesktopPaths.modelsDir.usableSpace,
        cpuCoreCount = Runtime.getRuntime().availableProcessors(),
        hasAccelerator = false,
    )

    fun memory(): MemoryBudget {
        val mb = 1024L * 1024L
        val total = os?.totalMemorySize ?: 0L
        val free = os?.freeMemorySize ?: 0L
        return MemoryBudget(usedMB = ((total - free) / mb).toInt(), totalMB = (total / mb).toInt())
    }

    fun freeStorageGb(): Int = (DesktopPaths.modelsDir.usableSpace / (1024L * 1024L * 1024L)).toInt()
}
