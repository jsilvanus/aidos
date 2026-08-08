plugins {
    kotlin("multiplatform")
}

kotlin {
    jvm()

    sourceSets {
        commonMain.dependencies {
            implementation(project(":kernel"))
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
        }

        jvmTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
