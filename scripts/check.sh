#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
./gradlew :core:test :android-app:testDebugUnitTest :android-app:assembleDebug :android-app:lintDebug
cmake -S runtime/native -B build/native -G Ninja
cmake --build build/native
ctest --test-dir build/native --output-on-failure
