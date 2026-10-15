# GPU baseline — separate paths, no game benchmark

| Path | Current measurement status |
|---|---|
| Native ARM64 Vulkan triangle | Existing independently qualified renderer; current regression evidence records its own timestamps/present counters/thermal data. No interpretation as Windows/game FPS. |
| Linux x64 Box64 Vulkan compute | Dev.2 qualified100 results10800/42; this checkpoint runs interpreter/dynarec regression. No presentation or frame-rate metric. |
| Windows x64 Wine Vulkan compute | Dev.2 qualified10800/42; regression retained here. Process wall time includes Wine initialization, not shader-only GPU time. |
| Windows WSI capability negative probe | Actual elapsed2142ms, exit44, no surface/device graphics pipeline/swapchain. This is loader/capability diagnostic time, not time to first frame. |
| Windows Vulkan triangle | Not implemented; GPU time, Present intervals, FPS and first-frame time unavailable. |
| D3D11/DXVK | Not integrated; all performance metrics unavailable. |
| Third-party game | Not run; FPS, frametime,1%low, thermal session and gameplay unavailable. |

Surface IPC acquisition/release checks have no GPU producer, so their successful lifecycle and test duration cannot be compared with rendering performance. No busy-wait, privileged frequency changes or unmeasured optimization was added. Existing native renderer's presentedFrames means successful present calls, not proven scanout. Any historical dev.2 benchmark remains historical; this checkpoint does not claim a Windows gaming performance improvement.

## Current native-only sample (same incomplete APK, POCO)

The independent realTriangleAndLifecycle regression's native-render event records71 successful Present calls,69 GPU timestamp samples, first frame28.0689ms, initialization23.4506ms, GPU TOP_OF_PIPE→BOTTOM_OF_PIPE mean0.236257ms, record/submit wall mean0.0955363ms, renderer-thread record/submit/present CPU mean0.199755ms, present-call mean interval16.6522ms/P95 16.793ms, thermalStatus0. Raw metrics are in outputs/wsi-validation/native-gpu-current-metrics.json and original native regression evidence. This is a short native triangle sample, not a sustained benchmark, scanout measurement, Windows Vulkan/D3D11 result or game FPS/1%low.
