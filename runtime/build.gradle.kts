plugins {
    kotlin("multiplatform") version "2.1.0" apply false
    kotlin("plugin.serialization") version "2.1.0" apply false
    kotlin("plugin.compose") version "2.1.0" apply false
    // ---------------------------------------------------------------------------
    // com.android.library (AGP 8.5.x) is declared here for submodule use.
    // Requires the Google Maven repository (dl.google.com) to be reachable.
    // Wire this apply false into :kernel, :api, :androidapp once the CI
    // environment has network access to dl.google.com (blocked in this sandbox).
    // ---------------------------------------------------------------------------
    id("com.android.library") version "8.5.2" apply false
    id("com.android.application") version "8.5.2" apply false
}
