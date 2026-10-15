#!/usr/bin/env bash
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
OUT="$ROOT/build/windows-fixtures";mkdir -p "$OUT"
cat > "$OUT/gui-user32.def" <<'DEF'
LIBRARY user32.dll
EXPORTS
RegisterClassExW
CreateWindowExW
ShowWindow
UpdateWindow
GetMessageW
TranslateMessage
DispatchMessageW
DefWindowProcW
BeginPaint
EndPaint
FillRect
GetClientRect
InvalidateRect
DestroyWindow
PostQuitMessage
PostMessageW
GetAsyncKeyState
DEF
cat > "$OUT/gui-gdi32.def" <<'DEF'
LIBRARY gdi32.dll
EXPORTS
CreateSolidBrush
DeleteObject
SetTextColor
SetBkMode
TextOutW
CreateFontW
SelectObject
GetGlyphIndicesW
DEF
cat > "$OUT/gui-kernel32.def" <<'DEF'
LIBRARY kernel32.dll
EXPORTS
GetModuleHandleW
GetStdHandle
WriteFile
ExitProcess
DEF
for LIB in user32 gdi32 kernel32; do llvm-dlltool -m i386:x86-64 -d "$OUT/gui-$LIB.def" -l "$OUT/gui-$LIB.lib"; done
clang --target=x86_64-pc-windows-msvc -O1 -ffreestanding -fno-stack-protector -c "$ROOT/tests/windows/gui.c" -o "$OUT/gui.obj"
lld-link /entry:mainCRTStartup /subsystem:windows /nodefaultlib /timestamp:0 "/out:$OUT/gui-x64.exe" "$OUT/gui.obj" "$OUT/gui-user32.lib" "$OUT/gui-gdi32.lib" "$OUT/gui-kernel32.lib"
mkdir -p "$ROOT/build/launcher-fixtures/assets/launcher"
cp "$OUT/gui-x64.exe" "$ROOT/build/launcher-fixtures/assets/launcher/"
sha256sum "$OUT/gui-x64.exe"
