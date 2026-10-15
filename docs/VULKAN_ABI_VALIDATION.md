# FrostBox 0.5.0-dev.2 — Vulkan ABI validation

Device: connected POCO F8 Ultra `YOUR_DEVICE_SERIAL`, model25102PCBEG, Android16/API36, ARM64,
application UID. Device results, not host Vulkan emulation. Raw evidence is in sibling output directory
`box64-vulkan-validation`; final APK hashes and regression gates will be listed below.

## First proven x64 GPU execution

`first-compute/computeDynarec/validation.json`: runtimePID926, childPID958,
exit42/signal0/execErrno0, elapsed107ms; CPU user18.168ms/system50.088ms. These include startup
and are not GPU throughput timings. Adreno (TM)840, vendor20803, type1, API1.4.295.

The GLSL450 shader executes `value[i] = 2*value[i]+7` on100 input uints1..100.
Two workgroups of64 invocations; bounds guard handles the extra28. The host initializes inputs,
submits the actual SPIR-V compute pipeline, waits on a Vulkan fence, reads every element and
checks9..207 plus checksum10800. Success exit42 requires complete output validation and balanced
tracked host allocations. Host code never computes or writes the expected output into the GPU buffer.

VkInstance/VkDevice, queue0, storage buffer, host-visible device memory, descriptor set,
shader module, pipeline, command pool/buffer, vkCmdDispatch, shader->host barrier,
vkQueueSubmit and vkWaitForFences all completed. Allocation288/free288; explicit debug-utils
callback1. No native renderer code is used by this ELF.

| Test / initial evidence | Actual result |
|---|---|
| Original default Box64 / `original-loader` | Expected missing libvulkan.so.1, exit11; control PASS |
| Patched dynarec / `first-compute` | Compute10800, exit42; PASS |
| Patched interpreter / `abi-controls` | Compute10800, exit42; PASS |
| 10 sequential dynarec processes / `abi-controls` | 10/10 compute10800/exit42, child reaped |
| Skip vkCmdDispatch / `abi-controls` | Submitted empty compute work; first output remains1 instead of9; exit43; PASS |

The negative control proves the successful readback depends on dispatch. It is not a performance
benchmark, Vulkan validation-layer certification, long-duration soak or universal ABI compatibility.
Non-coherent flush/invalidate entry points are resolved, but this driver's selected memory is coherent;
non-coherent execution is not claimed.

## Dispatch scope

Actual global, instance and device functions are logged individually as GLOBAL/INSTANCE/DEVICE
in stdout; the full list derives from `tests/vulkan/probe.c` FUNCTIONS. VkDevice-level calls use
vkGetDeviceProcAddr, instance calls use vkGetInstanceProcAddr. Tested extension calls:
vkCreateDebugUtilsMessengerEXT, vkSubmitDebugUtilsMessageEXT, vkDestroyDebugUtilsMessengerEXT.
The custom allocation/free function pointers and native-to-x64 debug callback returned
correctly. Reallocation is registered but its invocation count is not separately measured.
`vkGetPhysicalDeviceProperties` layout produces coherent GPU properties.

Not tested: every other extension, promoted aliases, unknown extension function lookup, arbitrary
nested pNext conversion, external semaphores/memory, sparse resources, ray tracing, Android Surface,
Win32 WSI, graphics pipelines, shared allocation callbacks across many concurrent instances.
Upstream wrapper static callback slots/unknown-extension signature coverage remain risks.

## Failures retained

Initial Windows test binding was killed by HyperOS: ApplicationExitInfo reason13,
`normal_mem_pressure`, not an ELF/Vulkan crash. Existing Wine launcher BIND_IMPORTANT flag was
applied to diagnostic binding. Initial overlay hardlink attempt got Permission denied; replaced by
bounded-memory copy+atomic file rename, no security bypass. No qualified files were linked or changed
(the first AUTHORS hardlink failed). Early PE build failures (__chkstk/_fltused) were resolved by
moving the large extension array to static storage and defining freestanding CRT float marker.

Final installed-build results and preservation hashes: see release section added after qualification.

## Final installed build and regression qualification

App0.5.0-dev.2/versionCode10 SHA256 `00b629a1b907283e6fe5cb286fb4f1ed9db1685d97cdc615e31e966fd78e2757`.
AndroidTest SHA256 `3e6f7f416d9efe0f5fd3501b2013843e3c9f4eed6cce41627437e09c476be345`.
All gates below verify these exact installed APKs.

| Gate | Actual results |
|---|---|
| release-abi |7/7methods PASS: original SONAME control, interpreter/dynarec compute,10/10GPU repeats, negative control, default Wine control, Windows headless compute|
| release-native-gpu |2/2PASS: probe/real triangle pixels, rotation/recreation/background/StopStart|
| release-display |6/7initial PASS: Unicode, IME/Back, GUI close/input,10/10GUI launches, runtime-death recovery, Compatibility Lab Unicode|
| release-surface-recheck |recreateSurfaceAndStop PASS on unchanged APK after initial PixelCopy ERROR_SOURCE_NO_DATA|
| release-library |3/3PASS: console5050/42/repeat/recreate, PlayStop/runtime recovery, imported DLL and missing DLL real failure|
| release-basic |4/4PASS: ARM64, default Box64 interpreter/dynarec, cancellation|
| Linux JVM/Robolectric |18/18PASS (12core+6app); zero failures/errors|
| CTest |2/2PASS runtime_contract/process_lifecycle|
| Incremental APK + AndroidTest + C++20/Werror + package verification |PASS|
| lintDebug |PASS,0errors/17warnings (pinned dependency notices)|

**23 distinct Android methods passed after one targeted recheck.** The original Display batch
is retained as failed6/7, not silently replaced. ERROR_SOURCE_NO_DATA arose in PixelCopy after
Activity.recreate; the existing test waits a session-wide frame count, which may already exceed2
before the new Surface has a buffer. This is consistent with a test timing race; its production
cause is not proven or patched. A future test should await a new Surface generation/frame.
No further full suite was run per change. Full historical prefix20repeat suite/long GPU soak
were not re-run and are not counted as dev.2 validation.

Wine preparation defect also retained: the first macro replacement remained an autoconf comment,
so that successfully compiled module was not Vulkan-enabled. Fixed by replacing the whole line
and auditing the live macro/ELF string; final Windows GPU result is separate evidence.

Final preservation versus dev.1 APK: Wine ZIP SHA
`583f3072e134528598e3374c95260c2cc69f10afbc225993e10eaf0023139c52`,
default Box64 SHA `167dbda2f6020c73f13c874fa1b2826725e638bc9bbf7005a14b872404669c56`,
all18display/font assets byte-identical. Experimental Box64 SHA
`b857bfbe1c134b0c239d6be2e3663fd2b3b158fba9b751fe23324548c41693d2`,
experimental win32u SHA `dce864b594ee3da5995c18a7c750c1e74d47f49b090ea2ba5ead257bb971035c`.
ELF SHA `abbe57dbd27acf62584059b4cfcf974f51e0ca4a92a54dcadbbcc8f78b0476d3`,
Windows PE SHA `b5eba2011dc28173231ebc3eeee7a3ad0dd5816be2ba2a8dd02c32a257081ec1`.

Final process snapshot contains no remaining app-application UID Wine/Box64 fixture processes.
Unrelated Wine processes under other UIDs were neither counted as FrostBox leaks nor terminated.
No pm clear/uninstall/prefix deletion/system security change; old APKs/evidence retained.

Artifacts in sibling outputs directory:
- FrostBox-0.5.0-dev.2-arm64-debug.apk
- FrostBox-0.5.0-dev.2-androidTest.apk
- FrostBox-0.5.0-dev.2-source.zip (own source + full pinned upstream archives/licenses/recipes)
- FrostBox-0.5.0-dev.2-evidence.zip (all new successes, failed attempts, raw logs/build checks)
- FrostBox-0.5.0-dev.2-vulkan-compute-x64.elf / .exe
- FrostBox-0.5.0-dev.2-SHA256SUMS.txt

Successful P0/P1 and additional P2 are qualified; P3 has a concrete documented design.
P4 WSI is deliberately not implemented; no general Windows Vulkan compatibility or DXVK claim.

The initial runner's global execution label describes the ELF path only. Windows-specific records
identify mode15, WINDOWS_X64, Wine overlay, prefix and server shutdown; those prove the additional
Wine path. The runner label is corrected for future mixed ELF/PE gates. Raw historical logs remain
unaltered. Vulkan PE diagnostics are not a user-facing Play backend in this version.
