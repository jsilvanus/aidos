pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}
dependencyResolutionManagement {
    repositories { mavenCentral() }
}

rootProject.name = "aidos-runtime"
include(":kernel")
include(":storage")
