plugins {
    kotlin("multiplatform")
}

// RFC-0108: the SSH tunnel is a standalone, kernel-free library. It must not depend on :kernel,
// the vault, AIDIN or any other Aidos module, so another app (e.g. a media-project Android app)
// can consume it. Aidos-specific wiring (vault KeyProvider, egress grant, audit) lives in Engine.
kotlin {
    jvm()

    sourceSets {
        commonMain.dependencies {
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
        }

        val jvmMain by getting {
            dependencies {
                implementation("org.apache.sshd:sshd-core:2.20.1")
                implementation("org.slf4j:slf4j-api:2.0.16")
                // MINA gets its Ed25519 key encoder/decoder from Bouncy Castle (or net.i2p eddsa); the JDK alone is not enough.
                implementation("org.bouncycastle:bcprov-jdk18on:1.80")
                implementation("org.bouncycastle:bcpkix-jdk18on:1.80")
            }
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
        }

        val jvmTest by getting {
            dependencies {
                implementation(kotlin("test-junit"))
                runtimeOnly("org.slf4j:slf4j-simple:2.0.16")
            }
        }
    }

    compilerOptions {
        allWarningsAsErrors.set(true)
    }
}
