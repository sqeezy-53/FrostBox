# Third-party notices

Original FrostBox code is MIT. This does not relicense upstream code or the whole
runtime APK. Preserve individual copyright/license headers. Exact selected source
pins are in [runtime-lock.json](third-party/runtime-lock.json).

| Component | Selected version | License / distribution requirements |
|---|---|---|
| Wine and Wine-derived display/input/audio modules, patches |11.19|LGPL-2.1-or-later and per-file notices; provide exact corresponding source/modifications and practical rebuild/replace rights |
| Box64|0.4.4 / pinned commit|MIT; retain notice and local Android wrapper patch source |
| DXVK|2.7.1|Zlib; retain notice; original DLL provenance/hashes recorded |
| FreeType|2.14.3|FTL option and per-file notices; retain notice/source correspondence |
| Wine fonts|from selected Wine source|Per-font upstream notices; see Wine-fonts-NOTICE.txt |
| Kotlin, AndroidX/Compose, coroutines, serialization|Gradle-pinned|Apache-2.0 notices; not FrostBox authorship |
| NDK libc++/LLVM and headers|27.2.12479018|Upstream notices/exceptions in NDK-NOTICE.txt |
| Gradle wrapper|8.13|Apache-2.0; upstream generated tool |
| JUnit / Robolectric / Android test tools|Gradle-pinned|Test-only, respective upstream licenses; not app-release dependencies |

Wine-derived code includes `runtime/display/winefrost.drv`,
`runtime/display/wine-wsi`, `input/winebus`, `audio/wine`, and Wine patches.
These remain LGPL; upstream authors are credited in source. Original Android/GPU
transport code remains MIT where independently authored and marked accordingly.

System Qualcomm Vulkan is accessed through Android APIs. No proprietary driver,
Windows installation, game EXE/resources or PhysX installer is distributed.
No Mesa/Turnip, FEX or VKD3D-Proton binary is bundled by the current integration.

[License texts](third-party/licenses/) · [corresponding-source policy](third-party/SOURCE_BUNDLE.md).
Binary GitHub releases remain gated pending a package-specific correspondence audit;
local historical archives do not automatically satisfy a new release's obligations.
