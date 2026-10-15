# Foundation checkpoint — 2026-10-15

VersionCode19 / `0.7.0-alpha.foundation.1`, full existing optional-runtime build flags.
This is a source/storage validation checkpoint, not a completed gaming0.7 release.

## GitHub

Real repository: [sqeezy-53/FrostBox](https://github.com/sqeezy-53/FrostBox), **PRIVATE**.
Default branch `main`; `develop` and real function/fix branches. Source history
starts with actual current snapshot8fbd8a9; no historical commits/tags invented.
Original code MIT, Wine-derived code LGPL; upstream notices retained.

Initial Android CI failed in SDK setup, because the action requested retired
`tools`. [PR6](https://github.com/sqeezy-53/FrostBox/pull/6) requests `platform-tools`
and preserves pinned SDK/NDK installs and all checks. Actual remote Android/native
checks passed before merge. Future check status must be read from Actions; local
passes do not imply a particular remote commit has passed.

Five real Issues track runtime storage, Mafia II startup, Game Panel, scaling and
KARLSON device loss. Branch protection returned HTTP403 (private-plan restriction).
Project creation was not completed: token lacks project scope. Neither limitation
was worked around by making the repository public or repeating authorization.

## Source review

Initial snapshot326 paths/~2.68MB before subsequent storage work. Only the Gradle
wrapper was a source binary at initial push. Raw evidence, editor copies, SDK/NDK,
builds, user games, prefixes, signing keys and device serials excluded. Personal
paths/identifiers redacted from historical docs; original local reports preserved.
A source gate supplements manual review and is not claimed as a universal secret scanner.

The local APK audit found no suspect credentials or personal paths in the reviewed
small metadata files. Deeper nested binary scanning found local build paths in
1,420 entries, predominantly Wine PE. **No full APK uploaded to GitHub Release.**
Separate sanitized distribution and corresponding-source audit remain release gates.
The qualified Wine ZIP/Box64/DXVK and previous APKs are preserved.

## Implemented

- Verified private PE/font object sharing for immutable runtime views; no hardlinks.
- Idle-only cross-process storage/runtime lease and owned-guest checks.
- Full-plan preflight, content/metadata identity, atomic path replacement;
  arbitrary symlink and stale-plan rejection.
- Real allocated-block Storage screen, dry-run estimate and an explicit action.
- WoW64 fixture-owned QA prefix reuse and retained-fixture regression option.
- API29-compatible bounded log read (two old API33 lint failures fixed).

## Host checks actually run

- Full existing runtime-enabled app APK and AndroidTest APK compiled.
- Source-only shell APK/test APK compiled.
- Kotlin/JVM:25 core +15 Android-module tests, zero failures.
- Android lint:PASS (existing warnings are not hidden by a baseline).
- CTest:2/2 PASS.
- Audio mock contract:3/3 PASS; not a physical audio test.
- Source staging privacy/artifact gate and `git diff --check`:PASS.

## POCO tests actually run

No root, data reset or protected-input bypass. App/test APK updated in place.

| Check | Actual result |
|---|---|
| New shared-data view copy |PASS; source remains regular, copied data matches |
| Identical sharing / active lease / prefix preservation |PASS |
| Interrupted unpublished reference |PASS; dry-run retains it, apply unlinks only the verified temp, object preserved |
| Stale plan |PASS: refused before mutation |
| Arbitrary runtime symlink |PASS: refused |
| Real installed dry-run + reconciliation |PASS; protected tree fingerprint unchanged |
| WoW64 console repeated3 invocations |PASS; each includes5050/42, x64 regression and interpreter/dynarec runs; same QA prefix reused |
| ARM64 + Box64 interpreter/dynarec + cancellation |4 tests PASS |
| Windows Vulkan compute |10800 / exit42; PASS |
| WoW64 Library console / Stop / forced runtime death / recovery |PASS using recorded built-in fixture prefix |
| WoW64 D3D9 Library |2×120 Presents/42;25 and24 distinct PixelCopies; Stop canceled/signal15 |
| x64 Library console / repeat / Activity recreation |PASS |
| x64 D3D11 Library |120 Presents/42;18 distinct PixelCopies |
| GDI / extended Unicode bridge |PASS |
| x64 WASAPI to actual Android output |PASS; no new human listening claim |
| x86 WASAPI through Library |9600PCM frames/42; actual Android output initialized |
| Native Vulkan / Surface lifecycle |PASS |
| Storage UI |PASS; actual totals and enabled maintenance action; reviewed screenshot |

Fourteen regression test invocations passed in the focused runtime suite (11
instrumentation launches; one contains four native/Box64 methods). Five boundary
methods, installed dry-run/apply, two earlier console invocations and one UI test
were executed separately. The two initial hardlink-prototype failures are retained
locally and are **not** counted as final passes; the final data-object design passed.

## Space result

74.6215 →54.5073GiB after sharing: **20.1142GiB reclaimed**. After the newly retained
QA prefix and regression evidence:55.6959GiB, net reduction18.9256GiB. Final repeated dry-run selected zero replacements / zero estimated reclaim. All50 runtime
roots remain. Old `run-*` Wine prefixes, imported games and saves are not deleted.
Details and measurement scope: [STORAGE](STORAGE.md).

## Reproduction

Build flags/SDK: [BUILDING](BUILDING.md). Device safety/dry-run/apply:
`scripts/validate-storage-sharing.py --serial YOUR_DEVICE_SERIAL --safety`.

Focused regressions use `adb -s YOUR_DEVICE_SERIAL shell am instrument -w -r`,
runner `org.frostbox.app.test/androidx.test.runner.AndroidJUnitRunner`, and `-e class`:

- `org.frostbox.app.runtime.RuntimeDeviceTest#nativeProcess` (also interpreter/dynarec/cancellation methods).
- `org.frostbox.app.runtime.Wow64DeviceTest#isolatedX86ConsoleAndX64Regression`.
- `org.frostbox.app.runtime.Box64VulkanDeviceTest#windowsCompute`.
- `org.frostbox.app.runtime.LibraryDeviceTest#wow64LibraryConsoleStopRecovery`.
- `org.frostbox.app.runtime.LibraryDeviceTest#wow64D3d9LibraryPlayRepeatAndStop`.
- `org.frostbox.app.runtime.LibraryDeviceTest#addPlayRepeatLogsAndRecreate`.
- `org.frostbox.app.runtime.LibraryDeviceTest#dxvkLibraryImportAndPlay`.
- `org.frostbox.app.runtime.DisplayDeviceTest#editUnicodeBridgeExtended`.
- `org.frostbox.app.library.RemoteInputAudioDeviceTest#wasapiPcmToActualAndroidOutput`.
- `org.frostbox.app.runtime.LibraryDeviceTest#wow64LibraryAudio`.
- `org.frostbox.app.runtime.VulkanDeviceTest#realTriangleAndLifecycle`.
- `org.frostbox.app.runtime.StorageUiDeviceTest#showsAllocatedUsageAndRealMaintenanceAction`.

For the two WoW64 Library regression methods, `-e reuseQaFixtures true` reuses only
a recorded built-in fixture with matching asset SHA256. It never selects a real
game EXE. Missing/unmatched retained records fail rather than silently selecting
another profile. Do not run game QA tests as generic regressions.

Raw command output, complete dry-run plan, before/after `du`, failed prototypes,
logcat and build reports remain in the local `outputs/foundation-validation/`
evidence directory outside Git. The source report contains aggregate results only.

## Unchanged / not verified this iteration

- No new physical controller or human listening test. Earlier confirmations remain historical.
- Mafia II: earlier menu/controller/music success; repeated intro startup still unreliable.
- KARLSON: earlier Tutorial gameplay/audio; Sandbox device loss unresolved.
- No new game level, gameplay FPS, sustained thermals or5/5 Mafia qualification.
- Game Panel/scaling work and general safe cache GC remain in real Issues.
- No clean-data cold full GPU extraction test and no approved binary GitHub release.
