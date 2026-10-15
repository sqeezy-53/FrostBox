# Windows Display — 0.4.0-dev.1 design and qualification boundary

Decision 2026-10-11. The 0.3.0-dev.2 evidence (29 Android, 16 JVM, 2 CTest)
and source were inspected before changing the runtime. These are historical results,
not automatically results for this version. The original Wine userspace ZIP stays immutable.

## Alternatives investigated against pinned Wine 11.19

| Path | Android/Box64 boundary | Work / maintenance | Resources / latency | Future graphics / input | Licensing |
|---|---|---|---|---|---|
| A: winex11 + embedded X server | X11 protocol separates x64 client from ARM64 server; rootless feasible | Build Bionic Xlib/Xext/Xrender/Xrandr etc.; integrate server, Android buffers, selection/window management | Server and client allocations, socket/event processing; exact device costs unmeasured | Mature Wine X11 input; Vulkan needs explicit cross-ABI WSI integration | Wine LGPL2.1+; Xorg per-file MIT; Termux:X11 integration GPL3, cannot silently relabel MIT |
| B: winewayland + Android compositor | Wayland protocol also separates architectures; stock compositor does not directly present on Android | wayland-client, xkbcommon, protocol generation, Android compositor + seat/output/shm support | Compositor buffers and IPC; exact device costs unmeasured | Modern Wine driver, input seat; Vulkan requires compatible allocation/WSI, not automatic | Wine LGPL2.1+, Wayland MIT, compositor-specific obligations |
| C: wineandroid | Upstream uses JNI, Android pseudo-device IPC, AHardwareBuffer, native window handles | Unmodified driver cannot run in our exec-launched x64 guest with ARM64 ART; native ABI pointers are not interchangeable | Stock path can share buffers but our ABI boundary is unqualified | Existing GDI/input model useful; upstream functionality does not establish our GPU compatibility | Wine LGPL2.1+ |

Local primary source: Wine tag wine-11.19, commit
455e3509b98a6919fd4ad1def4803e08c41c03b2; dlls/wineandroid.drv/{init,device,window}.c,
dlls/winewayland.drv, dlls/winex11.drv and programs/explorer/desktop.c.
Online references: [Wine source](https://github.com/wine-mirror/wine/tree/wine-11.19/dlls/wineandroid.drv),
[Wayland driver](https://github.com/wine-mirror/wine/tree/wine-11.19/dlls/winewayland.drv),
[Termux:X11](https://github.com/termux/termux-x11),
[Android Native Window API](https://developer.android.com/ndk/reference/group/a-native-window).

## Selected experiment: C-derived software surface adapter

Stock wineandroid is rejected for this ABI layout. Retain Wine's existing GDI rasterizer,
window_surface interface and user-driver event routing, with a **separate winefrost.drv**
software transport instead of the upstream JNI/AHardwareBuffer transport. This is a
limited adaptation, not qualification of stock wineandroid or a new GPU driver.
It avoids bringing in an X server/compositor for a single-window GDI smoke test.
If requirements grow beyond this small adapter, reconsider X11 instead of implementing
an entire window system. Driver code derived from Wine stays LGPL2.1-or-later.

Win32 x64 → Wine GDI surface → bounded local Unix socket → Android worker → SurfaceView.
Android touch/key → same UID-checked socket → Wine event queue → Windows messages.
No network, root, system input injection, frame timer or remote renderer. Pixel copying
is intentional; this version makes no zero-copy, GPU acceleration or FPS claim.
Initially one foreground top-level software window, fixed virtual desktop 800×600,
letterboxed to the current Android Surface; no compositor, clipboard, IME or multiwindow guarantee.

An independently hashed display package contains the driver and a narrowly patched explorer
(select frost only when a session endpoint is supplied). Wine searches its own dll_dir first, so a private runtime view copies the immutable base
and atomically replaces only package files in the view; setting WINEDLLPATH alone would not suffice.
Base Wine and its prefix identity are unchanged. No registry Graphics preference is persisted. Console launches use the old path.
The Android display session is ViewModel-owned, separate from the Library and existing ProcessManager.
Surface attach/detach does not own the Windows process. A terminal process/IPC failure closes
connections. Recreated surfaces receive the most recent real frame. Rendering happens only after
Wine damage or Surface recreation. Input has explicit coordinate mapping and focus release.

Device qualification is recorded separately after actual builds and tests; this design alone
is not proof of visible pixels, compatibility, performance or leak freedom.

## Current qualification scope

FreeType2.14.3 x64/Bionic and matching win32u/font overlay are built. Real fixture Latin/Cyrillic
pixels, in-app input, close, ten repeats and lifecycle recovery passed on POCO. This is software
GDI only; arbitrary GUI compatibility, physical input and long-term leak freedom are unqualified.
See [actual validation report](WINDOWS_DISPLAY_VALIDATION.md). Base headless runtime is preserved.

## Device findings — 2026-10-12

HyperOS denied hardlinks (EACCES), so the runtime view uses Files.copy and atomic replacement
of overlay files. This costs approximately one extra Wine installation per overlay version;
it is cached and reused, not copied per game or launch. The base ZIP is unchanged.

winefrost must include wine/unixlib.h so __wine_unix_lib_init has default export visibility.
Without it LoadLibrary could succeed while the Unix driver initialization remained absent.
Android x64/Bionic dynamic TLS __tls_get_addr is avoided in this adapter by a once-created
pthread key storing only a per-thread non-null token after successful Wine queue registration.
The key is process-owned; no per-thread heap allocation/destructor is needed.

SurfaceView uses RGBA8888. The render worker owns a separate Parcelable/native Surface reference,
retired on that same worker; SurfaceHolder lifecycle cannot release its Java wrapper under a pending
presentation. Conversion/lockCanvas never hold the main lifecycle lock. Stop disables/clears input
before IPC cancellation, while terminal cleanup retains real process errors.

A terminal Library report is published before activeId becomes null, preventing stale partial
snapshots from being read at the next Play. Optional Wine import_dll registration errors are retained
in logs; loader_init failures still produce Failed. Missing application DLL regression is required.
