#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
ANDROID_SDK_ROOT="${ANDROID_HOME:-$PWD/.tooling/android-sdk}"
ADB="$ANDROID_SDK_ROOT/platform-tools/adb"
export ADB_VENDOR_KEYS="$PWD/.tooling/emulator-home/adbkey"
# Restricted to our emulator: this opt-in test installs TV Bro from its official GitHub release.
"$ADB" -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk
"$ADB" -s emulator-5554 install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
"$ADB" -s emulator-5554 shell pm grant dev.githubstore android.permission.POST_NOTIFICATIONS
"$ADB" -s emulator-5554 shell appops set dev.githubstore REQUEST_INSTALL_PACKAGES allow
"$ADB" -s emulator-5554 shell am instrument -w -e class dev.githubstore.LiveDownloadTest -e orbitLive true \
  dev.githubstore.test/androidx.test.runner.AndroidJUnitRunner > /tmp/github-store-live-install.log
python3 - <<'PY'
from pathlib import Path
text=Path('/tmp/github-store-live-install.log').read_text()
if 'OK (1 test)' not in text:
    print(text[-10000:])
    raise SystemExit('真实下载安装测试未通过')
print('真实 GitHub 下载、APK 校验、系统确认与安装结果验证通过')
PY
