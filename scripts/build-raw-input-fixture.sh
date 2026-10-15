#!/usr/bin/env bash
set -euo pipefail
root=$(cd "$(dirname "$0")/.." && pwd)
out="$root/build/remote-input";mkdir -p "$out" "$root/build/remote-package/assets/launcher"
cat > "$out/user32.def" <<'DEF'
LIBRARY user32.dll
EXPORTS
RegisterClassExW
CreateWindowExW
ShowWindow
UpdateWindow
DefWindowProcW
DestroyWindow
PostQuitMessage
GetMessageW
TranslateMessage
DispatchMessageW
RegisterRawInputDevices
GetRawInputData
GetRawInputDeviceList
GetRawInputDeviceInfoW
GetForegroundWindow
GetFocus
GetActiveWindow
SetForegroundWindow
SetFocus
GetAsyncKeyState
GetKeyState
GetKeyboardState
SendInput
MapVirtualKeyW
SetTimer
KillTimer
DEF
printf 'LIBRARY kernel32.dll\nEXPORTS\nGetStdHandle\nWriteFile\nGetCommandLineA\nGetLastError\nExitProcess\n' > "$out/kernel32.def"
clang --target=x86_64-pc-windows-msvc -O1 -ffreestanding -fno-stack-protector -c "$root/tests/windows/raw-input.c" -o "$out/raw.obj"
for dll in user32 kernel32;do llvm-dlltool -m i386:x86-64 -d "$out/$dll.def" -l "$out/$dll.lib";done
lld-link /entry:mainCRTStartup /subsystem:console /nodefaultlib /timestamp:0 "/out:$out/raw-input-x64.exe" "$out/raw.obj" "$out/user32.lib" "$out/kernel32.lib"
cp "$out/raw-input-x64.exe" "$root/build/remote-package/assets/launcher/"
