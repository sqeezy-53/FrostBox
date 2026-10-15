> Current PROJECT TITAN continuation: [2026-10-13 checkpoint](PROJECT_TITAN_CHECKPOINT.md). Windows Vulkan 120 frames, DXVK/D3D11 and Library GPU Play are now device-confirmed. The older status below describes the preserved preceding checkpoint; it is not the current implementation status.

# Android input bridge — committed Unicode recovery, 2026-10-12

See [full investigation and device evidence](WIN32_TEXT_INPUT_RECOVERY.md). This report replaces
its earlier incomplete conclusion; the seven original failure directories and APKs are preserved.

## Cause and delivery

WindowsSurface emitted LEFTUP | RIGHTUP on every ordinary tap, cancel and lifecycle release.
Wine Edit responds to the unmatched right-button release by opening its standard context menu.
The single-window presenter then used that popup's latest frame/HWND as the next input destination;
the driver's show-window activation also moved focus to it. Changing KEYEVENTF_UNICODE to WM_CHAR,
root/current-thread/owning-thread focus lookup, or waiting for WM_SETFOCUS could not remove the
later popup. Fix: record the pressed button and release only that button, only while it is held.
No hardcoded HWND or remote SetFocus was introduced.

A second problem obscured Unicode evidence: ProcessSnapshot JSON escaped UTF-8 bytes individually
as Latin-1 characters. The serializer now decodes valid UTF-8 to escaped Unicode (including UTF-16
surrogate pairs), retaining the existing representation for invalid or temporarily incomplete bytes.
Logs from non-UTF-8 programs are not automatically assigned a Windows codepage.

Input remains Android Surface -> DisplaySession worker -> same-UID local socket -> winefrost.drv
-> Wine GUI queue -> DispatchMessageW -> standard Edit -> EN_CHANGE -> GetWindowTextW.
Process launch/control uses existing Binder IPC/JNI; text packets do not pass through Binder.
Type7 carries UTF-16 units, not Android keycodes. The existing driver queries the frame window's
owning GUI thread and routes WM_CHAR to its live focused descendant; it retains no child HWND.
Backspace/Enter remain keyboard events translated by the application's normal message loop.

Input packet h now carries a correlation sequence: Android text corr -> driver text corr/unit/HWND
-> Lab queue/procedure unit/HWND/thread, matched within the same run by FIFO order and contents.
The unique U+0416 diagnosis makes this match unambiguous. Detailed Wine traces are opt-in via
WINEDEBUG=+frost. Bitmap evidence comes from PixelCopy, not a simulated Android Edit.

## Android keyboard scope

Keyboard opens the actual Android IME. InputConnection supports committed text, bounded
local preedit until commit/finish, deleteSurroundingText, Enter and Back hiding the IME.
Local preedit is not reflected in Windows; no Windows IME, inline candidate UI, selection or
surrounding-text synchronization is claimed. The device test opens the system keyboard and
invokes InputConnection in-app; it is not proof of manual typing with every keyboard vendor.
Physical touch, physical keyboard and mouse, real right-click popup interaction, general
multiwindow presentation and arbitrary application focus behavior remain unqualified.

Shared DisplayGeometry maps aspect-fit coordinates plus the transported Windows screen origin.
Lab's ClientToScreen/GetWindowRect supplies test client offsets. Primary-pointer tracking ignores
extra fingers; up/cancel/focus loss/detach/surface destruction release only held input.
