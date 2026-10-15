# D3D11 / DXVK — POCO-qualified diagnostic path

2026-10-13, Adreno840 / Qualcomm0.842.27, Android16/API36, application UID.

Original FrostBox Windows x64 cube uses hardware D3D11 feature11_0, D3DCompile vs_5_0/ps_5_0, 36 vertices, changing constants, D32 depth and real Draw/IDXGISwapChain::Present. It does not call Vulkan or the Android-native renderer. DXVK2.7.1 logs identify winevulkan, Adreno840 and RGBA8/FIFO, three images, client392×273.

Initial diagnostic: 120 Draw/Present, exit42, 22 distinct PixelCopies. Final APK requalification: guest8836, runtime8769, 5006ms, 120 host frames, 120 SYNC_FD retirements, three imports, 22 distinct PixelCopies, no bridge error. wineserver kill/wait exit0. Ordinary Library SAF import/settings/Play separately passed 120 frames / exit42 / 20 distinct PixelCopies (guest25296).

This qualifies this fixture on this device. It does not qualify every D3D11 game, HDR, all WSI extensions, or long sessions. EDID/display metadata warnings and unsupported pipeline-library/memory-budget paths remain. See [checkpoint](PROJECT_TITAN_CHECKPOINT.md) and [game limitations](FIRST_WINDOWS_GAME_VALIDATION.md).

Build `python3 scripts/build-d3d11-probe.py --wine-work ../../work/wine-wsi --dxvk-work ../../work/dxvk-experiment`, then the full optional Gradle flags from the checkpoint. Device method `SurfaceIpcDeviceTest#d3d11CubeThroughDxvk`; validator `scripts/validate-surface-ipc.py`. Source: `tests/d3d11/cube.c`. No game files are included.
