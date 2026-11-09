#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
ANDROID_SDK_ROOT="${ANDROID_HOME:-$PWD/.tooling/android-sdk}"
ADB="$ANDROID_SDK_ROOT/platform-tools/adb"
export ADB_VENDOR_KEYS="$PWD/.tooling/emulator-home/adbkey"
"$ADB" -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk
"$ADB" -s emulator-5554 install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
"$ADB" -s emulator-5554 shell pm clear dev.githubstore
"$ADB" -s emulator-5554 shell am instrument -w -e class dev.githubstore.StoreNavigationTest \
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
