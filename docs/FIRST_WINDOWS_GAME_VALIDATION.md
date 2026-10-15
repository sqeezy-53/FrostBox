# First Windows 3D game — short experimental gameplay, not release qualification

2026-10-13. [KARLSON official free Windows64 demo, Dani](https://danidev.itch.io/karlson), Unity2019.2.6f1, PE AMD64. Download archive44066107bytes, SHA256 `c6cb79c2f6291a78ba3216adda65cf38d64b271be182f45dfac59e84a16ed61b`. Free download is not a redistribution license: no EXE/DLL/resources or crash dump are bundled with FrostBox source/evidence/release.

The existing SAF tree importer streamed all244 entries (~113776816 expanded bytes), preserving folders, into the game's private content directory. The real Library profile selects DXVK2.7.1 and reuses the validated Wine prefix. Play/Stop use the existing IPC Process Manager; no remote execution. Tests inject the selected SAF provider result; physical operation of the Android system picker is not claimed.

| Attempt / preserved evidence directory | Actual result |
|---|---|
| `karlson-1` | D3D11/Adreno device, no frames before selector/focus recovery; stopped after90s |
| `karlson-focus` | Enter/touch reached actual3D menu;346 frames/13 PixelCopies, then SIGSEGV |
| `karlson-upright` | Host preTransform corrected; upright menu,3081 observed frames/50 PixelCopies,40s Stop |
| `karlson-gameplay` | Play/Tutorial reached WALLRUNNING3D level;6325 observed frames/164 PixelCopies; Unity access violation before queued movement input arrived |
| `karlson-direct` | `-force-gfx-direct`,45s whole launch,1651 observed frames/47 PixelCopies; Play/Tutorial/W/Space/W, visible fall and return; Stop/server cleanup/prefix valid |
| `final-gameplay` | Same bounded scenario on finalAPK; guest11635/runtime11571,44.736s process lifetime,1651 observed frames/47 PixelCopies, six delivered input actions, Stop SIGTERM/server cleanup/prefix valid |

The counts span menu and level; they are observed host counters, not exact whole-session totals, average FPS, frametime or 1%low. Tutorial was active approximately27seconds per45-second run. PixelCopy snapshots show a real3D scene and camera movement/fall/return. Space is a delivered Android event; completing wallrunning/the level is not proven. Physical keyboard/mouse/gamepad and audio are not qualified. The final Unity log records `jumping` after the delivered Space; no crash in these bounded direct-mode runs.

Default threaded graphics remains a real blocker. Unity crash log reports `Gfx command not handled:0 Last10060`, then UnityPlayer.dll access violation0xc0000005 reading0x2B at RIP0x7ffe281c05 (`mov rdx,[rbx+0x28]`, RBX3) in GfxDeviceWorker. Threaded command corruption, translator ordering and runtime interactions need a bounded reproducer: the cause is not proven. `-force-gfx-direct` changes the observed outcome; it is a workaround, not an ABI/root-cause fix. Nonfatal shader/postprocessing/FMOD fallback/network SDK warnings remain. Android app has no INTERNET permission; failed game SDK requests are not FrostBox telemetry.

Working saved profile: `KARLSON · official demo`,640×360, DXVK, working directory `content`, shared existing prefix. Arguments:

```text
-force-d3d11 -screen-width 640 -screen-height 360 -screen-fullscreen 0 -force-gfx-direct
```

The QA profile also specifies an explicit private `-logFile` path. Press Enter after launch to dismiss the pre-render software configuration/selector path, then Play → Tutorial. GPU display currently does not composite that GDI dialog; this usability gap is explicit. Use Stop to close the process. Changing Surface/orientation during GPU execution is not seamless: Stop and restart with a fresh Surface is required.

Game QA methods require explicitly staged lawful game data plus `-e frostbox.gameQa true`; default tests skip them. The validator supplies the opt-in only for explicitly selected game methods. `saveValidatedKarlsonDirectProfile` only stores validated arguments; it never launches. No automatic download/launch after import. Automation's generic report deliberately leaves `gameplay=false`: frame counters alone cannot prove a level/input response. The manually reviewed checkpoint records short automated gameplay separately, with screenshots and delivered actions.

Next qualification requires fixing/reproducing threaded graphics corruption, a several-minute stable session, physical controls, audio and reliable window/configuration handling. The current result does not satisfy full0.6.0 or general game compatibility. [Overall checkpoint](PROJECT_TITAN_CHECKPOINT.md).
