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
        }

        val jvmMain by getting {
            dependencies {
                implementation(project(":androidapp"))
            }
        }

        val jvmTest by getting {
            dependencies {
                implementation(kotlin("test-junit"))
            }
        }
    }
}
