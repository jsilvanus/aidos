plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
    id("com.android.library")
}

// RFC-0105 (D50): inference groups. Phase 1 foundation: node identity, the two trust mechanisms
// (pairing, admin-issued group certificates), the single trust check and peer liveness. No network
// code yet; discovery and the mTLS peer link come next and build on these types.
kotlin {
    jvmToolchain(17)
    jvm()
    androidTarget()

    applyDefaultHierarchyTemplate()

    sourceSets {
        commonMain.dependencies {
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
        }

        // Keys, signatures and hashes use JCA (java.security), which is the same API on the JVM and
        // Android, so everything that touches crypto lives in jvmAndAndroidMain.
        val jvmAndAndroidMain by creating {
            dependsOn(commonMain.get())
        }
        jvmMain.get().dependsOn(jvmAndAndroidMain)
        androidMain.get().dependsOn(jvmAndAndroidMain)

        commonTest.dependencies {
            implementation(kotlin("test"))
        }

        jvmTest.dependencies {
            implementation(kotlin("test-junit"))
        }
    }
}

android {
    namespace = "fi.italeino.aidos.aidin"
    compileSdk = 35
    defaultConfig {
        minSdk = 26
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
