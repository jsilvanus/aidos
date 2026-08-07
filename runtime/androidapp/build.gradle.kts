plugins {
    kotlin("multiplatform")
    id("com.android.library")
}

kotlin {
    jvm()
    androidTarget()

    sourceSets {
        // M28/M31 platform-neutral logic lives in commonMain so that when androidTarget()
        // is wired (needs AGP from dl.google.com), no file moves are required.
        commonMain.dependencies {
            implementation(project(":kernel"))
            implementation(project(":api"))
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
            implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.6.1")
        }

        val jvmMain by getting {
            dependencies {
                implementation(project(":kernel"))
                implementation(project(":api"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
            }
        }

        val jvmTest by getting {
            dependencies {
                implementation(project(":api"))
                implementation(kotlin("test-junit"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
                implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.6.1")
            }
        }

        val androidMain by getting {
            dependencies {
                implementation(project(":kernel"))
                implementation(project(":api"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
                implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.6.1")
                
                // Compose and Material Design 3
                implementation("androidx.compose.ui:ui:1.6.0")
                implementation("androidx.compose.material3:material3:1.1.0")
                implementation("androidx.compose.foundation:foundation:1.6.0")
                implementation("androidx.activity:activity-compose:1.8.0")
                implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.6.1")
                implementation("androidx.lifecycle:lifecycle-runtime-compose:2.6.1")
                
                // Navigation
                implementation("androidx.navigation:navigation-compose:2.7.0")
                
                // Android core
                implementation("androidx.core:core-ktx:1.10.1")
                implementation("androidx.appcompat:appcompat:1.6.1")
                implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.6.1")
                
                // For scoped storage and file access
                implementation("androidx.documentfile:documentfile:1.0.1")
            }
        }
    }
}

android {
    namespace = "fi.italeino.aidos"
    compileSdk = 34
    
    defaultConfig {
        minSdk = 26
        targetSdk = 34
    }
    
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    
    kotlinOptions {
        jvmTarget = "11"
    }
}
