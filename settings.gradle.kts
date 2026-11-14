pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        google()
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/jsilvanus/gitsema-kotlin")
            credentials {
                username = System.getenv("GITHUB_ACTOR") ?: "token"
                password = System.getenv("GITHUB_TOKEN") ?: ""
            }
        }
    }
}

rootProject.name = "aidos"

includeBuild("agent")
includeBuild("engine")
includeBuild("sdk")

val aidosTcrCandidates = listOf(
    file("../aidos-tcr"),
    file("D:/aidos-tcr"),
    file("c:/Users/jsilv/Code/aidos-tcr")
)
val aidosTcrDir = aidosTcrCandidates.firstOrNull { it.exists() && it.isDirectory }
if (aidosTcrDir != null) {
    includeBuild(aidosTcrDir)
}

include(":kernel")
project(":kernel").projectDir = file("kernel")
