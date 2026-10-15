# PROJECT HORIZON — partial checkpoint, 2026-10-14

APK17: `0.7.0-alpha-horizon-experimental`. This is not a complete 0.7.0 or a
Mafia II playable release. Latest verified milestone: genuine x86 Windows GPU
execution on POCO YOUR_DEVICE_SERIAL/API36/ARM64/Adreno840, ordinary app application UID.

| Area | Actual status | Evidence |
|---|---|---|
| Premium launcher UI | Home/hero, dark purple theme, bottom navigation, rotation/Back dispatcher tested | HORIZON_UI_VALIDATION.md; ui-regressions.txt |
| Library | Existing DataStore, search/filter/sort/grid/list, lazy catalog; real Play/Stop x64 | ui-regressions.txt |
| Details | Real cover/profile/runtime/logs; old creation dates stay unknown | screenshots/; read-only profile comparison |
| Graphics settings | Existing x64 GDI/DXVK selection and resolution; x86 launcher not connected | FrostApp.kt; no fake D3D9 selector |
| Present stall | Sandbox reproduced; guest Acquire returns DEVICE_LOST (-4); not fixed | stall/; stall-diagnosis.json |
| GfxDeviceWorker | Previous invalid command evidence retained; relationship to device loss unproven | PHYSICAL_DEVICE_RECOVERY.md |
| Windows x86 PE | Console5050/42, three dynarec requests + one interpreter request; cancellation/server cleanup | wow64-result.json; x86-cancel-result.json |
| Wine WoW64 | Fresh standard win64 prefix with syswow64; same prefix reused for four console processes; isolated from user prefixes | wow64-device.txt |
| DirectDraw | Not tested | next qualification |
| D3D8 | Not tested | next qualification |
| D3D9 | x86 DXVK2.7.1: 120 DrawPrimitiveUP/Present, actual GPU/PixelCopy, exit42 | release-gpu-device.txt; x86-d3d9-final-result.json |
| D3D10 | Not tested | next qualification |
| D3D11 | Qualified x64 DXVK cube regression passed | regressions/6-instrumentation.txt |
| D3D12 | Not integrated/tested; no VKD3D claim | next qualification |
| Mafia II startup | Not attempted; original x86 pc/mafia2.exe found on phone | mafia-exe-audit.json |
| Mafia II menu | Not tested | requires complete import and launcher integration |
| Mafia II gameplay | Not tested | no playable claim |
| Audio | x64 WASAPI -> actual AudioTrack9600 frames/42 passed; previously heard by user; no new listening/32-bit qualification | regressions/1-instrumentation.txt; PHYSICAL_DEVICE_RECOVERY.md |
| Gamepad | Prior physical 8BitDo/XInput qualification retained, not repeated here | PHYSICAL_DEVICE_RECOVERY.md |
| Touch | App-local touch -> Win32 regression passed after harness update | harness-device.txt |
| Performance | No game FPS/frame-time/1% low measured in this iteration | only fixture counters and timings |
| Regressions | Seven existing Android cases pass across first run and targeted reruns; native renderer lifecycle and UI also pass | regressions/; harness-device.txt |

All evidence lives in [horizon-validation](../../horizon-validation/). Earlier
failed attempts are retained alongside the successful results.

## WoW64 architecture and proof

Wine11.19 compiled once in a separate work/horizon/wine-wow64/build with
`--enable-archs=i386,x86_64`, x86_64 Android29 Bionic Unix compiler and i386
Windows PE compiler. The overlay contains728 i386 PE files (525070336 bytes),
including ntdll/kernel32/kernelbase/winevulkan/winefrost.drv. The existing x64
userspace and the exact qualified Box64 binary are retained. BOX32 stays OFF;
Wine's 32-bit code executes through Box64's existing compatibility execution,
not a 32-bit Linux ELF/ARMv7 runtime. Modern WoW64 uses a win64 prefix with
system32 and syswow64; no user prefix conversion.

Four console runs:5050/42,523/533/529/531ms; init7282ms; x64 console42; wineserver
kill/wait0. Runtime PID11531, application UID. These are diagnostic runs, not a new
32-bit Library Play path. Requested interpreter/dynarec flags are recorded.
Cancellation test waits for a genuine x86 Sleep PE marker, stops its process,
then verifies kill/wait0. Cleanup commands ignore the cancelled game flag.

## Mapping failure and minimal correction

First x86 Vulkan PE reached Adreno840, created Instance/Device/buffer/memory,
but vkMapMemory returned-5 (exit11). Wine selected placed mapping4096 alignment.
Native ARM64 control used the same driver outside Wine/Box64:

| Allocation | High address placed map | Address below4GiB | Ordinary map |
|---|---|---|---|
| 128 bytes | -5 | -5 | 0 |
| 4096 bytes | 0 | 0 | 0 |

The control reserves its own VM ranges using MAP_FIXED_NOREPLACE and checks the
returned address. It never replaces unrelated mappings and performs no root or
security bypass. It is an ABI/memory control, not a substitute compute/render test.

A separate Wine Unix win32u overlay rounds ordinary host-visible WoW64 allocations
to placed mapping alignment. Imported/exported/dedicated allocations stay outside
this patch; overflow is rejected. Qualified x64 Wine ZIP, WSI win32u and GDI driver
are not overwritten. A distinct private runtime key includes patch SHA256.
[Khronos placed mapping contract](https://docs.vulkan.org/refpages/latest/refpages/source/VkMemoryMapPlacedInfoEXT.html)
and the native control establish the placement constraints; the patched guest
probe establishes the outcome.

After patch: x86 Windows GPU compute10800/42, SKIP_DISPATCH=0; resources/fence
completion and buffer readback verified. Debug callback fires; Wine explicitly
reports allocation callbacks not exercised. This does not qualify every extension.

## D3D9 proof and limits

Genuine x86 PE uses Direct3DCreate9/HAL device, fixed-function DrawPrimitiveUP,
120 real Presents. Pinned official DXVK2.7.1 x32 DLLs are extracted without
rebuilding DXVK. Wine WSI and the existing external image/SYNC_FD/ACK bridge are
used. Host:120 frames,3 imports,120 syncFDs,19 distinct PixelCopy captures, no
host error; client640x360; exit42, elapsed3049ms on the patched test run.
DXVK logs identify the actual Adreno840. D3D9's adapter identifier prints an AMD
compatibility identity; it is not evidence of an AMD physical GPU. These fixture
timings are not game FPS or a sustainable performance benchmark.

Fresh graphical wineboot initially timed out because it used the non-Vulkan
Box64 binary. Diagnostic initialization now selects the qualified Vulkan Box64
binary and forces guest libfrostvk/FreeType loading. No Box64/DXVK rebuild.

## Sandbox blocker

One90s direct-mode, no-audio run with physical user Sandbox selection froze at
1447 presents. PID8532, runtime8464, application UID. Last per-thread trace:
Acquire-return=-4, old generation1/frame1447 and new generation2/frame0. Host
error stayed empty; Stop cancelled and cleaned wineserver. This localizes a
guest device-loss symptom across scene/swapchain change, not its originating GPU
command. No guessed WSI synchronization patch or unbounded debugger attempts.
Next diagnostic: capture the first failing submit/fence plus device/swapchain
lifetimes around scene change; keep GfxDeviceWorker corruption separate.

## Preservation, artifacts, next scope

Original195 game directories are retained. No app-data deletion, real prefix
cleanup, game file modification, root, SELinux changes, or system-wide input
injection. Diagnostic prefixes are deliberately retained (~14GiB at the recorded
snapshot; later tests add more). No automatic storage cleanup is provided here.
Mafia2 is8.4GiB in /sdcard/Download/Mafia2; only its x86 EXE was copied to private
PC work for import audit. No proprietary game data is included in source/APK.

Safe milestones: horizon-ui-release/, horizon-wow64-console-release/, then
horizon-x86-gpu-release/. Original physical recovery APKs remain present.
Source snapshot plus pinned upstream source bundle and patches are provided.
Wine LGPL2.1-or-later; Box64 MIT; DXVK Zlib. Kotlin/Compose storage still uses the
existing database; no fork/repository recreation or upstream publication.

Next scope: reusable WoW64 package provider and production prefix version checks
in the existing Wine Runtime Manager; explicit experimental per-profile selection;
Library x86 console/Stop/recovery and D3D9 tests. Then SAF import the complete
Mafia2 directory and test real startup/menu. These are unimplemented, not declared
technical impossibilities. x86 audio/controller/gameplay need separate validation.

## Reproduce

From outputs/FrostBox, existing qualified environment:

```bash
export JAVA_HOME="$PWD/../../work/toolchain/jdk"
export PATH="$JAVA_HOME/bin:$PATH"
export GRADLE_USER_HOME="$PWD/../../work/gradle-home"
export ANDROID_NDK_HOME="$PWD/../../work/toolchain/sdk/ndk/27.2.12479018"
export FROST_WINE_SOURCE="$PWD/../../work/wine-wsi/source"
export FROST_WINE_TOOLS="$PWD/../../work/wine-android/host-tools"
export GRADLE="$GRADLE_USER_HOME/wrapper/dists/gradle-8.13-bin/5xuhj0ry160q40clulazy9h7d/gradle-8.13/bin/gradle"
scripts/build-wow64-candidate.sh ../../work/horizon/wine-wow64
python3 scripts/build-horizon-x86-probes.py --wine-build ../../work/horizon/wine-wow64/build --wine-source ../../work/wine-wsi/source --sdk ../../work/toolchain/sdk --dxvk-archive ../../work/dxvk-experiment/dxvk-2.7.1.tar.gz
python3 scripts/build-wow64-vulkan-overlay.py --qualified-work ../../work/wine-wsi --work ../../work/horizon/wine-wow64-vulkan --sdk ../../work/toolchain/sdk
./gradlew -PwithBox64=true -PwithWineProbe=true -PwithWindowsDisplay=true -PwithBox64Vulkan=true -PwithWineVulkan=true -PwithSurfaceIpcProbe=true -PwithWineWsi=true -PwithD3d11Probe=true -PwithWineInput=true -PwithRemoteAudio=true -PwithHorizonUi=true -PwithWow64=true :core:test :android-app:testDebugUnitTest :android-app:assembleDebug :android-app:assembleDebugAndroidTest
adb -s YOUR_DEVICE_SERIAL install -r -t android-app/build/outputs/apk/debug/android-app-debug.apk
adb -s YOUR_DEVICE_SERIAL install -r -t android-app/build/outputs/apk/androidTest/debug/android-app-debug-androidTest.apk
adb -s YOUR_DEVICE_SERIAL shell am instrument -w -r -e class 'org.frostbox.app.runtime.Wow64DeviceTest#isolatedX86ConsoleAndX64Regression,org.frostbox.app.runtime.Wow64DeviceTest#x86VulkanCompute,org.frostbox.app.runtime.Wow64DeviceTest#cancelX86AndTerminateServer,org.frostbox.app.runtime.SurfaceIpcDeviceTest#x86D3d9ThroughDxvk' org.frostbox.app.test/androidx.test.runner.AndroidJUnitRunner
```

Checks run this iteration: dual-PE Wine make-j6; incremental private win32u make-j4;
NDK C++/Kotlin APK/test builds;24 core JVM +11 Android JVM/Robolectric;2 CTest;
new native mapping control, x86 console/compute/D3D9/cancellation device tests;
three UI/library lifecycle tests; seven existing Android regressions and native
renderer Surface recreate/background/Stop/restart. Earlier Unicode/touch/native
failures were obsolete test navigation assumptions, fixed and rerun successfully.
