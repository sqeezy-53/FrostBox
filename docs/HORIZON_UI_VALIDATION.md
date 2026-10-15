# HORIZON UI checkpoint — 2026-10-14

`0.7.0-alpha-ui`, versionCode16. Partial UI checkpoint, not a complete 0.7.0.

Implemented: original near-black/purple Compose theme; real Home hero/history;
bottom navigation; private user covers and gradient fallback; library search,
x64/x86/DXVK filters, sort, grid/list; lazy grid for the existing 195 records;
expanded game details with real runtime/profile data. Existing imports,
confirmations, settings, logs and launcher are retained. Nullable addedAt is
backward compatible; older records have no invented creation date. Catalog state
survives details/Back and Activity rotation; cold-start catalog preferences are
not persistent yet. No downloaded catalog or automatic cover service.

POCO YOUR_DEVICE_SERIAL/API36: three instrumentation tests PASS (ui-regressions.txt):
- read-only real library navigation, app Back dispatcher, portrait/landscape;
- Add/Play, console5050/42 three times, logs and Activity recreation;
- Stop, recreation, runtime process death, recovery and repeated Play/Stop.

Screenshots were taken on the phone after a 500ms display settlement. The first
screenshot attempt exposed an IME inset/layout bug; fixed redundant imePadding
by consuming Scaffold insets, added Search IME action and compact library controls.
The initial test timed out; the two subsequent navigation runs passed. Logs retain
both attempts. Existing 195 records were not removed; regression tests add their
own diagnostic records. The read-only navigation test compares all profiles.

52 qualified runtime/native assets are byte-identical to v15. Build and host JVM
checks passed during development; app/test APK built again after layout fixes.
Evidence: ../../outputs/horizon-validation/; checkpoint: ../../outputs/horizon-ui-release/.
No Windows/Box64/DXVK or Wine prefix migration occurred in this UI checkpoint.

Next stages: bounded Present-stall diagnostics and isolated WoW64 console test.
Mafia II found in /sdcard/Download/Mafia2 (8.4GB); audited pc/mafia2.exe is x86.
Only the EXE was copied into local private work for PE import inspection, never
into source/evidence distribution. No original game file was changed or executed.
