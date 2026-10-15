# Installation and upgrades

No approved binary GitHub Release is published yet. Local qualified checkpoints
are preserved separately from CI shell APK artifacts. Do not assume a CI APK has
Wine/Box64/DXVK bundled.

Requirements: ARM64 Android, sufficient private storage, compatible Vulkan driver
for GPU paths. Main qualification device: POCO F8 Ultra / Android 16 / Adreno 840.
Android 10 is the build minimum; other devices/versions remain unqualified.

For an authorized, matching full checkpoint APK:

```sh
adb devices
adb -s YOUR_DEVICE_SERIAL install -r -t FrostBox-checkpoint.apk
```

Verify the APK SHA256 and signing identity first. `-r` updates while preserving
data, but only matching application IDs and signing keys can upgrade. Never
uninstall, clear app data or remove prefixes to work around a signature error.
Do not downgrade version codes blindly. This development repository does not
contain the existing private debug key.

Import EXEs/folders via Library and SAF. Import is explicit and does not execute
anything. Use only software you have rights to run. Root and broad storage access
are not required. Wine is not a security sandbox for untrusted Windows software;
it runs with the app's privileges.
