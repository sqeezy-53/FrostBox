# FrostBox 0.5.0-dev.1 · Vulkan GPU foundation

## Qualified boundary

This iteration implements **native ARM64 Android Vulkan diagnostics and a shader renderer**.
It does not implement Windows Vulkan, DXVK, DirectX, a game FPS limiter or a new Wine display driver.
The separate Wine/Box64 software GDI path, WindowsScreen and existing prefixes are retained.
Device results and raw JSON: [capabilities](VULKAN_DEVICE_CAPABILITIES.md),
[validation](VULKAN_RENDER_VALIDATION.md). All GPU observations come from JNI under FrostBox's
ordinary Android UID on POCO API36, not from an assumed phone specification.

The device reports Adreno (TM) 840, Vulkan 1.4.295, Qualcomm Technologies Inc. Adreno Vulkan Driver,
raw driverVersion 2150932507. The renderer requests Vulkan 1.1, uses the system loader and no bundled
ICD or Turnip. Extensions/format/queue support are reported, not inferred from Vulkan version.

## Implemented Android path

```text
Compose Vulkan Diagnostics (separate from WindowsScreen)
  -> VulkanSession (existing FrostModel/ViewModel lifetime)
  -> JNI libfrostbox_vulkan.so (ARM64, app main process)
  -> worker-owned VkInstance / VkDevice / graphics+present VkQueue
  -> VkAndroidSurfaceKHR (retained ANativeWindow from SurfaceView)
  -> FIFO VkSwapchainKHR / renderpass / framebuffers / GLSL->SPIR-V pipeline
  -> vkCmdDraw(3) / vkQueueSubmit / vkQueuePresentKHR
  -> Android BufferQueue / system Qualcomm Vulkan driver / display
```

Probe returns properties, all device extensions, queues, heaps/types, three image format queries and
RGBA8 external-AHB image compatibility. A headless probe explicitly returns null WSI fields; Start
queries real Surface support. Only hardware devices with graphics+present, KHR_swapchain and the
EXT_swapchain_maintenance1 feature are accepted. Missing extensions/features yield a concrete error.
This strict requirement is a prototype portability limit, not a claim all Android GPUs support it.

Resource ownership: one native renderer per VulkanSession; the ViewModel survives Activity recreation.
JNI transfers one owned ANativeWindow reference into a single-slot request, replacing pending requests.
The native worker retains its own reference until GPU/WSI retirement. SurfaceDestroyed posts null;
Stop and background retire the context. Positive SurfaceChanged dimensions trigger creation/recreation.
GPU operations, fence waits and destructor joins never run on Android UI thread. There is at most one
submitted command buffer in flight. CPU condition-variable deadlines target 60 Hz without busy-wait.

Render-finished semaphores and presentation fences are per swapchain image. The worker waits the
EXT maintenance1 present fences before teardown; vkDeviceWaitIdle alone is insufficient to prove WSI
resource retirement. Command completion has a separate submission fence. OUT_OF_DATE/SUBOPTIMAL
trigger recreation, surface loss/error becomes Failed, device loss is reported through VkResult.
A failed generation is latched until a new Surface or explicit Stop/Start, avoiding endless retries.
No forced GPU fault/hung-driver recovery was tested. A driver that never signals a fence can block
retirement on the worker; this prototype cannot safely destroy resources still used by such a driver.

The scene is original shader geometry using gl_VertexIndex and push constants; no Canvas, bitmap
scene or Wine rendering is used. PixelCopy reads the actual Surface output only for validation.
The swapchain uses queried currentTransform, swaps image dimensions for 90/270 degrees, and applies
that rotation in shader clip space after logical-Surface aspect correction. Reports distinguish image
extent from Surface extent. Identity and 90-degree behavior are device-qualified; 180/270 are implemented
but untested, mirrored/inherit transforms yield an explicit error. An initial incomplete pre-rotation
stretched the scene in landscape and was caught by visual inspection; RGB alone was insufficient.
The test now also checks triangle bounding-box proportions. Landscape report height is bounded to
preserve drawing space.

Metrics distinguish record/submit wall time, scoped renderer-thread CPU time, GPU timestamp interval
(TOP_OF_PIPE to BOTTOM_OF_PIPE, including any intervening synchronization), and present-call cadence.
Accepted present calls are not confirmed scanout or Windows-game FPS. Intervals have a bounded
256-sample window; pauses/recreation do not contribute a false long interval. PSS measures the app
process; heaps are capacities, not allocated GPU bytes. Unobservable dropped display frames/GPU usage
are null. PowerManager thermal status is permission-free. JSON is atomically saved in private files.

## Audit of the actual Wine build (no rebuild in this iteration)

Pinned components remain:

| Component | Pin | License | Role |
|---|---|---|---|
| Wine | 11.19, commit 455e3509b98a6919fd4ad1def4803e08c41c03b2 | LGPL-2.1-or-later plus per-file notices | x86-64/Bionic Wine Unix libraries and PE modules |
| Box64 | 0.4.4, commit 2f130fab1d6e1a4ee8a71dc60cfdfcc839ad192a | MIT | x86-64 execution, wrapped native libraries |
| Android NDK | 27.2.12479018, min API29 | upstream NDK/header notices | ARM64 Vulkan JNI build; existing x64 Bionic Wine build |
| FreeType | 2.14.3, existing qualified package | FTL plus per-file notices | existing GDI font path; unrelated to Vulkan |

`scripts/build-wine-bionic.sh` explicitly supplies `--without-vulkan` alongside `--without-x`,
`--without-wayland`, `--without-opengl`. The actual display build config.log repeats it and config.h
has `/* #undef SONAME_LIBVULKAN */`. PE winevulkan.dll, vulkan-1.dll and Unix winevulkan.so **exist**
in the package; the missing element is a configured host Vulkan loader/backend, not those filenames.

Wine's `dlls/win32u/vulkan.c:vulkan_init_once` loads SONAME_LIBVULKAN and resolves
vkGetInstanceProcAddr/vkGetDeviceProcAddr. If the macro is absent it emits exactly
`Wine was built without Vulkan support.` Its dispatch wrappers convert Wine client/host handles and
call Wine Unix Vulkan functions. PE Win64 entry points and x64 Unix entry points are distinct ABI
boundaries; changing configure options alone cannot provide Android window presentation.

Wine 11.19 `include/wine/vulkan_driver.h` has WINE_VULKAN_DRIVER_VERSION **48**. win32u calls
`user_driver->pVulkanInit(version, vulkan_handle, &driver_funcs)`. The four driver callbacks are:

- p_vulkan_surface_create(client_surface, vulkan_instance, VkSurfaceKHR*)
- p_get_physical_device_presentation_support(vulkan_physical_device, queue)
- p_map_instance_extensions(instance_extensions)
- p_map_device_extensions(device_extensions)

Existing winefrost.drv's user_driver_funcs does not implement pVulkanInit. Wine's null-driver fallback
cannot supply the required Android WSI. Existing software GDI pixels are a different presentation path.

## Actual Box64 wrapping mechanism and unresolved loader name

In the pinned `src/wrapped/wrappedvulkan.c`, `my_vkGetInstanceProcAddr` and
`my_vkGetDeviceProcAddr` use signature tables and AddCheckBridge2 to expose an x64 callable bridge to
ARM64 function pointers. Vulkan allocation/reallocation/free/debug callback wrappers invoke x64 code
via RunFunction helpers. Special wrappers handle creation structures/pNext and debug callback data;
`wrappedvulkan_private.h` includes a vkCreateAndroidSurfaceKHR signature. This is an existing Vulkan
ABI bridge, not ordinary loading of ARM64 code into an x64 ELF interpreter. No Box64 rewrite is needed.

There is a concrete unresolved naming boundary: pinned wrappedvulkan.c names the native library
**libvulkan.so.1**, with no ALTNAME fallback; wrappedlib_init.h uses dlopen of that name. Android's
public NDK loader is **libvulkan.so**. MAPNAME is C token concatenation, not an Android SONAME remapper.
library.c recognizes both guest names for wrapping, but that alone does not change the native dlopen
name. The current FrostBox native search paths do not install a libvulkan.so.1 alias. Therefore native
Android success in this iteration is **not proof** that this Box64 wrapper can load the Android loader.
This was established by source audit, not an executed guest Vulkan probe. Do not claim it is solved.

Next diagnostic gate: compile a tiny x64/Bionic ELF using NDK Vulkan headers, invoke it through the
existing ProcessManager under :runtime, require actual wrapped library load + enumeration + VkDevice,
and inspect stdout/stderr/wrapper resolution. If the predicted SONAME failure occurs, make a narrow
reviewable Android-only Box64 ALTNAME or correct library-name adjustment in a separate versioned build;
retain the qualified binary/package and regress interpreter/dynarec/Wine before choosing that build.
Do not symlink proprietary driver files, dlopen private vendor paths or bypass Android namespaces.

64-bit pointer width alone does not establish compatibility. Audit each necessary wrapper signature:
x86-64 SysV guest calls vs AAPCS64 native calls; Win64 PE dispatch is first converted by Wine. Native
function pointers must remain bridges when returned to guest. Vulkan dispatchable handles use Wine
client objects/host mappings; non-dispatchable handles are opaque uint64 values. Avoid treating HWND,
Win32 HANDLE, fd, ANativeWindow and AHardwareBuffer as interchangeable. Extension structures/pNext,
callback lifetimes, external semaphore/memory handles and loader dispatch tables need independent
qualification. The driver advertises 183 extensions; this does not mean Box64/Wine wrap all of them.

## Proposed Windows Vulkan presentation (future, not implemented)

```text
Windows x64 Vulkan program
 -> Wine vulkan-1 / winevulkan PE dispatch
 -> Wine x64/Bionic Unix Vulkan + win32u + winefrost pVulkanInit
 -> existing Box64 Vulkan ABI wrappers
 -> ARM64 Android libvulkan.so / system ICD
 -> runtime-owned VkAndroidSurfaceKHR / VkSwapchainKHR
 -> Surface displayed by app Activity
```

Surface ownership is the key IPC constraint. The current GUI Surface belongs to the main Android
process; Wine/Box64 execute in the separate :runtime process under the same UID. Their address spaces
are distinct. A main-process ANativeWindow pointer must never cross Binder or the pixel socket.
Extend existing runtime IPC to transfer a **Surface Parcelable**, then obtain and retain its ANativeWindow
in :runtime JNI. Associate it with an explicit window token/HWND lifecycle in a runtime registry.
Stop/Binder death/SurfaceDestroyed must retire work and release that registry entry. A Surface is one
BufferQueue producer path: simultaneous GDI and Vulkan producers on the same Surface are disallowed;
keep GDI fallback and an explicitly selected Vulkan presentation target.

A narrow ARM64 WSI adapter can hold these native objects and be exposed to Wine through the existing
Box64 wrapped-library mechanism. Its scope is Surface lifetime/token lookup and Android creation,
not implementing the Vulkan API. The exact Wine driver callbacks above map Windows
VK_KHR_win32_surface into Android VK_KHR_android_surface while retaining Wine's handle conversion.
Extension reporting must hide unsupported Win32 external memory/fence/semaphore capabilities; Android
AHB/fd support does not supply NT handles. Headless Vulkan should be qualified before this adapter.

Before changing Wine, prepare an isolated build/cache/package:
remove `--without-vulkan`, use the existing x64/Bionic compiler and NDK Vulkan headers/stub libraries
for configure probes, and verify generated SONAME_LIBVULKAN resolves through the tested Box64 native
loader name. Never link ARM64 driver objects as x64 Unix libraries. Check Wine generated Vulkan
headers against the pinned wrapper table and selected driver functions. Keep current qualified ZIP,
prefixes and GDI adapter; package an experimental overlay separately. No full Wine rebuild was run now.

## Ordered next gates and DXVK blockers

1. App-UID x64/Bionic headless Box64 Vulkan loader/dispatch/callback proof.
2. Isolated Vulkan-enabled Wine build: Windows x64 enumerate/create/destroy device, no Surface.
3. Surface Parcelable transfer + Wine driver version48 callbacks; Windows Vulkan shader frame.
4. Rotation/Stop/runtime death/synchronization/extension filtering regressions in Windows path.
5. Only then select and validate a DXVK version, its required feature bits/extensions and test D3D.

Native Vulkan 1.4 availability is insufficient for DXVK: Wine build configuration, Box64 SONAME and
wrapper coverage, Windows WSI, cross-process Surface ownership, Windows external handles, shader
features/driver behavior and synchronization remain unqualified. No DXVK/VKD3D binary, dependency pin
or driver switch was added. Turnip support for this GPU was not assumed or tested.

## Sources consulted

- [Khronos WSI guide](https://docs.vulkan.org/guide/latest/wsi.html)
- [Khronos swapchain semaphore reuse / present retirement](https://docs.vulkan.org/guide/latest/swapchain_semaphore_reuse.html)
- [Android NDK Vulkan extensions](https://developer.android.com/ndk/guides/graphics/extensions)
- [Android Vulkan pre-rotation](https://developer.android.com/games/optimize/vulkan-prerotation)
- [Android Vulkan design notes](https://developer.android.com/ndk/guides/graphics/design-notes)
- [Android Vulkan loader/ICD architecture](https://source.android.com/docs/core/graphics/implement-vulkan)
- [Pinned Wine Vulkan driver interface](https://github.com/wine-mirror/wine/blob/455e3509b98a6919fd4ad1def4803e08c41c03b2/include/wine/vulkan_driver.h)
- [Pinned Wine win32u Vulkan initialization](https://github.com/wine-mirror/wine/blob/455e3509b98a6919fd4ad1def4803e08c41c03b2/dlls/win32u/vulkan.c)
- [Pinned Box64 Vulkan wrapping](https://github.com/ptitSeb/box64/blob/2f130fab1d6e1a4ee8a71dc60cfdfcc839ad192a/src/wrapped/wrappedvulkan.c)

Upstream sources were checked against locally unpacked pinned sources and the actual configure output.
Local source/config audit is a verified mechanism description, not proof of Windows Vulkan execution.
