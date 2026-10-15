#!/usr/bin/env bash
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
# Reuse the existing tiny SDK import libraries; no Wine/Box64 rebuild.
"$ROOT/scripts/build-gui-fixture.sh"
OUT="$ROOT/build/windows-fixtures"
cat >> "$OUT/gui-user32.def" <<'DEF'
SendMessageW
SetWindowTextW
GetWindowTextW
SetFocus
GetFocus
CallWindowProcW
SetWindowLongPtrW
ClientToScreen
GetWindowRect
GetWindowThreadProcessId
GetForegroundWindow
GetGUIThreadInfo
DEF
echo GetCurrentThreadId >> "$OUT/gui-kernel32.def"
llvm-dlltool -m i386:x86-64 -d "$OUT/gui-kernel32.def" -l "$OUT/lab-kernel32.lib"
llvm-dlltool -m i386:x86-64 -d "$OUT/gui-user32.def" -l "$OUT/lab-user32.lib"
clang --target=x86_64-pc-windows-msvc -O1 -ffreestanding -fno-stack-protector -c "$ROOT/tests/windows/compat-lab.c" -o "$OUT/compat-lab.obj"
lld-link /entry:mainCRTStartup /subsystem:windows /nodefaultlib /timestamp:0 "/out:$OUT/compat-lab-x64.exe" "$OUT/compat-lab.obj" "$OUT/lab-user32.lib" "$OUT/gui-gdi32.lib" "$OUT/lab-kernel32.lib"
cp "$OUT/compat-lab-x64.exe" "$ROOT/build/launcher-fixtures/assets/launcher/"
sha256sum "$OUT/compat-lab-x64.exe"
