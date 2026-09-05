plugins {
    kotlin("multiplatform")
    id("com.android.library")
}

kotlin {
    jvm()
    androidTarget()

    sourceSets {
        commonMain.dependencies {
            implementation(project(":kernel"))
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
            implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.6.1")
            implementation("app.cash.sqldelight:runtime:2.0.2")
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
        }

        val jvmMain by getting {
            resources.srcDir(rootProject.projectDir.resolve("../schema"))
            resources.include("*.sql")
            dependencies {
                implementation("app.cash.sqldelight:sqlite-driver:2.0.2")
                implementation("org.xerial:sqlite-jdbc:3.45.2.0")
            }
        }

        val jvmTest by getting {
            dependencies {
                implementation(kotlin("test-junit"))
            }
        }

        val androidMain by getting {
            dependencies {
                implementation("app.cash.sqldelight:android-driver:2.0.2")
            }
        }
    }

    compilerOptions {
        allWarningsAsErrors.set(true)
    }
}

android {
    namespace = "dev.aidos.storage"
    compileSdk = 34

    defaultConfig {
        minSdk = 26
    }

    // The canonical DDL remains in the repository-level schema/ directory. Package those files
    // as Android assets so AndroidStorage can feed the same MigrationRunner as the JVM target.
    sourceSets["main"].assets.srcDir(rootProject.projectDir.resolve("../schema"))

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}
