#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
if [ -d ".tooling/android-sdk" ]; then
  export ANDROID_HOME="$PWD/.tooling/android-sdk"
fi
if [ -x "/Users/durianlollipop/Library/Java/JavaVirtualMachines/jdk-17.0.18.jdk/Contents/Home/bin/java" ]; then
  export JAVA_HOME="/Users/durianlollipop/Library/Java/JavaVirtualMachines/jdk-17.0.18.jdk/Contents/Home"
fi
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-$PWD/.gradle-home}"
python3 scripts/catalog.py validate
if [ -x .tooling/gradle/gradle-8.11.1/bin/gradle ]; then
  .tooling/gradle/gradle-8.11.1/bin/gradle testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest --console=plain
else
  ./gradlew testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest --console=plain
fi
