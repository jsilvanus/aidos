package dev.aidos.daemon

import app.cash.sqldelight.db.SqlDriver
import dev.aidos.kernel.ModelRuntime
import dev.aidos.kernel.PlatformProfile
import dev.aidos.storage.AidosStorage
import dev.aidos.storage.DesktopPaths
import dev.aidos.vault.SqliteSecretsVault
import java.nio.file.Files
import java.nio.file.Path

/**
 * Test composition DSL for building a [RuntimeCompositionRoot] with fake leaves and a real kernel
 * (RFC-0048 §Test composition). Lives strictly in test sources.
 */
class TestComposition(
    private val tempHome: Path = Files.createTempDirectory("aidos-test-runtime"),
) {
    var profile: PlatformProfile = PlatformProfile.DESKTOP
    var networkAvailable: Boolean = true
    var sidecarAvailable: Boolean = false
    var anthropicApiKey: () -> CharArray? = { null }
    var userDriver: SqlDriver? = null
    var vault: SqliteSecretsVault? = null
    var modelRuntime: ModelRuntime? = null
    var platform: PlatformAdapter? = null

    fun build(): RuntimeCompositionRoot {
        val resolvedPlatform = platform ?: DesktopPlatformAdapter(
            home = tempHome.toString(),
            profile = profile,
            networkCheck = { networkAvailable },
            sidecarCheck = { sidecarAvailable },
        )
        val resolvedUserDriver = userDriver ?: AidosStorage.openUser(
            DesktopPaths.userDb(tempHome.toString()),
            "test-runtime",
        ) { "2026-11-14T00:00:00Z" }.driver
        return RuntimeCompositionRoot(
            anthropicApiKey = anthropicApiKey,
            userDriver = resolvedUserDriver,
            vault = vault,
            modelRuntime = modelRuntime,
            platform = resolvedPlatform,
        )
    }
}

/** Constructs a test [RuntimeCompositionRoot] with substituted leaves and a real kernel (RFC-0048). */
fun testRuntime(configure: TestComposition.() -> Unit = {}): RuntimeCompositionRoot =
    TestComposition().apply(configure).build()
