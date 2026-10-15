> Current PROJECT TITAN continuation: [2026-10-13 checkpoint](PROJECT_TITAN_CHECKPOINT.md). Windows Vulkan 120 frames, DXVK/D3D11 and Library GPU Play are now device-confirmed. The older status below describes the preserved preceding checkpoint; it is not the current implementation status.

# Windows Vulkan presentation validation — INCOMPLETE

The ownership-only results below are the preserved original dev.3 checkpoint. A later [separate-process GPU image checkpoint](WSI_IPC_IMAGE_CHECKPOINT.md) verifies Linux x64 image IPC and host Present; Windows WSI remains missing. Exact APK identities/results differ and are retained separately.


## Exact current result

There is **no Windows Vulkan Triangle executable, VkWin32Surface→Android implementation, Windows swapchain or Windows Present** in this checkpoint. No PixelCopy result is offered as proof of Windows Vulkan rendering. Native Android triangle and Windows headless compute remain separate qualified paths from dev.2.

New capability PE is tests/vulkan/wsi-capability.c, packaged separately as vulkan-wsi-capability-x64.exe. It loads vulkan-1.dll, resolves GIPA and instance enumeration, lists11 client extensions and requests VK_KHR_surface+VK_KHR_win32_surface:

```text
FROSTBOX_WSI_AVAILABLE WIN32=0 ANDROID=0 HEADLESS=0
FROSTBOX_WSI_CREATE_INSTANCE RESULT=-7
```

POCO run: native child PID949, :runtime PID919, application UID, Win32 logical PID32, exit44, elapsed2142ms; no signal/exec error/timeout/cancellation, stdout/stderr dropped0, Wine server kill0/wait0 and child reaped. These PIDs are from the preserved qualified-device test, not a promise of fixed IDs. Exit44 is the expected missing-WSI control, not success42.

New device results are in ../wsi-validation/qualified-device relative to the repository parent (outputs/wsi-validation). Initial mistaken dlopen expectation and runner method-name regex failure are retained under first-device; corrected complete run uses a newer APK with a different SHA. Exact build/evidence SHAs and current regression results are in outputs/wsi-validation/checkpoint-summary.json after qualification.

## Release gate still required

A Windows x64 PE must create an actual HWND, Wine Win32 VkSurfaceKHR, Adreno graphics/present queue and swapchain; acquire/submit/present changing triangle frames, log Windows/Wine/Box64 provenance and present count, and release all objects. Independent PixelCopy must observe distinct GPU frames. Resize/orientation/recreation/pause/resume/StopStart and runtime death must be qualified. Shader/instance/device creation alone cannot pass this gate.

No fake swapchain, GDI/Canvas substitution, native renderer proxy or cross-process numeric native pointer was added. Build version is 0.5.0-dev.3-incomplete; no completed dev.3 release exists.

Known historical issue: dev.2 once failed GDI recreation PixelCopy with ERROR_SOURCE_NO_DATA, then passed on the same APK. This checkpoint did not change that test/renderer or claim the race fixed. Current GDI recreation passed once; that does not establish long-run immunity. The initial current regression command also used nonexistent compatibilityLabUnicode (OK0tests, correctly marked failed); the corrected actual compatibilityLabControlsAndUnicode result is archived separately.

## Current APK qualification summary

- Five new Android methods passed their explicit ownership/capability assertions.
- Nineteen distinct existing Android regression methods passed:3 ABI (ELF interpreter/dynarec + Windows compute),7 GDI/display methods (including corrected actual Compatibility Lab method),2 native Vulkan,3 Library and4 basic process methods. Total24 distinct methods; GUI loop10/10 is part of one method. This is not the entire historical Android suite.
- Current GDI recreation passed once; no claim that the old dev.2 timing race was fixed.
-18 JVM/Robolectric tests (12core+6app),2 CTest, lint0errors/17warnings.
-29 preserved runtime/archive/GDI files match prior SHA snapshots; no application UID process remained in the final ps snapshot. Unrelated UID10371 Wine processes were untouched.

App SHA256: `6a3013856de24a9bcc02f4902fa7a7845f5adcc90d0a5df877aa640e695aa759`.
Test APK SHA256: `2df70eaf3392d083cb7a5e45ca50d4f13d3cb87dd6a4334f09902d3a67c3181b`.
Installed versionCode11 / versionName0.5.0-dev.3-incomplete, API36; app and test APK hashes match all current qualified regression evidence. Raw failed attempts and full bounded diagnostic output remain archived.

## Persistent transport continuation

The older per-frame-spawn checkpoint remains historical evidence. A subsequent [persistent transport checkpoint](PERSISTENT_GPU_IPC_VALIDATION.md) qualifies a two-image pool, 100-frame x64 sessions, actual semaphore SYNC_FD and Stop/resize/runtime recovery. Windows WSI, Win32 extension and swapchain semantics remain unimplemented; a new Windows triangle PE is compiled only. No change to qualified Wine/GDI runtime or completed Windows/DXVK/game milestone is implied.
