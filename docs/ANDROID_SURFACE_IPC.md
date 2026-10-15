> Current PROJECT TITAN continuation: [2026-10-13 checkpoint](PROJECT_TITAN_CHECKPOINT.md). Windows Vulkan 120 frames, DXVK/D3D11 and Library GPU Play are now device-confirmed. The older status below describes the preserved preceding checkpoint; it is not the current implementation status.

# Experimental Android Surface IPC — actual implementation

The ownership-only results below are the preserved original dev.3 checkpoint. A later [separate-process GPU image checkpoint](WSI_IPC_IMAGE_CHECKPOINT.md) verifies Linux x64 image IPC and host Present; Windows WSI remains missing. Exact APK identities/results differ and are retained separately.


Implemented: non-exported RuntimeSurfaceService in the existing :runtime process, separate from RuntimeProbeService/Process Manager. The service owns references only; it does not render, call Wine, intercept GDI or expose a Library GPU backend. Tests explicitly send lifecycle messages; automatic attachment of the production WindowsSurface view is not implemented.

Messenger operations: ATTACH=1, REVOKE=2, SNAPSHOT=3, LOADER_DIAGNOSTIC=4, CLOSE=5, RESULT=101. Messages carry a Parcelable Surface (ATTACH only), canonical session UUID, owner IBinder, strictly increasing sequence, nondecreasing positive generation, bounded positive dimensions and request correlation ID. Messenger.sendingUid must equal the app UID. Responses contain status/dimensions/PID/UID/counters, never native addresses. Synchronous native queries and ownership transitions execute on one HandlerThread, away from UI. No Surface global JNI reference is retained.

On ATTACH the native registry calls ANativeWindow_fromSurface in the runtime's own JNIEnv, acquires one independent reference and returns a process-local opaque ID. The decoded Java Surface is released in finally; the caller's original Surface remains valid. Replacement acquires before retiring the previous reference. REVOKE/CLOSE releases the window exactly once; unknown IDs are idempotently ignored on release. Session UUID+owner Binder prevent owner replacement; sequence/generation reject stale events. Closed IDs remain tombstoned for the service lifetime: at most64 sessions, then a clear error requires service recreation rather than evicting tombstones and accepting stale requests.

onUnbind/onDestroy and owner Binder death retire the session on the same worker. Rebinding after runtime process death needs a fresh session and Surface; no pointer/ID is reused. Abrupt SIGKILL cannot run application finally blocks; Android/kernel reclaim that process's references. The death/restart test verifies a new PID and freshly acquired refs. Owner-token death handling is implemented but a distinct client-process death test was not performed.

Device tests verify real SurfaceView 320x240 and recreated400x300 dimensions in ANativeWindow; reject duplicate sequence, changed owner, stale generation and attach after CLOSE; balance3 acquisitions/3 releases; force-kill the app-owned :runtime PID, observe service disconnection and restore in a fresh process. No actual GPU queue, fence, VkSurfaceKHR or swapchain is created by these tests. Orientation/pause/present synchronization/device-loss on the **new Windows path** remains untested.

Important: the Wine Box64 exec child still lacks access to this runtime-local window. ANativeWindow_readFromParcel (public API34+) reads a real Binder Parcel, not arbitrary marshalled bytes. The tested Surface Parcel cannot be flattened because it contains Binder objects. APIs29–33 can use the implemented Parcelable Surface/JNI path; capability of native Parcel APIs34+ is documented, not claimed tested.

[Android Surface Parcelable API](https://developer.android.com/reference/android/view/Surface), [ANativeWindow reference ownership and Parcel APIs](https://developer.android.com/ndk/reference/group/a-native-window), [Parcel API](https://developer.android.com/reference/android/os/Parcel).

Reproduce (from repo):

```bash
python3 scripts/build-wsi-capability-probe.py --sdk ../../work/toolchain/sdk --output build/wsi-capability
JAVA_HOME="$PWD/../../work/toolchain/jdk" PATH="$PWD/../../work/toolchain/jdk/bin:$PATH" \
 GRADLE_USER_HOME="$PWD/../../work/gradle-home" ./gradlew \
 -PwithBox64=true -PwithWineProbe=true -PwithWindowsDisplay=true \
 -PwithBox64Vulkan=true -PwithWineVulkan=true -PwithSurfaceIpcProbe=true \
 :android-app:assembleDebug :android-app:assembleDebugAndroidTest
python3 scripts/validate-surface-ipc.py YOUR_DEVICE_SERIAL --output build/surface-ipc-NEW
```

Runtime packages must already be built as in dev.2. The WSI diagnostic reuses `wine-vulkan-profile-id.txt` and refuses a missing/unmanaged prefix; run the existing windowsCompute test first only if no diagnostic prefix exists. The validator updates APKs using install -r -t, checks installed/local SHA, records bounded app logs, logcat, exit-info and UID/PID snapshots; never clears data. Its `passed` means the explicit positive IPC and expected negative capability assertions passed, not Windows presentation. Output dirs must be new.
