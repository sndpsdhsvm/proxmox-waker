#!/usr/bin/env bash
# Build the Proxmox Waker debug APK.
#
# The toolchain lives outside the system package manager:
#   - JDK 17 via mise  (mise use -g java@temurin-17)
#   - Android SDK in ~/Android/Sdk (cmdline-tools + platform 35 + build-tools 34/35)
#
# Usage: ./build.sh [gradle task...]   (default: assembleDebug)

set -euo pipefail

cd "$(dirname "$0")"

if command -v mise >/dev/null 2>&1; then
    JAVA_HOME="$(mise where java@temurin-17 2>/dev/null || true)"
fi

if [[ -z "${JAVA_HOME:-}" || ! -x "${JAVA_HOME}/bin/java" ]]; then
    echo "error: JDK 17 not found. Install it with: mise use -g java@temurin-17" >&2
    exit 1
fi

export JAVA_HOME
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"

if [[ ! -d "$ANDROID_HOME/platforms/android-35" ]]; then
    echo "error: Android SDK platform 35 not found in $ANDROID_HOME" >&2
    echo "Install with: sdkmanager 'platforms;android-35' 'build-tools;34.0.0' 'platform-tools'" >&2
    exit 1
fi

echo "JAVA_HOME=$JAVA_HOME"
echo "ANDROID_HOME=$ANDROID_HOME"

TASK="${1:-assembleDebug}"
exec ./gradlew "$TASK" "${@:2}"
