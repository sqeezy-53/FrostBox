# Current: 0.5.0-dev.3-incomplete

Current continuation: [Linux x64 GPU image sharing and host Present](WSI_IPC_IMAGE_CHECKPOINT.md) are now separately demonstrated on POCO. Windows swapchain/Present remains unimplemented and the negative PE still returns −7. Embedding-first is superseded by the [tested IPC direction](WINDOWS_VULKAN_WSI.md).


Windows headless compute remains working. Windows Vulkan presentation remains **absent**, now reproduced by a separate real Windows PE: client extensions WIN32=0/ANDROID=0/HEADLESS=0, vkCreateInstance requesting Win32 WSI -7, diagnostic exit44. Native Surface ownership in :runtime is implemented but not connected to the Box64 exec child or Wine WSI. No Windows triangle, swapchain, DXVK/D3D11 or game execution is claimed. [Current device gate](WINDOWS_VULKAN_PRESENT_VALIDATION.md), [process-boundary audit](WINDOWS_VULKAN_WSI.md).

Historical qualified dev.2 result:

# FrostBox 0.5.0-dev.2 — Windows Vulkan status on POCO

**Real Windows x64 headless GPU compute: PASS**, using the isolated experimental runtime only.
Normal Library Play continues using the qualified Wine/GDI runtime, built --without-vulkan.
No general user-Vulkan launcher switch or Windows Vulkan window presentation is offered.

## Exact qualified PE result

Evidence `box64-vulkan-validation/release-abi/windowsCompute/validation.json`:
- Android16/API36 POCO, app/runtime application UID, runtimePID13451, native childPID13489,
  instrumentationPID13418. Windows logical process ID32 is distinct from Android PID.
- WINEBOOTSTRAPMODE absent, existing complete managed diagnostic prefix reused under lock.
- Actual `LoadLibraryA("vulkan-1.dll")`, global GIPA dispatch, VkInstance and VkDevice PASS.
- `Adreno (TM) 840`, vendor20803, Vulkan1.4.295; queue0 with compute capability.
- VkBuffer/device memory/shader/pipeline/descriptors/command buffer, vkCmdDispatch2 workgroups,
  shader->host barrier, fence submission/wait, readback of **all100** values9..207 PASS.
- `FROSTBOX_VULKAN_RESULT=10800`, exit42, signal0, execErrno0, timedOut=false.
- DEBUG_UTILS_AVAILABLE=1, native-to-Windows debug messenger callback1.
- Wine11.19 passes NULL allocators in its Vulkan Unix thunks; guest allocation/free counters0/0.
  This is explicitly printed as NOT_EXERCISED, not mistaken for allocation-callback validation.
- Reverse Vulkan resource cleanup completed; Wine server kill0/wait0, child reaped.
- Elapsed6019ms, user198.141ms/system152.667ms, peak sampled RSS159812KiB. Startup/first use
  contributes; these are not compute shader GPU-time/throughput or game FPS metrics.

One successful Windows headless compute was qualified in this gate. Windows20repeat/leak/soak,
arbitrary games, full extension compatibility and graphical presentation are not confirmed.
stdout is complete (`droppedOut=0`). Vulkan trace stderr is intentionally bounded32KiB;
65114bytes were dropped (`droppedErr` explicitly recorded). Raw stdout/assertions prove each
compute stage; retained stderr is not claimed to contain every Wine trace line.

## Controls and corrected build defects

Qualified default no-Vulkan Wine control returns exit11 with `Wine was built without Vulkan support`.
Initial experiment also emitted this message because the build recipe replaced #undef inside
an autoconf comment. That candidate **was not Vulkan-enabled**, despite successful compilation.
The recipe now replaces the entire line, asserts a live #define, and audits the resulting ELF:
libvulkan.so.1 present and the without-Vulkan message absent. This corrected overlay actually passes.
No full Wine rebuild or qualified ZIP mutation was needed.

Other retained preparation failures: HyperOS normal_mem_pressure runtime kill; hardlink EACCES;
copy lost executable bits. BIND_IMPORTANT, streaming copy and preserved guest executable bits fixed
these without root/security modifications. Failure directories and build logs remain in evidence.

## Independent statuses

| Path | 0.5.0-dev.2 status |
|---|---|
| Native ARM64 Android triangle/swapchain | Qualified independently; regression results in ABI report |
| x64/Bionic ELF via Box64 to ARM64 Vulkan | Qualified interpreter/dynarec +10sequential repeats +negative dispatch control |
| Windows x64 PE via Wine to Box64 to ARM64 Vulkan | Isolated headless compute qualified;1run |
| Windows Vulkan presentation/swapchain | Not implemented or tested |
| DXVK / VKD3D-Proton / DirectX | Not integrated or tested |

Wine filters unsupported extensions; retained warnings include VK_ANDROID_external_format_resolve
and VK_EXT_device_memory_report. Enumerated extension names are not execution qualification.
Only the compute/core/debug-utils calls exercised here have evidence.

## Next gate

Qualify more headless PE programs, error/callback/pNext/function-pointer variants and repeated
sessions before promoting this isolated runtime to a selectable launcher backend. Implement the
concrete Surface Parcelable/generation/ANativeWindow ownership and Wine driver interface48 WSI
contract in WINE_VULKAN_FEASIBILITY.md, then validate actual Windows-produced pixels and lifecycle.
The independent native triangle is not a substitute for those Windows frames. GDI remains fallback.

## Persistent transport continuation

The older per-frame-spawn checkpoint remains historical evidence. A subsequent [persistent transport checkpoint](PERSISTENT_GPU_IPC_VALIDATION.md) qualifies a two-image pool, 100-frame x64 sessions, actual semaphore SYNC_FD and Stop/resize/runtime recovery. Windows WSI, Win32 extension and swapchain semantics remain unimplemented; a new Windows triangle PE is compiled only. No change to qualified Wine/GDI runtime or completed Windows/DXVK/game milestone is implied.
