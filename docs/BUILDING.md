# Build from source

## Reproducible source-only shell

Pinned toolchain: JDK 17, Gradle 8.13 (wrapper checksum enforced), AGP 8.13.2,
Kotlin 2.2.20, SDK 36, build tools 35.0.0, NDK 27.2.12479018, CMake 3.22.1.
Use Linux, Python 3.12+, Clang/LLVM, Ninja, Git and the official Android command-line tools.

```sh
export JAVA_HOME=/absolute/path/to/jdk-17
export PATH="$JAVA_HOME/bin:$PATH"
export ANDROID_HOME=/absolute/path/to/android-sdk
sdkmanager 'platforms;android-36' 'build-tools;35.0.0' 'ndk;27.2.12479018' 'cmake;3.22.1'
./gradlew :core:test :android-app:testDebugUnitTest :android-app:assembleDebug :android-app:assembleDebugAndroidTest :android-app:lintDebug
cmake -S runtime/native -B build/native -G Ninja
cmake --build build/native
ctest --test-dir build/native --output-on-failure
```

Accept SDK licenses yourself. Alternatively put `sdk.dir` in ignored local.properties.
Output: `android-app/build/outputs/apk/debug/android-app-debug.apk`.
No personal signing key, game, runtime extraction or external work directory is
needed for the default build. The NDK builds original ELF/native test fixtures.
The default APK is a diagnostic shell, **not a full Windows runtime distribution**.

## Optional runtime tiers

- `scripts/build-box64.py --sdk "$ANDROID_HOME" --work build/box64-source` obtains
  the pinned source hash in `third-party/runtime-lock.json`. Enable `-PwithBox64=true`.
- Wine base: `scripts/build-wine-bionic.sh WORK`, then `package-wine-probe.py`.
  See [Bionic Wine recipe](WINE_BIONIC.md) and [detailed build history](BUILD.md).
- Full display/GPU/input/audio/WoW64 packaging uses separate scripts and manifests
  in ignored `build/*-package`. These inputs are checked, never downloaded by Play.
  Existing `--work` paths in older examples refer to prepared build trees; adapt
  them to your workspace. A clean full GPU release pipeline is **not yet automated**.

Current full checkpoint flags (require already generated, qualified package inputs):

```sh
./gradlew -PwithBox64=true -PwithWineProbe=true -PwithWindowsDisplay=true \
  -PwithBox64Vulkan=true -PwithWineVulkan=true -PwithSurfaceIpcProbe=true \
  -PwithWineWsi=true -PwithD3d11Probe=true -PwithWineInput=true \
  -PwithRemoteAudio=true -PwithHorizonUi=true -PwithWow64=true -PwithEmpireBay=true \
  :core:test :android-app:testDebugUnitTest :android-app:assembleDebug :android-app:assembleDebugAndroidTest
```

Do not mix x64 and WoW64 audio overlays, overwrite the qualified Wine ZIP, or treat
successful compilation as device validation. Runtime hashes and upstream sources
must accompany a distributable binary; see [release gates](RELEASING.md).

## CI scope

Android Checks builds the shell, unit tests and lint. Native Tests runs CTest.
Manual runtime workflows attempt source builds and fail if inputs/builds fail;
they do not claim full GPU reproducibility or physical-device execution.
Connected tests require an explicitly authorized physical device. Game QA tests
are opt-in and require user-owned imported files; no games are CI inputs.
