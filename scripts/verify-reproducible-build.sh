#!/bin/bash
#
# M34: Verify reproducible build
#
# RFC-0050 § Distribution: "Reproducible build, no proprietary dependencies, published"
#
# This script verifies that two builds with identical source produce byte-for-byte
# identical APKs. This is a requirement for F-Droid distribution.
#
# Usage:
#   ./scripts/verify-reproducible-build.sh
#
# Environment:
#   SOURCE_DATE_EPOCH=<unix-timestamp>  Build timestamp (default: current time)
#
# Exit codes:
#   0: Builds are reproducible (identical byte-for-byte)
#   1: Builds are not reproducible (differ) — this is a bug
#   2: Build failed or prerequisite missing

set -e

BUILD_DIR="runtime/androidapp/build/outputs/apk/release"
APK1="/tmp/aidos-build-1.apk"
APK2="/tmp/aidos-build-2.apk"

# Set SOURCE_DATE_EPOCH for reproducibility
export SOURCE_DATE_EPOCH="${SOURCE_DATE_EPOCH:-$(date +%s)}"

echo "=== Aidos Reproducible Build Verification ==="
echo "BUILD_DIR: $BUILD_DIR"
echo "SOURCE_DATE_EPOCH: $SOURCE_DATE_EPOCH"
echo ""

# Check prerequisites
if [ ! -f "build.gradle.kts" ]; then
    echo "ERROR: Not in project root (build.gradle.kts not found)"
    exit 2
fi

if [ ! -d "scripts" ]; then
    echo "ERROR: scripts/ directory not found"
    exit 2
fi

# Build 1
echo "Building first APK (clean)..."
if [ -x "./gradlew" ]; then
    ./gradlew clean assembleRelease 2>/dev/null || {
        echo "ERROR: First build failed"
        exit 2
    }
else
    echo "ERROR: gradlew not found. Run from project root."
    exit 2
fi

if [ ! -f "$BUILD_DIR/app-release-unsigned.apk" ]; then
    echo "ERROR: APK not found at $BUILD_DIR/app-release-unsigned.apk"
    exit 2
fi

cp "$BUILD_DIR/app-release-unsigned.apk" "$APK1"
echo "✓ First build saved to $APK1"
echo ""

# Build 2
echo "Building second APK (clean)..."
./gradlew clean assembleRelease 2>/dev/null || {
    echo "ERROR: Second build failed"
    exit 2
}

if [ ! -f "$BUILD_DIR/app-release-unsigned.apk" ]; then
    echo "ERROR: APK not found at $BUILD_DIR/app-release-unsigned.apk"
    exit 2
fi

cp "$BUILD_DIR/app-release-unsigned.apk" "$APK2"
echo "✓ Second build saved to $APK2"
echo ""

# Compare
echo "Comparing builds..."
SIZE1=$(stat -c%s "$APK1" 2>/dev/null || stat -f%z "$APK1")
SIZE2=$(stat -c%s "$APK2" 2>/dev/null || stat -f%z "$APK2")

echo "APK1 size: $SIZE1 bytes"
echo "APK2 size: $SIZE2 bytes"

SHA1=$(sha256sum "$APK1" | awk '{print $1}')
SHA2=$(sha256sum "$APK2" | awk '{print $1}')

echo "APK1 SHA256: $SHA1"
echo "APK2 SHA256: $SHA2"
echo ""

if [ "$SHA1" = "$SHA2" ]; then
    echo "✅ BUILD REPRODUCIBLE"
    echo ""
    echo "Both builds are identical. Ready for F-Droid submission."
    rm -f "$APK1" "$APK2"
    exit 0
else
    echo "❌ BUILD NOT REPRODUCIBLE"
    echo ""
    echo "Builds differ. This is a bug. Investigate:"
    echo "  - Timestamps in JAR/APK entries"
    echo "  - Dependency version mismatch"
    echo "  - Non-deterministic bytecode generation"
    echo "  - Kotlin compiler non-determinism"
    echo ""
    echo "APK files retained for analysis:"
    echo "  APK1: $APK1"
    echo "  APK2: $APK2"
    exit 1
fi
