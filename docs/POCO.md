# Current TITAN checkpoint on POCO

Update installation preserves data: `adb install -r -t` for the app and test APK; do not clear app data or prefixes. Windows Vulkan120frames, DXVK/D3D11 and short KARLSON gameplay are confirmed on the connected POCO, with limits in [PROJECT_TITAN_CHECKPOINT](PROJECT_TITAN_CHECKPOINT.md).

For user programs: existing Library → Add Game/EXE → Import game folder (explicit SAF selection) → Game Settings → DXVK experimental → choose an existing validated prefix → Save → Play. Keep dependencies/resources beside EXE; conflicting native d3d11/dxgi DLLs are refused. DXVK is available only in the optional WSI/D3D11 build. Use Stop to clean up. Existing GDI remains selectable. Current GPU Surface loss requires Stop/restart. [KARLSON arguments and input limitations](FIRST_WINDOWS_GAME_VALIDATION.md). No graphics compatibility is promised for arbitrary EXE.

The instructions below describe earlier checkpoints.

# Current 0.5 GPU check

Install the new app/test APKs with `adb install -r -t` to preserve data. Native Vulkan Diagnostics
and its PixelCopy/lifecycle gate are qualified on POCO; commands and boundaries are in
[VULKAN_RENDER_VALIDATION.md](VULKAN_RENDER_VALIDATION.md). Windows Vulkan/DXVK are pending.

Historical runtime instructions:

# POCO F8 Ultra / HyperOS device acceptance

Target hardware is supplied by the project brief; exact GPU ID, Android build and supported
Vulkan features must be read on the physical device. ADB/installation/Activity checks have run; four basic runtime tests and the original Windows x64 bootstrap console probe now passed. See ANDROID_RUNTIME_VALIDATION.md.

1. Build/install the APK with bundled Box64 + Wine; see BUILD.md and WINE_BIONIC.md.
   `adb install -r android-app/build/outputs/apk/debug/android-app-debug.apk` or install via HyperOS file manager.
2. Open FrostBox → Add Game → Select EXE. Choose your console x64 EXE explicitly.
3. Details → Import / replace game folder for adjacent DLL/resources. Check private relative paths.
4. Game Settings → name/EXE/working directory/literal argv JSON/restricted environment/prefix → Save.
5. Play → Runtime Logs: actual output/exit/PID. Repeat; then test Stop on your own safe console fixture.
6. Force-stop/reopen FrostBox: library persists, no EXE automatically restarts. Recover runtime is explicit.
7. Rotate landscape/portrait with logs/settings open. Export diagnostics via SAF if needed.
8. Remove library entry requires confirmation and keeps private files/shared prefixes.

Full limits: LAUNCHER_USER_GUIDE.md. Controller/thermal diagnostics are retained from earlier versions;
controller forwarding, FPS and Windows graphics are not enabled.

Avoid deliberate overheating. Battery temperature is not the SoC junction temperature. No root,
CPU frequency overrides, vendor thermal-service disabling or unsafe thermal thresholds are required.
No game or thermal-performance benchmark is meaningful until Alpha 0.3/0.4.

Useful manual commands:

```bash
adb devices -l
adb shell getprop ro.build.fingerprint
adb shell getprop ro.product.cpu.abilist
adb logcat -s FrostBox FrostBoxNative AndroidRuntime
```

Uninstall removes all private profiles and EXEs; exported files remain at your chosen SAF destination.

## Runtime Lab (0.2 development)

Build with `-PwithBox64=true` after `scripts/build-box64.py` and build the instrumentation APK.
Run `scripts/test-device.sh SERIAL` to execute four app-UID tests. Keep the complete output in
`build/device-results/instrumentation.txt` and `validation.json`. Missing Box64 fails the suite; it must not be skipped.

Manually run Runtime → Native process, Box64 interpreter, Box64 dynarec in that order. Each must show
exact computation output and exit 42. Run the cancellation test, then Stop; expect `cancelled: true`,
no timeout, and no lingering child. Export diagnostics from Settings. Capture logcat for a crash.
Record `adb shell getconf PAGESIZE` and Android fingerprint; the APK is built for 16 KiB alignment,
but this is not proof of device execution. An adb-shell execution alone is not an app-UID test.

These are fixed diagnostics. Library now separately launches explicit imported console x64 programs.

## Managed prefix acceptance (0.3.0-dev.1)

The managed x64 console API is qualified separately from the old Runtime Lab bootstrap. Run with bundled Box64 + Bionic Wine:

```bash
python3 scripts/validate-prefix-runtime.py YOUR_SERIAL --output build/prefix-validation
```

The runner creates diagnostic profiles without deleting user data, tests real wineboot, reuse,
20 launches, Windows cancellation, app-UID runtime death, interrupted wineboot, lock contention,
critical-file corruption/explicit prefix deletion, content-URI import and restart after force-stop.
It records installed APK hashes and complete process evidence. Read PREFIX_RUNTIME.md and the
current ANDROID_RUNTIME_VALIDATION.md for exact results, limitations and regression commands.

## Library acceptance (0.3.0-dev.2)

```bash
python3 scripts/validate-library-runtime.py YOUR_SERIAL --output build/library-validation
```

This installs/checks both APKs, exercises Compose controls and real ContentResolver imports/runtime,
and retains device logs/results/exit-info/process snapshots. It does not clear app data. Default tests
control the returned picker URI via Espresso, not runtime behavior. On this POCO HyperOS denied
cross-app UI Automator input injection (INJECT_EVENTS); systemPickerImport is explicitly ignored.
Do not disable OS protections to pass it. Manual system-picker acceptance is still required.
Landscape/activity recreation is tested separately using the actual app.
