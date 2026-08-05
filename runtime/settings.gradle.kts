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
include(":settings")
include(":identity")
include(":capability")
include(":broker")
include(":executor")
include(":lock")
include(":api")
include(":cli")
include(":filesystem")
include(":git")
include(":vault")
