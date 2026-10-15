# Storage recovery

Foundation validation: 2026-10-15, POCO F8 Ultra / Android16.

| Measurement | Allocated app-private usage |
|---|---:|
| Immediate pre-sharing baseline |74.6215GiB|
| After reconciliation |54.5073GiB|
| Reclaimed by reconciliation |**20.1142GiB**|
| After new retained QA prefix and regression evidence |55.6959GiB|
| Final net reduction versus baseline |18.9256GiB|

These are `du -k` allocated-block measurements, not the sum of logical file sizes.
The Storage screen uses `lstat.st_blocks * 512`, counts each inode once and includes
directory blocks. Its scope is `filesDir`; the table also includes private app
cache/code-cache. Android Formatter displays decimal GB, whereas this table uses GiB.

## Root cause

Display, Vulkan, WSI, input, audio and WoW64 overlays copied the entire preceding
runtime. Small module changes multiplied hundreds of MiB of PE files. In addition,
WoW64 diagnostics created and retained a fresh roughly1.2GiB prefix for every run.
Games/prefixes are real mutable data and must not be treated as disposable cache.

## Android-compatible sharing

Hardlink prototype was rejected on the phone with AccessDeniedException. Android16
has an explicit [AOSP app hardlink prohibition](https://android.googlesource.com/platform/system/sepolicy/+/android16-release/private/app_neverallows.te#113).
The denial is consistent with that policy; no policy bypass was attempted.

Final implementation shares **guest Windows PE and font data only** through
`runtime-objects/v1`. Object names encode SHA256, permissions, mtime and extension.
A finalized view contains relative references to these exact private objects.
Resolver rejects links outside the store, nested/unknown links, missing objects
and hash mismatch. Unix ELF/bin files remain regular and retain their loader paths.
Prefixes, games, saves, registries, configuration and markers are never shared.
Overlay replacement is atomic and does not modify a referenced object's inode.

Reconciliation checks ready package roots only, a narrow payload path policy,
bytes/mode/mtime, ownership, process state and stale-plan identity. A cross-process
file lease excludes runtime operations; live or ambiguously identified owned
processes block maintenance. OS lock release on process death does not authorize
cleanup while a guest remains alive. Persistent owner metadata aids diagnosis.

The successful dry-run examined50 roots/43,210 eligible regular files and selected
32,591 paths. All runtime paths remained present; no runtime root, game or prefix
was deleted. A pre/post fingerprint of game/prefix/profile tree path/inode/size/
mtime/mode was identical during reconciliation. Later normal test launches can
legitimately update their own Wine registries and last-run metadata.

## Recurrence and remaining work

New view copying reuses verified guest data objects. WoW64 diagnostics retain one
explicitly owned QA prefix per fixture/runtime, rather than `run-UUID` per launch.
Existing legacy diagnostic prefixes are preserved. Three console invocations used
the same QA prefix: first created it, next reused it; after warmup the second/third
`horizon-wow64` allocated totals were identical. First warmup added10,484KiB, then
stabilized. This is a scoped repetition test, not an indefinite no-growth guarantee.

Dry-run records an interrupted unpublished reference without changing it. Apply
can retire only a same-UID temporary reference in an approved payload directory
whose target is a verified private object; the object itself remains intact.

No general root/object garbage collector is shipped yet. Old versions, legacy
prefixes, unowned/partial directories and smaller diagnostic outputs still need a
separate reference/lifecycle policy. Cold full-package extraction after these
changes was not qualified on a wiped device; app data was deliberately preserved.

## Device commands

Install a matching full checkpoint and test APK with `adb install -r -t`; never clear data.

```sh
python3 scripts/validate-storage-sharing.py --serial YOUR_DEVICE_SERIAL --safety
# Review the saved private dry-run and ensure the runtime is stopped.
python3 scripts/validate-storage-sharing.py --serial YOUR_DEVICE_SERIAL --apply
```

Logs go to ignored `build/device-storage-validation`. Production UI:
**Settings → Storage → find identical runtime files → review estimate → combine**.
Unknown or changed paths fail closed. There is no universal “delete all cache” action.

[Exact scoped validation](FOUNDATION_VALIDATION.md).
