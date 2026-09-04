plugins {
    kotlin("multiplatform")
    kotlin("plugin.compose")
    kotlin("plugin.serialization")
    id("com.android.application")
}

kotlin {
    jvmToolchain(21)
    jvm()
    androidTarget()

    applyDefaultHierarchyTemplate()

    sourceSets {
        val jvmAndAndroidMain by creating {
            dependsOn(commonMain.get())
            dependencies {
                implementation(project(":kernel"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
                implementation("io.ktor:ktor-server-core:3.5.2")
                implementation("io.ktor:ktor-server-cio:3.5.2")
                implementation("io.ktor:ktor-server-content-negotiation:3.5.2")
                implementation("io.ktor:ktor-serialization-kotlinx-json:3.5.2")
                implementation("io.ktor:ktor-server-auth:3.5.2")
                implementation("commons-codec:commons-codec:1.16.0")
            }
        }

        androidMain.get().dependsOn(jvmAndAndroidMain)
        jvmMain.get().dependsOn(jvmAndAndroidMain)

        val jvmMain by getting {
            dependencies {
                implementation("androidx.compose.runtime:runtime:1.7.5")
            }
        }

        val jvmTest by getting {
            dependencies {
                implementation(kotlin("test-junit"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
                implementation("io.ktor:ktor-server-test-host:3.5.2")
            }
        }

        val androidMain by getting {
            dependencies {
                implementation(project(":modelruntime"))
                implementation(project(":cookbook"))
                implementation(project(":huggingface"))
                implementation(project(":downloads"))
                implementation(project(":models"))
                implementation(project(":voice"))
                implementation("de.kherud:llama:4.2.0")
                implementation("androidx.compose.ui:ui:1.7.5")
                implementation("androidx.compose.material3:material3:1.3.1")
                implementation("androidx.compose.material:material-icons-extended:1.7.5")
                implementation("com.google.android.material:material:1.12.0")
                implementation("androidx.compose.foundation:foundation:1.7.5")
                implementation("androidx.activity:activity-compose:1.9.3")
                implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
                implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
                implementation("androidx.navigation:navigation-compose:2.8.3")
                implementation("androidx.core:core-ktx:1.15.0")
                implementation("androidx.core:core-splashscreen:1.0.1")
                implementation("androidx.appcompat:appcompat:1.7.0")
                implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
                implementation("androidx.lifecycle:lifecycle-service:2.8.6")
                implementation("androidx.security:security-crypto:1.1.0")
                implementation("io.ktor:ktor-client-android:3.5.2")
                implementation("io.ktor:ktor-client-serialization:3.5.2")
                implementation("io.ktor:ktor-client-content-negotiation:3.5.2")
            }
        }
    }
}

android {
    namespace = "fi.italeino.aidos.engine"
    compileSdk = 35

    defaultConfig {
        applicationId = "fi.italeino.aidos.engine"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        aidl = true
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }
}
