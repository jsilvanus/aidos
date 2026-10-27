plugins {
    kotlin("multiplatform") version "2.4.10" apply false
    kotlin("plugin.serialization") version "2.4.10" apply false
    kotlin("plugin.compose") version "2.4.10" apply false
    // Compose Multiplatform desktop runtime + packaging for :desktopapp. Pinned to the same 1.7.3
    // as :engineui's libraries (see engineui/build.gradle.kts for why 1.7.3).
    id("org.jetbrains.compose") version "1.7.3" apply false
    // ---------------------------------------------------------------------------
    // com.android.library (AGP 8.5.x) is declared here for submodule use.
    // Requires the Google Maven repository (dl.google.com) to be reachable.
    // Wire this apply false into :androidapp once the CI environment has
    // network access to dl.google.com (blocked in this sandbox).
    // ---------------------------------------------------------------------------
    id("com.android.library") version "8.5.2" apply false
    id("com.android.application") version "8.5.2" apply false
}

// RFC-0022: de.kherud:llama extracts its native library into `de.kherud.llama.tmpdir` (default
// java.io.tmpdir) and, on load, deletes libraries it takes for stale leftovers. Test JVMs of
// different modules run in parallel, so one could delete the library another was loading
// (ExceptionInInitializerError at LlamaLoader). Give every test task its own directory.
subprojects {
    tasks.withType<Test>().configureEach {
        val dir = layout.buildDirectory.dir("tmp/$name/llama-native")
        doFirst { dir.get().asFile.mkdirs() }
        systemProperty("de.kherud.llama.tmpdir", dir.get().asFile.absolutePath)
    }
}
