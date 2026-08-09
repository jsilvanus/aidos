plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization") version "1.9.25"
    id("app.cash.sqldelight") version "2.0.2"
    id("com.android.application")
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
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.1")
        }

        val jvmMain by getting {
            dependencies {
                implementation(project(":kernel"))
                implementation(project(":api"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.1")
                implementation("app.cash.sqldelight:runtime:2.0.2")
                implementation("app.cash.sqldelight:sqlite-driver:2.0.2")
                implementation("org.xerial:sqlite-jdbc:3.45.2.0")
            }
        }

        val jvmTest by getting {
            dependencies {
                implementation(project(":api"))
                implementation(kotlin("test-junit"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
                implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.6.1")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.1")
                implementation("app.cash.sqldelight:sqlite-driver:2.0.2")
            }
        }

        // androidMain sourceSet — uncomment when androidTarget() is wired.
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

sqldelight {
    databases {
        create("ScheduledJobsDb") {
            packageName.set("dev.aidos.androidapp")
            srcDirs.setFrom("src/commonMain/sqldelight")
        }
    }
}

// android { } — uncomment when androidTarget() is wired.
android {
    namespace = "fi.italeino.aidos"
    compileSdk = 34
    
    defaultConfig {
        applicationId = "fi.italeino.aidos"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }
    
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    
    kotlinOptions {
        jvmTarget = "11"
    }
    
    signingConfigs {
        create("release") {
            val keystore = System.getenv("KEYSTORE_FILE") ?: System.getenv("HOME")?.let { "$it/.android/aidos-keystore.jks" }
            val keystorePassword = System.getenv("KEYSTORE_PASSWORD") ?: ""
            val keyAlias = System.getenv("KEY_ALIAS") ?: "aidos-key"
            val keyPassword = System.getenv("KEY_PASSWORD") ?: keystorePassword
            
            if (keystore != null && keystore.isNotEmpty()) {
                storeFile = File(keystore)
                storePassword = keystorePassword
                this.keyAlias = keyAlias
                this.keyPassword = keyPassword
            }
        }
    }
    
    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.getByName("release")
        }
    }
}
