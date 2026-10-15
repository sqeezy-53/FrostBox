# Windows XInput — physical device checkpoint

**Confirmed:** Windows x64 PE → Wine's original xinput1_3.dll / winexinput.sys / winebus.sys
→ separate experimental winebus Unix backend → authenticated Android controller stream.
This is not synthesized Android KeyEvent evidence and not a custom replacement XInput DLL.

`xinput-physical-2.txt` passed on POCO, app application UID. Windows result 42, signal 0,
`FROSTBOX_XINPUT_RESULT=PASS`, 271737 ms including human interaction, zero dropped stdout.
- Discovery and XInputGetState succeeded.
- ABXY and all D-pad directions; both signed stick axes; triggers reached 255.
- XInputGetCapabilities returned 0.
- xinput1_4.dll and xinput9_1_0.dll also returned state successfully.
- 1000 cached XInputGetState calls took 1 ms in this run. This is **not end-to-end latency**.
- Disconnect returned ERROR_DEVICE_NOT_CONNECTED (1167); reconnect succeeded.
- Windows haptics are not implemented, XInputEnable semantics not independently tested.

The first probe exited 42 but its excessive repeated HID reports overflowed bounded stdout;
that instrumentation assertion failed and is retained. The backend now queues only changed
HID reports, including one initial report after device start, and the fixture bounds event
printing. Android-to-Wine transport still samples latest state every ~16 ms; very brief
presses between snapshots may be missed. It is not a lossless event transport.

## Isolation and cleanup

Android LocalServerSocket `frost-pad-<UID>-<prefix UUID>`, one same-UID peer, fixed 132-byte
little-endian packets for four slots. Both ends check SO_PEERCRED; no root, system changes or
synchronous Binder on XInput polling. Wine retains its existing Windows HID/XInput cache.
Wire ranges and magic are validated. Android closes the channel on Stop/runtime completion.
`shutdown` is required before closing a blocked accept; a regression found an abstract socket
remaining bound without it. Twenty open/close/rebind cycles passed after the fix, as did
three console launches and runtime-death/recovery. The physical test predates this cleanup-only
fix; input translation itself is unchanged.

Build `scripts/build-wine-input-overlay.py --wine-work <existing isolated wine-wsi work>`.
It builds only winebus.so in the experimental no-SDL Wine 11.19 tree. The qualified Wine ZIP,
GDI, Box64 and Vulkan/DXVK binaries remain unchanged. Backend source is LGPL-2.1-or-later;
Android original code is MIT. The source bundle includes the upstream Wine sources and the
new include/build script. Opt in with `-PwithWineInput=true`.

Fixture: `scripts/build-xinput-fixture.sh`; Android test:
```
adb -s SERIAL shell am instrument -w -r -e frostbox.physicalPad true \
  -e class org.frostbox.app.library.XInputDeviceTest#physicalAndroidControllerReachesWindowsXInput \
  org.frostbox.app.test/androidx.test.runner.AndroidJUnitRunner
```
Follow emitted markers: controls → disconnect → reconnect. The test imports only its own PE
through the existing content provider and never removes user records, game files or prefixes.

Keyboard/mouse is a separate experimental per-game mode. Its software-event Win32 regression
confirmed GetAsyncKeyState and a mouse-button handler; relative mouse displacement was not
independently measured. The qualified physical XInput fixture does not prove KARLSON gameplay;
see GAME_STABILITY.md for negative human checks and the remaining input blocker.
