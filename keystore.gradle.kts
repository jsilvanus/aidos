/**
 * M34: Signing configuration for F-Droid distribution.
 *
 * RFC-0050 § Distribution: Reproducible build, no proprietary dependencies, published.
 *
 * ## Key Management
 *
 * Aidos uses a two-key system for Android app distribution:
 *
 * 1. **Upload Key (developer-controlled)**
 *    - Used to sign APKs during local builds and CI
 *    - Kept private, typically in `~/.android/keystore` or CI secret
 *    - Cannot be used to sign app updates after F-Droid takes over
 *
 * 2. **Release Key (F-Droid-controlled)**
 *    - F-Droid generates and signs the final APK with their key
 *    - This ensures users can trust the distribution source
 *    - Prevents the developer from later distributing modified versions
 *
 * ## Configuration
 *
 * The upload key is read from environment or Gradle properties:
 * - Set `KEYSTORE_FILE`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`
 * - Or in local.properties: `keystore.file=...` etc
 *
 * For CI/CD:
 * - Secrets should be passed as environment variables
 * - Never commit the actual keystore file or passwords to the repository
 *
 * For F-Droid submission:
 * - The signing configuration below is ignored (F-Droid has their own)
 * - The metadata file `metadata/fi.italeino.aidos.yml` will declare F-Droid's key
 */

// This file is included by build.gradle.kts in the androidapp module.
// It centralizes signing setup.

fun getSigningConfig(): Map<String, String> {
    val keystore = project.findProperty("keystore.file") as? String
        ?: System.getenv("KEYSTORE_FILE")
        ?: System.getenv("HOME")?.let { "$it/.android/aidos-keystore.jks" }

    val keystorePassword = project.findProperty("keystore.password") as? String
        ?: System.getenv("KEYSTORE_PASSWORD")
        ?: ""

    val keyAlias = project.findProperty("key.alias") as? String
        ?: System.getenv("KEY_ALIAS")
        ?: "aidos-key"

    val keyPassword = project.findProperty("key.password") as? String
        ?: System.getenv("KEY_PASSWORD")
        ?: keystorePassword

    return mapOf(
        "keystore" to (keystore ?: ""),
        "keystorePassword" to keystorePassword,
        "keyAlias" to keyAlias,
        "keyPassword" to keyPassword
    )
}

/**
 * To use this in your android {} block:
 *
 *   android {
 *       ...
 *       signingConfigs {
 *           create("release") {
 *               val config = getSigningConfig()
 *               storeFile = config["keystore"]?.let { File(it) }
 *               storePassword = config["keystorePassword"]
 *               keyAlias = config["keyAlias"]
 *               keyPassword = config["keyPassword"]
 *           }
 *       }
 *       buildTypes {
 *           release {
 *               signingConfig = signingConfigs.getByName("release")
 *               minifyEnabled = true
 *               proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
 *           }
 *       }
 *   }
 */
