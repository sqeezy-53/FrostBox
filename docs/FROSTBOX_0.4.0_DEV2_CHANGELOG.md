# 0.4.0-dev.2 text recovery — 2026-10-12

Fixed unmatched right-button release opening Edit context menus and redirecting Unicode input.
Fixed UTF-8 output JSON escaping; qualified child Edit commits and limited Android InputConnection.
VersionCode8. Exact final evidence and limits: WIN32_TEXT_INPUT_RECOVERY.md.

## Earlier incomplete iteration (retained)

#0.4.0-dev.2-incomplete — 2026-10-12

Added independent MIT Win32 Compatibility Lab and reproducible tiny PE build, real standard
controls, Unicode static labels, Library import/launch/control assertions. Added tested pure
letterbox coordinate model, primary-pointer tracking/cancel releases, extra virtual key mappings.
Attempted a separate UTF-16 InputConnection transport; child Edit delivery failed, so the
production text/IME path is explicitly disabled. No ready release claim.

Preserved Wine11.19/Box64/base ZIP/prefix, Marlett and640×400 crop. Build entry points/CI now build
the Lab too; versionCode7, versionName0.4.0-dev.2-incomplete. No Vulkan/DXVK/proprietary fonts,
new process manager, extra dependency download or external publication.

Changed code: tests/windows/compat-lab.c, win32-minimal.h; scripts/build-compat-lab.sh;
core DisplayGeometry + two meaningful mapping tests; DisplaySession, WindowsSurface,
WindowsScreen, DisplayDeviceTest, winefrost frost.c; version/build entry points/CI and docs.
Exact verification/limitations and artifact identity are in GUI_RUNTIME_STABILITY.md.
