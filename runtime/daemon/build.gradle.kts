plugins {
    kotlin("multiplatform")
}

kotlin {
    jvm()

    sourceSets {
        val jvmMain by getting {
            dependencies {
                implementation(project(":kernel"))
                implementation(project(":api"))
                implementation(project(":cli"))
                implementation(project(":storage"))
                implementation(project(":identity"))
                implementation(project(":executor"))
                implementation(project(":broker"))
                implementation(project(":capability"))
                implementation(project(":filesystem"))
                implementation(project(":git"))
                implementation(project(":prompt"))
                implementation(project(":routing"))
                implementation(project(":vault"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
                implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.6.1")
                implementation("app.cash.sqldelight:sqlite-driver:2.0.2")
                implementation("org.xerial:sqlite-jdbc:3.45.2.0")
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
