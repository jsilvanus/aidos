plugins {
    kotlin("multiplatform")
    id("com.android.library")
}

// RFC-0022: the vision capability (image + text in, text out). llama.cpp's Java binding (de.kherud:llama
// 4.2.0, the newest published) is built without libmtmd, so a GGUF + mmproj pair cannot be driven from
// the JVM or Android hosts. Vision therefore runs on the ONNX Runtime both hosts already ship for decision
// models: SmolVLM-256M-Instruct as its three exported graphs (vision encoder, token embedding, merged
// decoder with KV cache). Same ai.onnxruntime Java API on both platforms, only the artifact differs.
kotlin {
    jvmToolchain(17)
    jvm()
    androidTarget()

    applyDefaultHierarchyTemplate()

    sourceSets {
        commonMain.dependencies {
            api(project(":kernel"))
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
        }

        val jvmAndAndroidMain by creating {
            dependsOn(commonMain.get())
        }
        jvmMain {
            dependsOn(jvmAndAndroidMain)
            dependencies {
                api("com.microsoft.onnxruntime:onnxruntime:1.22.0")
                // OnnxVisionBackend decorates the JVM host's InferenceBackend.
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
    namespace = "fi.italeino.aidos.vision"
    compileSdk = 35
    defaultConfig {
        minSdk = 26
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
