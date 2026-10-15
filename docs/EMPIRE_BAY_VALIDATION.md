> Physical follow-up: the user also confirmed audible Mafia II music in the successful menu session. Repeated startup and level gameplay remain unqualified.

# OPERATION EMPIRE BAY — Mafia II menu milestone (2026-10-14)

**Physical user confirmed the original Mafia II (2010) main menu and physical
8BitDo input inside the menu on POCO F8 Ultra, Android36/ARM64/Adreno840.**
This is a menu milestone, not a playable/stable 0.7.0 release. Repeatability and
level/gameplay remain under qualification. Source game files stay unchanged.

Evidence: `../empire-bay-validation/`; immediate preservation checkpoint:
`../empire-bay-mafia2-menu-checkpoint/`. The separate x86 Library milestone stays
in `../empire-bay-x86-library-release/`; HORIZON APKs are retained.

## Implementation and initial failures

* Existing Library/IPC/Wine Process Manager now auto-selects from the real PE
  machine field. x64 stays qualified; x86 uses the checked private WoW64 overlay.
  Runtime/API strings have defaults so older profiles remain readable. An
  incompatible explicit runtime is rejected; editing EXE revalidates architecture.
* `WineWow64Package` is shared with diagnostics. GDI/WSI is composed before the
  private allocation-alignment patch. System32 and SysWOW64 are initialized with
  wineboot64, reused and validated through existing locks/journals/recovery.
  x86 imports receive their own prefix and cannot silently migrate an x64 prefix.
* Managed DXVK x32 DLLs are installed only in the private imported copy. Native
  graphics launch/initialize operations select the existing Vulkan Box64 binary;
  arbitrary EXEs use the managed launcher, not the built-in fixture exception.
* Real UI x86 D3D9: two runs, each120 Presents/42 and22 different PixelCopies,
  same prefix; Stop cancelled. x86 console repeated5050/42, Activity recreate,
  runtime death/recover and another Play passed. application UID, separate`:runtime`.
* Complete Mafia2 folder was imported with user-granted SAF:8.4GiB, EXE
  `content/pc/mafia2.exe`, working directory`content/pc`. Old Mafia record/prefix
  and KARLSON data were retained. New profile/prefix UUID:
  `<private-profile-id>`.
* Initial real game launch loaded EXE/common/APEX/PhysXLoader and enumerated
  Adreno through DXVK, then faulted at0162bc23 readingBF800000, exit5.
  Instructions adjacent to the fault call NxGetCookingLib. The prefix lacked
  PhysX Core. The original bundled PhysX9.10.0513 MSI was installed via x86
  msiexec/Library into this isolated prefix: exit0. The fault disappeared;
  D3D9 Device/swapchain and actual game frames then appeared. No DRM modification.
* x86 audio initially failed because the qualified Unix PCM module had no WoW64
  dispatch export. A **separate**`wine-audio-wow64` overlay adapts Wine11.19's
  parameter thunks; low-address NtAllocateVirtualMemory allocations prevent
  truncating PCM buffer pointers. Qualified x64 audio module remains identical.
  x86 WASAPI fixture: endpoint,9600 frames sent/consumed by actual AudioTrack,
  two Start/Stop/42, clean release; physical user heard the test signals.
  First instrumentation assertion referred to nonexistent `deviceTested` report
  field; assertions corrected to actual outputInitialized/totalReceivedFrames;
  rerun passed. The original failing harness log is retained.
* Mafia audio subsequently transmitted3389760 PCM frames and consumed3384960;
  audibility of the game itself is not separately confirmed.

## Exact saved menu configuration

Wine11.19 + qualified Box64 + checked728-file i386 overlay + private placed
mapping alignment patch + DXVK2.7.1 x32 + qualified WSI/input Unix modules +
private WoW64 audio module. Exact component hashes and private imported game/MSI
hashes are in the menu checkpoint. Proprietary DLL/MSI binaries are not bundled.

Profile: auto runtime, DXVK/d3d9,800×600, no arguments, audio enabled, XInput,
private prefix; successful environment:
`WINEDEBUG=err+all,warn+module,+frost,+frostwsi`.
Generated video config saved exactly: `0 0 800 600 0 0 0 1`.
Changing its fifth integer was a diagnostic experiment; DXVK still reports
fullscreen. We do not claim that integer is a windowed setting.

The successful test used an app-local touch and Space/Enter/Esc at20s to test
activation/intro skipping (no Android system-wide injection). It then produced
2999 Presents/64 different PixelCopies and a difficulty menu screenshot. User
independently confirmed the main menu and **physical 8BitDo** menu operation.
Exit3 followed; no crash signal. The precise cause of previous intro stalls
(focus, timing or application wait) is not proven just by that input experiment.

Later candidate GPU viewport keeps the profile aspect ratio instead of stretching
4:3 to the phone. Profile and game are both800×600; this is not a claim that a
profile forces every game's internal resolution. An installation raced APK
packaging and failed certificate verification; no user data changed. Installation
was retried only after completed build/apksigner verification. The preserved
candidate is marked separately until repeated device validation.

## Reproduction without proprietary redistribution

Build existing pinned packages (HORIZON instructions); additionally:

```bash
python3 scripts/build-wine-audio-overlay.py --wow64 --wine-work ../../work/wine-wsi --sdk ../../work/toolchain/sdk
scripts/build-empire-audio-probe.sh
# Same HORIZON Gradle flags, plus -PwithEmpireBay=true
```

Import owned original Mafia2 EXE/folder via SAF; never auto-execute on import.
Use a fresh WoW64 prefix, install the **original bundled** PhysX MSI with Wine's
x86 msiexec `/i ORIGINAL_MSI /qn /norestart /L*V LOG`, then apply saved profile.
Do not replace a user's existing prefix to reproduce this milestone. Existing
QA tests use a dedicated test DocumentsProvider and explicit gameQa flag.

```bash
adb -s YOUR_DEVICE_SERIAL shell am instrument -w -r -e class org.frostbox.app.runtime.LibraryDeviceTest#mafia2LibraryAttempt -e frostbox.gameQa true -e frostbox.gameSeconds 180 -e frostbox.skipIntro true -e frostbox.mafiaDebug 'WINEDEBUG=err+all,warn+module,+frost,+frostwsi' org.frostbox.app.test/androidx.test.runner.AndroidJUnitRunner
```

That test passing means boot attempt/cleanup recorded; **it does not assert
menu, gameplay or controller compatibility**. Those statuses require evidence
and physical confirmation. Original prefixes, games, save files and old APKs
are never deleted. No commits, uploads or publication performed.

## Subsequent repeat outcome and retained baseline

The fixed-aspect candidate rendered the intro but stalled at4 frames over180s.
The exact saved debug environment was also tested with the restored original
viewport code:16 frames over120s, still at the PhysX/legal splash. Thus neither
viewport change nor reduced logging alone explains the failure. The successful
menu remains a valid physical milestone, while reliable repeated menu entry
remains **FAIL/not qualified**. No renderer/Wine/Box64 timing patch was guessed.
The working baseline UI implementation is retained; the separate candidate APK
and WindowsScreen.kt are saved in the menu checkpoint, not labelled stable.

The interpreter control produced1783 frames/196 different PixelCopies in120s;
its last capture remained a loading screen. That is more rendering activity,
not verified menu/gameplay. Named-thread snapshots found the guest Render thread
runnable with DXVK submit/queue/cs threads waiting; this is a localized diagnostic
symptom, not an identified faulty instruction. No root or security bypass.

M1 EXE/PASS; M2 essential DLL loading/PASS after MSI; M3 window/PASS; M4 D3D9
Device/PASS; M5 real game frames/PASS; M6 main menu/PASS once (physical); M7
physical menu controller/PASS once; M8 game audible/NOT TESTED separately; M9
level/NOT REACHED in subsequent repeat; M10 scene/NOT TESTED; M11 character
control/NOT TESTED; M12 Stop/PASS, repeat menu/FAIL. Game FPS/frame-time/1%low
not measured; fixture and intro counters are not gameplay performance.
