# Controllers — 0.6.0-alpha-evolution

## Confirmed on POCO (13 October 2026)

Physical **8BitDo Ultimate wireless over Bluetooth**, Android InputDevice external device,
not the built-in `uinput-xiaomi`. Android coverage/hotplug test passed with real human input:
ABXY, four D-pad directions, both sticks, both triggers, disconnect neutral state and reconnect
in the same slot. Evidence: `evolution-validation/controller-physical-passed.json` and
`controller-physical-4.txt`. USB and other controller models are **not tested**.

The tester uses public InputManager/InputDevice APIs, normalized device motion ranges,
radial deadzones, Y inversion, trigger deadzone and explicit right-stick axis selection.
Mappings are stored in a separate controller preferences DataStore by descriptor; optional
per-game overrides live in the existing GameProfile store. Save completion is reported only
when the DataStore write succeeds. No Bluetooth scanning/location permissions are used.
The OS Bluetooth settings button opens the real settings page. Device vibration is offered
only when that InputDevice has a vibrator; it never vibrates the phone as a substitute.
Windows rumble is currently unsupported by the experimental HID backend.

Launcher mode routes A to activation, B to Back and left stick to directional navigation.
Tester/game mode consumes gamepad navigation so buttons do not accidentally activate UI.
Keyboard/mouse/IME continue through their existing paths. Focus loss, pause and hot-unplug
neutralize the normalized state. Hardware mapping changes and multi-controller assignment
have not yet received the same physical qualification as the default single-controller path.

## Touch

Per-game touch layout: enable, keyboard/XInput choice, normalized positions, size, opacity and
visibility; persisted with the profile. Two sticks, ABXY, shoulders, triggers and Start.
Keyboard preset is explicit in Settings: left WASD, right arrows (not mouse-look), ABXY
Space/E/Q/R, LB/RB Shift/Ctrl, LT/RT Z/X, Start Esc. Hidden overlay does not intercept touches.
XInput touch owns virtual slot 1 while visible, replacing physical snapshots rather than
sending duplicate state. It is not physical-controller evidence.

A real on-device GUI test confirmed touch A reaches Win32 keyboard input and changes GDI
pixels, then touch Start closes the EXE (42). Virtual XInput touch, simultaneous multi-touch,
rotation while holding controls and extended gameplay remain unqualified. There is no claim
of FPS mouse-look or a complete touch-gameplay qualification.

## Keyboard/mouse applications (14 October)

Game Settings offers an explicitly experimental keyboard/mouse mode, with per-game button
VK mappings and mouse speed. Left stick/D-pad = WASD, right stick = relative mouse, RT/LT =
left/right click. The Wine display driver handles a separate relative input packet in its
experimental overlay; the qualified base display package is not overwritten. Focus intent
is replayed when the first actual HWND arrives, fixing an early request dropped before a
window exists. See GAME_STABILITY.md: the physical mapper did not control KARLSON despite
sending events, so the mode is not advertised as verified game support. XInput touch cannot
be selected together with keyboard controller mode; invalid combinations are rejected.
