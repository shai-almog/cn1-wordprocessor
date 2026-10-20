#!/usr/bin/env bash
# Cold-start timing on a running emulator: six force-stopped launches of the installed APK,
# ActivityManager's TotalTime (process start to first frame) appended to android-launch-ms.txt.
set -euo pipefail
apk=$1
adb install -r "$apk"
pkg=com.codenameone.wordprocessor.app
act=$(adb shell cmd package resolve-activity --brief "$pkg" | tail -1 | tr -d '\r')
[[ $act == */* ]] || { echo "No launcher activity for $pkg: $act" >&2; exit 1; }
for i in 1 2 3 4 5 6; do
  adb shell am force-stop "$pkg"
  sleep 1
  out=$(adb shell am start -W -n "$act" | tr -d '\r')
  ms=$(awk '/TotalTime/{print $2}' <<<"$out")
  [[ -n $ms ]] || { echo "$out" >&2; exit 1; }
  echo "$ms" >> android-launch-ms.txt
  sleep 2
done
