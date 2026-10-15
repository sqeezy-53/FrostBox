> Current PROJECT TITAN continuation: [2026-10-13 checkpoint](PROJECT_TITAN_CHECKPOINT.md). Windows Vulkan 120 frames, DXVK/D3D11 and Library GPU Play are now device-confirmed. The older status below describes the preserved preceding checkpoint; it is not the current implementation status.

# Windows Vulkan WSI — 0.5.0-dev.3 incomplete checkpoint

Milestone A is **not complete**. This iteration implements and device-tests Binder Surface ownership, and reproduces missing Win32 WSI in the qualified headless overlay. No Windows triangle, Windows swapchain or Windows Present is implemented. Milestones B/C remain gated.

## Actual process boundary

```text
Android UI / instrumentation (application UID)
  ├─ Parcelable Surface over Binder → RuntimeSurfaceService in :runtime
  │    └─ runtime-local ANativeWindow_fromSurface + opaque JNI registry ID
  └─ existing RuntimeProbeService in :runtime → Process::spawn/exec
       └─ distinct ARM64 Box64 child → x64 Wine Unix code → Windows PE
```

The JNI service and the Box64 child do not share an address space. The old dev.2 future-design paragraph missed this extra boundary: sending Surface only to :runtime does not make its pointer valid in the Wine child. GDI is unaffected because it sends pixel bytes and input through authenticated Unix sockets.

## Exact Wine 11.19 contract

Pin: 455e3509b98a6919fd4ad1def4803e08c41c03b2, LGPL-2.1-or-later. `include/wine/vulkan_driver.h` defines interface **48** with exactly four callbacks:

- `p_vulkan_surface_create(struct client_surface*, const struct vulkan_instance*, VkSurfaceKHR*)`.
- `p_get_physical_device_presentation_support(struct vulkan_physical_device*, uint32_t)`.
- `p_map_instance_extensions(struct vulkan_instance_extensions*)`.
- `p_map_device_extensions(struct vulkan_device_extensions*)`.

`user_driver_funcs.pVulkanInit(UINT, void*, const struct vulkan_driver_funcs**)` supplies them. `winefrost.drv` currently supplies neither pVulkanInit nor pCreateClientSurface. The latter must return a Wine-managed `client_surface` with destroy/detach/update/present/set_color_space callbacks; its HWND, refcount and geometry are managed by win32u. In `win32u_vkCreateWin32SurfaceKHR`, Wine obtains that client, calls the driver's surface callback and registers a Wine-client/host surface handle pair. Swapchain operations use Wine's existing handle translation; replacing this with a raw HWND pointer is invalid.

The null-driver fallback uses VK_EXT_headless_surface, which the tested Android loader does not advertise. `vulkan_init_once` filters host extensions using generated ALL_VK_INSTANCE_EXTS, then maps host/client bits. Pinned generated `wine/vulkan.h` lacks VK_KHR_android_surface declarations/bits. `make_vulkan` UNEXPOSED_EXTENSIONS/UNEXPOSED_PLATFORMS includes xlib/wayland/macOS, but not Android. The actual Wine trace warns that VK_KHR_android_surface is unsupported. Adding a name to the PE extension enumeration alone cannot make this path work: the host create-instance list, typed function dispatch and driver surface implementation must agree.

Box64 pin 2f130fab1d6e1a4ee8a71dc60cfdfcc839ad192a already has GOM(vkCreateAndroidSurfaceKHR, iFEpppp) and CREATE(vkCreateAndroidSurfaceKHR). The generic create wrapper converts allocation callbacks; it does not transport an ANativeWindow between processes. Struct layouts/pNext, graphics/presentation and surface lifetime are still unqualified. No new Box64 patch was made here; the dev.2 SONAME fallback is retained.

## Bounded experiments, actual outcomes

1. Binder Surface ownership succeeds on POCO, including resized/recreated Surface, explicit revoke/close and runtime death/rebind.
2. Raw `Parcel.marshall()` fails with `Tried to marshall a Parcel that contains objects (binders or FDs).` Thus flattening Surface into existing byte-socket transport is not valid Binder transfer.
3. `dlopen(libbox64_vulkan.so)` **succeeds**. The initial test incorrectly expected executable rejection; its failure is preserved. Corrected diagnostic finds `dlsym("main")` fails (`undefined symbol: main`). ELF symbol audit shows main/initialize LOCAL HIDDEN. No Box64 entry point was invoked from JNI and embedding is not qualified.
4. A real Windows x64 capability PE under Wine + Box64 reports WIN32=0/ANDROID=0/HEADLESS=0; vkCreateInstance requesting Win32 WSI returns -7; expected diagnostic exit 44, server kill/wait 0. This is evidence of a missing path, not successful presentation.

These findings are not a claim that Android prohibits WSI or that embedding is impossible. A new execution/ownership integration must be built and qualified; the current milestone remains incomplete rather than silently passing a pointer into a spawned process.

## Architecture decision after the image IPC experiment

The previous recommendation to start with embedding is superseded. It was a hypothesis, not a necessary condition. The new [executable image checkpoint](WSI_IPC_IMAGE_CHECKPOINT.md) demonstrates a narrower separate-process GPU bridge on POCO. This remains short of Windows WSI.

| Option | Ownership and execution | Cost / main risk | Decision |
| --- | --- | --- | --- |
| A: supported embedded Box64 | Android-bound service receives Surface; a deliberately built CPU-engine library exports a one-shot entry and executes guest in that process | Avoids frame IPC/copy but must qualify Box64 exit/atexit, signals, ART coexistence, TLS, cancellation and teardown. Still needs Wine WSI callbacks and extension mapping. Current executable/main experiment does not supply that contract. | Keep as alternative, not required or implemented. |
| B1: deliver Binder Surface directly to exec child | Real Binder object transfer plus public client/service bootstrap in the guest process | `ANativeWindow_readFromParcel` requires an actual AParcel with Binder objects. The selected NDK27 has no Binder RPC server/client bootstrap headers; AOSP API36 accessor APIs live in include_platform and do not establish a demonstrated app-side RpcServer transport. Raw parcel bytes, pointer transfer and inherited Binder state are invalid substitutes. Bundling a separate Binder implementation would add ABI/platform integration that is not qualified. | Feasible in principle; not demonstrated, not categorically prohibited. |
| B2: local guest Vulkan plus image IPC and host WSI | Guest owns its device, commands and external-memory images. Android-bound service owns real Surface/swapchain and imports guest memory. | One GPU copy, ownership transfers and IPC/synchronization. Needs WSI-only dispatch provider, per-image lifecycle and external synchronization; does not need full Vulkan API RPC. Physical interoperability now verified by Linux x64 GPU output and PixelCopy. | **Selected experimental direction.** |

Public references: [NDK NativeWindow parcel contract](https://developer.android.com/ndk/reference/group/a-native-window), [AOSP platform Binder RPC API](https://android.googlesource.com/platform/frameworks/native/+/refs/heads/main/libs/binder/ndk/include_platform/android/binder_rpc.h), [Khronos external memory/synchronization guide](https://github.khronos.org/Vulkan-Site/guide/latest/extensions/external.html). Only the selected NDK and current implementation were checked; this is not a claim that every future Android Binder API is unsuitable.

## Concrete Windows WSI boundary

In B2 the Android Vulkan extension belongs to the **host service**. Wine's guest driver must provide interface48 and a client-surface lifecycle connected to a guest-side WSI provider; it must not forward a service-local VkSurfaceKHR/ANativeWindow as a guest handle. The guest provider keeps opaque surface/swapchain state locally and implements the surface queries, image creation/pool, acquire and queue-present semantics. Ordinary Vulkan draw/compute/command recording remains local and uses Box64's existing ARM64 dispatch bridges. The wire protocol carries surface generation, dimensions/capabilities, image-memory and synchronization descriptors, and results, never all Vulkan commands.

Required consistent dispatch includes Create/DestroySurface, all advertised surface capabilities/formats/present-mode queries, Create/DestroySwapchain, GetSwapchainImages, AcquireNextImage/2 and QueuePresent, including GIPA/GDPA retrieval. If the provider uses offscreen images, it also must correctly translate PRESENT_SRC_KHR layout use in command barriers/render-pass paths; passing invented swapchain handles or swapchain-only layouts through the system driver is not a correct implementation. Unsupported pNext and optional WSI functions/extensions must be rejected or hidden, not silently forwarded.

Wine 11.19's existing nulldrv already maps Win32 to headless when the loader actually exposes it, but that fallback is not proof of a connected presentation provider. A production winefrost WSI overlay must implement pVulkanInit, pCreateClientSurface and interface48 callbacks against the chosen provider. A host-only Android surface does not require adding Android structs to the Windows client ABI. Conversely a future direct-Surface B1/A path does require Android host extension generation and same-process vkCreateAndroidSurfaceKHR. Qualified GDI stays separate in all cases.

Next gate: a separately packaged guest-side WSI provider that implements the bounded swapchain contract above, plus an experimental Wine driver/loader overlay. Then run the actual PE extension→instance→surface→capabilities→swapchain→present→PixelCopy chain. The current test harness intentionally does not advertise Win32 WSI and cannot satisfy that chain. No Windows/DXVK completion checkpoint is inferred from image sharing.

Qualified Wine ZIP, GDI/FreeType overlay, both dev.2 Box64 executables, win32u overlay and previous releases are preserved. No base Wine/Box64 rebuild, root, security changes, data clear, prefix deletion or publication occurred.

Source references: [pinned Wine interface](https://github.com/wine-mirror/wine/blob/455e3509b98a6919fd4ad1def4803e08c41c03b2/include/wine/vulkan_driver.h), [pinned win32u](https://github.com/wine-mirror/wine/blob/455e3509b98a6919fd4ad1def4803e08c41c03b2/dlls/win32u/vulkan.c), [pinned Box64 wrapper](https://github.com/ptitSeb/box64/blob/2f130fab1d6e1a4ee8a71dc60cfdfcc839ad192a/src/wrapped/wrappedvulkan.c).

## Persistent transport continuation

The older per-frame-spawn checkpoint remains historical evidence. A subsequent [persistent transport checkpoint](PERSISTENT_GPU_IPC_VALIDATION.md) qualifies a two-image pool, 100-frame x64 sessions, actual semaphore SYNC_FD and Stop/resize/runtime recovery. Windows WSI, Win32 extension and swapchain semantics remain unimplemented; a new Windows triangle PE is compiled only. No change to qualified Wine/GDI runtime or completed Windows/DXVK/game milestone is implied.
