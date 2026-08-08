plugins {
    kotlin("multiplatform")
}

kotlin {
    jvm()

    sourceSets {
        commonMain.dependencies {
            implementation(project(":kernel"))
        }

        jvmTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
