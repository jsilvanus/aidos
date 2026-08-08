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
        // gitsema-kotlin is published to GitHub Packages (M22 dependency).
        // Reads require a GITHUB_TOKEN with `read:packages` scope.
        // In GitHub Actions the default GITHUB_TOKEN already has this.
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
include(":prompt")
include(":agentloop")
include(":memory")
include(":mcp")
include(":modelruntime")
include(":routing")
include(":worker")
include(":retention")
include(":androidapp")
include(":knowledge")
include(":daemon")
include(":voice")
