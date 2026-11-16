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
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
                // RFC-0042: the egress client dials the addresses the policy checked, so it needs a
                // client with a pluggable resolver (java.net.http has none). OkHttp is already used by
                // sdk/client at this version. `api` because DefaultEgressClient's constructor names it.
                api("com.squareup.okhttp3:okhttp:4.11.0")
                implementation("org.jetbrains.kotlinx:kotlinx-io-core:0.5.1")
            }
        }

        val jvmTest by getting {
            dependencies {
                implementation(kotlin("test-junit"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
            }
        }
    }

    compilerOptions {
        allWarningsAsErrors.set(true)
    }
}
