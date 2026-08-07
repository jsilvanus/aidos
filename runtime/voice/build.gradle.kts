plugins {
    kotlin("multiplatform")
}

kotlin {
    jvm()
    
    sourceSets {
        commonMain.dependencies {
            implementation(project(":kernel"))
            implementation(project(":settings"))
            implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.6.1")
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
        }

        val jvmMain by getting {
            dependencies {
                implementation(project(":androidapp"))
            }
        }

        val jvmTest by getting {
            dependencies {
                implementation(kotlin("test-junit"))
                implementation(project(":androidapp"))
            }
        }
    }
}
