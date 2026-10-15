# Validation record — 0.2.0-dev.1

Historical dev.1 host report; current POCO evidence is ANDROID_RUNTIME_VALIDATION.md.

Historical dev.1 report. Current device status: [ANDROID_RUNTIME_VALIDATION.md](ANDROID_RUNTIME_VALIDATION.md).

Date: 2026-10-10. Environment: Linux x86-64; local JDK 17, SDK 36, Build Tools 35.0.0,
NDK 27.2.12479018, CMake 3.22.1, Gradle 8.13. System JDK 27 was not used.
No physical Android device: `adb devices -l` returned an empty device list.

## Commands executed and outcomes

Commands below are shown from the repository root with work paths shortened. Local evidence accompanies
the deliverable in `../runtime-validation/`. No CI or phone run is implied by a local build.

| Command / check | Actual result |
|---|---|
| `python3 scripts/build-box64.py --sdk SDK --work WORK --jobs 4` | Unmodified checksum-pinned Box64 source compiled with NDK; ARM64/Bionic PIE packaged |
| `./gradlew -PwithBox64=true :core:test :android-app:testDebugUnitTest :android-app:assembleDebug :android-app:assembleDebugAndroidTest :android-app:lintDebug --console=plain` | BUILD SUCCESSFUL; application and instrumentation APKs produced |
| `cmake -S runtime/native -B WORK/native-host -G Ninja -DCMAKE_BUILD_TYPE=Debug` then `cmake --build WORK/native-host -j4` | C++20 host targets compiled; process library/test warnings treated as errors |
| `ctest --test-dir WORK/native-host --output-on-failure` | 2/2 suites passed: runtime_contract and process_lifecycle |
| `scripts/build-windows-fixtures.sh WORK/windows-fixtures` | Original x64 and x86 PE programs built with Clang/LLD/llvm-dlltool |
| `python3 benchmarks/run-host-wine.py --runner … --wine /usr/bin/wine --wineserver /usr/bin/wineserver --profile … --fixtures … --output …` | Exit 0; Wine 11.19 initialized private prefix and ran both PE programs |
| Direct Linux execution of original `compute-x64.elf` | Expected ELF stdout and exit 42; checks fixture only, no translation |
| `apksigner verify --verbose APK` | Debug APK signature verified (v2) |
| `zipalign -c -P 16 4 APK` | Passed |
| `llvm-readelf -h -l -d` on packaged native files | All AArch64, LOAD alignment 0x4000; Box64 and probe use `/system/bin/linker64` |
| Box64 manifest/binary SHA-256 comparison | Exact match |
| `git diff --check` | Passed |

JVM test reports: CoreTest 8, RepositoryTest 3, ActivityTest 1; **12 passed, 0 failures/errors/skips**.
Robolectric ActivityTest is a JVM smoke test, not visual inspection or an Android-device run.
Lint: **0 errors, 11 warnings** (dependency upgrade suggestions and the deliberate ARM64-only ABI).
The four instrumentation tests **compiled but were NOT executed**. GitHub workflows were written,
not run in GitHub Actions. No release signing or remote publication performed.

## What native tests actually exercise

Host lifecycle suite launches actual subprocesses and checks computation/stdout/stderr/exit, literal
arguments and explicit environment, process-group descendant cleanup, inherited signal reset,
concurrent output saturation with truncation, timeout SIGTERM→SIGKILL, cancellation, inherited FD
closure, replacement of closed parent stdin, signal termination, exec/cwd errno, embedded-NUL rejection,
RAII reaping and byte-safe JSON. Test-only Linux subreaper allows explicit descendant reaping assertions.
These host results do not validate Android seccomp, SELinux or Bionic runtime behavior.

Host Wine smoke initializes a new private prefix, verifies registry creation and rejects a concurrent
manager lock. Both `console-x64.exe` and `console-x86.exe` printed exactly
`FROSTBOX_PE_RESULT=5050\n` and returned **42**. Prefix-scoped shutdown returned 0.
Wineboot produced XDG_RUNTIME_DIR/Mesa warnings; raw output is retained in the JSON report.
A development failure (wineboot returned before registry flush) was corrected with `wineserver -w`.

Recorded host measurement: whole-run elapsed about 30.5 seconds including fresh prefix setup; direct
PE stages 220 ms / 40 ms in that single run. This is smoke evidence, not a reproducible performance
comparison, game FPS, Android result or evidence that FrostBox outperforms another product.
Direct-child wait4 peak RSS/CPU time do not cover the whole Wine process tree.

## Packaging provenance

- Box64 v0.4.4 commit: `2f130fab1d6e1a4ee8a71dc60cfdfcc839ad192a`.
- Source archive SHA-256: `ce844041092de3f44316bd1c033a3cc10024e5f798c1a8e8a58f046c6d967981`.
- Packaged Box64 SHA-256: `167dbda2f6020c73f13c874fa1b2826725e638bc9bbf7005a14b872404669c56`.
- Source lock in `third-party/runtime-lock.json`; APK metadata in `assets/runtime/box64.json`.
- MIT license present in APK; no Wine, DXVK, GPU driver, glibc userspace or game is distributed.
- Only ARM64 native code; executables installed through native library packaging (`extractNativeLibs=true`).
- Non-exported `:runtime` service; no INTERNET or broad storage permission.

Source compilation was repeated from the pinned archive after the initial upstream checkout build.
This verifies the recipe works here; it is not a claim of bit-for-bit reproducibility across machines.

## Explicit remaining blockers

1. No ARM64 Android execution evidence. APK installation, app-UID exec, Box64 interpreter and dynarec,
   JIT memory/cache behavior, HyperOS, Bluetooth/USB and physical thermal sensors remain untested.
2. Historical dev.1 restriction: superseded for the console bootstrap probe by ANDROID_RUNTIME_VALIDATION.md. General prefix/game support still requires Bionic/glibc ABI,
   guest/native dependencies, TLS/signals, Android wineserver or WoW64 integration.
3. No Windows window, graphics translation, audio, game, XInput, actual FPS cap or game-present timestamps.
4. Native process groups are cleanup boundaries, not a complete untrusted-code sandbox.

Next concrete gate: run `scripts/test-device.sh SERIAL` on ARM64 Android with both APKs built using
`-PwithBox64=true`, retain all four test outcomes, and fix actual app-UID failures. Then qualify a Wine
userspace and run the same original x64 PE on Android before beginning graphics integration.
