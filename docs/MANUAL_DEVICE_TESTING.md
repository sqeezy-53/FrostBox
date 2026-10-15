# Manual POCO testing — not yet performed by a human

Do not clear app data/prefix or uninstall. Use the dev.2 text-recovery debug APK; keep earlier APKs/evidence.
The standalone FrostBox-0.4.0-dev.2-CompatibilityLab-x64.exe can be copied to Downloads by the
user, then imported using Add Game → Select EXE and the real system picker. Save profile; Play.

1. Confirm genuine Windows title/controls, no lower allocation padding and correct caption symbols.
2. Tap Count, Checkbox and Clear; confirm counter/check changes. Try hold, drag, quick/double taps.
3. Repeat after screen resize/activity recreation and restart FrostBox; use the saved profile.
4. Close window, Play again, Stop; inspect real status/exit/logs. Test Home/return separately.
5. Tap Edit, open Keyboard, type Hello, Привет and ёЁ № €. Verify real Edit text, logs, Backspace,
   Clear, switching to multiline/Enter and Back hiding the keyboard. Manual physical input is still
   separate from the passing automated InputConnection tests.
6. Test physical keyboard/mouse separately; those devices and real popup interactions are unqualified.

Record build SHA256, device/API, whether input was physically performed, observed changes,
stdout/stderr/exit and issues. ADB/instrumentation events do not substitute for this checklist.
Keep user programs separate; do not launch unknown downloaded EXEs automatically.
