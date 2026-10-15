# Evolution alpha additions (current)

Retain existing qualified runtime assets and use the isolated Wine WSI source/build tree:
```bash
python3 scripts/build-wine-input-overlay.py --wine-work ../../work/wine-wsi
python3 scripts/build-wine-keyboard-driver.py --source-work ../../work/wine-wsi --work ../../work/wine-keyboard-driver
bash scripts/build-xinput-fixture.sh
./gradlew -PwithBox64=true -PwithWineProbe=true -PwithWindowsDisplay=true \
  -PwithBox64Vulkan=true -PwithWineVulkan=true -PwithSurfaceIpcProbe=true \
  -PwithWineWsi=true -PwithD3d11Probe=true -PwithWineInput=true \
  :core:test :android-app:testDebugUnitTest :android-app:assembleDebug :android-app:assembleDebugAndroidTest
```
Use JDK17/SDK/NDK specified below. `withWineInput` yields versionCode13,
`0.6.0-alpha-evolution`. Without packaged runtime dependencies the ordinary CI build still
checks the shell and source, not physical Wine/XInput. Install with `adb install -r -t`, never
`pm clear`. Settings contains Runtime/Vulkan diagnostics. Historical build notes follow.

## Optional dev.3-incomplete Surface IPC diagnostic package

Use the existing dev.2 Box64/Wine/display packages; do not rebuild/replace the base Wine ZIP. Build only the new separate PE:

```bash
python3 scripts/build-wsi-capability-probe.py --sdk ../../work/toolchain/sdk --output build/wsi-capability
python3 scripts/verify-vulkan-package.py --wine --wsi
```

Add `-PwithSurfaceIpcProbe=true` to the full dev.2 Gradle feature flags. This yields versionCode11 / 0.5.0-dev.3-incomplete. [Exact full build/device commands](ANDROID_SURFACE_IPC.md). No GPU backend is exposed by this option; Windows Present remains unimplemented.

# Build on Linux

Pinned baseline (chosen for reproducibility, not a claim these are the newest tools):
AGP 8.13.2, Gradle 8.13, Kotlin/Compose compiler 2.2.20, Compose BOM 2025.09.01,
JDK 17, compile/target SDK 36, min SDK 29, NDK 27.2.12479018, CMake 3.22.1.
Only `arm64-v8a` is packaged. Flexible 16 KiB page sizes enabled for the native library.

1. Install JDK 17, Python 3.12+, unzip, CMake and Ninja using your Linux distribution or vendor archives.
2. Download Android command line tools from https://developer.android.com/studio#command-tools
   and extract into `$ANDROID_HOME/cmdline-tools/latest/`.
3. Read and accept the SDK licenses; install exact packages:

```bash
export JAVA_HOME=/absolute/path/to/jdk-17
export PATH="$JAVA_HOME/bin:$PATH"
export ANDROID_HOME=/absolute/path/to/android-sdk
"$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" --licenses
"$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"   'platforms;android-36' 'build-tools;35.0.0' 'ndk;27.2.12479018' 'cmake;3.22.1'
./gradlew :core:test :android-app:testDebugUnitTest :android-app:assembleDebug :android-app:lintDebug
```

Alternatively set `sdk.dir=/absolute/path/to/android-sdk` in ignored `local.properties`.
System JDK 27 is deliberately not used by this Gradle baseline. Set JAVA_HOME explicitly.
The wrapper checks the Gradle distribution SHA-256. Initial build downloads dependencies.

APK: `android-app/build/outputs/apk/debug/android-app-debug.apk`.
JVM reports: `core/build/reports/tests/test/` and `android-app/build/reports/tests/testDebugUnitTest/`.
Lint: `android-app/build/reports/lint-results-debug.html`.

Host native checks (contract and real subprocess lifecycle; JNI/Vulkan compilation happens in assembleDebug):

```bash
cmake -S runtime/native -B build/native -G Ninja
cmake --build build/native
ctest --test-dir build/native --output-on-failure
```

No secret keys required. Release signing and distribution are intentionally not configured.
Do not commit local.properties, SDK/NDK, build caches, debug keys, imported EXEs or game files.
If sdkmanager reports a broken ZIP, retry the exact official archive and verify its checksum;
do not substitute untrusted toolchain mirrors.

## Optional Box64 package and device tests

The basic build includes the native runtime probe and independent Vulkan Diagnostics. To prepare the CPU runtime laboratory:

```bash
python3 scripts/build-box64.py --sdk "$ANDROID_HOME" --work "$PWD/build/box64-source" --jobs 4
./gradlew -PwithBox64=true :android-app:assembleDebug :android-app:assembleDebugAndroidTest
scripts/test-device.sh YOUR_ADB_SERIAL --output "$PWD/build/device-basic"
```

The first command downloads a checksum-pinned Box64 source archive and compiles it with the NDK.
The APK includes the binary, source manifest and MIT notice. Do not claim Android execution until
all device tests pass. Source-only workflow: `.github/workflows/runtime.yml` (manual dispatch).
Host Wine and original Windows fixture instructions: [NATIVE_RUNTIME.md](NATIVE_RUNTIME.md).

See [Android validation and installation blocker](ANDROID_RUNTIME_VALIDATION.md) for manual APK installation,
`--no-install`, structured app-UID results and the separate stress gate.

## Managed Android Wine prefixes (0.3)

The Wine build is separate: follow [WINE_BIONIC.md](WINE_BIONIC.md). Run
`scripts/build-windows-fixtures.sh` before packaging Wine so the package recipe also installs the
sleep lifecycle fixture as a separate asset. Then build with both `-PwithBox64=true -PwithWineProbe=true`.
Prefix transactions and real-device commands: [PREFIX_RUNTIME.md](PREFIX_RUNTIME.md).
The optional manual `wine-prefix.yml` workflow builds these components and distributes corresponding
Wine source/patches with the artifacts. It has been syntax-checked locally; no GitHub run was performed.
CI compilation is never evidence of device execution. A base APK without the optional components
must still build and return a clear component-unavailable error for runtime requests.

## Library device fixtures and tests

`scripts/build-windows-fixtures.sh` now also builds FrostBox's own dependency-x64.exe / frostmath.dll
and places console/sleep/dependency/DLL assets under build/launcher-fixtures/assets/launcher for **debug**
instrumentation. This requires clang, llvm-dlltool and lld-link on PATH. With Wine assets prepared:

```bash
scripts/build-windows-fixtures.sh
./gradlew -PwithBox64=true -PwithWineProbe=true :core:test :android-app:testDebugUnitTest \
  :android-app:assembleDebug :android-app:assembleDebugAndroidTest :android-app:lintDebug
python3 scripts/validate-library-runtime.py YOUR_ADB_SERIAL --output build/library-device-results
```

For all 29 device gates in the required order, run `scripts/validate-launcher-release.sh SERIAL NEW_OUTPUT_DIR`.
The runner installs/verifies both APK hashes, drives real Library buttons via instrumentation and saves
JSON results, logcat, ApplicationExitInfo and process snapshots. It never clears app data. Test programs
are original MIT-licensed fixtures; no downloaded executable runs automatically. Most test picker
results are controlled by Espresso Intents while all copied data and runtime execution are real.
Full details and any real-picker qualification: ANDROID_LAUNCHER_VALIDATION.md.

Use the supplied staged device runners for the complete regression gate: prefix restart tests require
force-stop **between** their two explicit methods. A single unordered connectedDebugAndroidTest run
is not a substitute for those restart conditions. The optional system-picker test is ignored on this
HyperOS because cross-app input injection is denied; do not count it as passed.

## Optional 0.4 display prototype

The 0.4 GDI/Text path was subsequently qualified on POCO; see WIN32_TEXT_INPUT_RECOVERY.md. Build `scripts/build-gui-fixture.sh` and `scripts/build-display-driver.sh WINE_WORK`,
then add `-PwithWindowsDisplay=true` to the existing Wine Gradle build. See
[display gate](WINDOWS_DISPLAY_GUIDE.md) and [actual limitations](WINDOWS_DISPLAY_VALIDATION.md).
The manual Wine source-build workflow has an optional windows_display input; it does not run POCO tests.

## Native Vulkan foundation (0.5.0-dev.1)

The native diagnostic backend is compiled by every Android build; it needs no Wine or Box64 rebuild.
The qualified full runtime APK retains `-PwithBox64=true -PwithWineProbe=true -PwithWindowsDisplay=true`.
`graphics/vulkan/shaders.hpp` contains checked-in SPIR-V generated from the original GLSL sources;
normal builds require only the pinned NDK. To edit shaders, install glslangValidator and run
`python3 scripts/build-vulkan-shaders.py` (no network download). Native GPU compilation uses C++20
and warnings as errors; host CTest covers the existing manager rather than emulating a phone GPU.

```bash
./gradlew -PwithBox64=true -PwithWineProbe=true -PwithWindowsDisplay=true \
  :core:test :android-app:testDebugUnitTest :android-app:assembleDebug \
  :android-app:assembleDebugAndroidTest :android-app:lintDebug
python3 scripts/validate-vulkan-runtime.py YOUR_SERIAL --output build/new-vulkan-validation
```

The runner installs both current APKs with `-r -t`, checks installed SHA256, uses app-UID JNI Vulkan,
captures PixelCopy PNG, JSON, logcat and ApplicationExitInfo, and never clears app data/prefixes.
Use a new output directory per run. `--no-install` requires matching installed hashes, including the
test APK; recompiling only that APK requires installing it before using the flag. This test is for
native Android Vulkan, not Windows Vulkan/DXVK. See [actual results](VULKAN_RENDER_VALIDATION.md).

## Translated Vulkan experiment (0.5.0-dev.2)

Keep qualified packages; use separate work directories and preserve app data. Build recipes:
[BOX64_VULKAN_BRIDGE.md](BOX64_VULKAN_BRIDGE.md),
[WINE_VULKAN_FEASIBILITY.md](WINE_VULKAN_FEASIBILITY.md).
Host prerequisites additionally include glslangValidator, clang, llvm-dlltool and lld-link.

```sh
./gradlew -PwithBox64=true -PwithWineProbe=true -PwithWindowsDisplay=true \
  -PwithBox64Vulkan=true -PwithWineVulkan=true \
  :android-app:assembleDebug :android-app:assembleDebugAndroidTest
python3 scripts/validate-box64-vulkan.py YOUR_DEVICE_SERIAL --methods \
  originalLoaderControl computeDynarec computeInterpreter dispatchNegativeControl repeatedCompute \
  windowsWithoutVulkanControl windowsCompute --output /path/to/new-evidence
```

Missing optional packages fail integrity gates. `--no-install` compares both installed APK SHAs.
Normal install -r -t preserves data; no pm clear/uninstall. Tests run under app UID; ADB only invokes
instrumentation and transports evidence. Host execution is never Android qualification.
Windows tests reuse a managed diagnostic prefix. Vulkan-enabled Wine is diagnostic-only;
standard Library Play retains qualified GDI, with no fictitious windowed Vulkan toggle.
