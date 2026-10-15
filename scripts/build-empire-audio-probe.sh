#!/usr/bin/env bash
set -euo pipefail
frost_root=$(cd "$(dirname "$0")/.." && pwd)
frost_out="$frost_root/build/empire-audio"
mkdir -p "$frost_out" "$frost_root/build/empire-package/assets/launcher"
cat > "$frost_out/ole32.def" <<'DEF'
LIBRARY ole32.dll
EXPORTS
CoInitializeEx@8
CoCreateInstance@20
CoUninitialize@0
DEF
cat > "$frost_out/kernel32.def" <<'DEF'
LIBRARY kernel32.dll
EXPORTS
GetStdHandle@4
WriteFile@20
Sleep@4
ExitProcess@4
DEF
for frost_dll in ole32 kernel32; do
    llvm-dlltool -m i386 --kill-at -d "$frost_out/$frost_dll.def" -l "$frost_out/$frost_dll.lib"
done
clang --target=i686-pc-windows-msvc -O1 -ffreestanding -fno-stack-protector -c "$frost_root/tests/windows/wasapi-audio-x86.c" -o "$frost_out/audio.obj"
lld-link /entry:mainCRTStartup /subsystem:console /nodefaultlib /safeseh:no /timestamp:0 "/out:$frost_root/build/empire-package/assets/launcher/wasapi-audio-x86.exe" "$frost_out/audio.obj" "$frost_out/ole32.lib" "$frost_out/kernel32.lib"
