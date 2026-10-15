#!/usr/bin/env bash
# Contract test only: its mock collector is not an audio output device.
set -euo pipefail
root=$(cd "$(dirname "$0")/.." && pwd)
wine_work=${1:?Usage: test-remote-audio-host.sh /path/to/wine-wsi}
wine_work=$(realpath "$wine_work")
out="$root/build/remote-audio-host"
mkdir -p "$out"
gcc -std=gnu11 -O1 -g -fPIC -shared -D_GNU_SOURCE -D__WINESRC__ -DWINE_UNIX_LIB \
    -I"$wine_work/build/include" -I"$wine_work/source/include" -I"$wine_work/source/dlls/mmdevapi" \
    "$root/audio/wine/frost_audio.c" "$root/tests/remote/audio_nt_stub.c" \
    -pthread -Wl,-z,defs -o "$out/audio-contract.so"
FROST_AUDIO_UNIT_LIBRARY="$out/audio-contract.so" python3 "$root/tests/remote/test_audio_driver.py"
