package dev.aidos.daemon

import dev.aidos.kernel.PlatformProfile
import dev.aidos.kernel.ToolDescriptor
import java.nio.file.Path

/**
 * Execution window policy provided by [PlatformAdapter] (RFC-0048, RFC-0009).
 *
 * On desktop/headless servers, [maxMillis] is null (unbounded) and [permitsLocalInference] is
 * true. On mobile, foreground service state governs local inference and burst duration.
 */
data class ExecutionWindowPolicy(
    val maxMillis: Long? = null,
    val permitsLocalInference: Boolean = true,
)

/**
 * Platform variation boundary injected at [RuntimeCompositionRoot] (RFC-0048, RFC-0049).
 *
 * Keeps platform differences in composition rather than scattered `if (isAndroid)` branches.
 */
interface PlatformAdapter {
    val profile: PlatformProfile
    val userDataDir: Path
    val modelDir: Path
    fun networkAvailable(): Boolean = true
    fun sidecarAvailable(): Boolean = false
    fun availableTools(): List<ToolDescriptor> = emptyList()
    fun executionWindow(): ExecutionWindowPolicy = ExecutionWindowPolicy()
}

/**
 * JVM desktop / headless-server [PlatformAdapter] (RFC-0048, RFC-0049).
 *
 * Profile defaults to `AIDOS_PROFILE` environment variable when set (`DESKTOP`,
 * `HEADLESS_SERVER`, `MOBILE`), otherwise [PlatformProfile.DESKTOP].
 */
class DesktopPlatformAdapter(
    home: String = System.getProperty("user.home"),
    override val userDataDir: Path = Path.of(home, ".aidos"),
    override val modelDir: Path = Path.of(home, ".aidos", "models"),
    override val profile: PlatformProfile = detectProfile(),
    private val networkCheck: () -> Boolean = { true },
    private val sidecarCheck: () -> Boolean = { false },
    private val windowPolicy: ExecutionWindowPolicy = ExecutionWindowPolicy(),
) : PlatformAdapter {
    override fun networkAvailable(): Boolean = networkCheck()
    override fun sidecarAvailable(): Boolean = sidecarCheck()
    override fun executionWindow(): ExecutionWindowPolicy = windowPolicy

    companion object {
        fun detectProfile(env: (String) -> String? = System::getenv): PlatformProfile {
            val raw = env("AIDOS_PROFILE")?.trim()?.uppercase()
            return if (raw != null) {
                PlatformProfile.entries.firstOrNull { it.name == raw } ?: PlatformProfile.DESKTOP
            } else {
                PlatformProfile.DESKTOP
            }
        }
    }
}
