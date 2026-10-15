#!/usr/bin/env bash
set -euo pipefail
root=$(cd "$(dirname "$0")/.." && pwd)
out="$root/build/remote-audio";mkdir -p "$out" "$root/build/remote-package/assets/launcher"
printf 'LIBRARY ole32.dll\nEXPORTS\nCoInitializeEx\nCoCreateInstance\nCoUninitialize\n' > "$out/ole32.def"
printf 'LIBRARY kernel32.dll\nEXPORTS\nGetStdHandle\nWriteFile\nSleep\nExitProcess\n' > "$out/kernel32.def"
for dll in ole32 kernel32;do llvm-dlltool -m i386:x86-64 -d "$out/$dll.def" -l "$out/$dll.lib";done
clang --target=x86_64-pc-windows-msvc -O1 -ffreestanding -fno-stack-protector -c "$root/tests/windows/wasapi-audio.c" -o "$out/audio.obj"
lld-link /entry:mainCRTStartup /subsystem:console /nodefaultlib /timestamp:0 "/out:$out/wasapi-audio-x64.exe" "$out/audio.obj" "$out/ole32.lib" "$out/kernel32.lib"
cp "$out/wasapi-audio-x64.exe" "$root/build/remote-package/assets/launcher/"
