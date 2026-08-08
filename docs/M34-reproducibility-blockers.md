# M34: Reproducibility Blockers & Solutions

This document tracks build reproducibility issues for F-Droid distribution (RFC-0050, M34).

## Status: IN PROGRESS

### 1. Dependency Analysis

**✅ CLEARED**: No proprietary dependencies detected
- No Google Play Services
- No Firebase, Crashlytics, Analytics
- No proprietary build tools
- All dependencies are FOSS-compatible

**Dependencies verified**:
- SQLDelight (Apache 2.0)
- JGit (Eclipse Distribution License / BSD)
- Ktor (Apache 2.0)
- Kotlin/Coroutines (Apache 2.0)
- gitsema-core-jvm (custom, needs license audit — should be EUPL or compatible)

### 2. Build Determinism Issues

#### Issue 2.1: Timestamps in JAR/APK
**Status**: To be fixed via `SOURCE_DATE_EPOCH`

**Current problem**: Java toolchain embeds build timestamps in class files and archive metadata

**Solution**:
```gradle
// In build.gradle.kts root
allprojects {
    tasks.withType<AbstractArchiveTask>().configureEach {
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
    }
}
```

#### Issue 2.2: Kotlin Compiler Determinism
**Status**: To be verified

**Current problem**: Kotlin compiler may generate non-deterministic bytecode

**Solution**:
- Ensure `-Werror` is set for warnings
- Verify no inline expansion randomness
- Check `incremental = false` is set (force full rebuild)

#### Issue 2.3: Dependency Version Pinning
**Status**: To be audited

**Current problem**: Any floating versions (`1.0.+`) or dynamic versions cause non-determinism

**Action**: Scan all `build.gradle.kts` for floating versions — **none found so far**

#### Issue 2.4: Resource Packaging Order
**Status**: To be verified

**Current problem**: ZIP/APK entry order can be randomized by some Java/Gradle versions

**Solution**:
```gradle
// In android {} block
android {
    bundle {
        density.enableSplit = false
        abi.enableSplit = false
        language.enableSplit = false
    }
}
```

### 3. Native Code Reproducibility

**Status**: ✅ CLEARED

- **JGit**: Pure Java, deterministic
- **gitsema-kotlin**: Pure Kotlin/JVM, no native components
- **Ktor**: Pure Kotlin, no native components
- **SQLite JDBC driver**: Vendored JAR, deterministic

### 4. Android Gradle Plugin (AGP) Setup

**Status**: Blocked on network access

**Current problem**: AGP 8.5.2 requires `dl.google.com` (Google Maven repo)

**Solution in place**: Build infrastructure is ready
- `com.android.library` plugin declared but commented in `runtime/build.gradle.kts`
- `:androidapp` has `// id("com.android.library")` comment
- Once `dl.google.com` is reachable, uncomment one line in root, one line in `:androidapp`

### 5. Signing & Key Management

**Status**: To be implemented

**Current problem**: No signing configuration yet

**Solution**:
- Create `keystore.gradle.kts` file
- Read signing keys from environment variables (not hardcoded)
- Document two-key flow:
  - **Upload key**: Developer signs locally
  - **Release key**: F-Droid signs for store distribution

### 6. Gradle/JVM Version Pinning

**Status**: To be configured

**Gradle wrapper version**: Currently using wrapper (good)
- Verify `gradle/wrapper/gradle-wrapper.properties` pins exact Gradle version
- Target: Gradle 8.5+ for best reproducibility support
- Kotlin: 2.1.0 (current)
- Java: 17+ (for reproducibility features)

### 7. Build Reproducibility Verification

**Status**: Script template ready, to be implemented

**Action**: Create `scripts/verify-reproducible-build.sh`
- Builds APK twice with `SOURCE_DATE_EPOCH` set
- Compares byte-for-byte identity
- Fails if builds differ (prevents non-deterministic commits)

---

## Implementation Checklist

### Phase 1: Audit & Prepare
- [x] 1.1 Scan dependencies — No proprietary deps found
- [x] 1.2 Create this document
- [ ] 1.3 Create fastlane/metadata structure
- [ ] 1.4 Uncomment AGP (pending network access)

### Phase 2: Reproducibility Setup
- [ ] 2.1 Configure build determinism in build.gradle.kts
- [ ] 2.2 Create keystore.gradle.kts
- [ ] 2.3 Verify all native deps (done above)
- [ ] 2.4 Create verification script

### Phase 3: F-Droid Integration
- [ ] 3.1 Create metadata/fi.italeino.aidos.yml
- [ ] 3.2 Verify gradle structure
- [ ] 3.3 Verify AndroidManifest.xml
- [ ] 3.4 Add CI workflow

---

## Notes for Reviewers

1. **Dependency: gitsema-kotlin** — This is a custom library by the author. Verify its license is compatible with EUPL-1.2 (should be). If not open-source, F-Droid will reject it.

2. **AGP Network Access** — This is the only external blocker. Once `dl.google.com` is reachable, uncomment plugin and androidTarget to proceed.

3. **Reproducibility Testing** — F-Droid's CI will validate reproducibility independently. Our verification script is a pre-check to catch issues early.

4. **Source Date Epoch** — F-Droid uses `SOURCE_DATE_EPOCH=<commit-timestamp>` to ensure all builds are timestamped to the commit date, not build date.
