// Root aggregator only — no source code lives here now that sdk/ splits into :client and
// :adapters (Dictator plan D-1). Subprojects apply these without a version, matching the
// convention agent/build.gradle.kts and engine/build.gradle.kts already use.
plugins {
    kotlin("multiplatform") version "2.4.10" apply false
    kotlin("plugin.serialization") version "2.4.10" apply false
    id("com.android.library") version "8.5.2" apply false
}
