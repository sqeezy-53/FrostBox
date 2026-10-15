#!/usr/bin/env bash
# Real device gate; does not clear app data. Test programs belong to FrostBox.
set -euo pipefail
cd "$(dirname "$0")/.."
serial=${1:?Usage: validate-launcher-release.sh ADB_SERIAL [OUTPUT_DIRECTORY]}
result_dir=${2:-"$PWD/build/launcher-release-device"}
if [ -e "$result_dir" ]; then
    echo "Use a new output directory to preserve previous device evidence" >&2
    exit 1
fi
python3 scripts/validate-library-runtime.py "$serial" --output "$result_dir/library"
python3 scripts/validate-prefix-runtime.py "$serial" --no-install --output "$result_dir/prefix"
for phase in basic stress bionic wine-version wine-prefix wine-exe wine-stop wine-stress; do
    phase_args=()
    if [ "$phase" = stress ]; then phase_args+=(--baseline "$result_dir/regressions/basic/validation.json"); fi
    python3 scripts/validate-android-runtime.py "$serial" --no-install --phase "$phase" \
        --output "$result_dir/regressions/$phase" "${phase_args[@]}"
done
