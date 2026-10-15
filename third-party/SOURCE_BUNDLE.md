# Remote input/audio candidate source correspondence

The small remote source snapshot contains all current project-local sources,
modified Wine driver/module code, patches, tests, licenses and build scripts. It
does not duplicate upstream archives: use it together with the already delivered
`../evolution-release-0.6.0-alpha/FrostBox-0.6.0-alpha-evolution-source.zip`.
The remote validation.json and snapshot UPSTREAM-SOURCES.json record that archive
path and SHA256. Keep both available when redistributing this candidate; the small
snapshot alone is not the complete corresponding upstream source bundle.

# 0.5.0-dev.4-titan-checkpoint corresponding source

The checkpoint source ZIP contains current original GPU IPC/WSI/Launcher/fixtures and isolated Wine WSI/metric modifications, the pinned Wine/Box64/FreeType upstream archives listed below, all local patches, licenses and rebuild scripts. Optional DXVK2.7.1 official DLLs retain their Zlib notice; pinned binary provenance and build/download recipe are included. Game binaries/resources/dumps and proprietary drivers are excluded. Checksum manifest accompanies source and APKs. This is a device-qualified diagnostic checkpoint, not a completed stable0.6.0.

The following preceding source correspondence remains historical.

# Incomplete dev.3 checkpoint source bundle

FrostBox-0.5.0-dev.3-incomplete-source.zip includes the current original Surface IPC/JNI service, capability probe, recipes/tests/docs and the same unmodified Wine/Box64/FreeType upstream source archives listed below. No new Wine/Box64/DXVK binary/source modification is made in this checkpoint. The separate app/test APK are diagnostic builds, not a qualified Windows WSI release.

Historical dev.2 source correspondence:

# Corresponding upstream source in the 0.5.0-dev.2 archive

The delivered FrostBox-0.5.0-dev.2-source.zip includes the project, all local Wine/display patches and
recipes, licenses, and these **unmodified source archives** under `FrostBox/third-party/sources/`:

| Archive | SHA256 | License |
|---|---|---|
| FrostBox-Wine-11.19-upstream-source.tar.gz | 73eb0b02dfc0cb0ff4f4d8d36c5786261e9725ac91968ae4d4e4b9a498c4087f | LGPL-2.1-or-later plus per-file notices |
| FrostBox-Box64-0.4.4-upstream-source.tar.gz | ce844041092de3f44316bd1c033a3cc10024e5f798c1a8e8a58f046c6d967981 | MIT |
| FrostBox-FreeType-2.14.3-upstream-source.tar.xz | 36bc4f1cc413335368ee656c42afca65c5a3987e8768cc28cf11ba775e785a5f | FTL plus per-file notices |

Unpack Wine, apply `third-party/patches` with `scripts/build-wine-bionic.sh`, then use
`scripts/build-display-freetype.py` / `scripts/build-display-driver.sh` for the qualified overlay.
See docs/WINE_BIONIC.md and docs/BUILD.md for x64/Bionic toolchain and current build commands.
Box64 uses scripts/build-box64.py with its pinned source/NDK manifest. Guest components can be rebuilt
and replaced in the APK; rebuilding/reinstalling the APK with a personal key is permitted.
The source bundle is included with the binaries, not a link-only substitute for corresponding source.
Archives are attached to the delivered ZIP from the already downloaded/checksummed inputs; they need
not be committed as large tarballs to the Git repository. No proprietary GPU driver or game is included.
Native Vulkan shaders/renderer are original MIT code; the system Android Vulkan driver is not distributed.

The dev.2 bundle additionally includes the original compute probe/shader, local Android Box64
SONAME patch, and the isolated Wine win32u build recipe. Rebuild the qualified display source/build
first, then copy it using build-wine-vulkan-overlay.py; only config.h SONAME_LIBVULKAN changes in the
copy. The qualified runtime archive is retained; optional experimental pieces can be replaced/rebuilt.
The Box64 patch is AI-assisted FrostBox-local code; no upstream PR was submitted.

Evolution adds `input/winebus/android-pad.c.inc` under LGPL-2.1-or-later and a reproducible
build script for the existing Wine 11.19 no-SDL winebus backend. The release source bundle
includes this source and the same upstream Wine/Box64/FreeType source archives; no new
proprietary game or driver binaries are included. Experimental Wine display input changes
are built separately; immutable qualified Wine ZIPs are not replaced.
