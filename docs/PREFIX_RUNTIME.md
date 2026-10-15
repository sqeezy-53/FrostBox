# Managed Wine prefixes on Android — 0.3.0-dev.1

This is a headless x64/Bionic Wine runtime foundation. It does not implement a window system,
WoW64, DXVK or a general game launcher. Original FrostBox code orchestrates the existing native
ProcessManager; Wine and Box64 remain independently attributed components.

## Paths and ownership

```
files/games/<canonical UUID>/
  game.exe                   # SAF copy; fixed name, never provider-controlled path
  launch.json                # schema, SHA-256, literal argv, workingDirectory="."
  wine-manager.lock          # kernel file lock, shared with ProfileRepository
  runtime-journal.json       # AtomicFile, last lifecycle/owned process identity
  last-recovery.json         # durable last recovery + Android exit reasons
  runtime.ndjson             # bounded process logs; previous generation retained
  prefix.initializing/       # resumable staging, never presented as ready
  prefix/                    # published only after wineboot + shutdown + validation
    .frostbox-prefix.json    # schema, profile ID, Wine version, package digest, commit bit
    drive_c/windows/system32/
    dosdevices/c: -> ../drive_c
    dosdevices/z: -> /
    system.reg / user.reg / userdef.reg
```

Each application gets independent system files and registries. This costs approximately 655 MiB per
prefix with the current full x64 PE template; disk sharing/deduplication is intentionally deferred.
Z: is Wine's host mapping, **not a security sandbox**: a Windows process has the app's Android UID
and can access app-owned files. Android SELinux/UID boundaries remain intact. Do not claim that
profile directories isolate malicious EXEs from other FrostBox profiles.

## Initialization transaction

1. Validate canonical UUID and refuse redirected managed roots/files.
2. Acquire `FileChannel.tryLock()` for the profile. Concurrent initialization/save fails visibly;
   it does not block indefinitely. The service also serializes native registry use.
3. Check the journal for interrupted execution. Recover only recorded, app-owned processes and the
   prefix-specific wineserver before reuse. PID/start-time/UID/APK executable guards prevent PID reuse kills.
4. Create/resume staging with the exact Wine package identity. Copy missing x64 Wine PE system files
   using temporary files, fsync and rename. This supplies the bootstrap system files Wine needs before
   its own installation can run under the Android guest-image constraints.
5. Run actual `wineboot.exe --init` through Box64 **without WINEBOOTSTRAPMODE**. Wine performs its
   registry/installation routines. `mscoree`/`mshtml` are disabled; no Mono/Gecko download or installation.
6. Run prefix-scoped `wineserver -k` / `-w`; verify success, registries and critical files.
7. Atomically write committed ownership metadata, rename staging to final prefix, write READY journal.

AtomicFile fsyncs file contents but does not itself lock writers. Explicit file locks provide that
coordination. A killed process releases its kernel lock automatically. Journal and prefix publication
are distinct writes; interrupted transitions are reconciled from ownership metadata. This is tested
for process death, **not a guarantee against arbitrary power-loss/storage corruption**.

Existing final prefixes are validated and reused. Version/digest mismatch or corrupt committed prefix
fails without deleting/resetting user data. Automated Wine version migration, registry repair and backup
restoration are not implemented. An interrupted staging prefix is resumed with the same runtime only.

Validation checks owner/schema/commit/runtime identity, all three registry headers, C: mapping,
redirected critical directories, and SHA-256 of ntdll/kernel32/kernelbase/wineboot. It is a critical-path
integrity check; it does not verify every Wine DLL, registry value or future installed application.

## API and boundaries

`WineRuntimeClient` exposes initializePrefix, validatePrefix, launchWindowsExecutable, stopProcess,
getProcessStatus, getRuntimeLogs, recoverRuntime, terminateWineServer and deletePrefix. `state: StateFlow` streams live
process snapshots; status/log requests are serialized operations, not a concurrent observer service.
The client never executes JNI on the UI thread. Messenger binds a non-exported `:runtime` service.
`AndroidWineManager` owns transactions and journals; native execution goes through existing JNI
handles and ProcessManager (no second fork/exec implementation).

Launch accepts a managed profile ID, not arbitrary host paths. `launch.json` comes from the SAF
repository or explicit configure IPC. x64 PE type and SHA are checked immediately before execution.
Arguments are a bounded list (64 / 8192 characters, no NUL), passed directly as argv, never a shell.
The launcher supports a private working directory, adjacent DLL/assets via bounded SAF folder import
and a restricted environment map. See LAUNCHER_USER_GUIDE.md. Import never runs code.
Stored graphical/thermal preferences are not applied by this console path.

Explicit user launch has no deadline and remains cancellable; initialization has a 90-second timeout
and server commands have 15 seconds. Legacy built-in diagnostics keep their original bounds.

Shutdown releases JNI handles, stops the original process group, and waits for the matching wineserver.
Binder death is an error, not a fabricated exit status. On the next managed operation the journal is
recovered. `ApplicationExitInfo` is diagnostic evidence (Android 11+), not proof of an arbitrary HyperOS
kill cause. Cleanup only targets recorded process identities and the prefix server; no global killall.
Recovery is lazy/on explicit request, not an automatic background restart of user code.

Delete requires exact confirmation `DELETE_PREFIX:<UUID>`, checks ownership and shuts down Wine.
`Files.walk()` without FOLLOW_LINKS prevents traversing Z: or other directory symlinks. Imported EXE,
launch configuration and other files outside the prefix are retained. There is no blanket data reset.

## Reproduce on an authorized phone

Build the pinned Box64 and Wine components as in WINE_BIONIC.md; build the original fixtures,
package Wine, then ensure sleep-x64.exe and sleep-fixture.json are in the optional runtime assets.
`package-wine-probe.py` copies the sleep fixture when it exists alongside console-x64.exe.

```bash
scripts/build-windows-fixtures.sh
# package-wine-probe.py --installed ... --exe build/windows-fixtures/console-x64.exe ...
./gradlew -PwithBox64=true -PwithWineProbe=true \
  :core:test :android-app:testDebugUnitTest :android-app:lintDebug \
  :android-app:assembleDebug :android-app:assembleDebugAndroidTest
python3 scripts/validate-prefix-runtime.py YOUR_SERIAL --output build/prefix-validation
```

The runner installs both APKs without clearing data, checks installed APK digests, and executes ten
instrumentation methods. It records raw stdout/stderr/exit/PID/UID/timings, logcat, exit-info and process
snapshots. It creates dedicated UUID diagnostic profiles. It force-stops FrostBox only between the
explicit restart phases; Windows/runtime SIGKILL scenarios run under the app UID, without root.
Use `--no-install` only with matching APKs; `--methods METHOD ...` narrows a recheck. Keep the first
failed evidence separately before fixing/rerunning. Do not substitute adb-shell Wine execution for
instrumentation. Existing basic/ELF/Bionic/bootstrap test runners remain regression gates.

Primary implementation references:
- [Android AtomicFile](https://developer.android.com/reference/android/util/AtomicFile)
- [Android ApplicationExitInfo](https://developer.android.com/reference/android/app/ApplicationExitInfo)
- [Wine 11.19 wineboot](https://github.com/wine-mirror/wine/blob/wine-11.19/programs/wineboot/wineboot.c)
