#!/usr/bin/env bash
# Runs inside the Android emulator job, after the smoke test: checks Settings > App updates.
# It taps "Check for updates" and expects a clear answer (up to date, an update is ready, or a
# "couldn't check" message such as no connection or nothing published yet), and that the app
# doesn't crash. It can't expect "an update is ready" here, because that needs a newer published
# build than this one.
# Usage: updater-test.sh <apk> <output dir>
set -euo pipefail

APK="$1"
OUT="$2"
PKG=io.github.akrishna87.podcasts
HERE="$(cd "$(dirname "$0")" && pwd)"
mkdir -p "$OUT"

fail() {
  echo "UPDATER TEST FAILED: $*"
  adb exec-out screencap -p > "$OUT/failure.png" || true
  if adb shell uiautomator dump /sdcard/ui.xml > /dev/null 2>&1 && adb pull /sdcard/ui.xml "$OUT/failure.xml" > /dev/null 2>&1; then
    echo "On screen:"; grep -o '\(text\|content-desc\)="[^"]\+"' "$OUT/failure.xml" | head -60 || true
  fi
  adb logcat -d > "$OUT/logcat.txt" || true
  echo "App log (errors and warnings):"
  grep -A 40 "FATAL EXCEPTION" "$OUT/logcat.txt" | head -60 || true
  grep -E "AppUpdater" "$OUT/logcat.txt" | tail -20 || true
  exit 1
}
dump() {
  local i
  for i in 1 2 3; do
    adb shell uiautomator dump /sdcard/ui.xml > /dev/null 2>&1 && adb pull /sdcard/ui.xml "$OUT/$1.xml" > /dev/null 2>&1 || { sleep 2; continue; }
    # The emulator's own apps sometimes freeze; wave the "isn't responding" popup away.
    grep -q "isn&apos;t responding\|isn't responding" "$OUT/$1.xml" || return 0
    python3 "$HERE/find_text.py" "$OUT/$1.xml" "Wait" > /dev/null 2>&1 && adb shell input tap $(python3 "$HERE/find_text.py" "$OUT/$1.xml" "Wait")
    sleep 3
  done
}
tap() { # tap <dump name> <text>
  local xy
  xy=$(python3 "$HERE/find_text.py" "$OUT/$1.xml" "$2") || fail "couldn't find '$2' on screen"
  adb shell input tap $xy
}

adb wait-for-device
adb install -r "$APK"

echo "== The APK asks for the permissions the updater needs"
requested=$(adb shell dumpsys package "$PKG")
grep -q "android.permission.INTERNET" <<<"$requested" || fail "the APK doesn't ask for INTERNET"
grep -q "android.permission.REQUEST_INSTALL_PACKAGES" <<<"$requested" || fail "the APK doesn't ask for REQUEST_INSTALL_PACKAGES"

echo "== Open Settings"
adb shell pm clear "$PKG" > /dev/null
adb shell settings put global hide_error_dialogs 1 || true
adb shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS 2> /dev/null || true
adb logcat -c
adb shell am start -W -n "$PKG/.MainActivity"
sleep 6
dump home
tap home "Settings"
sleep 2
dump settings
grep -q 'text="PLAYBACK"' "$OUT/settings.xml" || fail "tapping Settings on Home didn't open the Settings screen"

size=$(adb shell wm size | grep -o '[0-9]*x[0-9]*' | tail -1)
W=${size%x*}
H=${size#*x}
found=no
for _ in $(seq 1 15); do
  dump settings
  if b=$(python3 "$HERE/find_text.py" "$OUT/settings.xml" "Check for updates" exact-bounds 2> /dev/null); then
    read -r _ _ _ y2 <<<"$b"
    # Whole button on screen, clear of the bottom navigation bar.
    if [ "$y2" -lt $((H * 84 / 100)) ]; then found=yes; break; fi
  fi
  # A third of the screen, slowly, so the list doesn't fling past the button.
  adb shell input swipe $((W / 2)) $((H * 2 / 3)) $((W / 2)) $((H / 3)) 600
  sleep 1
done
[ "$found" = yes ] || fail "Settings has no 'Check for updates' button"
grep -q "Podwave build [0-9]" "$OUT/settings.xml" || fail "Settings doesn't say which build is installed"
adb exec-out screencap -p > "$OUT/updater-before.png"
echo "PASS: Settings shows the installed build and a Check for updates button"

echo "== Tap Check for updates"
adb shell input tap $(python3 "$HERE/find_text.py" "$OUT/settings.xml" "Check for updates" exact)
answer=""
for _ in $(seq 1 40); do
  sleep 1
  dump result
  if grep -q "You have the latest build" "$OUT/result.xml"; then answer="up to date"; break; fi
  if grep -q "is ready\." "$OUT/result.xml"; then answer="an update is ready"; break; fi
  if grep -q "t check for updates\." "$OUT/result.xml"; then answer="couldn't check"; break; fi
done
adb exec-out screencap -p > "$OUT/updater-result.png"
adb logcat -d > "$OUT/logcat.txt" || true
echo "What the app says: ${answer:-nothing}"
grep -E "AppUpdater" "$OUT/logcat.txt" | tail -5 || true
[ -n "$answer" ] || fail "tapping Check for updates never produced an answer"
grep -q "FATAL EXCEPTION" "$OUT/logcat.txt" && fail "the app crashed while checking for updates"
grep -o 'text="[^"]*\(latest build\|is ready\|check for updates\.\)[^"]*"' "$OUT/result.xml" | head -3 || true
echo "PASS: Check for updates answers ($answer) and the app keeps running"
