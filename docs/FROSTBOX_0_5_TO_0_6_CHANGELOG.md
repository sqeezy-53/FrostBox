> Current PROJECT TITAN continuation: [2026-10-13 checkpoint](PROJECT_TITAN_CHECKPOINT.md). Windows Vulkan 120 frames, DXVK/D3D11 and Library GPU Play are now device-confirmed. The older status below describes the preserved preceding checkpoint; it is not the current implementation status.

# FrostBox 0.5→0.6 mission checkpoint

| Milestone | Status | Evidence |
|---|---|---|
| 0.5.0-dev.3 Windows Vulkan WSI | INCOMPLETE: Surface ownership qualified; Windows WSI absent | WINDOWS_VULKAN_PRESENT_VALIDATION.md and outputs/wsi-validation |
| 0.5.0-dev.4 DXVK/D3D11 | NOT STARTED, gated on A | DXVK_INTEGRATION.md; no DXVK DLL/fixture |
| 0.6.0 third-party3D game | NOT STARTED, gated on B | FIRST_WINDOWS_GAME_VALIDATION.md; no game import/run |

## Implemented

Non-exported worker-owned Binder Surface registry in :runtime with session/sequence/generation/owner validation, explicit revoke/close, native reference accounting and runtime death recovery foundation. Separate SHA-verified Windows WSI capability PE and diagnostic mode16, using the existing Process Manager, managed diagnostic prefix and dev.2 Wine overlay. Fixed the diagnostic success verdict for CRLF in existing Windows compute stdout. Conditional build property withSurfaceIpcProbe gives version11 / 0.5.0-dev.3-incomplete; qualified version metadata remains unchanged when omitted.

New source: RuntimeSurfaceService.kt, surface_owner.cpp, SurfaceIpcDeviceTest.kt, WsiCapabilityDeviceTest.kt, tests/vulkan/wsi-capability.c, build-wsi-capability-probe.py, validate-surface-ipc.py and eight mission documents. Integration edits: AndroidManifest.xml, Android Gradle file, runtime/native/CMakeLists.txt, process_jni.cpp, WineVulkanDiagnostics.kt, RuntimeProbeService.kt, RuntimeProbeClient.kt, verify-vulkan-package.py and current status/docs. Qualified Wine/GDI/Box64 binaries and dev.2 compute probes are unchanged; no Wine or Box64 source patch/rebuild occurred.

## Corrected observations

The initial native loader test assumed Android rejected dlopen of the packaged Box64 executable. POCO disproved it. The corrected test proves library loadable but main missing from dlsym; symbol audit confirms LOCAL HIDDEN main/initialize. First failing assertion/raw output is retained, not described as a Box64 runtime regression. Validator initially rejected a method containing Win32 because its regex excluded digits; corrected before full device validation. Raw Surface Parcel marshalling is actually rejected for Binder/FD objects. Wine actual Win32 extension create-instance gate fails with -7/exit44.

## Remaining work

Qualify same-process Box64/Surface execution (or real supported child Binder delivery), signal/TLS/exit ownership; isolated Android-aware Wine interface48/client_surface/extension mapping; real Windows PE triangle+swapchain+PixelCopy/lifecycle. Only then start B/C. No complete dev.3/dev.4/0.6 release is claimed. Keep old qualification artifacts; never clear app data or a prefix to make a test pass.

Exact new device/host regression counts, APK SHAs and preservation gates are in outputs/wsi-validation/checkpoint-summary.json and the validation report; failure histories remain in the evidence archive. No publication/commit/push occurred.

## Qualification counts

POCO:24 distinct Android methods passed after correcting one nonexistent selector; includes5 new IPC/capability methods and19 old regression methods, GUI10/10. Existing historical full suite was not rerun. Host:18 JVM/Robolectric +2 CTest, lint0errors/17warnings. No app-owned Wine process remained. Original early mistakes/failures are retained. 16new files and18 modified files relative to the dev.2 source archive before packaging; outputs/wsi-validation/iteration-changes.json lists exact paths.
