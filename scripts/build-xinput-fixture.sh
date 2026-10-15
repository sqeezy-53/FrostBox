#!/usr/bin/env bash
set -euo pipefail
root=$(cd "$(dirname "$0")/.." && pwd)
out="$root/build/xinput-fixture"
mkdir -p "$out" "$root/build/wine-input-package/assets/launcher"
printf 'LIBRARY kernel32.dll\nEXPORTS\nGetStdHandle\nWriteFile\nLoadLibraryA\nGetProcAddress\nSleep\nFreeLibrary\nGetTickCount64\nExitProcess\n' > "$out/kernel32.def"
clang --target=x86_64-pc-windows-msvc -O1 -ffreestanding -fno-stack-protector -c "$root/tests/windows/xinput.c" -o "$out/xinput.obj"
llvm-dlltool -m i386:x86-64 -d "$out/kernel32.def" -l "$out/kernel32.lib"
lld-link /entry:mainCRTStartup /subsystem:console /nodefaultlib /timestamp:0 "/out:$out/xinput-x64.exe" "$out/xinput.obj" "$out/kernel32.lib"
cp "$out/xinput-x64.exe" "$root/build/wine-input-package/assets/launcher/"

clang --target=x86_64-pc-windows-msvc -O1 -ffreestanding -fno-stack-protector -c "$root/tests/windows/audio.c" -o "$out/audio.obj"
lld-link /entry:mainCRTStartup /subsystem:console /nodefaultlib /timestamp:0 "/out:$out/audio-x64.exe" "$out/audio.obj" "$out/kernel32.lib"
cp "$out/audio-x64.exe" "$root/build/wine-input-package/assets/launcher/"
