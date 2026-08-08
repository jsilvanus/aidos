plugins {
    kotlin("multiplatform")
}

kotlin {
    jvm()
    androidTarget()

    sourceSets {
        val jvmMain by getting {
            dependencies {
                implementation(project(":kernel"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
                // gitsema-kotlin — the retrieval core (RFC-0015, M22).
                // JVM variant: commonMain + jvmAndroidMain + jvmMain compiled into one artifact.
                // Published to GitHub Packages; credentials via GITHUB_TOKEN (env var, not secrets).
                implementation("io.github.jsilvanus:gitsema-core-jvm:0.1.0-SNAPSHOT")
                // JGit for the GitRepository adapter that gitsema uses to walk the repository.
                implementation("org.eclipse.jgit:org.eclipse.jgit:7.1.0.202411261347-r")
            }
        }

        val jvmTest by getting {
            dependencies {
                implementation(kotlin("test-junit"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
                // JGit test repositories.
                implementation("org.eclipse.jgit:org.eclipse.jgit:7.1.0.202411261347-r")
            }
        }

        val androidMain by getting {
            dependencies {
                implementation(project(":kernel"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
                implementation("io.github.jsilvanus:gitsema-core-androidRelease:0.1.0-SNAPSHOT")
            }
        }
    }

    compilerOptions {
        allWarningsAsErrors.set(true)
    }
}
