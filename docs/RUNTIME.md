# Runtime boundaries

Production Library launches go through `LibraryLauncher` / `WineRuntimeClient`,
the Binder runtime service, `AndroidWineManager`, JNI and the native process registry.
Use the existing process manager for new operations.

Wine base ZIP remains immutable. Separate checked display, WSI, input, audio and
WoW64 overlays are assembled under private roots. New WoW64 mode adds i386 Windows
PE files to the 64-bit Wine Unix/Bionic userspace; Box64 remains ARM64 host code.
Android-native executables live in the APK nativeLibraryDir because writable app
home is not a supported host executable installation path for modern targets.

Prefixes use locks, initialization journals, version/integrity markers and explicit
wineserver shutdown. Recovery validates process identity before acting. Logs are
bounded. Mutable game/prefix data must never be hard-linked into shared caches.
No automatic execution follows file import.

[Prefix design](PREFIX_RUNTIME.md) · [native manager](NATIVE_RUNTIME.md) · [storage](STORAGE.md).
