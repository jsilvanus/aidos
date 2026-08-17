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

rootProject.name = "aidos-engine"

// RFC-0103: kernel is a shared fourth root (../kernel), included here by path rather than
// copied — same reasoning as agent/settings.gradle.kts. agent/ and engine/ still share no other
// module; kernel is the one deliberate exception, being frozen contract types with no
// implementation (RFC-0002/ARCHITECTURE.md: "every service depends on these; they depend on
// none").
include(":kernel")
project(":kernel").projectDir = file("../kernel")

include(":modelruntime")
include(":cookbook")
include(":huggingface")
include(":downloads")
include(":models")
include(":voice")
include(":androidapp")
include(":cli")
