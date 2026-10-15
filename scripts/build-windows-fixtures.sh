#!/usr/bin/env bash
set -euo pipefail
root=$(cd "$(dirname "$0")/.." && pwd)
out=${1:-"$root/build/windows-fixtures"}
mkdir -p "$out"
clang --target=x86_64-pc-windows-msvc -O1 -ffreestanding -fno-stack-protector -c "$root/tests/windows/console.c" -o "$out/console-x64.obj"
llvm-dlltool -m i386:x86-64 -d "$root/tests/windows/kernel32.def" -l "$out/kernel32-x64.lib"
lld-link /entry:mainCRTStartup /subsystem:console /nodefaultlib /timestamp:0 "/out:$out/console-x64.exe" "$out/console-x64.obj" "$out/kernel32-x64.lib"
# 32-bit PE is built separately; this does not establish Android WoW64 support.
printf 'LIBRARY kernel32.dll\nEXPORTS\nGetStdHandle@4\nWriteFile@20\nExitProcess@4\n' > "$out/kernel32-x86.def"
clang --target=i686-pc-windows-msvc -O1 -ffreestanding -fno-stack-protector -c "$root/tests/windows/console.c" -o "$out/console-x86.obj"
llvm-dlltool -m i386 --kill-at -d "$out/kernel32-x86.def" -l "$out/kernel32-x86.lib"
lld-link /machine:x86 /entry:mainCRTStartup /subsystem:console /nodefaultlib /timestamp:0 "/out:$out/console-x86.exe" "$out/console-x86.obj" "$out/kernel32-x86.lib"
sha256sum "$out/console-x64.exe" "$out/console-x86.exe"
clang --target=x86_64-pc-windows-msvc -O1 -ffreestanding -fno-stack-protector -c "$root/tests/windows/sleep.c" -o "$out/sleep-x64.obj"
llvm-dlltool -m i386:x86-64 -d "$root/tests/windows/sleep-kernel32.def" -l "$out/sleep-kernel32.lib"
lld-link /entry:mainCRTStartup /subsystem:console /nodefaultlib /timestamp:0 "/out:$out/sleep-x64.exe" "$out/sleep-x64.obj" "$out/sleep-kernel32.lib"
# Extra diagnostic asset, separate from the immutable Wine userspace ZIP.
if [ -f "$root/build/wine-probe-package/assets/runtime/wine-probe.json" ]; then
    cp "$out/sleep-x64.exe" "$root/build/wine-probe-package/assets/runtime/sleep-x64.exe"
    python3 - "$root/build/wine-probe-package/assets/runtime" <<'PY'
import pathlib,json,hashlib,sys
root=pathlib.Path(sys.argv[1])
(root/'sleep-fixture.json').write_text(json.dumps({'sha256':hashlib.sha256((root/'sleep-x64.exe').read_bytes()).hexdigest()}))
PY
fi
sha256sum "$out/sleep-x64.exe"
clang --target=x86_64-pc-windows-msvc -O1 -ffreestanding -fno-stack-protector -c "$root/tests/windows/math.c" -o "$out/math.obj"
lld-link /dll /entry:DllMain /nodefaultlib /timestamp:0 "/out:$out/frostmath.dll" "/implib:$out/frostmath.lib" "$out/math.obj"
llvm-dlltool -m i386:x86-64 -d "$root/tests/windows/math.def" -l "$out/math-import.lib"
printf 'LIBRARY kernel32.dll\nEXPORTS\nGetStdHandle\nWriteFile\nExitProcess\nGetFileAttributesA\n' > "$out/dependency-kernel32.def"
llvm-dlltool -m i386:x86-64 -d "$out/dependency-kernel32.def" -l "$out/dependency-kernel32.lib"
clang --target=x86_64-pc-windows-msvc -O1 -ffreestanding -fno-stack-protector -c "$root/tests/windows/dependency.c" -o "$out/dependency.obj"
lld-link /entry:mainCRTStartup /subsystem:console /nodefaultlib /timestamp:0 "/out:$out/dependency-x64.exe" "$out/dependency.obj" "$out/math-import.lib" "$out/dependency-kernel32.lib"
mkdir -p "$root/build/launcher-fixtures/assets/launcher"
cp "$out/console-x64.exe" "$out/sleep-x64.exe" "$out/dependency-x64.exe" "$out/frostmath.dll" "$root/build/launcher-fixtures/assets/launcher/"
