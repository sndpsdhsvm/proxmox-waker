#!/usr/bin/env bash
# Install the debug APK onto a connected phone and launch it.
#
# Requires: USB debugging enabled, phone connected, and `adb devices` showing it.

set -euo pipefail

cd "$(dirname "$0")"

export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
ADB="$ANDROID_HOME/platform-tools/adb"
APK="app/build/outputs/apk/debug/app-debug.apk"

if [[ ! -x "$ADB" ]]; then
    echo "error: adb not found at $ADB" >&2
    exit 1
fi

if [[ ! -f "$APK" ]]; then
    echo "APK missing, building first..."
    ./build.sh assembleDebug
fi

if [[ -z "$("$ADB" devices | sed '1d' | grep -w device || true)" ]]; then
    echo "error: no device detected. Run '$ADB devices' and accept the USB debugging prompt." >&2
    exit 1
fi

echo "Installing $APK..."
"$ADB" install -r "$APK"

echo "Launching..."
"$ADB" shell monkey -p dev.sndp.proxmoxwaker -c android.intent.category.LAUNCHER 1 >/dev/null

echo "Done. The app is on your phone."
