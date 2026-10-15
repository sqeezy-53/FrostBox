# KARLSON — bounded validation and input blocker

This report refers only to the locally imported official Windows64 demo. No game files are
included in the source distribution. Tests use the existing profile and restore its launch
configuration; they do not delete game resources or Wine prefixes.

## 14 October 2026 device evidence

- Direct mode (`-force-gfx-direct`): 120-second controlled render session, 12609 GPU frames,
  11 PixelCopies / 10 changes, Stop signal15, prefix validation succeeded.
- Keyboard/mouse mapper experiment: another 120-second session, 12649 GPU frames;
  Android sent 150 keyboard events and 118 relative-mouse packets. The user reported that
  the game did not respond. This is not a gameplay success.
- Focus replay experiment: 60-second render session; 102 keyboard events / 89 mouse packets.
  The user again reported no control. Wine trace subsequently showed the intended root HWND
  as foreground with a valid thread focus. The input blocker is **not fixed**.
- Threaded mode: a 45-second session produced 1860 frames, but Player.log again contained
  invalid GfxDeviceWorker commands (0, 185, 688; last command 10039, etc.). Stop was controlled.
  Lack of a fatal crash in that short run does not repair the threaded command-state bug.

The first new game-test attempt timed out before Wine execution because the test did not
advance Compose synchronization for Surface creation. That harness error was corrected;
its failed report remains in the evidence folder.

## Why XInput does not directly control this demo

Read-only inspection of Assembly-CSharp PlayerMovement.MyInput/Look and InputManager confirmed
that Horizontal/Vertical are keyboard bindings (WASD/arrows), Jump is Space, Crouch Ctrl,
Pickup E, Drop Q, and look uses mouse axes. No joystick axis binding is configured in that
asset. The original Windows XInput fixture passing therefore does not establish support
inside this game's input setup. Game files and settings were not patched.

A per-game keyboard/mouse mode now exists: left stick/D-pad maps to WASD, right stick to
relative mouse motion, RT/LT to mouse buttons, and configurable buttons to virtual keys.
The software-event device regression confirmed WM_KEYDOWN, GetAsyncKeyState high-bit and a
mouse-button handler in a real Win32 GUI. Its separate GDI driver overlay avoids enabling
Vulkan for GDI-only applications. Physical
KARLSON compatibility remains blocked. The mapper is explicitly experimental in Settings.
No automatic switch of the user's saved KARLSON controller mode has been made.

Next bounded diagnosis: compare Unity's raw-input/polling state with the Win32 fixture after
foreground/focus is correct, particularly raw keyboard/mouse registration and message-pump
consumption. Do not repeat long render tests or alter Box64 globally without evidence.
The earlier automated 45-second movement scripts are not a substitute for today's failed
physical-control checks. No level completion, sound, long thermal qualification or stable
threaded gameplay is claimed.
