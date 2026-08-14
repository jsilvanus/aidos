plugins {
    kotlin("multiplatform")
}

kotlin {
    jvmToolchain(21)
    jvm()

    sourceSets {
        commonMain.dependencies {
            // VoiceProviders.kt (SttProvider/TtsProvider) is the only thing left in this module
            // after RFC-0103's voice split — model-serving interfaces, hence :kernel for
            // ModelKind. SpokenSummaryGenerator and VoiceApprovalHandler moved to agent/voice;
            // this module no longer depends on :androidapp or :settings.
            implementation(project(":kernel"))
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
        }

        val jvmTest by getting {
            dependencies {
                implementation(kotlin("test-junit"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
            }
        }
    }
}
