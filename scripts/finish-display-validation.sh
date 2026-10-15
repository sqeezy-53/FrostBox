#!/usr/bin/env bash
# Run in a normal Linux terminal with USB, network and local socket access.
# Never clears app data or replaces the qualified Wine base archive.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
WINE_WORK=$(realpath "${1:?Usage: finish-display-validation.sh WINE_WORK NEW_OUTPUT_DIR [SERIAL]}")
RESULT=${2:?Choose a new results directory}
SERIAL=${3:?Pass your ADB device serial as argument 3}
[[ ! -e "$RESULT" ]] || { echo 'Results directory already exists; preserve previous evidence' >&2; exit 1; }
mkdir -p "$RESULT"; RESULT=$(realpath "$RESULT")
cd "$ROOT"
: "${ANDROID_HOME:?Set SDK path}"
export ANDROID_NDK_HOME="${ANDROID_NDK_HOME:-$ANDROID_HOME/ndk/27.2.12479018}"
exec > >(tee "$RESULT/build-and-validation.log") 2>&1
# Fail before any build/install if this shell cannot access sockets/USB/ADB.
adb -s "$SERIAL" get-state
adb -s "$SERIAL" shell getprop > "$RESULT/device-properties.txt"
python3 - <<'PY'
import socket
with socket.socket() as s: s.bind(('127.0.0.1',0))
PY
BASE=build/wine-probe-package/assets/runtime/wine-probe.zip
sha256sum "$BASE" > "$RESULT/base-wine-before.sha256"
scripts/build-compat-lab.sh
scripts/build-display-driver.sh "$WINE_WORK"
sha256sum --check "$RESULT/base-wine-before.sha256"
./gradlew -PwithBox64=true -PwithWineProbe=true -PwithWindowsDisplay=true \
    :core:test :android-app:testDebugUnitTest :android-app:lintDebug \
    :android-app:assembleDebug :android-app:assembleDebugAndroidTest
cmake -S runtime/native -B build/native -G Ninja
cmake --build build/native -j4
ctest --test-dir build/native --output-on-failure
# Names reserved for newly built candidates only. No old APK relabeling.
cp android-app/build/outputs/apk/debug/android-app-debug.apk "$RESULT/FrostBox-0.4.0-dev.1-arm64-debug.apk"
cp android-app/build/outputs/apk/androidTest/debug/android-app-debug-androidTest.apk "$RESULT/FrostBox-0.4.0-dev.1-androidTest.apk"
cp build/display-package/assets/display/manifest.json "$RESULT/display-manifest.json"
FT_WORK=${FREETYPE_WORK:-"$(dirname "$WINE_WORK")/wine-display/freetype"}
cp "$FT_WORK/freetype-2.14.3.tar.xz" "$RESULT/"
cp -a build/display-package/assets/display/licenses "$RESULT/"
sha256sum "$RESULT/"*.apk > "$RESULT/APK-SHA256SUMS.txt"
python3 scripts/validate-display-runtime.py "$SERIAL" --methods guiPixelsMouseKeyboardAndClose --output "$RESULT/smoke"
python3 scripts/validate-display-runtime.py "$SERIAL" --no-install --output "$RESULT/display"
scripts/validate-launcher-release.sh "$SERIAL" "$RESULT/regressions"
echo "Automated gates passed. Physical touch and system SAF picker still require manual verification. Evidence: $RESULT"
