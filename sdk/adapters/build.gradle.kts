plugins {
    kotlin("multiplatform")
    id("com.android.library")
    id("maven-publish")
}

kotlin {
    jvmToolchain(21)

    androidTarget {
        // Required for the Android target to get a Maven publication at all (Dictator plan S3) —
        // without it, `publishing {}` below silently has nothing to publish for this target.
        publishLibraryVariants("release")
    }
    jvm()

    applyDefaultHierarchyTemplate()

    sourceSets {
        // Matches sdk/client's shape: commonMain can't see java.util (Base64), so the actual
        // code lives in this intermediate source set shared by both targets.
        val jvmAndAndroidMain by creating {
            dependsOn(commonMain.get())

            dependencies {
                // RFC-0103 MVP item 5: the ModelAdapter bindings, which necessarily depend on
                // :kernel — the reason this is a separate artifact from :client (Dictator plan
                // D-1). `api`, not `implementation`, for both: ModelAdapter/ModelRequest/
                // ModelResponse (kernel) and AidosEngineClient (client) all appear directly in
                // this module's public class signatures (EngineLocalModelAdapter etc.), so a
                // consumer needs them on its own compile classpath, not just at runtime.
                api(project(":kernel"))
                api(project(":client"))

                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
            }
        }

        androidMain.get().dependsOn(jvmAndAndroidMain)
        jvmMain.get().dependsOn(jvmAndAndroidMain)

        jvmTest.dependencies {
            implementation(kotlin("test"))
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
        }
    }
}

android {
    namespace = "fi.italeino.aidos.sdk.adapters"
    compileSdk = 34

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

group = "fi.italeino.aidos.sdk"
// Dictator plan D-4 — see sdk/client/build.gradle.kts's identical comment.
version = (findProperty("aidosSdkVersion") as String?) ?: "0.1.0"

publishing {
    // See sdk/client/build.gradle.kts: renames each target's default artifactId ("adapters",
    // "adapters-jvm", ...) to the aidos-sdk-adapters name the plan specifies.
    //
    // :kernel now has real Maven coordinates too (kernel/build.gradle.kts) — this module's
    // published POM references it by kernel's own group/artifactId/version (Gradle's normal
    // project-dependency substitution), which resolves against GitHub Packages instead of the
    // groupId=aidos-sdk/version=unspecified placeholder Gradle emits for an unpublished project
    // dependency. Publishing :kernel this way doesn't change that it's still source-included by
    // path everywhere in-monorepo (RFC-0103); it only means a genuinely external consumer can also
    // resolve it as an ordinary transitive dependency.
    publications.withType<MavenPublication>().configureEach {
        artifactId = artifactId.replace(project.name, "aidos-sdk-${project.name}")
    }

    repositories {
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/jsilvanus/aidos")
            credentials {
                username = System.getenv("GITHUB_ACTOR") ?: "token"
                password = System.getenv("GITHUB_TOKEN") ?: ""
            }
        }
    }
}
