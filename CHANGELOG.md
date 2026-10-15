# Changelog

## 0.7.1-alpha-game-experience.1 — partial UI milestone — 2026-10-15

- Real Compose Game Panel above a stable SurfaceView: Resume, confirmed Stop/Restart, Back, diagnostics and host-only settings. No guest pause claim.
- Auto/Fit/Fill/Stretch with per-game persistence, inverse touch geometry and explicit output-buffer/backbuffer distinction. Desktop resolution changes are Next Launch.
- Per-game live controller mapping, keyboard/mouse camera speed, touch layout, AudioTrack gain/mute and a labelled sampled host-Present HUD.
- POCO lifecycle fixture:973/975 Presents, eight exact Win32 clicks, Surface identity preserved across panel/scaling/rotation, Stop/repeat/Restart PASS.
- Twelve focused regression groups pass, including actual x86/x64 audio; no changed qualified runtime assets/native libraries (98 compared).
- Physical 8BitDo: user confirmed Windows D3D11 cube control, panel navigation and return to control after Resume; Stop and repeated Play of the same QA profile also physically confirmed (14889/3217 host Presents). Frozen frame-clock and pre-dispatch wait defects were fixed in the manual harness.
- KARLSON startup did not qualify in two preserved-profile attempts; first logged Device Lost/FMOD failure, second stalled before presentation. Gameplay and physical-controller qualification are separate gates.
- Full binary release remains blocked by the existing distribution privacy audit. See [Game Experience](docs/GAME_EXPERIENCE.md).

## 0.7.0-alpha.foundation.1 — 2026-10-15

- Real PRIVATE GitHub source baseline, main/develop, pinned Actions, scoped CI and issue/PR templates. SDK setup failure fixed and real remote CI passed.
- Verified private object sharing for immutable Wine PE/font payloads; Android hardlinks rejected and not used. Unix ELF, games and mutable prefixes remain separate.
- Idle runtime/storage lease, owned-process checks, hash/metadata preflight, atomic references, stale-plan/symlink rejection and allocated-block accounting.
- Device reconciliation: 32,591 paths, 20.11GiB reclaimed; protected game/prefix/profile tree unchanged.
- Built-in WoW64 QA prefix reuse; no directory growth after warmup. Actual fixture regressions pass; game stability unchanged.
- Full binary GitHub release blocked by embedded local build paths in qualified Wine PE files; local APK preserved.


## Foundation source baseline — 2026-10-15

Source history begins with the real current snapshot; prior headings below are
preserved checkpoint history, not historical Git tags or stable releases.

- Private GitHub launch preparation, English/Russian entry points, architecture
  and source/release boundaries; original code MIT confirmed by owner.
- Raw private evidence, editor backups and device identifiers excluded from source.
- Earlier physical Mafia II menu result also includes user-confirmed audible music.
- No storage reclaim, repeatable Mafia II startup or level gameplay claimed here.

# 0.7.0-alpha-mafia2-menu — partial milestone — 2026-10-14

- Connect checked WoW64 overlay to existing Library/IPC/Process Manager; real PE
  detection, runtime/API profile defaults, separate System32/SysWOW64 prefix,
  x32 DXVK D3D9, actual Play/Stop/repeat/recovery tests.
- First original Mafia II EXE execution and D3D9 frames. Original bundled PhysX
  MSI fixed startup fault; no proprietary binaries redistributed.
- Private WoW64 audio thunk overlay, low-address PCM buffers, actual endpoint and
  AudioTrack9600-frame/42 device test; test signal heard by physical user.
- App-local intro/focus test reached difficulty/main menu,2999 Presents; physical
  user independently confirmed main menu and physical 8BitDo operation.
- Intro repeatability remains blocked. Level/gameplay/FPS not qualified. Exact
  menu configuration, failed repeats and separate viewport candidate preserved.

# 0.7.0-alpha-horizon-experimental — partial — 2026-10-14

- Original dark/purple Home, real library hero/history, bottom navigation, lazy searchable/filterable catalog and details; actual POCO UI/rotation/Back dispatcher and Play/Stop/recovery tests.
- Separate Wine11.19 i386 PE overlay over unchanged x64 Bionic userspace/Box64: console5050/42, interpreter/dynarec requests, cancellation and wineserver cleanup.
- x86 Vulkan map failure localized with genuine ARM64 control; narrow isolated Wine WoW64 placed allocation alignment patch. Windows x86 GPU compute10800/42 passes.
- Official DXVK2.7.1 x32 D3D9:120 Draw/Present,19 changing PixelCopy samples on Adreno840. No DXVK/Box64 rebuild.
- Sandbox device-loss trace retained; root cause and GfxDeviceWorker issue unresolved. Mafia2 files found/audited only; production x86 Library integration and game startup remain next scope.
- [Validation](docs/HORIZON_X86_GPU_VALIDATION.md); original qualified Wine ZIP/prefixes and prior APKs preserved.

# 0.6.0-alpha-physical-recovery — partial — 2026-10-14

- Wine accumulated raw mouse motion lacked host frame flush; movement-only regression failed before and passed after minimal driver patch. Physical KARLSON camera now responds.
- Empty MIDI discovery now respects mmdevapi Unix dispatch contract; Android WASAPI fixture9600/42 and actual user listening pass. KARLSON PCM/audio audible.
- Existing camera speed setting clarified, range50–6000, persistent validated keyboard/audio game profile; UI save/recreate test passes.
- Final7 Android regressions +1 sensitivity UI;35 JVM/Robolectric,3 audio contracts,2 CTest PASS.
- Sandbox/level-selection presentation stall and GfxDeviceWorker invalid commands unresolved. No long-game qualification, root or user-data cleanup. [Exact report](docs/PHYSICAL_DEVICE_RECOVERY.md).

# 0.6.0-alpha-remote-input-audio — candidate — 2026-10-14

- Isolated Wine keyboard/WSI driver: derive missing scan codes using Wine layout; submit independent raw mouse samples across cursor recentering.
- Raw Win64 diagnostic fixture and internal-app-only Android input test; no system injection. Host delivery passes, host device enumeration/lookup remains incomplete.
- Narrow opt-in Wine11.19 Unix audio module, same-UID bounded PCM transport, actual Android AudioTrack lifecycle/focus, persistent profile flag. No base runtime/registry overwrite.
- WASAPI PE: Linux stock Wine exit42/9600 frames; native Unix contract tests2 PASS; Android audio is compiled only.
- 23 core JVM +11 Android JVM/Robolectric +2 CTest PASS; APK/test APK built. ADB empty, no device/physical/gameplay/audibility qualification.
- [Exact results and next gates](docs/REMOTE_INPUT_AUDIO.md).

# 0.6.0-alpha-evolution — 2026-10-14

- Atomic record-only library removal/clear; physical system Back verified with the user.
- Library search/sort/grid, SAF covers, read-only private storage inventory.
- Persistent Android controller mappings, range/deadzone normalization and hotplug cleanup.
- Physical Bluetooth 8BitDo reached original Wine XInput APIs through isolated winebus overlay.
- Fixed duplicate HID reports and abstract-socket lifetime after blocked accept.
- Per-game touch layouts and explicit Win32/XInput modes; Win32 touch device test passed.
- Audio endpoint absence is explicit; no claim of Windows sound or a production 0.6.0 release.

# 0.5.0-dev.4-titan-checkpoint — 2026-10-13

- Wine interface48 and separate guest-local WSI: real Win32 Vulkan surface, dedicated GPU image pool, Acquire semaphore/fence, application semaphore wait, external queue release/SYNC_FD and Android Present retirement ACK. No full Vulkan RPC or embedded Box64.
- Traced HWND 6357189×0 to corrupt persisted WindowMetrics (6357103), not RECT ABI or Vulkan. Isolated Wine patch initializes failed GDI TEXTMETRIC output; guarded diagnostic prefix repair with registry backup restores decorated 312×213 client and 472×333 resize. Historical creation of corrupt values remains an inference.
- POCO: Windows triangle 120 real frames / exit42 / 21 PixelCopies; DXVK2.7.1 hardware D3D11 cube 120 Draw/Present / exit42 / 22 PixelCopies. Library SAF import/settings/Play route: 120 frames / exit42 / 20 PixelCopies.
- Private verified DXVK DLL cache and conflict-refusing per-application installation; native DLL override only on selected backend. Separate Binder GPU owner; client-area input geometry. Avoid stale portrait Surface during initial orientation change.
- KARLSON official demo imported through Library with resources. Two 45-second `-force-gfx-direct` sessions reached Tutorial with injected Enter/touch/W/Space, visible movement/fall/return, 1651 observed GPU frames and 47 distinct PixelCopies each. Stop/server cleanup/prefix integrity passed. Unity threaded graphics previously crashed; root cause and long/physical gameplay remain unqualified. No ready 0.6.0 claim. [Checkpoint report](docs/PROJECT_TITAN_CHECKPOINT.md).
- Final app: seven targeted Android regression methods passed, plus a repeated short game run; 18 JVM/Robolectric tests and 2 CTest passed. Game QA is explicit opt-in and never downloads/distributes game files.

# 0.5.0-dev.3-incomplete — Surface IPC checkpoint — 2026-10-12

## Persistent image IPC checkpoint — incomplete

- One exec Box64 guest per diagnostic session; two dedicated external-memory images reused across 100 frames. Host imports each image once, with no image/memory allocation per subsequent frame.
- Actual exported/imported semaphore SYNC_FD, EXTERNAL queue ownership transitions, bounded submission/present retirement and ACK. No per-frame DeviceWaitIdle. Abstract session sockets remove stale pathname recovery risk.
- POCO: 100-frame PixelCopy gate plus four additional 100-frame sessions; Stop/resize/guest death/runtime death recovery and stable FD counts. Old Surface reuse after runtime death failed with NATIVE_WINDOW_IN_USE; recovery explicitly recreates SurfaceView.
- 16 Android methods, 18 JVM/Robolectric tests and 2 CTest passed. GPU timestamp observations are for tiny diagnostic clears/copies, not game performance.
- Prepared a separate real Win64 Vulkan triangle PE source/build fixture (HWND, shader pipeline, Draw/Present, resize/close): **compiled only**, not Android-executed. Wine still lacks Win32 WSI; no completed dev.3/dev.4/0.6.0 claim. [Report](docs/PERSISTENT_GPU_IPC_VALIDATION.md).


## 0.5.0-dev.3 image IPC checkpoint — incomplete

- Added real guest-owned Vulkan image export/import through authenticated private Unix FD transport and Android host swapchain presentation, independently checked with PixelCopy. Box64 remains an exec child; qualified Wine/GDI packages are unchanged.
- Replaced the assumed embedding-first recommendation with a tested separate-process image bridge direction. Windows WSI extension/swapchain/Present, DXVK and games remain unimplemented. Exact device results and limitations: [checkpoint](docs/WSI_IPC_IMAGE_CHECKPOINT.md).
- Real Binder Surface ownership in the existing :runtime process, worker-owned JNI refs, session/owner/generation/sequence validation and cleanup/rebind foundation.
- Separate Win64 WSI capability PE reproduces missing Win32 extension: -7/exit44, not a successful Window/Swapchain/Present test.
- On POCO dlopen of Box64 succeeds, but main is not exported. First incorrect loader expectation is retained in evidence.
- Surface Parcel raw marshalling is rejected for Binder/FD objects. No native addresses cross IPC.
- Fixed Windows compute diagnostic verdict for CRLF stdout; existing execution architecture retained.
- Optional incomplete build/version, source/evidence artifacts and eight mission reports. No Wine/Box64 rebuild, base ZIP change, data cleanup, DXVK/D3D11/game integration or completed dev.3 claim.
- [Detailed results and remaining gates](docs/FROSTBOX_0_5_TO_0_6_CHANGELOG.md).

# 0.5.0-dev.2 — Real Box64 Vulkan Bridge — 2026-10-12

- Actual x64/Bionic ELF compute on Android system Adreno840 via existing Box64 Vulkan wrappers.
- Separate Android-only libvulkan.so.1 -> public libvulkan.so fallback executable; qualified default unchanged.
- GIPA/GDPA/core/EXT debug dispatch, allocation/free/debug callbacks, interpreter/dynarec,
 10sequential GPU processes and skip-dispatch negative control device-qualified.
- Separate Wine11.19 win32u host-loader overlay and Windows x64 headless compute:10800/exit42,
 complete managed prefix, debug callback and server kill/wait0. Production launcher unchanged.
- Corrected experiment preparation: Android hardlink denial, guest execute-bit preservation,
 autoconf commented SONAME definition and diagnostic binding under HyperOS memory pressure.
- Independent native/GDI/Library/basic regressions and exact APK/source/evidence artifacts are listed in
 [VULKAN_ABI_VALIDATION.md](docs/VULKAN_ABI_VALIDATION.md).
- No Windows WSI, DXVK or VKD3D claim. [Windows status](docs/WINDOWS_VULKAN_STATUS.md),
 [exact files](docs/FROSTBOX_0.5.0_DEV2_CHANGELOG.md).

Historical releases:

# 0.5.0-dev.1 — Native Vulkan GPU Foundation — 2026-10-12

- Independent ARM64 JNI capability probe and real rotating SPIR-V triangle on Android SurfaceView.
- System Qualcomm Adreno840 / Vulkan1.4.295 observed under app UID on POCO; no bundled GPU driver.
- Worker-owned GPU/WSI lifecycle, per-image semaphores/present fences, single submission in flight,
  sleeping cadence, Surface recreation, background/foreground and Stop/Start.
- Corrected landscape drawing-area loss and incomplete pre-rotation; pixel aspect regression added.
- Actual CPU/GPU timestamp/present cadence/PSS/thermal JSON, with explicit unknown scanout drops/GPU usage.
- Existing Wine/Box64/GDI assets unchanged. Concrete Wine Vulkan dispatch/Box64 SONAME/WSI audit;
  Windows Vulkan/DXVK deliberately remain unimplemented.
- Tests and exact artifacts: [VULKAN_RENDER_VALIDATION.md](docs/VULKAN_RENDER_VALIDATION.md).
  [Complete change list](docs/FROSTBOX_0.5.0_DEV1_CHANGELOG.md).

Historical releases:

> Current: **0.4.0-dev.2**, committed child Edit Unicode recovered on POCO.
> [Exact diagnosis, tests and limits](docs/WIN32_TEXT_INPUT_RECOVERY.md).

## 0.4.0-dev.2 — Win32 text input recovery — 2026-10-12

Released held mouse buttons individually instead of generating unmatched RIGHTUP, which opened
Edit's context menu and redirected subsequent text. Fixed UTF-8 JSON log escaping; retained invalid
byte handling and bounded logs. Existing queued WM_CHAR/live owner-thread focus path now qualifies
single/multiline Edit, BMP Unicode, Backspace, Clear and recreation. Enabled limited committed-text
Android InputConnection and Keyboard/Back. Added correlated Wine/Win32 trace and device tests.
No base Wine ZIP, Box64, prefix cleanup, GPU work or external publication. VersionCode8.
Old incomplete artifacts and seven failed attempts are preserved; full IME/multiwindow is unqualified.


## 0.4.0-dev.1 — experimental GDI GUI qualified on POCO — 2026-10-12

Built FreeType2.14.3 x64/Bionic/font overlay and app/test APKs. Fixed malformed C string,
pthread TLS queue marker, Unix init export visibility, Surface ownership/RGBA8888, stale input
on cancellation and terminal report publication. Real fixture pixels/Latin/Cyrillic/input/close,
10 GUI repeats, recreation/Stop and runtime-death recovery passed on POCO under app application UID.
Console5050/42, adjacent/missing DLL and four basic runtime tests passed. Linux: 16 JVM/Robolectric,
2 CTest. No GPU API or general GUI compatibility claim; original Wine base/prefix preserved.
See docs/WINDOWS_DISPLAY_VALIDATION.md for exact evidence, failures and limitations.

## 0.3.0-dev.2 — 2026-10-11

Added the real Compose Windows EXE Library: explicit Play/Stop, persistent records, details/settings,
live process phases and bounded logs/export through the existing Wine IPC. SAF folder import preserves
relative paths/DLL/assets with streaming, progress/cancellation, space/path/collision checks and a
staged rollback journal. Added shared prefix references and a shared execution lease; no per-game
655 MiB duplication by default. Literal argv, working directory and restricted environment are editable.
Native Wine/Box64 versions, ZIP and both Wine patches remain unchanged.

Fixed concurrent AtomicFile reads discarding unpublished results, correlated IPC request lifetimes,
JNI standard UTF-8 paths, and noisy launch traces hiding missing-DLL errors. Explicit user launches
now have no automatic deadline and remain cancellable; init/control diagnostics keep their bounds.
Own MIT DLL/resource fixtures and Compose device scripts added. Real POCO results, initial failures
and the HyperOS system-picker automation limitation are in ANDROID_LAUNCHER_VALIDATION.md.
No GUI/graphics/DirectX/controller/FPS integration or publication.

## 0.3.0-dev.1 — 2026-10-11

Android managed Wine prefixes: private per-profile staging, PE template provisioning, actual wineboot
without bootstrap, registry/critical DLL checks, atomic ownership/commit journal, runtime-version guards
and nondestructive reuse. WineRuntimeClient/AndroidWineManager reuse the existing JNI ProcessManager.
Added explicit recovery with recorded PID/UID/start-time guards, prefix-scoped wineserver shutdown,
ApplicationExitInfo diagnostics and a durable last-recovery record. Explicit prefix deletion does not
follow symlinks or remove imported files. SAF repository persists bounded literal argv/working directory.

Ten lifecycle instrumentation tests passed on POCO, including 20/20 normal-prefix executions (5050/42),
real content-URI import and explicit execution, cancellation, runtime SIGKILL, interrupted wineboot,
file-lock contention, integrity refusal/safe deletion and application force-stop/restart.
13 JVM/Robolectric tests and 2 CTest suites pass. Existing Android regression evidence is in the report.
Old bootstrap diagnostics are retained. No GUI, WoW64, DXVK, games, automatic Wine migration,
arbitrary power-loss guarantee or user-facing general launcher. Optional source-build workflow added;
no GitHub execution/publication claim.

## 0.2.0-dev.2 — 2026-10-10

Real POCO validation: four app-UID runtime tests,20 repeated ELF executions, Bionic pthread/TLS,
and20 repeated original Windows x64 EXE runs (5050 / exit42) passed. Fixed guest ELF executable
metadata required by Box64. Added pinned Wine11.19 NDK/Bionic cross-build, two local portability
patches, verified bundled userspace extraction, console bootstrap command and prefix-scoped shutdown/wait.
Runtime screen has a real console test button only when that optional package is present.
Added detailed device logs, installed APK hash verification, build recipe and corresponding Wine source.

Wine's normal prefix DLL provisioning, GUI, imported programs, WoW64 and games remain unqualified.
One Wine service attempt was killed by HyperOS normal_mem_pressure; earlier failed tests are preserved.
No SELinux/root/security restrictions were disabled.12 JVM tests and2 CTest suites passed.

## 0.2.0-dev.1 — 2026-10-10

Added C++ process lifecycle/diagnostics, host Wine prefix manager, original x64/x86 PE fixtures,
checksum-pinned Box64 Android source build and optional APK packaging, private runtime service,
Runtime Lab diagnostics and four device instrumentation tests. Host Wine ran both PE fixtures.

Host process tests cover cancellation, timeout escalation, descendant cleanup, descriptor/signal
inheritance, output bounds and exec errors. APK and instrumentation APK compile. No Android
Box64 execution, Android Wine/PE, window, DXVK, game or performance validation yet.


## 0.1.0-alpha — 2026-10-10

Initial independent Android foundation: Compose launcher, SAF PE import, DataStore profiles,
Android thermal/controller diagnostics, C++20 JNI and system Vulkan device probe, local export/logs,
core policy/statistics tests and Linux/GitHub build configuration.

Windows execution, graphics translation, active thermal/FPS control and Windows gamepad forwarding
remain unimplemented. No physical-device or game compatibility claims.
