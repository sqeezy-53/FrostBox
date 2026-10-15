# FrostBox 0.5.0-dev.2 — Box64 Vulkan bridge

## Actual path

Android instrumentation binds the non-exported `:runtime` under application UID. The existing JNI
Process registry execs the **separate ARM64** `nativeLibraryDir/libbox64_vulkan.so`. Box64 parses
the **x86-64 Bionic ELF** `vulkan-compute-x64.elf`, wraps libdl/libc and its existing Vulkan ABI.
`dlopen("libvulkan.so.1")` in the guest uses that wrapper. Native Android dlopen first tries the
Linux SONAME, then the Android-only ALTNAME `libvulkan.so`. Android's public loader resolves
its system Qualcomm Adreno driver inside the app's normal linker namespace. No filesystem
alias, proprietary driver redistribution, `/system` modification, root or SELinux change.
The native Android triangle renderer is independent and does not execute this guest.

Pinned Box64 0.4.4 commit `2f130fab1d6e1a4ee8a71dc60cfdfcc839ad192a` already contains Vulkan.
`src/wrapped/wrappedvulkan.c`, `wrappedvulkan_private.h`, `wrappedlib_init.h`,
`src/library.c`, `src/libtools/libdl.c`, `src/library_list.h` were inspected. Both Vulkan
names are recognized, but the wrapper originally dlopens only `libvulkan.so.1`.
The original qualified binary failed on POCO with that exact missing-library error; exit11.
Only four Android-conditional lines were added in an isolated source copy. Existing ABI,
CPU translator and production default executable are preserved. This is a FrostBox-local
experimental patch, not an upstream contribution; its automated authorship is disclosed.
Box64 upstream contribution policy does not accept predominantly AI-authored PRs.

## ABI / dispatch

`my_vkGetInstanceProcAddr` / `my_vkGetDeviceProcAddr` resolve the native symbol, choose the
registered signature/custom `my_` wrapper and use AddCheckBridge2 to expose a guest callable
entry. Calls cross SysV x64 -> AAPCS64; native-to-guest callbacks use RunFunctionFmt.
Dispatchable handles are opaque host pointers; guest process pointers share the same address
space. Non-dispatchable handles are64bit; this is not an RPC protocol or arbitrary pointer
serialization. Structure layout/conversion and callback-bearing structures still depend on
Box64's wrapper coverage. x64+Bionic is deliberate: the probe's ELF interpreter is Android's
linker64, and libc/libdl are wrapped native Bionic, not a transplanted glibc loader.

Five static callback slots per callback class are an upstream limitation, not proof of unlimited
registration/lifetime support. Allocation/reallocation/free and debug-utils callbacks in this
probe were tested. Every dynamically returned GPU function is invoked through GIPA/GDPA.
Unlisted extensions / arbitrary pNext chains / external-memory and WSI are not qualified.

## Reproduce on Linux + POCO

Run from repository root, with the same pinned offline SDK and upstream archive:

```sh
python3 scripts/build-vulkan-probes.py --sdk "$ANDROID_SDK_ROOT" --output build/vulkan-bridge
python3 scripts/build-box64-vulkan.py --sdk "$ANDROID_SDK_ROOT" \
  --archive /path/to/box64-source.tar.gz --work /path/to/separate-box64-vulkan-work
python3 scripts/verify-vulkan-package.py
./gradlew -PwithBox64=true -PwithWineProbe=true -PwithWindowsDisplay=true \
  -PwithBox64Vulkan=true :android-app:assembleDebug :android-app:assembleDebugAndroidTest
python3 scripts/validate-box64-vulkan.py YOUR_DEVICE_SERIAL --output /path/to/new-evidence
```

Optional Wine experiment additionally requires `-PwithWineVulkan=true` and the isolated package
in [WINE_VULKAN_FEASIBILITY.md](WINE_VULKAN_FEASIBILITY.md). Build integrity checks are not Android
success claims. Test runners compare the installed APK SHA with the local build and never clear data.

## Sources / licensing

- [Pinned upstream Vulkan wrapper](https://github.com/ptitSeb/box64/blob/2f130fab1d6e1a4ee8a71dc60cfdfcc839ad192a/src/wrapped/wrappedvulkan.c) — MIT, license/source in source archive.
- [Android NDK Vulkan loader](https://developer.android.com/ndk/guides/graphics/getting-started).
- [Vulkan initialization and procedure-address contracts](https://docs.vulkan.org/spec/latest/chapters/initialization.html).
- Original FrostBox C probe, shader, scripts and diagnostic integration: MIT.

The supplied APK is a debug experimental build, not a promise that arbitrary Vulkan applications work.
