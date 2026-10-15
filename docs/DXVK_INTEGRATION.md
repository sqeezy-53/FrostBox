# DXVK 2.7.1 — experimental Android integration

The Windows D3D11 hardware cube and ordinary Library Play are verified on POCO. This does not qualify arbitrary D3D applications or game performance. Windows WSI is supplied by the isolated Wine11.19 interface48 + libfrostvk overlay. Ordinary shaders/buffers/commands use Box64 Vulkan wrapper and the real Qualcomm driver; only image FD/SYNC_FD/metadata cross host IPC.

Pinned official x64 d3d11.dll/dxgi.dll archive SHA256: `d85ce7c79f57ecd765aaa1b9e7007cb875e6fde9f6d331df799bce73d513ce87`. License: Zlib, included in third-party/licenses/DXVK-Zlib.txt and APK assets. [Upstream release](https://github.com/doitsujin/dxvk/releases/tag/v2.7.1).

Library DXVK selection is persisted in launch.json. Import never executes a program. Explicit Play verifies manifests/DLL hashes, prepares the independent WSI view and adds two DLLs to the **private imported EXE directory**, refusing an existing DLL with a different hash. Original SAF files, prefix system DLLs and base ZIP are unchanged. WINEPATH alone failed because Wine selected a builtin system32 DLL before searching the appended directory. The native override uses argv/environment, not shell concatenation. Switching the same imported profile to GDI explicitly selects builtins for these managed DLLs.

Known limits: one guest device/surface/swapchain, RGBA8/FIFO, 2–4 dedicated images, no general oldSwapchain handover, no HDR/mutable formats/fullscreen-exclusive/present-wait, no transparent Android Surface-loss recovery. Stop/restart recreates the host owner. Queue submissions and idle calls serialize external queue access. DXVK logs are read with a bounded report limit; third-party generated log files do not yet have a storage quota.

Reproduction and qualification: [PROJECT_TITAN_CHECKPOINT](PROJECT_TITAN_CHECKPOINT.md), [D3D11_VALIDATION](D3D11_VALIDATION.md).
