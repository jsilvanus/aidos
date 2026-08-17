plugins {
    kotlin("jvm")
    application
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":modelruntime"))
    implementation(project(":models"))
    implementation(project(":huggingface"))
    implementation(project(":downloads"))
    implementation(project(":cookbook"))

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")

    testImplementation(kotlin("test"))
}

application {
    mainClass.set("dev.aidos.engine.cli.MainKt")
}

tasks.test {
    useJUnitPlatform()
}
