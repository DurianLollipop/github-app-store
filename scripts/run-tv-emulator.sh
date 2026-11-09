#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
export ANDROID_HOME="${ANDROID_HOME:-$PWD/.tooling/android-sdk}"
export ANDROID_USER_HOME="$PWD/.tooling/android-user"
export ANDROID_AVD_HOME="$PWD/.tooling/avd"
export ANDROID_EMULATOR_HOME="$PWD/.tooling/emulator-home"
export ADB_VENDOR_KEYS="$ANDROID_EMULATOR_HOME/adbkey"
mkdir -p "$ANDROID_AVD_HOME" "$ANDROID_EMULATOR_HOME"
if [ -x "/Users/durianlollipop/Library/Java/JavaVirtualMachines/jdk-17.0.18.jdk/Contents/Home/bin/java" ]; then
  export JAVA_HOME="/Users/durianlollipop/Library/Java/JavaVirtualMachines/jdk-17.0.18.jdk/Contents/Home"
fi
if [ ! -d "$ANDROID_AVD_HOME/orbit-tv.avd" ]; then
  printf 'no\n' | "$ANDROID_HOME/cmdline-tools/latest/bin/avdmanager" create avd -n orbit-tv \
    -k 'system-images;android-33;android-tv;arm64-v8a' -d tv_1080p
fi
exec "$ANDROID_HOME/emulator/emulator" -avd orbit-tv -no-window -no-audio -no-boot-anim \
  -no-snapshot -no-metrics -skip-adb-auth -gpu swiftshader_indirect -memory 2048 -cores 2 -port 5554
