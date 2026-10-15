# Game Experience — device validation checkpoint

The in-game panel is being qualified against Foundation. It does not replace Wine,
Box64, DXVK, GDI, the audio driver, IPC or existing prefix/process management.

## Architecture

```text
Library Play → existing LibraryLauncher → isolated :runtime / Wine / Box64
              ↓                           ↓ existing GPU IPC
      existing profile DataStore     fixed-size Surface buffer
              ↕                           ↓
      atomic setting edits          stable WindowsSurface
              ↕                           ↓
       Compose Game Panel     WindowsViewport bounds (Fit / Fill / Stretch)
                                    ↓
                       touch in child-local coordinates → existing Wine input
```

The panel is a sibling above AndroidView in the application's own window. No overlay
permission, TextureView, new Activity or guest restart is involved. SurfaceView keeps
its default composition order. Host view layout/clipping, not Vulkan device reset,
implements scaling. Android 16 handles landscape configuration in the same Activity.
A genuinely destroyed GPU Surface still requires Stop/Play under the existing WSI
lifecycle contract; arbitrary Activity destruction/background recovery is not upgraded
by this UI change.

Android's [SurfaceView contract](https://developer.android.com/reference/android/view/SurfaceView)
supports synchronized view positioning/scaling on API24+; FrostBox requires API29+.
[Immersive mode](https://developer.android.com/develop/ui/views/layout/immersive)
uses transient system bars by swipe. Insets protect UI controls; Back remains available.

## Opening and input

Touch the edge menu handle, or press Android Back. Back closes an open panel, and first
closes the IME if it is visible. It never unexpectedly stops the game. The handle can
be hidden or moved to the left; Back remains an alternative. No default controller
button is hijacked to open the panel. Configurable controller opening chords and
edge-swipe gestures are not implemented yet.

Opening the panel releases Surface keys/buttons and pointer capture and deactivates
Wine input focus. ControllerMonitor keeps physical diagnostics but returns connected,
neutral snapshots to both existing XInput and keyboard/mouse bridges. The existing
Window.Callback routes controller navigation to Compose while this gate is active.
Resume clears snapshots, returns Surface/Win32 focus and restores previously owned
pointer capture. Panel UI gestures do not become Windows mouse clicks.

## Settings and their lifetime

| Setting | Application |
|---|---|
| Auto / Fit / Fill / Stretch | Live, host layout only |
| Camera speed in keyboard/mouse controller mode | Live after finishing the slider gesture |
| Deadzones / invert Y | Live, per-game controller override |
| Touch visibility / existing layout editor | Live, inputs released while editing |
| Output gain / mute | Live on existing AudioTrack, no system volume changes |
| HUD / corner / panel handle | Live |
| Desktop resolution preference | Next Launch; not a running-game backbuffer reset |
| Wine / graphics DLLs / input mode / enabling audio backend | Existing Library settings, next launch |

The new fields have additive serialization defaults. Atomic DataStore edits preserve
other fields and concurrent last-launch writes, without acquiring the active game's
content/prefix lock. Library and panel read the same GameProfile. No new database.

For an image `W×H` and viewport `Vw×Vh`, Fit/Auto uses
`min(Vw/W,Vh/H)`; Fill uses `max(...)`; Stretch uses each axis independently.
Centered destination bounds, source crop and inverse touch mapping share these
values. Android ViewGroup converts viewport touches into the scaled child's local
coordinates; the existing DisplaySession converts those into guest screen/client
coordinates. Outside-Fit touches do not hit the child; release coordinates clamp safely.

**Important distinction:** GPU source aspect here is the existing host output buffer,
whose dimensions are fixed at launch. Guest swapchain images may already have been
blitted into that buffer by the qualified bridge. Their actual backbuffer dimensions
are not exposed to this UI and are explicitly shown as unknown. This checkpoint does
not claim to repair arbitrary guest-to-host aspect mismatches inside that earlier blit.
Integer scaling, forced aspect ratios, live guest resolution reset and VSync controls
are intentionally unavailable.

## HUD and audio

The GPU HUD samples the existing completed host-Present counter once per second
(counter reports arrive about every500ms). It is an approximate host completion rate,
not display refresh, guest FPS or exact screen scanout timing. GDI uses its separately
labelled software-post count. No per-frame timestamps means no frametime / 1% low.
No PixelCopy, logs scan, native renderer changes or new high-rate polling is used.

Gain applies only to the actual AndroidPcmOutput AudioTrack, on the existing audio
worker. A reset recreates the track and the next protocol packet reapplies gain.
No microphone, new endpoint or system audio setting is used. Software consumption
counters are not proof that a human heard playback.

## Validation

Software checks on POCO (2026-10-15):

- Full Game Panel lifecycle fixture PASS:973 host Presents; Auto/Fit/Fill/Stretch;
  four center and four off-center Win32 clicks; no panel-click leakage; Back;
  landscape/reverse; Stop; persisted repeated Play and controlled Restart.
- One Surface creation, zero destroys before Stop; same GuestWsiSession throughout
  every panel/scale/orientation change. Destruction occurs during intentional Stop.
- Twelve regression groups / fifteen methods PASS: native ARM64, Box64 interpreter /
  dynarec / cancellation; WoW64 console5050/42+x64 control; Windows Vulkan10800/42;
  Library Stop/recovery; x86 D3D9; x64 D3D11; GDI/Unicode; Raw Input relative motion;
  IME/Back; actual x64+x86 WASAPI/AudioTrack9600-frame fixtures; native Surface lifecycle.
- Separate actual AudioTrack test PASS: gain0/.4/1, mute/unmute, bounded silent PCM
  drain, reset/restart, cleanup. This is Android output testing, not new listening
  confirmation or a replacement for the separately executed Windows audio tests.
- Host:31 core +16 Android-module JVM tests;2 CTest. Full APK/test APK and lint pass.
- All98 previous asset/native APK entries match Foundation byte-for-byte.

The first panel-test failures were harness defects (scrolling a non-scroll child,
searching oversized virtualized QA cards and comparing pre/post-restart session
identities). Their evidence is retained. The final lifecycle test passed.

### Short overhead observation

| Stage (2s each) | Completed host Presents | Rate/s | Main PSS KiB | Main FDs |
|---|---:|---:|---:|---:|
| Before panel |90|45.0|141717|247|
| Panel open |121|60.5|150462|250|
| Resumed after scaling |122|61.0|162148|255|

These are short warmup-sensitive observations, not game FPS benchmarks or proof of
zero overhead/leaks. PSS/FD increases are recorded, not hidden. No per-frame latency
or GPU-copy/JNI-overhead claim is made. No new Device Lost in the fixture.

### Real-game boundary

Two preserved-profile KARLSON attempts produced zero host Presents in120s and were
cancelled by the diagnostic test's `finally` cleanup. First attempt logged
`VK_ERROR_DEVICE_LOST` in DXVK and FMOD initialization failure in Unity. The second
stalled without an explicit Device Lost in its collected DXVK logs. The exact cause
of the second startup stall remains unresolved; it is not declared a Game Panel fix
or a proven panel regression. No game files, prefix, driver or launch arguments were
changed to force a pass. Do not count a timed diagnostic stop as a proven game crash.
Mafia II is not requalified by this checkpoint.

### Physical controller qualification

The user confirmed real 8BitDo movement/rotation/A in the interactive Windows D3D11
fixture via the existing Wine XInput bridge. An initial manual panel attempt did
not qualify: the test's sleep loop did not advance Compose's test frame clock;
native rendering/input continued while Compose overlays and counters were frozen.
This was a harness defect, not a successful panel-navigation test. The manual loop
now advances the clock and pumps UI work. On the corrected repeat the user explicitly
confirmed touch-handle opening, D-pad/A panel navigation, B to close, and resumed
left/right-stick control of the Windows D3D11 cube. Physical Stop/repeated Play are
being recorded separately. This is not KARLSON gameplay qualification.

A keyboard/mouse + GPU attempt using the same fixture family stalled before its
first Present; XInput mode rendered. This contrast is a diagnostic lead, not proof
of the exact Wine/PnP root cause (the interactive PE was also extended between
attempts). No production Wine/Box64/WSI changes were made for this experiment.

Raw evidence stays local/ignored. Final artifact/PR details follow after validation.
