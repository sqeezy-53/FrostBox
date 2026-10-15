# Display: window bounds and caption symbols — 2026-10-12

Scope: only the extra lower field and incorrect caption glyphs. No runtime architecture change,
Vulkan/DXVK, additional application, profiling or full regression suite.

## Causes and minimal fixes

Wine11.19 win32u/window.c get_surface_rect rounds allocation to multiples of128. The existing
adapter sent all backing bitmap rows (640×512) as window content although GetWindowRect
reported640×400. winefrost flush now intersects the backing rectangle with actual window
bounds, packs rows with the original source stride, and sends only640×400. Android SurfaceView,
letterboxing and input mapping use the existing frame dimensions; no Android renderer change.
Top-down and bottom-up bitmap row order are handled; no Wine allocator patch.

Wine user32/uitools.c draws caption controls with SYMBOL_CHARSET Marlett (character codes
0x30,0x31,0x72). The font overlay lacked Marlett, resulting in visible0/1/r fallback glyphs.
Added unmodified fonts/marlett.ttf from the pinned Wine11.19 source, its LGPL2.1-or-later notice,
and package completeness requirements. FreeType is unchanged; no Microsoft/proprietary font.
Marlett SHA256:1a9b951ca1815344050ae6158263991e2145918bc74ad65d82bc6ec4056a57d1.

## Checks actually performed

- bash -n scripts/build-display-driver.sh: PASS.
- Existing display build script / NDK27: adapter/font overlay compiled; ELF init/TLS audit PASS.
- verify-display-package.py: package hashes/ABI and required Marlett PASS.
- Gradle assembleDebug + assembleDebugAndroidTest with Box64/Wine/Display: BUILD SUCCESSFUL.
- One POCO instrumentation method guiPixelsMouseKeyboardAndClose: PASS, application UID/API36.
  Real frame640×400, exact GDI color and Cyrillic pixels, mouse/key markers, close exit42,
  prefixValidation.valid=true, display.error empty. Screenshot inspected: no white lower field;
  caption controls display minimize/maximize/close symbols. Library is the actual launch path.
- APK SHA256 matched the installed app and test APK.

No full stress/regression rerun, no separate console5050 test this iteration, no claim of arbitrary
GUI compatibility or physical keyboard/touch qualification. Existing Wine/Box64/base ZIP and
prefix code are unchanged. User data/prefix/previous builds were not cleared; only a new
version-keyed display runtime view was prepared by the existing manager.

## Changed files

- runtime/display/winefrost.drv/frost.c — crop allocation padding before frame transport.
- scripts/build-display-driver.sh — package Wine Marlett and its manifest hash.
- scripts/verify-display-package.py — require the symbol font.
- android-app/src/main/java/org/frostbox/app/display/DisplayPackage.kt — require symbol font.
- android-app/src/androidTest/java/org/frostbox/app/runtime/DisplayDeviceTest.kt — assert640×400.
- third-party/licenses/Wine-fonts-NOTICE.txt — Marlett copyright/license/source notice.
- docs/DISPLAY_WINDOW_FIXES.md and WINDOWS_DISPLAY_VALIDATION.md — this limited result.

Evidence in sibling outputs/android-display-validation/window-bounds-marlett/: validation.json,
installed APK hashes, instrumentation/stdout/stderr/exit/PID/UID/logcat/prefix report and unmodified
PixelCopy first-frame.png/after-input.png. Existing unrelated Wine DNS/network/service and
WGL/Vulkan-disabled messages remain in stderr. No blocking error observed in this test.
Multiwindow, resize variants and broader caption behavior were not qualified here.

Reproduce (configured JDK17/SDK/NDK; from repo):
```bash
ANDROID_NDK_HOME=/absolute/path/to/ndk/27.2.12479018 scripts/build-display-driver.sh /absolute/path/to/work/wine-android
./gradlew -PwithBox64=true -PwithWineProbe=true -PwithWindowsDisplay=true :android-app:assembleDebug :android-app:assembleDebugAndroidTest
python3 scripts/validate-display-runtime.py YOUR_DEVICE_SERIAL --methods guiPixelsMouseKeyboardAndClose --output ../android-display-validation/new-window-fixes
```

Two requested display issues are resolved in the tested fixture. No next phase started.
