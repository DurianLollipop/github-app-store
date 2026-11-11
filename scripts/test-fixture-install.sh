#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
export ADB_VENDOR_KEYS="$PWD/.tooling/emulator-home/adbkey"
ADB="${ANDROID_HOME:-$PWD/.tooling/android-sdk}/platform-tools/adb"
cleanup_fixture() { "$ADB" -s emulator-5554 uninstall dev.orbit.fixture >/dev/null 2>&1 || true; }
trap cleanup_fixture EXIT
cleanup_fixture
"$ADB" -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk
"$ADB" -s emulator-5554 install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
"$ADB" -s emulator-5554 shell mkdir -p /sdcard/Android/data/dev.githubstore/files/fixtures
"$ADB" -s emulator-5554 push test-fixture/build/outputs/apk/old/debug/test-fixture-old-debug.apk /sdcard/Android/data/dev.githubstore/files/fixtures/v1.apk
"$ADB" -s emulator-5554 push test-fixture/build/outputs/apk/newer/debug/test-fixture-newer-debug.apk /sdcard/Android/data/dev.githubstore/files/fixtures/v2.apk
"$ADB" -s emulator-5554 shell appops set dev.githubstore REQUEST_INSTALL_PACKAGES allow
"$ADB" -s emulator-5554 shell am instrument -w -e class dev.githubstore.FixtureInstallTest -e orbitFixture true dev.githubstore.test/androidx.test.runner.AndroidJUnitRunner > /tmp/orbit-fixture-install.log
python3 - <<'PY'
from pathlib import Path
text=Path('/tmp/orbit-fixture-install.log').read_text()
print(text[-7000:])
if 'OK (1 test)' not in text: raise SystemExit('安装夹具回归失败')
PY
