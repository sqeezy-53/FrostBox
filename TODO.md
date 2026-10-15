# HORIZON next verified slice

- Factor checked WoW64 package preparation into the existing Wine Runtime Manager; retain separate prefix identity/hash, refuse x64 prefix migration.
- Explicit experimental per-profile WoW64 + D3D9 selection and real Library Play/Stop/recovery, with backwards compatible persisted configuration.
- Import complete user Mafia2 folder via SAF; test startup/menu before gameplay claims. Do not redistribute game/PhysX files.
- x86 audio, controller, Unicode and Surface recreation qualification.
- Trace first guest DEVICE_LOST around Sandbox scene transition, with device/queue/image generations; keep Unity GfxDeviceWorker independent.
- Ordinary placed allocation patch excludes imported/exported/dedicated memory: qualify those contracts separately.
- DDraw/D3D8/D3D10/D3D12 remain unqualified. No game performance/long-session qualification.
- Explicit safe cleanup of obsolete diagnostic/artwork cache, and persistent cold-start catalog preferences remain pending.

## Current mission checkpoint: Windows Present gate still closed

- [x] Real Binder Parcelable Surface ownership on POCO, resize/recreation, explicit revoke/close and runtime death/rebind.
- [x] Preserve qualified dev.2 Wine/Box64/GDI assets and APK/evidence.
- [x] Device-reproduce missing Win32 WSI with real Windows PE (-7/44).
- [x] Correct initial dlopen hypothesis: executable is loadable, main is not exported; first failure retained.
- [ ] Supported isolated Box64 entry harness or actual public-API child Binder delivery; qualify same-process Surface use, ART signals/TLS, native exit, cancellation and lifetime.
- [ ] Separate Wine Android host extension generation, interface48 Vulkan callbacks and pCreateClientSurface lifecycle.
- [ ] Real Windows PE triangle, HWND→Android VkSurfaceKHR, swapchain, acquire/submit/present, PixelCopy changes and lifecycle tests.
- [ ] After A: selected SHA/license-verified DXVK overlay and real D3D11 PE/Present.
- [ ] After B: lawful third-party Windows x64 game through Library, input, scene, Stop/Restart and bounded measured session.

This is not a release-complete dev.3. See docs/FROSTBOX_0_5_TO_0_6_CHANGELOG.md; do not skip the Windowed Windows Present gate.

# Next gate after 0.5.0-dev.2

Device-proven: Box64 x64/Bionic compute (interpreter/dynarec,10repeats/negative control), isolated
Wine Windows x64 headless compute10800/exit42. Production launcher still selects qualified GDI.
Next: headless PE session repeats/failure injection/callback and pNext coverage, then a selectable
isolated backend and the Wine interface48 WSI/Surface Binder design documented in
[Wine feasibility](docs/WINE_VULKAN_FEASIBILITY.md). Qualify real Windows presentation/resize/Stop
before any DXVK work. No WSI prototype was added in this version.
Retain qualified runtimes/prefixes and all independent native/GDI regressions. Windows external
memory/synchronization, non-coherent memory, every extension, long-session leaks and GPU device-loss
remain unqualified. Historical TODO entries below are not current release status.

# Next GPU gate after 0.5.0-dev.1

Native Android Vulkan foundation is device-qualified. Do not treat it as Windows Vulkan/DXVK support.
Next isolated gate: x64/Bionic headless Vulkan through existing Box64 wrapping under :runtime,
including native libvulkan.so.1 versus Android libvulkan.so resolution. Then Vulkan-enabled Wine
headless dispatch, Surface Parcelable transfer / Wine driver version48 WSI callbacks, Windows Vulkan
frame/lifecycle, and only then DXVK qualification. Keep the qualified runtime packages/prefixes.
See [architecture and concrete blockers](docs/VULKAN_GPU_ARCHITECTURE.md).
Unqualified: forced device loss, hung-driver recovery,180/270-degree transforms, long-session leaks,
AHB/fd sharing, full extension/feature compatibility. Older tasks below are historical stage records.

> Current dev.2: committed Unicode child Edit recovery is complete; see [report](docs/WIN32_TEXT_INPUT_RECOVERY.md). Earlier display tasks below are historical. Next input work is manual device/keyboard qualification and full IME/selection/multiwindow; it was not started here.

## Display follow-up after real POCO qualification — 2026-10-12

- Correct requested640×400 versus allocated640×512 viewport/padding.
- Add correctly licensed caption symbol font coverage.
- Qualify physical touch/keyboard and another real GDI application; then multiwindow/IME.
- Measure long sessions/resource growth; ten repeats are not a leak proof.
- Preserve software display/console/prefix regressions before adding any graphics API.

# Next display gate (0.4.0-dev.1 unfinished)

- Restore host Gradle/ADB access; do not count sandbox failures as Wine/device failures.
- Add pinned, licensed x64/Bionic FreeType and matching win32u to the display view, preserving base Wine.
- Build candidate APK/test APK with withWindowsDisplay=true; run first GUI pixel/input test.
- Verify actual winefrost load, GDI text, colors, input coordinates and clean exit42 on POCO.
- Then 10 repeats, focus/background, Surface recreation, runtime kill/recovery, process/FD/memory audit.
- Run complete 29 Android/16 JVM/2 CTest gate on exactly the final APK; manual SAF separately.
- Measure first-frame/close/idle CPU only from real device evidence. No measurements available yet.

# 0.3.0-dev.2 follow-up

- Keep all Library/prefix/Box64 regression gates passing before Windows Display work.
- Manually qualify system SAF picker on HyperOS; cross-app UiAutomator injection is currently denied.
  Controlled picker-result tests already exercise real ContentResolver data and actual UI launches.
- Expand SAF provider fault coverage: cloud latency, blocked reads, storage-full during commit,
  process death at every publication stage and real multi-GiB files. Cancellation is cooperative.
- Console log encoding selection (OEM/code page/UTF-8); current byte-preserving JSON display is qualified for ASCII.
- Export round-trip and configuration restore UI; integrated safe file/prefix management with explicit
  references/confirmation (Library record removal currently keeps data).
- Profile AtomicFile + DataStore writes are atomic independently; Play repairs the config snapshot,
  but a unified versioned transaction/migration and import recovery under arbitrary power loss remain.
- Prefix sharing is deliberate registry/C: sharing. Add named standalone prefix catalogue and migration.
- Extend critical integrity checks; test real low-memory/background/reboot/resource growth. Current
  SIGKILL scenarios do not prove every HyperOS kill path or unlimited-duration Windows sessions.
- Add a restricted storage/process boundary before claiming isolation of hostile Windows code.
- Next technical feature: **FrostBox Windows Display**, original Win32 window into Android Surface,
  before any DXVK/game performance work. No current GUI compatibility claim.

# Roadmap / evidence gates

## Alpha 0.1 follow-up
- Physical POCO/HyperOS acceptance: docs/POCO.md; accessibility, landscape, controller focus.
- SAF directory import for dependent DLLs/assets, cancellation/progress and configurable size budget.
- Profile deletion with confirmation, covers, config restore/migration and stale import recovery.
- Dedicated persisted controller mappings/calibration, trigger fallback, touch controls, rumble.
- Richer Vulkan feature/extension enumeration and driverProperties; device-specific inventory.
- Custom thermal sensitivity/threshold policy editing; profile-scoped advisory display.
- UI localization and screenshot/device instrumentation suite.

## Alpha 0.2 — Windows runtime (blocking gates)

Completed: four native Android tests,20 repeated ELF runs, Bionic pthread/TLS, original Windows x64
console through Wine/Box64 with5050/exit42 and20 repeats on POCO. The source build and two Wine
patches are pinned. Private service reports actual output and performs prefix-scoped wineserver shutdown.
This establishes the first console EXE milestone, not completion of Alpha0.2.

Ordinary prefix provisioning without WINEBOOTSTRAPMODE, transaction/locking/recovery and Library
launching are implemented. Remaining gates: full tree ownership under all crash times and actual
HyperOS memory-pressure recovery.
No DXVK/Vulkan integration before these console/runtime lifecycle gates.

1. Box64 execution is qualified for these fixtures; qualify Wine WoW64 independently.
   Verify Bionic vs glibc, ELF interpreter, guest/native library wrappers, 16 KiB pages, dynarec
   W^X behavior and instruction-cache synchronization. Never execute downloaded host binaries.
2. Extend the qualified bootstrap console path to normal prefixes and an interpreter Windows run producing exact stdout
   and exit code on ARM64 Android. Then x86 WoW64 equivalent; retain device logs.
3. Extend existing APK-packaged diagnostic service/process ownership to Wine lifecycle/death handling,
   wineserver ownership and targeted cleanup. No global killall/Box64 -k.
4. Port the host-verified prefix manager to a qualified Android Wine package; repeat wineboot,
   concurrent prefix exclusion, failed init and cleanup tests under app UID.
5. Resolve display: evaluate Wine Android driver status vs independently implemented display bridge.
   Validate actual Windows window rendering into Android Surface and keyboard/mouse input.
6. Package audit: all source offers, notices, reproducible patches and LGPL replacement/relink pathway.
   If Bionic path fails, document the failed test and evaluate isolated glibc loader; do not hide it.

## Alpha 0.3 — graphics
- Enumerate Vulkan extensions/features/limits, check against a pinned DXVK feature manifest.
- Build Wine Vulkan bridge and WSI Surface path, validate triangle and presentation on Qualcomm.
- Run original D3D9 and D3D11 test apps; then one legally owned simple game.
- Capture actual present timestamps; report unsupported values honestly.
- Turnip only after exact GPU/kernel/build verification. D3D12 is a later VKD3D gate.

## Alpha 0.4 — optimization
- Apply DXVK `dxgi.maxFrameRate` / `d3d9.maxFrameRate` only to tested releases.
- Feed actual frametimes/status into policy, log recommendations vs applied cap and reason.
- Verify 40/45 FPS pacing against available display modes; use sleeping/presentation scheduling,
  never an application busy-wait. Restore settings on stop/failure.
- Bridge normalized controller events into Wine XInput; verify with Windows diagnostic program.
- Compare ≥20-minute controlled sessions: average, 1% low, p95/p99, long frames, battery/thermal trends.

## Alpha 0.5 — gaming preview
- Process crash/low-memory recovery, resource leak and long-session regression suite.
- Driver A/B only on supported devices; compatibility matrix with exact game/runtime/driver builds.
- Release signing, reproducible artifacts, source bundles, versioned component manifests and checksums.
- GitHub Releases after device tests and license/package review; no unverified compatibility claims.
