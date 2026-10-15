# EMPIRE BAY Library runtime integration

The existing DataStore, launcher IPC, JNI process registry and Wine Manager remain
in charge. PE machine chooses qualified x64 or private checked WoW64. Runtime/API
strings have backwards-compatible defaults and explicit mismatch validation.
`WineWow64Package` is shared by diagnostic and Library paths. Prepare GDI/WSI first,
then the WoW64 allocation patch, then input/audio modules. Prefix identity includes
base and i386 archive; never migrate an x64 user's prefix silently. Existing
atomic prefix provisioning, locks, journaling, server shutdown and recovery apply.

Private WoW64 audio module reuses Wine11.19 parameter thunks and the existing PCM
IPC; NtAllocateVirtualMemory supplies buffers addressable by32-bit clients.
Qualified x64 audio/module/ZIP/Box64/DXVK are unchanged. PCM is bounded shared
stereo48kHz, not a full arbitrary-format/capture audio engine. Wine thunk source
attribution and LGPL notices are retained in`audio/wine/frost_audio_wow64.c`.
[Actual device proof and limitations](../EMPIRE_BAY_VALIDATION.md).

Historical architecture below describes earlier checkpoints.

# HORIZON experimental WoW64 layer

Existing UI/IPC/Process Manager architecture is retained. New console/GPU probes
use a private SHA-keyed i386 PE overlay, standard fresh win64/syswow64 prefix,
and existing64-bit Bionic Wine/Box64. Optional private win32u module fixes ordinary
host-visible placed mapping allocation alignment. No32-bit Android ABI or Box86.
New NativeVulkan map control is separate from Windows compute/render evidence.
No production profile backend switching yet; diagnostic routes are debug-only.
[Validation and next integration contracts](../HORIZON_X86_GPU_VALIDATION.md).

# Remote input/audio candidate (2026-10-14)

Wine11.19 hardware input contract supplies scan codes and separate raw mouse samples;
existing Wine server/Raw Input routing retains Windows structure/handle ownership.
Audio uses the existing mmdevapi Unix contract in a separate opt-in wineoss overlay,
with same-UID bounded PCM socket to launcher-owned Android AudioHub/AudioTrack on IO
workers. Execution remains under the existing :runtime/JNI ProcessManager; no new
process manager or Vulkan RPC. Limits and unverified device gates:
[REMOTE_INPUT_AUDIO](../REMOTE_INPUT_AUDIO.md).

## PROJECT TITAN experimental Windows GPU path (2026-10-13)

Wine/Box64 remains an exec child. libfrostvk supplies guest-local Win32 WSI via Wine interface48; core Vulkan dispatch remains the existing Box64 wrapper. It allocates real exportable VkImages, waits application render semaphores and exports SYNC_FD after EXTERNAL ownership release. The same-UID private endpoint carries only versioned metadata, memory/sync FDs and retirement ACKs. The Binder owner in :runtime imports images and presents through its own Android swapchain. No Vk pointer, ANativeWindow pointer or flattened Surface parcel crosses Unix IPC. Library owns a separate bounded Surface session and feeds the validated endpoint to the existing Process Manager. GDI remains an independent qualified path and supplies window/control events, not GPU frames. Prefix ownership/locks/recovery remain in AndroidWineManager.

Current limits and evidence: [PROJECT_TITAN_CHECKPOINT](../PROJECT_TITAN_CHECKPOINT.md).

## 0.5.0-dev.3-incomplete: Surface ownership boundary

RuntimeSurfaceService is a non-exported Binder endpoint in :runtime. A HandlerThread serializes Parcelable Surface attach/revoke/close, native acquisition and release. UUID/session owner Binder/sequence/generation authenticate ownership and reject stale requests; opaque registry IDs never leave JNI service code. GDI and RuntimeProbeService/Process Manager remain separate and unchanged in topology.

Crucially Process::spawn executes Box64/Wine in another process, so :runtime's ANativeWindow is not a valid child pointer. This checkpoint has no child Binder delivery or Box64 entry invocation. dlopen succeeds on POCO but main/initialize are LOCAL HIDDEN; a supported isolated entry harness plus signals/TLS/exit tests is needed before any same-process WSI claim. Pinned Wine interface48/client_surface lacks Android host-extension generation and driver callbacks. New Windows WSI capability PE returns expected -7/44. Windows presentation, DXVK and game stages remain unimplemented. See [exact audit](../WINDOWS_VULKAN_WSI.md) and [IPC contract](../ANDROID_SURFACE_IPC.md).

# Vulkan foundation 0.5.0-dev.1

Independent ARM64 JNI Vulkan diagnostics/renderer added beside existing Wine GDI.
No Wine/Box64 runtime changed. Native Surface ownership, ABI audit and the ordered Wine Vulkan
plan are in [VULKAN_GPU_ARCHITECTURE.md](../VULKAN_GPU_ARCHITECTURE.md).

# Architecture — current software GDI display

The qualified console runtime described below remains the baseline. The **fixture-qualified**
0.4.0-dev.1 extension is documented in [WINDOWS_DISPLAY_ARCHITECTURE.md](../WINDOWS_DISPLAY_ARCHITECTURE.md).
`display/DisplaySession` is ViewModel-owned; `WindowsSurface` owns only Surface/input lifecycle.
`winefrost.drv` is LGPL and uses Wine GDI surfaces. Existing LibraryLauncher/IPC/JNI ProcessManager
still own execution. No new process manager, X server, graphics API or GPU implementation.
Actual build/device limits: [text recovery report](../WIN32_TEXT_INPUT_RECOVERY.md).
Dev.2 text uses UTF-16 packets on the existing same-UID local display socket, live owning-thread
focus lookup and queued WM_CHAR; process IPC is unchanged. Only actually held mouse buttons
are released. Runtime JSON decodes valid UTF-8 rather than treating its bytes as Latin-1.

# FrostBox architecture — 0.3.0-dev.2

Status: Android application plus real headless Windows x64 execution on POCO through Wine/Box64.
Host Wine runs original x64/x86 PE tests. [Native details and evidence](../NATIVE_RUNTIME.md).

- `android-app`: Compose Material 3 UI → ViewModel → profile repository / runtime interface.
  DataStore stores versioned JSON. SAF import streams into a private UUID directory, checks PE headers,
  hashes the file, atomically publishes it, then saves the profile. No broad storage permissions.
- `core`: Android-independent Kotlin models, PE identification, environment validation,
  runtime/backend/input contracts, thermal advisory policy and frame statistics. JVM tests.
- `runtime/native`: C++20 JNI bridge, explicit runtime-unavailable status, system Vulkan probe.
  Native errors become diagnostic text and logcat entries. Fatal native crashes cannot be caught by JNI;
  previous process exit reasons are collected on Android 11+. Built-in native diagnostics run in a
  non-exported bound `:runtime` service process via Messenger, with IO-dispatched JNI polling.
- `runtime/process`: host/Android C++ process lifecycle, bounded IO, cancellation and resource metrics.
- `runtime/cpu`: Box64 command adapter; APK-packaged Bionic build, pinned upstream source.
- `runtime/wine`: existing host manager plus Android Wine command factory; reuses ProcessManager.
- `AndroidWineManager`: private per-profile Wine transactions, staging, critical integrity, journal,
  recovery and prefix-specific server control. WineRuntimeClient owns nonblocking Messenger IPC.
  [Prefix protocol and limits](../PREFIX_RUNTIME.md).
- `tests/guest`, `tests/windows`: original deterministic ELF/PE programs; no game files.
- `scripts`: reproducible component/fixture builds and explicit device-test runner.
- Android adapters are separated into `data`, `input`, `thermal`, `diagnostics`, `runtime`, `ui` packages.
  Dedicated Gradle modules can be extracted when their binary boundaries become useful.

## Runtime decision

Selected and device-tested path: APK-installed ARM64/Bionic Box64 0.4.4 translating x64/Bionic
Wine 11.19 with two documented local Wine patches. Linux/glibc Wine is not loaded into Bionic.
Console Windows x64 execution works on the POCO; WoW64, display/Surface and graphical drivers are
unimplemented. Native Android protection is unchanged; no root, target-SDK downgrade or SELinux bypass.
Guest PE image copy and anonymous translator mappings are documented in WINE_BIONIC.md.

Android app directories separate games but are NOT security isolation from a malicious Windows process
running under the same UID. Before executing untrusted code, design a restricted process/storage boundary.
Guest EXEs are data for the translator, never Android executables. Import never launches.

## Graphics / input / telemetry

System Vulkan enumeration reports real device properties; a successful probe is not DXVK qualification.
Turnip is unavailable until GPU ID, kernel interface and exact build are confirmed on the actual phone.
Backend preferences are stored, never advertised as active. No framebuffer, DXVK or XInput path exists yet.
Android InputManager and Activity events feed diagnostic Flow state. Future RuntimeInputSink accepts
normalized events; it must remain disconnected until a Windows test confirms delivery.

Thermal status and battery temperature are different measurements. Headroom is sampled at 10-second
intervals, treated as unavailable on NaN. ThermalPolicy is advisory: EMA, immediate severe-status
reaction and 30-second cool hysteresis; no CPU/GPU frequency writes. Collection stops in background.
Frame statistics require runtime-present timestamps. Compose/UI FPS is never used as game FPS.
1% low = 1000 / mean of slowest ceil(N * .01) frame durations. Long frame > 1.5 * target duration.

## Persistence and lifecycle

Single application DataStore instance, IO dispatcher for copying/probing/export, transactional updates,
bounded import (512 MiB) and log history. Profiles validated before writes. Damaged storage is surfaced,
not silently reset. ViewModel survives Activity recreation; listeners are removed on stop/clear.
No game session or lastLaunch timestamp is fabricated on a failed readiness check.

Device evidence and next gate: [ANDROID_RUNTIME_VALIDATION.md](../ANDROID_RUNTIME_VALIDATION.md).

## Android prefix lifecycle (0.3)

PROVISIONING → INITIALIZING → READY → RUNNING → IDLE. Interrupted staging is resumed, while
invalid committed data/version changes fail without automatic reset. Kernel file locks and AtomicFile
coordinate repository launch configurations with runtime transactions. Recovery checks recorded PID
identity and UID before signalling; it never resumes a user EXE automatically. SAF remains UI→repository;
DataStore is not accessed by the separate runtime process. Detailed device evidence belongs in
ANDROID_RUNTIME_VALIDATION.md; historical bootstrap results are retained separately.

## Windows EXE Library (0.3.0-dev.2)

The saved Library now launches real user-selected console x64 PE through the managed Wine API.
SAF EXE/tree imports, literal argv/environment editor, shared prefix references, process phases,
bounded logs and export are implemented. Folder imports use staged publication plus a generation
journal; request IDs separate consecutive IPC operations. No component execution was moved into UI.
See [Library contracts](../LIBRARY_ARCHITECTURE.md) and [user guide](../LAUNCHER_USER_GUIDE.md).
Future graphical/input preferences remain in old records but unavailable controls are hidden.

## 0.5.0-dev.2: translated Vulkan diagnostics

Production Wine/Box64/GDI selection remains unchanged. Optional `withBox64Vulkan` packages a
separate ARM64 Box64 executable with a four-line Android SONAME fallback, an x64/Bionic ELF
compute fixture and pinned manifests. RuntimeProbeService diagnostic modes10..13 use the same
Binder/JNI Process registry, bounded output and child cancellation/reaping. ARM64 native Vulkan
renderer remains an independent main-process subsystem.

Optional `withWineVulkan` adds an isolated Wine win32u Unix module. Diagnostic modes14/15 compare
the qualified no-Vulkan runtime with a separate headless runtime view, use a managed diagnostic-owned
prefix under its existing lock and terminate/wait Wine server before completion. Production
AndroidWineManager and winefrost.drv are not changed. Immutable runtime files and mutable ready
markers are separate; copy uses bounded buffers, space checks, atomic rename and executable-bit
preservation. Android hardlink denial is handled without changing security policy.

Proof/ABI coverage: [BOX64_VULKAN_BRIDGE.md](../BOX64_VULKAN_BRIDGE.md),
[VULKAN_ABI_VALIDATION.md](../VULKAN_ABI_VALIDATION.md).
Wine/Surface design and limits: [WINE_VULKAN_FEASIBILITY.md](../WINE_VULKAN_FEASIBILITY.md),
[WINDOWS_VULKAN_STATUS.md](../WINDOWS_VULKAN_STATUS.md). These are separate statuses; no DXVK/WSI
switches are offered in UI based solely on successful compute.

## Evolution input and library checkpoint

Library removal/clear are atomic DataStore record edits with active-session guards; no file
or prefix deletion. Cover decoding is bounded and off the UI thread. Storage inventory is
read-only and does not follow symlinks. UI sections are Library/Controllers/Storage/Settings.
ControllerMonitor owns Android normalization, per-device preferences and per-game overrides.
ControllerHub publishes bounded same-UID latest-state snapshots to the isolated experimental
Wine winebus Unix backend. Wine's existing HID and XInput drivers retain the Windows ABI.
The base runtime ZIP is immutable; input overlay identity includes the base runtime view.
See docs/XINPUT_VALIDATION.md for protocol, cleanup and remaining limitations.
Touch profiles use the existing game store and route to exactly one selected input backend.
