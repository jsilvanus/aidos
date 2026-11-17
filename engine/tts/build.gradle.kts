plugins {
    kotlin("multiplatform")
    id("com.android.library")
}

// Text-to-speech (RFC-0117 amendment): Piper VITS voices on ONNX Runtime, the same runtime :stt and :decisions use,
// so one native dependency serves the JVM and Android hosts. The TTS contract lives here, not in :kernel (frozen);
// the kernel already has ModelKind.TTS, and TtsModelAdapterImpl bridges a loaded voice onto the normal ModelRuntime.
kotlin {
    jvmToolchain(17)
    jvm()
    androidTarget()

    applyDefaultHierarchyTemplate()

    sourceSets {
        commonMain.dependencies {
            api(project(":kernel"))
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
        }

        // ai.onnxruntime is the same Java API on both platforms; only the artifact differs
        // (ONNX Runtime D44). The runner source lives in jvmAndAndroidMain.
        val jvmAndAndroidMain by creating {
            dependsOn(commonMain.get())
        }
        jvmMain {
            dependsOn(jvmAndAndroidMain)
            dependencies {
                api("com.microsoft.onnxruntime:onnxruntime:1.22.0")
                // OnnxSttBackend decorates the JVM host's InferenceBackend.
                implementation(project(":modelruntime"))
            }
        }
        androidMain {
            dependsOn(jvmAndAndroidMain)
            dependencies {
                api("com.microsoft.onnxruntime:onnxruntime-android:1.22.0")
            }
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
        }

        jvmTest.dependencies {
            implementation(kotlin("test-junit"))
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
        }
    }
}

android {
    namespace = "fi.italeino.aidos.tts"
    compileSdk = 35
    defaultConfig {
        minSdk = 26
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
