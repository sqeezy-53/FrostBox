# Current: 0.6.0-alpha-physical-recovery — partial

Device-validated input/audio checkpoint, versionCode15. Physical camera/movement
and Windows/game sound confirmed; Present stall/GfxDeviceWorker errors remain.
No stable0.6.0 or long-session claim. [Qualification](PHYSICAL_DEVICE_RECOVERY.md).
Earlier sections are historical.

# Remote candidate checkpoint

`FrostBox-0.6.0-alpha-remote-input-audio` is host-validated only. No online ADB
device, no new physical-input/gameplay/audibility result. Input repairs and a
limited opt-in audio backend are compiled; full Raw Input lookup is unresolved
in the Linux probe. [Current report and gates](REMOTE_INPUT_AUDIO.md).

The following evolution results are historical; they do not qualify the new APK.

# FrostBox 0.6.0-alpha-evolution

Status: **partial alpha checkpoint**, not completed Operation Evolution or gaming beta.
The base Wine ZIP, qualified Box64 and existing release archives are preserved.
No root, app-data clearing, prefix removal, Git commit/push or external publication.

| Component | Device evidence / limit |
|---|---|
| Library delete / clear | Record-only mutations, isolated store tests; existing files preserved |
| System Back | User pressed actual Back in dialog/details; 3 Android tests passed |
| Library UI | Search/sort/list-grid/storage read-only Android test passed; covers persisted in native-decoder Robolectric test |
| Controller tester | Physical 8BitDo Ultimate Bluetooth: all controls and hotplug passed |
| USB | Not tested |
| Mappings | Implemented global/per-game persistence and normalization; physical default mapping qualified |
| Wine XInput | Physical 1.3/1.4/9.1.0, axes/triggers/buttons/caps/disconnect/reconnect and bounded polling passed |
| Touch | Win32 GUI key/pixel/close device test passed; virtual XInput touch and multi-touch gameplay unqualified |
| Keyboard/mouse gamepad mode | Win32 software route passed; physical KARLSON compatibility blocked |
| Windows audio | No endpoint: WinMM probe clean exit77; no PCM/audio output |
| Unity threaded mode | Invalid GfxDeviceWorker commands persist |
| KARLSON | Sustained 2-minute rendering and cleanup verified; physical game control failed |
| Thermal/FPS HUD | Existing diagnostic data only; no new guest frametime limiter or 1% low guarantee |
| Storage cleanup | Read-only inventory; destructive cache/prefix cleanup unavailable |

Host checks: 21 core JVM tests + 11 Android JVM/Robolectric tests, zero failures; 2 CTest pass.
Device evidence is stored in the release evidence archive, including failed attempts and
physical user interaction. Specific final regression outcomes are in its summary.json.
Full physical-controller qualification predates later cleanup/focus/keyboard-mode additions;
those additions have targeted regressions rather than a second complete human XInput run.

## User path

Library → Add Game → select EXE; Details → Import folder for adjacent resources → Settings.
For a confirmed XInput application choose XInput. For keyboard/mouse applications the
experimental alternative is configurable per game; KARLSON is not yet qualified with it.
Play always executes through the existing isolated runtime. Logs and real exit code appear
in Details/Runtime Logs. Stop closes the runtime and input channels. No EXE runs on import.
Delete/Clear remove records only, so orphaned private game directories remain intentionally.
SAF originals and shared Wine prefixes are not removed by those actions.

Build and test commands: BUILD.md, XINPUT_VALIDATION.md. Remaining engineering work:
raw/polling game input, a real Wine audio Unix backend, virtual-touch/controller gameplay,
USB and multiple controller qualification, owned-cache cleanup, deeper Unity thread-state
reproducer and longer thermal/game sessions. No production release claim is made.
