# FrostBox 0.5.0-dev.2 — exact change list

Original own code added:
- `tests/vulkan/compute.comp`, `probe.c`: actual shared ELF/PE compute shader fixture, readback,
  allocation/debug callbacks, negative dispatch control, reverse cleanup and precise exit codes.
- `scripts/build-vulkan-probes.py`: pinned NDK Bionic ELF and freestanding Win64 PE + SPIR-V/identity.
- `scripts/build-box64-vulkan.py`: separate upstream source/build/executable with manifest.
- `third-party/patches/box64-0.4.4-android-vulkan-soname.patch`: Android ALTNAME fallback only.
- `scripts/build-wine-vulkan-overlay.py`: isolated incremental Unix host-loader experiment.
- `scripts/verify-vulkan-package.py`: executable/probe/patch integrity and immutable qualified hashes.
- `scripts/validate-box64-vulkan.py`: fresh output directories, installed APK SHA, real instrumentation,
  app-owned stdout/stderr/process JSON, logcat, exit-info, process/memory snapshots and raw evidence.
- `android-app/.../runtime/Box64VulkanDeviceTest.kt`: interpreter/dynarec/10repeats/negative control,
  original SONAME control, qualified Wine control and experimental Windows compute qualification.
- `android-app/.../runtime/WineVulkanDiagnostics.kt`: explicit diagnostic-only, separate runtime view,
  prefix lock and finally kill/wait through existing Process registry.
- Four requested bridge/ABI/feasibility/Windows status documents, this change list.

Existing files extended minimally:
- `runtime/native/process_jni.cpp`: diagnostic modes/new headless diagnostic command using existing
  registry; standard AndroidWineCommand/Box64 backend reused, production Wine launch unchanged.
- `android-app/.../runtime/RuntimeProbeService.kt`: prepare verified probe, route headless diagnostics,
  preserve existing Wine paths and attach runtime identity to diagnostic failures.
- `RuntimeProbeClient.kt`: optional diagnostic mode validation, explicit successful compute verdict,
  normal BIND_IMPORTANT flag consistent with the existing Wine launcher.
- `NativeRuntime.kt`: headless diagnostic JNI declaration.
- `android-app/build.gradle.kts`: versionCode10, version0.5.0-dev.2, optional separate assets/libs,
  package integrity build gates; default UI/production runtime remains qualified.
- README RU/EN, CHANGELOG, ARCHITECTURE, third-party notices/source bundle and TODO updated.

No changes to native renderer, shaders/WSI, display/GDI driver, IME/input, Game Library or prefix
management. Qualified Wine ZIP, default Box64 and all18 display assets are byte-identical to dev.1.
No source commits/push/publication performed. Old APKs, user profiles/prefixes and prior evidence retained.
Final actual tests/artifact SHA are in VULKAN_ABI_VALIDATION.md / WINDOWS_VULKAN_STATUS.md.
