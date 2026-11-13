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
        // The MCP Binder library, until it is published (kotlin-mcp-android-binder: publishToMavenLocal).
        mavenLocal {
            content { includeGroup("fi.italeino.mcp") }
        }
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

rootProject.name = "aidos-agent"

// RFC-0103: kernel is a shared fourth root (../kernel), included here by path rather than
// copied, because Aidos Engine's modules also depend on these frozen contract types (Models.kt's
// ModelAdapter chief among them) and must not carry a second, drifting definition of them.
include(":kernel")
project(":kernel").projectDir = file("../kernel")

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
include(":http")
include(":vault")
include(":prompt")
include(":agentloop")
include(":memory")
// RFC-0031 "Implementation Layering": what was :mcp is three modules. :mcp-core and :mcp-policy
// are kernel-free by construction — that is what makes them reusable outside this repo — and
// :mcp-broker is the only one that binds to :kernel.
include(":mcp-core")
include(":mcp-policy")
include(":mcp-broker")
include(":mcp-binder-android")
include(":routing")
include(":worker")
include(":retention")
include(":androidapp")
include(":knowledge")
include(":daemon")
include(":voice")
include(":amp")
include(":portability")

// RFC-0108 Phase 1: Route discovery and dialer fallback (kernel-free, multiplatform).
include(":routes")

// RFC-0108 Phase 1: AIDIN wire format extension for route hints.
include(":wire-aidin")

// RFC-0108: standalone, kernel-free SSH tunnel library (fi.italeino.sshtunnel). No :kernel dependency.
include(":ssh-tunnel")

// RFC-0108 Phase 3: Engine adapter for SSH tunnels with vault and audit integration.
include(":tunnel-manager")

// RFC-0108 Phase 2: Route planner policy and per-peer remote-access control (kernel-free, multiplatform).
include(":policy")

// RFC-0108 Phase 1: Route discovery and dialer fallback (kernel-free, multiplatform).
include(":routes")

// RFC-0108 Phase 1: AIDIN wire format extension for route hints.
include(":wire-aidin")

// RFC-0108 Phase 2: Route planner policy and per-peer remote-access control (kernel-free, multiplatform).
include(":policy")
