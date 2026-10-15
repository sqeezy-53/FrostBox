# Compatibility matrix

These are scoped physical-device observations, primarily POCO F8 Ultra / Android 16
/ Adreno 840. They are not a general support guarantee. Historical reports retain
older test totals; they are not all rerun on every source change.

| Component | Status | Evidence / limit |
|---|---|---|
| Windows x64 | DEVICE VERIFIED | Console 5050 / exit42, normal prefix, repeat/Stop/recovery |
| Windows x86 new WoW64 | DEVICE VERIFIED | Console, compute, D3D9, Library Stop/recovery fixtures |
| Native Android Vulkan | DEVICE VERIFIED | Real Android swapchain and PixelCopy; separate from Wine |
| Windows Vulkan compute | DEVICE VERIFIED | Result10800 / exit42, x64 and WoW64 fixtures |
| Windows Vulkan Present | DEVICE VERIFIED | Guest shared images/syncFD to host Android WSI |
| DXVK2.7.1 D3D9 x86 | DEVICE VERIFIED | Draw/Present fixture and real Mafia II frames |
| DXVK2.7.1 D3D11 x64 | DEVICE VERIFIED | Fixture and KARLSON rendering |
| GDI / Unicode / IME | DEVICE VERIFIED | Win32 fixtures and dedicated regression |
| XInput1.3/1.4/9.1.0 | PHYSICAL INPUT TESTED | 8BitDo controls, triggers, disconnect/reconnect |
| Windows audio x64/x86 | DEVICE VERIFIED | Endpoint, actual PCM/AudioTrack; physical tones heard |
| D3D12 / VKD3D-Proton | NOT INTEGRATED | No support claim |
| DirectDraw / D3D8 / D3D10 | NOT QUALIFIED | DXVK capability alone does not qualify these APIs |

## Games

| Game | Observed behavior | Classification |
|---|---|---|
| KARLSON official Windows demo | User confirmed Tutorial movement/camera/audio; Sandbox scene changes can produce Vulkan device loss | Partial gameplay, not stable qualification |
| Mafia II original2010 | Menu reached once, physical8BitDo menu input and audible music confirmed; repeated boots stall at intro | Menu milestone only; level/gameplay/FPS NOT VERIFIED |

The original bundled PhysX installer was used only with the user's private Mafia II
installation/prefix. No installer/game binary is distributed. Intro logo visibility
is not proof that PhysX causes the remaining stall.

[Empire Bay report](EMPIRE_BAY_VALIDATION.md) · [physical input/audio report](PHYSICAL_DEVICE_RECOVERY.md).
