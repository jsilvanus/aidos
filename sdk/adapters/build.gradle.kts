plugins {
    kotlin("multiplatform")
    id("com.android.library")
}

kotlin {
    jvmToolchain(21)

    androidTarget()
    jvm()

    applyDefaultHierarchyTemplate()

    sourceSets {
        // Matches sdk/client's shape: commonMain can't see java.util (Base64), so the actual
        // code lives in this intermediate source set shared by both targets.
        val jvmAndAndroidMain by creating {
            dependsOn(commonMain.get())

            dependencies {
                // RFC-0103 MVP item 5: the ModelAdapter bindings, which necessarily depend on
                // :kernel — the reason this is a separate artifact from :client (Dictator plan
                // D-1).
                implementation(project(":kernel"))
                implementation(project(":client"))

                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
            }
        }

        androidMain.get().dependsOn(jvmAndAndroidMain)
        jvmMain.get().dependsOn(jvmAndAndroidMain)

        jvmTest.dependencies {
            implementation(kotlin("test"))
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
        }
    }
}

android {
    namespace = "fi.italeino.aidos.sdk.adapters"
    compileSdk = 34

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}
