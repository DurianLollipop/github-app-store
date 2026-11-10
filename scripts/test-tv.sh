#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
ANDROID_SDK_ROOT="${ANDROID_HOME:-$PWD/.tooling/android-sdk}"
ADB="$ANDROID_SDK_ROOT/platform-tools/adb"
export ADB_VENDOR_KEYS="$PWD/.tooling/emulator-home/adbkey"
if [ "${1:-}" = "--reduced-motion" ]; then
  shift
  original_motion_scale="$("$ADB" -s emulator-5554 shell settings get global animator_duration_scale)"
  restore_motion_scale() {
    if [ "$original_motion_scale" = "null" ]; then
      "$ADB" -s emulator-5554 shell settings delete global animator_duration_scale >/dev/null
    else
      "$ADB" -s emulator-5554 shell settings put global animator_duration_scale "$original_motion_scale"
    fi
  }
  trap restore_motion_scale EXIT
  "$ADB" -s emulator-5554 shell settings put global animator_duration_scale 0
fi
"$ADB" -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk
"$ADB" -s emulator-5554 install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
"$ADB" -s emulator-5554 shell pm clear dev.githubstore
"$ADB" -s emulator-5554 shell am instrument -w -e class "${1:-dev.githubstore.StoreNavigationTest,dev.githubstore.DetailReadingTest,dev.githubstore.HomeGridTest}" \
  dev.githubstore.test/androidx.test.runner.AndroidJUnitRunner > /tmp/github-store-ui-tests.log
python3 - <<'PY'
from pathlib import Path
import re
text=Path('/tmp/github-store-ui-tests.log').read_text()
match=re.search(r'OK \((\d+) tests?\)',text)
if not match:
    print(text[-10000:])
    raise SystemExit('原生 UI 测试未通过，详见 /tmp/github-store-ui-tests.log')
print('Android TV 原生 UI 测试通过：'+match[1]+' 项')
PY
