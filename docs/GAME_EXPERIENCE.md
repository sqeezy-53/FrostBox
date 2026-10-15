# Game Experience checkpoint (work in progress)

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

Results, commands and remaining qualification gates will be recorded below after
real POCO execution. Raw evidence is local/ignored and never uploaded unredacted.
