plugins {
    kotlin("multiplatform")
}

kotlin {
    jvm()

    sourceSets {
        val jvmMain by getting {
            dependencies {
                implementation(project(":kernel"))
                implementation(project(":settings"))
                // Public: LargeOutputStore (executor) is implemented by BlobStore.
                api(project(":executor"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
                // The project database is reached through the same SqlDriver every other module
                // uses (RFC-0040), not a second JDBC connection.
                implementation("app.cash.sqldelight:runtime:2.0.2")
            }
        }

        val jvmTest by getting {
            dependencies {
                implementation(project(":storage"))
                implementation(kotlin("test-junit"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
                implementation("app.cash.sqldelight:sqlite-driver:2.0.2")
                implementation("org.xerial:sqlite-jdbc:3.45.2.0")
            }
        }
    }
}
