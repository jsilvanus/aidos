plugins {
    kotlin("multiplatform")
}

kotlin {
    jvm()

    sourceSets {
        commonMain.dependencies {
            // SpokenSummaryGenerator and VoiceApprovalHandler read execution-graph/run-summary
            // types from :androidapp and the voice-approval policy setting from :settings
            // (RFC-0057). Neither needs :kernel directly. See RFC-0103: this half of the
            // original voice module stays in agent/ — only the STT/TTS provider interface
            // (engine/voice) moved to Aidos Engine.
            implementation(project(":androidapp"))
            implementation(project(":settings"))
            implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.6.1")
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
        }

        val jvmTest by getting {
            dependencies {
                implementation(kotlin("test-junit"))
            }
        }
    }
}
