plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
}

kotlin {
    jvm()

    sourceSets {
        val jvmMain by getting {
            dependencies {
                implementation(project(":kernel"))
                implementation(project(":mcp-core"))
                implementation(project(":mcp-policy"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
                // McpServerStore and McpOperationAdoptionStore both read/write project- and
                // user-scope SQLite directly (SqlDriver), same as :capability's
                // SqliteCapabilityManager. This was missing -- McpServerStore.kt already imported
                // app.cash.sqldelight.db.SqlDriver/QueryResult without it being declared here.
                implementation("app.cash.sqldelight:runtime:2.0.2")
            }
        }

        val jvmTest by getting {
            // McpToolTest drives the same fake stdio server StdioMcpClientTest uses. Point at
            // :mcp-core's copy rather than keeping a second one here: when the SDK migration
            // tightened what `initialize` must return, the duplicate went stale and every call
            // through McpTool timed out instead of failing loudly. One fixture, one place to fix.
            resources.srcDir(project(":mcp-core").projectDir.resolve("src/jvmTest/resources"))

            dependencies {
                implementation(kotlin("test-junit"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
                // McpServerStoreTest opens a real user-scope DB against schema/user.sql (not a
                // hand-written subset) via :storage's MigrationRunner, same as :settings' tests.
                implementation(project(":storage"))
                implementation("app.cash.sqldelight:sqlite-driver:2.0.2")
                implementation("org.xerial:sqlite-jdbc:3.45.2.0")
            }
        }
    }

    // See :mcp-core. This module is Aidos-only, so the argument is plain consistency with the
    // other kernel-bound modules (:broker, :capability, :executor, ...) that already set it.
    compilerOptions {
        allWarningsAsErrors.set(true)
    }
}
