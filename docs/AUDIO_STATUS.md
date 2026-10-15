# Windows audio — partial device qualification

POCO: shared stereo48k polling WASAPI→AudioTrack9600 received/consumed frames,
exit42,2 rounds/cleanup PASS. User heard the Windows signal and KARLSON audio.
Fixed optional MIDI dispatch assert. No universal event/exclusive/XAudio2/mixing
qualification; game presentation still stalls. [Exact evidence](PHYSICAL_DEVICE_RECOVERY.md).
Earlier remote/evolution statuses below are historical.

# Windows audio — experimental remote candidate

An opt-in Wine Unix backend and actual Android AudioTrack transport are implemented
and compiled. Host ABI/transport unit tests2 PASS; WASAPI fixture on **stock Linux
Wine** submitted9600 frames/exit42. The new Windows→Android path has **not run on
POCO**: ADB has no online device. Endpoint/output/PCM consumption/audibility on
Android remain unconfirmed. Shared polling stereo48kHz only; no general WinMM/
XAudio2/event-driven qualification. [Exact limits and device commands](REMOTE_INPUT_AUDIO.md).

The previous qualified base remains unchanged; its historical status follows.

# Windows audio — unavailable

POCO runtime query `waveOutGetNumDevs()` returned **0**. The separate Windows x64 PCM test
printed `AUDIO_NO_ENDPOINT` and exited 77 (not an audio success). Wine reported:
`mmdevapi:init_driver No driver from "pulse,alsa,oss,coreaudio" could be initialized.`
The qualified package contains mmdevapi.dll but no winealsa/winepulse/wineoss Unix audio
backend. No Android AudioTrack/AAudio backend is wired to the Wine guest.

`tests/windows/audio.c` loads real WinMM entry points. If an endpoint exists it submits a
bounded 1-second PCM square wave twice, waits for WHDR_DONE, resets/unprepares/closes each
stream. That branch has **not run on POCO**. PCM submission, output, stop/restart, audio focus,
headphones and audible sound are all unconfirmed. No microphone permission was added.

An earlier endpoint-only probe printed the absence result but received SIGSEGV during exit.
Explicitly releasing its dynamically loaded WinMM module before ExitProcess produced a clean
77 in the repeat. The precise teardown crash cause is not established; the failure evidence
is retained. This does not repair Wine audio.

Next bounded milestone: implement a Wine Unix audio driver using the existing mmdevapi
contract and a bounded PCM transport to Android AudioTrack in the runtime service, including
format negotiation, audio focus, stop and guest-death cleanup. AAudio direct calls from the
x86 Wine guest would also need a qualified Box64 ABI path. Neither approach is implemented;
a native Android tone alone must not be presented as Windows audio.
