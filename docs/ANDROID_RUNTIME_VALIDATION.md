# Latest: EMPIRE BAY menu milestone — 2026-10-14

Actual POCO: ordinary Library x86 D3D9 Play/Stop/repeat120 frames/42, x86 console
5050/42/recovery, bundled PhysX MSI0, x86 AudioTrack9600/42 and heard test signals.
Mafia II rendered2999 frames and a difficulty/menu screen; physical user confirmed
main menu and8BitDo in-menu operation. Subsequent intro boots stall; no playable
release or level/gameplay/FPS qualification. Qualified binaries/data are retained.
[Current complete report](EMPIRE_BAY_VALIDATION.md).

Older validation snapshots below are historical, not current aggregate statuses.

# HORIZON x86 GPU addition — 2026-10-14

On the same POCO/app UID: x86 WoW64 console5050/42; patched Windows x86 Vulkan
compute10800/42; x86 DXVK D3D9 120 GPU Draw/Present + PixelCopy. Fresh diagnostic
prefixes only, not production x86 Library support. Sandbox returns guest -4.
[Exact report](HORIZON_X86_GPU_VALIDATION.md). Prior validation below is retained.

Current checkpoint:24 distinct Android methods,18 JVM/Robolectric,2 CTest, lint0errors/17warnings. AppSHA6a3013856de24a9bcc02f4902fa7a7845f5adcc90d0a5df877aa640e695aa759; testSHA2df70eaf3392d083cb7a5e45ca50d4f13d3cb87dd6a4334f09902d3a67c3181b. Windows presentation remains false; details follow.

# 0.5.0-dev.3-incomplete checkpoint (2026-10-12)

New Surface IPC and WSI capability evidence is preserved in outputs/wsi-validation. Five new POCO methods pass their explicit assertions: real Surface ownership/recreation, runtime death/rebind, loadable Box64 with unexported main, rejected raw Surface marshalling and absent Win32 WSI (-7/exit44). These expected negative diagnostics do **not** prove Windows presentation. Full current regression counts/SHAs are recorded in outputs/wsi-validation/checkpoint-summary.json. Original mistaken dlopen expectation/failure and validator digit-regex bug are retained in first-device.

No data/prefix cleanup, Wine/Box64 rebuild or qualified ZIP/GDI asset change. Native triangle, x64 ELF compute, Windows compute and normal Library/GDI are separate paths. [Exact audit](WINDOWS_VULKAN_WSI.md), [current gate](WINDOWS_VULKAN_PRESENT_VALIDATION.md), [repeatable commands](ANDROID_SURFACE_IPC.md).

Historical qualification follows:

# Current 0.4.0-dev.1 work: display qualification pending

See [WINDOWS_DISPLAY_VALIDATION.md](WINDOWS_DISPLAY_VALIDATION.md). No new Android tests ran
and no new APK was produced after the sandbox lost Gradle/ADB access. Historical evidence follows.

# Android runtime validation — 0.3.0-dev.2

Current Library/Launcher report: [ANDROID_LAUNCHER_VALIDATION.md](ANDROID_LAUNCHER_VALIDATION.md).
It records the exact APK hashes, 29 passed POCO tests, real Compose/Wine/Box64 results, regressions, failures and limitations.

Historical evidence is preserved separately:
- [0.3.0-dev.1: normal prefix and recovery](ANDROID_RUNTIME_VALIDATION_0.3-dev.1.md).
- [0.2: first Windows bootstrap execution](ANDROID_RUNTIME_VALIDATION_0.2.md).

Do not substitute Linux Wine success or compilation for Android/POCO execution. New graphics,
WoW64, games, performance tuning and arbitrary application compatibility remain unqualified.

## 0.5.0-dev.2 Vulkan bridge — 2026-10-12

New actual device qualification: x64/Bionic ELF -> Box64 -> public ARM64 Vulkan compute on Adreno840;
interpreter/dynarec/10sequential repeats/negative dispatch control. Isolated Wine11.19 Windows x64
headless compute also verified10800/exit42 under application UID, no bootstrap/root; qualified production
Wine ZIP/Box64/GDI assets unchanged. Full exact results, SHA and current regressions in
[VULKAN_ABI_VALIDATION.md](VULKAN_ABI_VALIDATION.md),
[WINDOWS_VULKAN_STATUS.md](WINDOWS_VULKAN_STATUS.md). Windows WSI/DXVK remain unimplemented.
Earlier results in this document are historical, not re-counted as dev.2 tests.
