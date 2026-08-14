# Android APK Build and Publish Guide

This guide explains how to build and publish the Aidos Android app to GitHub Packages.

## Overview

The GitHub Actions workflow (`android-build-and-publish.yml`) automatically:
1. Builds the Android APK in release mode
2. Signs the APK (optional, requires keystore secret)
3. Publishes the APK to GitHub Packages Maven repository
4. Uploads the APK as a workflow artifact

## Prerequisites

### Local Setup (for manual builds)

```bash
# Clone the repository
git clone https://github.com/jsilvanus/aidos.git
cd aidos/agent

# Ensure you have Java 21 installed
java -version

# Build the APK
gradle :androidapp:assembleRelease
```

The APK will be located at:
```
agent/androidapp/build/outputs/apk/release/androidapp-release.apk
```

## GitHub Actions Workflow Setup

### Option 1: Unsigned Builds (No Keystore)

The workflow will build an unsigned APK without any additional configuration.

**Triggers:**
- Push to `main` or `develop` branches
- Pull requests to `main` or `develop` branches
- Manual trigger via workflow_dispatch

### Option 2: Signed Builds (Recommended for Production)

To sign APKs with your release key, configure these GitHub Secrets:

1. **KEYSTORE_BASE64** (Required)
   - Your keystore file encoded as base64
   - Generate with:
     ```bash
     base64 -i your-keystore.jks | tr -d '\n' | pbcopy
     # Or on Linux:
     base64 your-keystore.jks | tr -d '\n' | xclip -selection clipboard
     ```
   - Add to GitHub > Settings > Secrets and variables > Actions > New repository secret

2. **KEYSTORE_PASSWORD** (Required)
   - Password for the keystore file

3. **KEY_ALIAS** (Required)
   - Alias of the signing key within the keystore
   - Default: `aidos-key`

4. **KEY_PASSWORD** (Required)
   - Password for the specific key (often same as KEYSTORE_PASSWORD)

### Setting GitHub Secrets

1. Go to your repository on GitHub
2. Settings → Secrets and variables → Actions
3. Click "New repository secret"
4. Add each secret with the exact names above
5. Save

## Creating a Keystore for Signing

If you don't have a keystore yet, create one:

```bash
keytool -genkey -v -keystore aidos-keystore.jks \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -alias aidos-key -storepass your-password -keypass your-password \
  -dname "CN=Your Name, O=Your Organization, C=US"
```

Then encode it for GitHub:
```bash
base64 aidos-keystore.jks
```

**⚠️ Security Warning:** Never commit the keystore file to the repository. Only store the base64-encoded version in GitHub Secrets.

## Publishing to GitHub Packages

The workflow automatically publishes the APK to GitHub Packages Maven repository:

**Repository URL:**
```
https://maven.pkg.github.com/jsilvanus/aidos
```

**Published Artifact Details:**
- **Group ID:** `fi.italeino`
- **Artifact ID:** `aidos-android`
- **Version:** `0.1.0-build.<BUILD_NUMBER>+<COMMIT_SHA>`
- **Packaging:** `apk`

### Consuming the Published APK

To download the APK from GitHub Packages:

```bash
# Using Maven
# Configure your settings.xml with GitHub credentials
mvn dependency:copy \
  -Dartifact=fi.italeino:aidos-android:0.1.0-build.123+abc12345:apk \
  -DoutputDirectory=./downloads

# Using curl (requires GitHub token)
curl -H "Authorization: token YOUR_GITHUB_TOKEN" \
  -L "https://maven.pkg.github.com/jsilvanus/aidos/fi/italeino/aidos-android/0.1.0-build.123/aidos-android-0.1.0-build.123.apk" \
  -o aidos-android.apk
```

## Workflow Artifacts

After each build, the APK is also available as a GitHub Actions workflow artifact:

1. Go to the workflow run
2. Download the `aidos-android-release-apk` artifact
3. Artifacts are retained for 30 days

## Troubleshooting

### APK Build Fails

**Check build logs:**
```bash
cd agent
gradle :androidapp:assembleRelease --no-daemon -i
```

**Common issues:**
- Java version mismatch (requires Java 21)
- Missing dependencies
- Android SDK issues

### Publishing to GitHub Packages Fails

- Verify `GITHUB_TOKEN` has `packages:write` permission
- Check that secrets are correctly configured
- Verify network connectivity to `maven.pkg.github.com`

### Signing Issues

- Verify keystore password is correct
- Confirm key alias matches the one in the keystore
- Check that keystore file is valid:
  ```bash
  keytool -list -v -keystore aidos-keystore.jks
  ```

## Build Configuration

### Android Configuration

Located in `agent/androidapp/build.gradle.kts`:

- **Namespace:** `fi.italeino.aidos`
- **Min SDK:** 26 (Android 8.0)
- **Target SDK:** 34 (Android 14)
- **Java Target:** 11

### Signing Configuration

Signing keys are configured via environment variables (set by GitHub Secrets):
- `KEYSTORE_FILE`: Path to keystore
- `KEYSTORE_PASSWORD`: Keystore password
- `KEY_ALIAS`: Key alias in keystore
- `KEY_PASSWORD`: Key password

### Gradle Properties

For local builds, you can also set signing properties in `agent/gradle.properties`:
```properties
keystore.file=/path/to/aidos-keystore.jks
keystore.******
key.alias=aidos-key
key.******
```

## Next Steps

1. [Set up GitHub Secrets](#github-actions-workflow-setup) for production builds
2. Push a commit to trigger the workflow
3. Monitor the workflow run in GitHub Actions
4. Download the APK from workflow artifacts or GitHub Packages
5. Test on Android devices (min API 26)

## References

- [GitHub Actions Documentation](https://docs.github.com/en/actions)
- [GitHub Packages Maven Registry](https://docs.github.com/en/packages/working-with-a-github-packages-registry/working-with-the-gradle-registry)
- [Android Signing Documentation](https://developer.android.com/studio/publish/app-signing)
- [Android Build System (Gradle)](https://developer.android.com/build)
