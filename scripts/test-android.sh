#!/usr/bin/env bash
# Runs the Android acceptance tests on the connected device and reports what the
# device says.
#
# `./gradlew connectedDebugAndroidTest` runs the same tests but has been seen to
# mark the task FAILED after every test passed (AGP 9.4.1, wireless adb, a Pixel
# Fold): its post-run collection of "additional test output" from /sdcard fails,
# and switching that collection off breaks the task outright. The instrumentation
# result is the source of truth, so this asks for it directly.
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
adb="${ANDROID_HOME:-$HOME/Library/Android/sdk}/platform-tools/adb"
cd "$here/android"
./gradlew -q :app:assembleDebug :app:assembleDebugAndroidTest
"$adb" install -r -t app/build/outputs/apk/debug/app-debug.apk >/dev/null
"$adb" install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk >/dev/null
out="$("$adb" shell am instrument -w ai.pipestream.samples.courtsearch.test/androidx.test.runner.AndroidJUnitRunner)"
echo "$out"
grep -q '^OK (' <<<"$out"
