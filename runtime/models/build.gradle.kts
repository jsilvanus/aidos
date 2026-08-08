plugins {
    kotlin("multiplatform")
}

kotlin {
    jvm()

    sourceSets {
        commonMain.dependencies {
            api(project(":cookbook"))
            api(project(":kernel"))
            api(project(":downloads"))
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
        }

        jvmTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
