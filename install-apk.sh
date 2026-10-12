#!/usr/bin/env bash
# Install the debug APK on a connected phone.
# Lists connected devices, lets you pick one if there are several, and
# offers to build the APK (./gradlew assembleDebug) if it doesn't exist yet.
set -euo pipefail

cd "$(dirname "$0")"
APK="app/build/outputs/apk/debug/app-debug.apk"

command -v adb >/dev/null || { echo "adb not found on PATH" >&2; exit 1; }

# Connected phones (state "device"; skips offline/unauthorized ones).
serials=()
while read -r serial state; do
  [[ "$state" == "device" ]] && serials+=("$serial")
done < <(adb devices | tail -n +2 | grep -v '^$' || true)

echo "Connected phones:"
if [[ ${#serials[@]} -eq 0 ]]; then
  echo "  (none)"
  adb devices | tail -n +2 | grep -v '^$' | sed 's/^/  not ready: /' || true
  exit 1
fi
for i in "${!serials[@]}"; do
  s="${serials[$i]}"
  model=$(adb -s "$s" shell getprop ro.product.model </dev/null 2>/dev/null | tr -d '\r')
  echo "  $((i + 1))) $s  ${model:-unknown model}"
done

if [[ ${#serials[@]} -eq 1 ]]; then
  device="${serials[0]}"
else
  read -rp "Install on which phone? [1-${#serials[@]}] " choice
  if ! [[ "$choice" =~ ^[0-9]+$ ]] || (( choice < 1 || choice > ${#serials[@]} )); then
    echo "Invalid choice" >&2; exit 1
  fi
  device="${serials[$((choice - 1))]}"
fi

if [[ ! -f "$APK" ]]; then
  echo "$APK does not exist."
  read -rp "Build it now with ./gradlew assembleDebug? [y/N] " answer
  [[ "$answer" =~ ^[Yy]$ ]] || { echo "Not building; nothing installed."; exit 1; }
  ./gradlew assembleDebug
fi

echo "Installing $APK on $device..."
adb -s "$device" install -r "$APK"
