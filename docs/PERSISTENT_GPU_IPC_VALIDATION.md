# Persistent GPU image IPC — 0.5.0-dev.3-persistent-ipc-incomplete

This checkpoint completes the **diagnostic persistent transport gate**, not the master mission or Windows presentation. Architecture B remains selected: exec Wine/Box64 plus local GPU commands and image IPC to Android-owned WSI. No embedding or full Vulkan command RPC was introduced.

| Milestone | Status | Evidence |
| --- | --- | --- |
| Persistent GPU IPC | POCO diagnostic qualified | 100-frame single PID, four further 100-frame sessions, PixelCopy, FD and recovery tests |
| Windows WSI | Unimplemented | Existing Windows PE still reports Win32 extension absent, VkInstance request -7, expected exit44 |
| DXVK / D3D11 | Not started | Windows Present prerequisite not met |
| Third-party Windows 3D game | Not started | No gameplay claim |

## Execution and reuse

The original x64/Bionic ELF is still launched through existing `Process::spawn` / `Box64Backend`; no new Wine/process manager was created. It loads Box64's wrapped Vulkan loader and creates one real Adreno instance/device and **two** dedicated optimal-tiling RGBA8 external-memory images before the loop. A single command buffer, command pool, fence, exportable semaphore and timestamp query pool are reused. Each of 100 frames is GPU-cleared in the guest. Host imports each image's memory only on its first use, GPU-copies it into a real Android swapchain and presents it. The host never generates the red/green content.

A private abstract Unix session endpoint includes UID, runtime PID and a monotonic nonce. It disappears when its descriptor closes, including runtime death; no stale filesystem socket must be deleted. SO_PEERCRED checks exact child PID and app UID. Each fixed 72-byte description validates magic, dimensions/format, memory allocation size/type, device and driver UUID, monotonic frame sequence, synchronization mode, reserved field and exact descriptor count. Memory metadata cannot change on reuse. No raw Vulkan handles or CPU pixel data cross IPC. A new connection is a new session; frame numbering is scoped to that authenticated connection.

Queue depth is one in-flight frame, with two preallocated images used alternately. Format is explicitly **RGBA8 / optimal tiling only**; unsupported formats are rejected, not silently converted. Resizing revokes the Surface generation and recreates host swapchain and both pools. In-place asynchronous resize, dynamic orientation while guest frames are pending and multi-window presentation are not yet qualified.

## Synchronization and resource ownership

On POCO external-image feature bits are 7 (dedicated/import/export). Semaphore SYNC_FD features are 3 (import/export), compatible type16. The guest actually exports a SYNC_FD after each GPU submit; the host temporarily imports it into a Vulkan semaphore and waits in its copy submit at ALL_COMMANDS. The native Android loader does not export `vkImportSemaphoreFdKHR` as a direct link symbol; the corrected host resolves it with `vkGetDeviceProcAddr`. This linker failure is retained in build-sync.log.

Each guest image transitions to TRANSFER_DST, is GPU-written, then released from guest family to EXTERNAL in GENERAL layout. Host acquires EXTERNAL→host family, copies GENERAL→TRANSFER_SRC into a TRANSFER_DST swapchain image, releases the shared image back to EXTERNAL/GENERAL and transitions the swapchain image to PRESENT. On reuse the guest acquires EXTERNAL→guest before writing. Physical and driver UUID equality is mandatory.

The wire distinguishes actual FD and Vulkan's already-signalled SYNC_FD=-1 sentinel. Actual descriptors are CLOEXEC and ownership transfers only on successful Vulkan imports; error/unused FDs are closed. The final 100-frame gate imported **100 real sync FDs**, not just -1 sentinels.

The initial guest fence wait remains bounded to five seconds. Host waits copy-submission and EXT_swapchain_maintenance1 present fences before ACK42. ACK makes the buffer reusable only after host GPU work and present retirement finish. This serialized correctness-first transport uses actual external GPU synchronization, but still pays CPU fence/ACK waits; it is not a pipelined scheduler. There is no per-frame DeviceWaitIdle. The unsupported-SYNC_FD fallback uses bounded fences/ACK, is not the final POCO path and has not been separately qualified on unsupported hardware.

Each frame is one worker-thread Binder request, not a 100-frame native call holding Surface-control state. UI is not blocked; control operations are serviced between frames. A single native frame can still occupy the control worker during its bounded waits. A production autonomous Wine WSI server needs a separate graphics worker plus generation-safe control/cancellation; the diagnostic does not pretend to supply that server yet.

Stop/revoke destroys the owned Process (kill/reap), closes the session socket and retires resources. The existing Process implementation already supplies PDEATHSIG. Runtime death tests observe the guest gone and rebind with a **new SurfaceView/BufferQueue**. Reusing the old Surface after killing its Vulkan producer actually failed with `VK_ERROR_NATIVE_WINDOW_IN_USE_KHR (-1000000001)`; Surface.isValid alone is insufficient. The failed run is retained under final-device. The subsequent qualified test recreates the Surface rather than retrying the invalid connection.

If GPU retirement times out, the native context is poisoned and retained until runtime-process recovery, avoiding destruction of in-use resources. GPU stall/device-loss fault injection and every exceptional driver path are not qualified.

## Actual POCO results

Device serial YOUR_DEVICE_SERIAL, model25102PCBEG, Android16/API36, application UID, Adreno840, system Qualcomm Vulkan. Evidence directory is `outputs/wsi-persistent-validation` beside this repository. The authoritative run is **qualified**, with its APK/test identities recorded in validation.json.

- `persistentBox64HundredFrames`: host/guest different PIDs; **one guest PID12884**, 100 Present calls with retirement, two image imports, 100 real semaphore FDs, exit42, no dropped stdout/stderr. PixelCopy at frames0/1/48/49/98/99 confirms alternating colors. One vkCreateInstance, one vkCreateDevice, two vkAllocateMemory calls in guest log.
- `persistentRepeatedSessionsAndFdBounds`: four new guest PIDs, each100 frames and exit42; two imports per session. Open host FD count after each Stop: **131,131,131,131**. This proves no observed FD growth in this test, not a universal leak-free guarantee.
- `persistentStopResizeAndGuestDeath`: Stop kills/reaps guest, 400×300 replacement Surface produces frames; guest SIGKILL becomes an explicit failure, revoke/rebind starts a fresh successful session.
- `persistentRuntimeDeathRecovery`: kill app-owned :runtime, observe service disconnect and guest gone, recreate SurfaceView, then successful new guest and release.
- Existing two separate-image diagnostic still passes. Windows WSI negative capability PE still reports -7/44 with wineserver cleanup.

The first fence/ACK persistent attempt passed. One validation accidentally started against an old installed APK while a rebuild completed: it failed the mandatory device/local SHA comparison and is not counted as qualification. Its evidence is under sync-device. The later measured run failed old-Surface reuse as described above. No failure was relabelled a pass.

## Measured costs and limits

GPU timestamps bracket the diagnostic guest GPU clear/barriers and host GPU copy/barriers. In the qualified 100-frame test, mean guest GPU interval is **17.5µs**, mean host copy interval **43.5µs**, at320×240. These include the corresponding barriers; they are not full-frame GPU timings or a Windows triangle benchmark. Two exported allocations total633192 bytes. Guest elapsed process time888ms includes startup, Binder/test scheduling, waits and PixelCopy. It cannot be advertised as game FPS.

Cumulative host CPU durations are recorded separately for acquire, copy-fence wait, QueuePresent call and present-fence retirement. The primary gate spent434ms in copy-fence waits,14ms in acquire and12ms in QueuePresent calls over100 frames. Child CPU time and peak RSS are recorded in checkpoint-summary.json. RSS varies across processes; memory/thermal stability is not proven by fixed image allocation or stable FD counts. IPC-only latency, frame distribution, battery/thermal cost and energy were not isolated/measured; no FPS, 1% low or power claims are made.

## Regression coverage on the exact APK

16 distinct Android methods passed:

- SurfaceIpcDeviceTest: persistentBox64HundredFrames, persistentRepeatedSessionsAndFdBounds, persistentStopResizeAndGuestDeath, persistentRuntimeDeathRecovery, separateBox64ImageGpuPresent.
- WsiCapabilityDeviceTest: currentWineDoesNotExposeWin32Surface (expected negative result).
- Box64 Vulkan: computeDynarec, computeInterpreter, windowsCompute (10800/42).
- Windows display: guiPixelsMouseKeyboardAndClose, committedImeAndBack, compatibilityLabControlsAndUnicode.
- Native Android Vulkan: realGpuProbe, realTriangleAndLifecycle.
- Library: addPlayRepeatLogsAndRecreate (5050/42), stopRecreateAndRuntimeDeath.

18 JVM/Robolectric tests (12core+6app), 2CTest passed. The full historical Android suite, separate10-GUI-loop test, every controller/import test and lint were not rerun. Qualified Wine ZIP, GDI/FreeType assets, both Box64 libraries, headless Wine overlay and all other native libraries match the prior checkpoint byte-for-byte (43 APK entries checked). Previous artifacts/prefixes/imported files are preserved.

## Windows WSI next boundary, not a solved contract

Wine11.19 interface48 still has no winefrost pVulkanInit/pCreateClientSurface integration. The tested loader does not advertise Win32 or headless surface. Wine maps instance extension bits before clearing the host Win32 bit, creates a Wine-managed client surface and translates client/host handles; adding an extension string cannot supply a surface or swapchain.

The next missing implementation is a separate guest-side WSI provider plus experimental Wine driver/loader overlay. It must own real exportable guest VkImages; guest-local opaque surface/swapchain objects must never be forwarded to Android's driver. Acquire must signal actual guest semaphore/fence, preserve timeout/nonblocking/error semantics and reserve a reusable image. Present must wait the application's real rendering semaphores, release the image, transmit to the host and return actual errors. Surface capability/formats/modes, destruction, resize and all advertised GIPA/GDPA dispatch must agree. Offscreen guest images also require consistent PRESENT_SRC layout translation in barriers and render-pass/dynamic-rendering paths. Ordinary device/draw commands continue locally through Box64. This is an unimplemented engineering stage, **not proof that architecture B or Android is impossible**.

Prepared `tests/vulkan/windows-triangle.c` and `scripts/build-windows-vulkan-triangle.py` build a real PE32+ AMD64 program with HWND, Win32 extension gate, surface/queue/capabilities/formats, RGBA8 swapchain, real SPIR-V graphics pipeline, vkCmdDraw, animated120 Draw/Present submissions, resize and WM_CLOSE. It returns44 when Win32 WSI is absent and42 only after120 submissions and close. **This PE is compiled only; it was not run on Android or used as presentation evidence.** No Vulkan extension advertisement or experimental Wine runtime was changed merely to make it pass. The existing capability PE is the actual phone reproducer of the missing path.

Milestone A remains incomplete. DXVK/D3D11 and third-party game work remain behind genuine Windows Draw/Present verification. Native Android triangle, Linux image presentation and Windows headless compute are three separate proven paths.

## Reproduce

```sh
python3 scripts/build-wsi-image-probe.py --sdk ../../work/toolchain/sdk --output build/wsi-image-probe
python3 scripts/build-windows-vulkan-triangle.py --sdk ../../work/toolchain/sdk --output build/windows-vulkan-triangle
JAVA_HOME="$PWD/../../work/toolchain/jdk" PATH="$PWD/../../work/toolchain/jdk/bin:$PATH" \
GRADLE_USER_HOME="$PWD/../../work/gradle-home" ./gradlew \
  -PwithBox64=true -PwithWineProbe=true -PwithWindowsDisplay=true \
  -PwithBox64Vulkan=true -PwithWineVulkan=true -PwithSurfaceIpcProbe=true \
  :android-app:assembleDebug :android-app:assembleDebugAndroidTest
python3 scripts/validate-surface-ipc.py YOUR_DEVICE_SERIAL --output /absolute/new/evidence \
  --methods SurfaceIpcDeviceTest#persistentBox64HundredFrames \
  SurfaceIpcDeviceTest#persistentRepeatedSessionsAndFdBounds \
  SurfaceIpcDeviceTest#persistentStopResizeAndGuestDeath \
  SurfaceIpcDeviceTest#persistentRuntimeDeathRecovery
```

Standalone triangle PE needs the future experimental WSI overlay and host session to test presentation; the normal Library remains qualified GDI. No automatic user EXE launch, root, security bypass, app-data clear, Wine-prefix deletion or external publication occurred.

References used for synchronization contracts: [Khronos external memory/synchronization](https://docs.vulkan.org/guide/latest/extensions/external.html), [queue ownership and synchronization](https://docs.vulkan.org/spec/latest/chapters/synchronization.html). The measured behavior above comes from phone logs and timestamp queries, not from those general references.
