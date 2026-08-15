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

// RFC-0103: kernel is a shared fourth root (../kernel), included here by path rather than
// copied, because Aidos Engine's modules also depend on these frozen contract types (Models.kt's
// ModelAdapter chief among them) and must not carry a second, drifting definition of them.
include(":kernel")
project(":kernel").projectDir = file("../kernel")


