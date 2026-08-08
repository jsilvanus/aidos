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
            implementation("app.cash.sqldelight:runtime:2.0.2")
        }

        jvmMain.dependencies {
            implementation("app.cash.sqldelight:sqlite-driver:2.0.2")
        }

        jvmTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
