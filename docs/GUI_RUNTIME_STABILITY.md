> Historical incomplete-build results below. The subsequent text recovery and final dev.2 gates are in [WIN32_TEXT_INPUT_RECOVERY.md](WIN32_TEXT_INPUT_RECOVERY.md).

# GUI runtime stability — 0.4.0-dev.2-incomplete,2026-10-12

**Not a ready release.** Child Edit/IME input is blocked. The following is one final preservation
subset on the exact incomplete app/test APKs; it excludes the known failing text scenario.

POCO25102PCBEG, serialYOUR_DEVICE_SERIAL, API36, ARM64, appapplication UID, no root. No app data/prefix/user
EXE cleanup, uninstall or base Wine ZIP repack. Prior APKs/evidence preserved.

| Actual final device check | Result |
|---|---|
| Compatibility Lab controls/close/repeat/Stop | PASS: WM_COMMAND counter/Checkbox/Clear; real control pixels; exit42; cancellation |
| Original GUI pixels/mouse/key/close | PASS: Unicode GDI pixels and640×400; Marlett inspected |
| Original GUI repeated launches | PASS:10/10 exit42 |
| Surface/Activity recreation + Stop | PASS |
| Runtime death/display close/recovery | PASS |
| Library console5050/42/repeat/recreate | PASS |
| Adjacent DLL / missing DLL | PASS: correct success and real failure |
| ARM64/interpreter/dynarec/cancellation | PASS:4 base tests |
| Lab Edit Unicode/Enter/Backspace | BLOCKED: not passed; not included above |

11 unique Android methods passed in the preservation subset (5Display+2Library+4basic).
The text diagnostic had 7 failed focused device attempts, retained under: dev2-lab-smoke, dev2-lab-diagnostic, dev2-lab-focus, dev2-key-trace, dev2-child-focus, dev2-focus-order, dev2-text-commit.
Do not count these as successful tests or pretend the entire instrumentation suite passed.
Linux:18 JVM/Robolectric (12core+6app),2CTest; Gradle app/test APKs and lint passed.
Initial lint found API30 WindowInsets use at minSdk29; fixed with WindowInsetsCompat, not suppression.
A first tiny PE link failed with __chkstk from a >4KiB scratch buffer; bounded buffers reduced,
then the independent Lab PE built without CRT/security bypass. No Box64 rebuild.

## Measured values (not FPS)

Lab close scenario: process elapsed2436ms; first presented frame2248ms;
10 presented frames;640×400; exit42; display error empty.
Repeat/Stop scenario: elapsed2129ms; first frame1859ms;
6 frames; cancelled=true, signal15.
Elapsed is execution until terminal result, not isolated startup or Stop latency. Meminfo/process
snapshots are stored after each test, not a validated idle-runtime CPU/memory benchmark.
Dropped frames, CPU idle usage, startup-to-window time and isolated Stop duration were not measured.
No busy-wait/FPS counter or performance claim introduced.

## Evidence/artifacts

Raw final gates: sibling android-display-validation/dev2-final-display,dev2-final-library,
dev2-final-basic. Includes instrumentation, stdout/stderr/exit/PID/UID/prefix validation, logcat,
ApplicationExitInfo, process/memory snapshots and untouched PixelCopy PNGs. Summary is in
android-display-validation/dev2-summary/FINAL_SUMMARY.json. Both installed APK hashes are
verified by all runners. ZIPs and SHA256SUMS are sibling outputs artifacts; filenames explicitly
mark app/test as incomplete. Lab EXE is supplied separately for ordinary user import.

Existing DNS/network/services and disabled WGL/Vulkan messages remain; no blocker for the
validated software GDI/console subset. No arbitrary GUI/multiwindow/background/resize/IME,
physical keyboard/touch or long-session leak guarantee. Additional overlay candidates create
cached runtime views; this iteration did not delete prior views/user data to reclaim storage.

Final commands:
```bash
./gradlew -PwithBox64=true -PwithWineProbe=true -PwithWindowsDisplay=true :android-app:assembleDebug :android-app:assembleDebugAndroidTest :core:test :android-app:testDebugUnitTest :android-app:lintDebug
python3 scripts/validate-display-runtime.py YOUR_DEVICE_SERIAL --methods compatibilityLabControlsAndClose guiPixelsMouseKeyboardAndClose tenGuiLaunches recreateSurfaceAndStop runtimeDeathClosesDisplay --output ../android-display-validation/new-dev2-display
python3 scripts/validate-library-runtime.py YOUR_DEVICE_SERIAL --no-install --methods addPlayRepeatLogsAndRecreate adjacentDllDirectoryAndMissingDependency --output ../android-display-validation/new-dev2-library
python3 scripts/validate-android-runtime.py YOUR_DEVICE_SERIAL --no-install --phase basic --output ../android-display-validation/new-dev2-basic
ctest --test-dir build/native --output-on-failure
```
The separately retained compatibilityLabControlsAndUnicode is the future acceptance gate;
it must actually pass before text support is enabled. No checks were removed to claim success.
