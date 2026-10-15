# Architecture

See the [host/guest diagram](../README.md#architecture--how-it-works).

| Layer | Implementation | Responsibility |
|---|---|---|
| Android host | `android-app`, Kotlin/Compose, DataStore | Library, SAF import, profiles, controller/audio UI |
| IPC | `WineRuntimeClient`, `RuntimeProbeService` | Isolated `:runtime`, state and Surface Binder transport |
| Execution | `AndroidWineManager`, JNI, `runtime/process` | Prefix locks/journals, bounded logs, spawn/Stop/recovery |
| CPU | `runtime/cpu`, packaged Box64 ARM64 executable | x86-64 execution, Bionic library wrapping |
| Windows | Wine 11.19, optional i386 PE WoW64 overlay | Windows API, registry/system files, Windows x86 on 64-bit Unix |
| GDI | `winefrost.drv`, Android display session | Software pixels, window/input events; independent of GPU |
| GPU | Wine interface48, `libfrostvk`, Box64 Vulkan wrapper | Guest GPU commands; export images and synchronization FDs |
| Presentation | `graphics/wsi`, native Surface owner | Import shared images and present on host Android swapchain |
| Audio | `audio/wine`, Android `AudioHub` | Existing Wine mmdevapi contract to bounded PCM/AudioTrack |
| Input | Android controller/window callbacks, Wine input/HID | Real Android events to Wine keyboard/mouse/XInput consumers |

Guest Box64 exec children and the Android Binder Surface owner have different
address spaces. No Java Surface or Vulkan object pointer is sent as an IPC handle.
GPU transport uses same-UID endpoints, authenticated sessions and memory/sync FDs.
The host owns Surface generations and the Android swapchain lifecycle.

Runtime packages are hash-identified read-only inputs. Mutable Wine prefixes and
imported game files are distinct private data. Current overlays still duplicate
many files; [storage remediation](STORAGE.md) must preserve this boundary.
UI does not directly spawn native executables. All production Play/Stop uses the
existing launcher/runtime IPC and native process registry.

[Historical implementation notes](history/ARCHITECTURE-pre-foundation.md) describe
successive experiments; the current model above takes precedence.

## In-game UI boundary

[Game Experience](GAME_EXPERIENCE.md) retains the same AndroidView/SurfaceView while
a Compose sibling panel opens. Host layout/cropping and its inverse coordinate
transform are independent of the fixed GPU output buffer. A neutral controller gate
protects Wine from panel navigation; Resume restores input focus. Atomic DataStore
record updates do not acquire the active game's content lock or change prefix identity.
