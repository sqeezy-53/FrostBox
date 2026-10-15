# Separate-process GPU image checkpoint (after 0.5.0-dev.3-incomplete)

Status: experimental Linux x86-64 image transport, **not** Windows Vulkan presentation and **not** a completed dev.4/0.6.0 release.

The implementation keeps the exec-based Box64 process and the qualified Wine/GDI packages. `RuntimeSurfaceService` owns the Binder-delivered Android Surface. An original x86-64/Bionic ELF runs under the unchanged Vulkan Box64 candidate, creates a dedicated optimal-tiling RGBA8 image on Adreno 840, clears it with `vkCmdClearColorImage`, releases queue ownership to `VK_QUEUE_FAMILY_EXTERNAL`, waits its submission fence and exports OPAQUE_FD memory. It sends one SCM_RIGHTS descriptor and a 64-byte fixed-width description over a private Unix socket. There are no image pixels, guest pointers or Vulkan handles in the wire packet.

The ARM64 service verifies producer UID/PID, packet size, FD count, dimensions, format, allocation size/type and physical-device **and driver** UUIDs. It creates the identically described image, imports the dedicated memory, acquires external queue ownership, GPU-copies the image into its actual Android swapchain, releases ownership and presents. Submission and EXT_swapchain_maintenance1 present fences are waited before acknowledging the guest. Independent PixelCopy must see red and green output. The host contains no color-clear command or shader that substitutes for guest output.

The first device attempts found an unsupported fixed opaque alpha mode and an omitted executable bit on the copied guest fixture. Both were fixed. The third attempt passed both colors on POCO, host PID17056 / guest PIDs17084 and17095, application UID, external-image feature bits7, memory316596 bytes, exit42. Original verbose dynarec output was bounded but truncated; the final build disables instruction dumps and requires zero dropped bytes. Exact final results and installed APK identities are in `outputs/wsi-image-validation/qualified-final/validation.json` (outside the source tree).

## Reproduce

From repository root, with the existing local SDK/JDK and all previously prepared packages:

```sh
python3 scripts/build-wsi-image-probe.py --sdk ../../work/toolchain/sdk --output build/wsi-image-probe
JAVA_HOME="$PWD/../../work/toolchain/jdk" PATH="$PWD/../../work/toolchain/jdk/bin:$PATH" \
GRADLE_USER_HOME="$PWD/../../work/gradle-home" ./gradlew \
  -PwithBox64=true -PwithWineProbe=true -PwithWindowsDisplay=true \
  -PwithBox64Vulkan=true -PwithWineVulkan=true -PwithSurfaceIpcProbe=true \
  :android-app:assembleDebug :android-app:assembleDebugAndroidTest
python3 scripts/validate-surface-ipc.py YOUR_DEVICE_SERIAL --output /absolute/new/evidence-directory \
  --methods SurfaceIpcDeviceTest#separateBox64ImageGpuPresent
```

No user executable is accepted by this diagnostic. The SHA-checked bundled ELF is the only launched payload; IPC requires the current owning Binder/session/generation, a debuggable APK and color0/1. No production launcher action or default renderer changed.

## Boundaries still missing

This diagnostic creates one dedicated image and starts one guest process per frame. It proves physical cross-process GPU image interoperability; it does not implement a guest-visible Vulkan swapchain, `vkAcquireNextImageKHR`, guest `vkQueuePresentKHR`, rendering wait semaphores, image-pool reuse, Surface resize while presenting or graphics-runtime cancellation. Fence completion plus socket acknowledgement intentionally serialize the two processes. This costs a GPU copy and host/guest waits; no FPS/latency/energy claims follow.

Windows WSI still needs a guest-side WSI provider and Wine interface48 client-surface integration. Native Surface pointers remain process-local. Vulkan functions operating on guest-side surface/swapchain handles must be intercepted consistently by that provider; ordinary command-buffer/device/queue work remains local and goes through the existing Box64 wrapper. Advertising Win32 extensions before this provider is ready would only conceal the -7 failure. The existing negative PE must continue to report absence/-7 until a genuinely connected implementation replaces it in a separate overlay.

Error recovery is diagnostic, not production qualified: submission and present waits are bounded. If GPU retirement remains incomplete, the prototype intentionally retains the affected native context/window until app-owned runtime-process recovery rather than destroying in-use resources. This driver-stall path has not been fault-injected on POCO. Do not reuse this synchronous fixed-frame harness as the production swapchain scheduler. In particular, normal rendering needs external semaphore/fence-FD synchronization, per-image ownership and a worker independent of Surface control requests, with safe pending-resource retirement. Neither DXVK nor a Windows game has been started.

## Persistent transport continuation

The older per-frame-spawn checkpoint remains historical evidence. A subsequent [persistent transport checkpoint](PERSISTENT_GPU_IPC_VALIDATION.md) qualifies a two-image pool, 100-frame x64 sessions, actual semaphore SYNC_FD and Stop/resize/runtime recovery. Windows WSI, Win32 extension and swapchain semantics remain unimplemented; a new Windows triangle PE is compiled only. No change to qualified Wine/GDI runtime or completed Windows/DXVK/game milestone is implied.
