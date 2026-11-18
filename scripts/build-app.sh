#!/usr/bin/env bash
# Builds the Hivepal Android app (release APK) and, with --install, puts it on the phone over adb.
#
#   scripts/build-app.sh [--install]
#
# Needs JDK 17+ and the Android SDK (ANDROID_HOME, or ~/Android/Sdk). Without your own keystore the
# APK is signed with the repository's public debug key: fine for your phone, never for a store.
# Your key: HIVEPAL_KEYSTORE_FILE, HIVEPAL_KEYSTORE_PASSWORD, HIVEPAL_KEY_ALIAS (docs/release.md).
# The app needs no Firebase file: it gets the push settings from your relay when it pairs.
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
INSTALL=0
[[ "${1-}" == "--install" ]] && INSTALL=1
die() { printf '\033[1;31m✗ %s\033[0m\n' "$*" >&2; exit 1; }

command -v java >/dev/null || die "a JDK 17+ is needed (Fedora: sudo dnf install java-21-openjdk-devel; Debian/Ubuntu: sudo apt install openjdk-21-jdk)"
jv=$(java -version 2>&1 | sed -n 's/.*version "\([0-9]*\).*/\1/p' | head -n1)
[[ ${jv:-0} -ge 17 ]] || die "JDK 17+ is needed (found ${jv:-none})"

if [[ -z "${ANDROID_HOME:-}" ]]; then
  for d in "${ANDROID_SDK_ROOT:-}" "$HOME/Android/Sdk" "$HOME/Library/Android/sdk"; do
    [[ -n "$d" && -d "$d" ]] && { export ANDROID_HOME=$d; break; }
  done
fi
[[ -n "${ANDROID_HOME:-}" ]] || die "the Android SDK is needed: install Android Studio, or the command-line tools
  (https://developer.android.com/studio#command-line-tools-only) and then:
  sdkmanager 'platform-tools' 'platforms;android-36' 'build-tools;36.0.0'
  and set ANDROID_HOME to the SDK folder"

version=$(sed -n 's/^version = "\(.*\)"/\1/p' "$ROOT/daemon/pyproject.toml")
code=$((20000 + $(git -C "$ROOT" rev-list --count HEAD 2>/dev/null || echo 1)))
echo "Building Hivepal $version ($code)…"
cd "$ROOT/app-android"
./gradlew -q :app:assembleRelease "-PversionName=$version" "-PversionCode=$code"
apk="$ROOT/app-android/app/build/outputs/apk/release/app-release.apk"
out="$ROOT/hivepal-$version.apk"
cp "$apk" "$out"
echo "APK: $out"

if [[ $INSTALL == 1 ]]; then
  command -v adb >/dev/null || export PATH="$ANDROID_HOME/platform-tools:$PATH"
  adb get-state >/dev/null 2>&1 || die "no phone on adb: plug it in with USB debugging on, and accept the prompt"
  adb install -r "$out"
  echo "Installed. Open Hivepal on the phone and pair it (hivepal pair)."
fi
