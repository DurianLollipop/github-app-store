#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
export ADB_VENDOR_KEYS="$PWD/.tooling/emulator-home/adbkey"
ADB="${ANDROID_HOME:-$PWD/.tooling/android-sdk}/platform-tools/adb"
original_size="$("$ADB" -s emulator-5554 shell wm size | awk '/Override size:/ {print $3}')"
original_density="$("$ADB" -s emulator-5554 shell wm density | awk '/Override density:/ {print $3}')"
restore_display() {
    if [ -n "$original_size" ]; then "$ADB" -s emulator-5554 shell wm size "$original_size"; else "$ADB" -s emulator-5554 shell wm size reset; fi
    if [ -n "$original_density" ]; then "$ADB" -s emulator-5554 shell wm density "$original_density"; else "$ADB" -s emulator-5554 shell wm density reset; fi
}
trap restore_display EXIT
"$ADB" -s emulator-5554 shell wm size 1280x720
"$ADB" -s emulator-5554 shell wm density 213
./scripts/test-tv.sh --reduced-motion "$@"
