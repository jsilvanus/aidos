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

// Dictator plan D-1 (docs/dictator-sdk-integration-plan.md): the SDK ships as two artifacts, not
// one. `:client` (published as aidos-sdk-client) is the handshake/transport/wire-format code and
// has no dependency on :kernel — a third-party consumer like Dictator can ask for a chat
// completion without linking Aidos's frozen contract types. `:adapters` (aidos-sdk-adapters) is
// the RFC-0021 ModelAdapter bindings RFC-0103 MVP item 5 requires, and depends on both :client
// and :kernel — Aidos Agent, which already depends on kernel, links both artifacts.
include(":client")
include(":adapters")

// RFC-0103: kernel is the shared fourth root (../kernel), included by path rather than copied —
// the same arrangement agent/settings.gradle.kts and engine/settings.gradle.kts already use, and
// for the same reason. :adapters' ModelAdapter implementations must build against the identical
// ModelAdapter/ModelRequest/ModelResponse definitions Aidos Agent uses, not a second copy free to
// drift. kernel remains the one exception to "no shared build graph": frozen contract types with
// no implementation, depended on by everything and depending on nothing.
include(":kernel")
project(":kernel").projectDir = file("../kernel")
