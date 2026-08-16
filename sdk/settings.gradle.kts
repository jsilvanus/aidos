pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        // AGP (com.android.library) is distributed via Google's Maven repository.
        google()
    }
}
dependencyResolutionManagement {
    repositories {
        mavenCentral()
        // AGP dependencies (e.g. lint, core-ktx) come from Google Maven.
        google()
    }
}

rootProject.name = "aidos-sdk"

// RFC-0103: kernel is the shared fourth root (../kernel), included by path rather than copied —
// the same arrangement agent/settings.gradle.kts and engine/settings.gradle.kts already use, and
// for the same reason. The SDK's ModelAdapter implementations must build against the identical
// ModelAdapter/ModelRequest/ModelResponse definitions Aidos Agent uses, not a second copy free to
// drift. kernel remains the one exception to "no shared build graph": frozen contract types with
// no implementation, depended on by everything and depending on nothing.
include(":kernel")
project(":kernel").projectDir = file("../kernel")
