plugins {
    kotlin("jvm")
    application
}

kotlin {
    jvmToolchain(17)
}

// Sources live in src/jvmMain and src/jvmTest (multiplatform layout), but this is a plain JVM
// module, which only reads src/main and src/test: without this nothing here was ever compiled.
sourceSets {
    main { kotlin.srcDir("src/jvmMain/kotlin") }
    test { kotlin.srcDir("src/jvmTest/kotlin") }
}

dependencies {
    implementation(project(":kernel"))
    implementation(project(":downloads"))
    implementation(project(":modelruntime"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}

application {
    mainClass.set("dev.aidos.engine.cli.MainKt")
}

tasks.test {
    useJUnitPlatform()
}
