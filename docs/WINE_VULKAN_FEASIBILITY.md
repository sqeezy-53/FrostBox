# dev.3-incomplete correction to the future WSI design

The dev.2 headless result below remains valid. Its future-design paragraph did not distinguish the Android JNI :runtime process from its exec-spawned Box64/Wine child. Surface is now actually transferable/owned in :runtime, but that window address cannot be used by the child. No Wine Android WSI has been implemented. Current executable dlopen succeeds but main is hidden; a supported callable entry/signal/TLS/exit harness or real child Binder delivery remains required. [Current exact interface48 audit and checkpoint](WINDOWS_VULKAN_WSI.md).

Historical dev.2 implementation and results:

# FrostBox 0.5.0-dev.2 — Wine Vulkan feasibility

## Existing runtime and the isolated experiment

Qualified Wine11.19 (`455e3509b98a6919fd4ad1def4803e08c41c03b2`) is x64/Bionic,
not glibc, built --without-vulkan. PE `vulkan-1.dll`, `winevulkan.dll` and Unix `winevulkan.so`
already exist, but `dlls/win32u/vulkan.c` sees SONAME_LIBVULKAN undefined and its host backend
returns unavailable. Android control `windowsWithoutVulkanControl` now proves this exact error.
The PE loads its loader, but no GPU compute success is claimed for the qualified runtime.

An incremental **separate** source/build copy changes only `include/config.h` to define
SONAME_LIBVULKAN="libvulkan.so.1" and rebuilds `dlls/win32u/win32u.so` using the same pinned
NDK toolchain and FreeType configuration. Generated Wine Vulkan thunks already compile in the
qualified tree. This is an explicit host-loader build experiment, not a general --with-vulkan
configure certification or a replacement production runtime. All source/build mutations occur
in the copy; the qualified Wine ZIP/display assets remain byte-identical.

```sh
python3 scripts/build-wine-vulkan-overlay.py --qualified-work /path/to/qualified-wine-display \
  --work /path/to/separate-wine-vulkan --sdk "$ANDROID_SDK_ROOT"
python3 scripts/verify-vulkan-package.py --wine
./gradlew -PwithBox64=true -PwithWineProbe=true -PwithWindowsDisplay=true \
  -PwithBox64Vulkan=true -PwithWineVulkan=true \
  :android-app:assembleDebug :android-app:assembleDebugAndroidTest
python3 scripts/validate-box64-vulkan.py YOUR_DEVICE_SERIAL \
  --methods windowsWithoutVulkanControl windowsCompute --output /path/to/new-evidence
```

The APK stores experimental `wine-vulkan/win32u.so` separately. A private runtime view copies
immutable base+qualified GDI files, then atomically replaces only its own win32u.so. Android
hardlinks were denied; streaming copy preserves executable bits and checks storage. View v2
is separately versioned; failed v1 is retained. It never changes qualified Wine or production
launcher/GDI selection. Box64 is the separate Vulkan-patched executable for this experiment.

The diagnostic PE uses an existing diagnostic-owned, fully initialized prefix under the same
profile lock as production management. There is no WINEBOOTSTRAPMODE, no prefix duplication or
automatic imported-EXE launch. Wine server kill/wait occurs through the existing native Process
registry in finally/NonCancellable; PID, stdout/stderr, exit/signal, timings and shutdown are recorded.
Runtime death remains a visible error. BIND_IMPORTANT is the same ordinary binding flag as Wine
launcher, not a system-kill prevention mechanism or privileged service.

## Wine 11.19 Unix / driver contracts

`winevulkan` PE thunks marshal Win64 structures/handles/callbacks into the Unix side. Unix
win32u loads Vulkan and resolves native GIPA/GDPA via Box64; further translated calls use the
registered Vulkan ABI wrappers. WINE_VULKAN_DRIVER_VERSION is **48**. The Wine user_driver
`pVulkanInit(version, vulkan_handle, &driver_funcs)` contract supplies:

- p_vulkan_surface_create(client_surface, vulkan_instance, VkSurfaceKHR*)
- p_get_physical_device_presentation_support(physical_device, queue_index)
- p_map_instance_extensions / p_map_device_extensions

winefrost.drv has no Vulkan init callback. If pVulkanInit returns STATUS_NOT_IMPLEMENTED,
Wine's null driver maps extensions and offers a headless-surface callback. The compute probe
requires no surface or VK_EXT_headless_surface: this is headless **operations**, not a headless
presentation claim. Driver extensions/thunk coverage may still cause separate ABI failures.

Exact outcome is recorded in [WINDOWS_VULKAN_STATUS.md](WINDOWS_VULKAN_STATUS.md).

## Concrete future windowed design (not implemented)

UI Activity owns a dedicated SurfaceView and passes **Parcelable Surface** with window token,
generation and pixel dimensions over authenticated/non-exported Binder to `:runtime`.
Runtime takes ANativeWindow_fromSurface on its own JNIEnv/thread, owns the reference and releases
it exactly once after GPU/WSI retirement. No Java Surface pointer, JNIEnv or raw ANativeWindow
pointer crosses Binder. Only a process-local, locked HWND/token/generation registry is exposed
to winefrost. That driver is unchanged in this version.

A future Wine driver implementing interface48 converts Windows HWND -> registered surface;
Box64 needs a qualified vkCreateAndroidSurfaceKHR path accepting a genuine runtime-local
ANativeWindow. Native Vulkan structures/extension names must be mapped to Wine's Win32 facade,
with physical-device queue presentation queries and verified thunk coverage. Wine builds with
Android-platform declarations must be audited rather than pretending Win32 handles are windows.
Each runtime owns its VkInstance/Device/Surface/swapchain; the independent main-process native
triangle renderer must not produce into the same Surface.

Destroy/resize/orientation: generation invalidates old attaches; runtime stops presentation,
retires fences/submissions, destroys old swapchain/Surface, releases window and acknowledges.
New generation creates a new Surface/swapchain using actual capabilities/currentTransform and
presentation support. Binder death invalidates the producer session; restart sends a fresh Surface.
Stop cancels runtime tasks and tears down GPU resources; no stale frame delivery after Stop.
Swapchain synchronization/present completion and device-loss need their own tests, not GDI frame
counters. WSI is blocked on this unimplemented driver/IPC/ABI integration, not native GPU availability.

## Licenses

Wine Unix overlay remains LGPL-2.1-or-later; upstream source, all existing FrostBox patches and
isolated recipe accompany the release source archive. Box64 MIT; original probe/scripts MIT.
System Qualcomm binaries are used through Android's public loader and never redistributed.
No Mesa/Turnip, DXVK or VKD3D files are added. Current source provenance is pinned, not a
claim that these dependency versions are globally latest.
