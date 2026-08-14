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
