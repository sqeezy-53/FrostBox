# Native runtime development slice — 0.2.0-dev.2

Current device report: [ANDROID_RUNTIME_VALIDATION.md](ANDROID_RUNTIME_VALIDATION.md).

## Evidence boundaries

| Path | Written / compiled | Execution evidence |
|---|---|---|
| Kotlin → private `:runtime` service → JNI → process manager | Android ARM64 APK | Four basic tests passed on POCO |
| C++ Process → native helper | Host + Android | Linux and POCO lifecycle tests passed |
| C++ WineRuntimeManager → Wine 11.19 → original x64 PE | Host | stdout `FROSTBOX_PE_RESULT=5050`, exit 42 |
| Same manager → original x86 PE | Host | stdout `FROSTBOX_PE_RESULT=5050`, exit 42 |
| Box64 0.4.4 ARM64/Bionic | Built and packaged from pinned source | Interpreter/dynarec and Bionic probe verified on POCO |
| Box64 → Linux x86-64 ELF fixture | UI/JNI and instrumentation path compiled | 20 translated runs passed on POCO |
| Box64 → Wine → PE on Android | Optional Bionic bootstrap probe | 5050 / exit42;20 repeats on POCO |
| Windows window / Vulkan WSI / DXVK / game | NOT integrated | No result |

The host Wine tests contain no CPU translation: host CPU is x86-64. The native Linux execution
of `compute-x64.elf` checks the fixture itself, not Box64. No button launches imported user EXEs.

## Chosen architecture and alternatives

Choose a staged hybrid (variant C, using variant A's Box64/Wine roles): Kotlin owns UI/profile/lifecycle,
a non-exported bound Android service owns native sessions, APK-installed Bionic executables run in child
processes with explicit argv/environment, and the translator loads guest ELF/PE data separately.

Evidence for this choice: unmodified Box64 v0.4.4 compiled with NDK r27c into an ARM64 PIE with
`/system/bin/linker64`, only libc.so/libm.so/libdl.so DT_NEEDED, and 16 KiB LOAD alignment.
A generic ARM64 dynarec build is used, not a marketing-model-specific optimization preset.
Wine's Android-compatible userspace/build is NOT selected yet. A desktop glibc Wine tarball is not
silently accepted as Bionic-compatible. This slice intentionally stops before inventing that bridge.

Alternative A with full glibc userspace requires a separately packaged loader, compatible guest/native
library boundaries, TLS/signal behavior and graphics thunking; mixing glibc and Bionic in one link namespace
is not a valid shortcut. Alternative B (ARM64 Wine + FEX/WoW64 translator) needs a supported Wine CPU
interface and an Android-capable FEX environment. FEX is Linux/rootfs-oriented; it is not a drop-in JNI library.
No unused FEX adapter has been added. X11, Wayland and Wine Android driver remain display candidates,
not selected working integrations. Vulkan enumeration is NOT evidence of Windows surface creation.

## Process implementation

`runtime/process` is C++20, shared by the host runner and Android JNI. It provides PID/running/exit/signal,
separate bounded stdout/stderr, exec errno, cancellation, timeout, elapsed time and wait4 resource metrics.
Environment is explicit (not inherited); argv is passed directly to execve. No shell or process-global chdir.

Before fork all arguments and environment memory are allocated. Child uses only async-signal-safe
operations / Linux calls, resets signal dispositions and mask, creates its own process group, redirects
stdin to /dev/null, closes inherited descriptors, sets cwd and execs. It performs no JNI, allocation,
logging or C++ exception handling. Android uses public close() for FD cleanup; the optional close_range
host optimization is disabled there to avoid assuming an allowed Android seccomp syscall.

CLOEXEC error pipe distinguishes exec failure from a legitimate exit 127. Nonblocking pipes are drained
with per-poll work bounds and truncation counters, so an output flood cannot deadlock or grow RAM forever.
SIGTERM escalates to SIGKILL after 250 ms. waitid(WNOWAIT) reserves the leader PID until group cleanup,
then wait4 reaps it. RAII kills/reaps a still-owned child. Linux parent-death signal protects the direct child.

This is NOT complete process containment. Descendants that escape the process group/session are not
covered by group cleanup or parent-death signals. Wine shutdown is separately scoped by a private prefix
and its wineserver. Memory/CPU metrics cover the direct child, not all Wine processes; peak RSS is not
instantaneous RAM. Uninterruptible kernel waits cannot be given an absolute cleanup deadline.

## Wine manager

`runtime/wine/WineRuntimeManager` holds an exclusive prefix lock, runs wineboot, waits for wineserver
completion/registry flush, verifies real registry files, executes EXEs and shuts down only that prefix's
server. A real bug was found: wineboot exit 0 preceded registry persistence. Waiting for server completion
fixed the fresh-prefix test. Mere directory presence is never reported as initialized.

The original test source `tests/windows/console.c` calls Win32 WriteFile and ExitProcess and computes 1..100.
Clang + llvm-dlltool + lld-link produce separate x64 and x86 PE files without CRT/SDK headers. Host Wine
11.19 ran both through this manager. Wineboot emitted host graphics/environment warnings; these were
retained, not treated as evidence of working graphics. The optional Bionic console probe now distributes built Wine binaries with corresponding source; see WINE_BIONIC.md.

The Wine manager accepts only explicitly configured installation paths; its host path is not exposed as
an Android capability. Production Android prefix management still requires a qualified Wine package,
DLL dependency graph, wineserver launch semantics, signals/TLS tests and an untrusted-code storage design.

## Android bridge

RuntimeProbeClient → Messenger → non-exported RuntimeProbeService (`android:process=":runtime"`) → JNI
handle registry → Process/Box64Backend. Only four fixed diagnostic modes exist. Native startup/polling and
cleanup run on Dispatchers.IO. Android diagnostic output is capped at 8 KiB per stream so escaped JSON
remains below Binder transaction limits. UI receives snapshots at 100 ms while a bounded test is running. Closing
the client unbinds the service; cancellation/destruction releases the native session.

APK uses legacy native packaging so Android installs PIE executables named `lib*.so` into nativeLibraryDir.
They are executed as PIE, never dlopen'ed as shared libraries. Code is not extracted to writable filesDir
and chmod'ed. The guest fixture is copied as data, checksum verified and passed to Box64 as an argument.
Packaging and manifest inspection passed; actual exec permissions under HyperOS SELinux remain a device gate.

Box64 interpreter and dynarec tests are distinct. Both expect the exact built-in ELF output and exit 42.
DynaCache writes are disabled in these diagnostic runs. No affinity, priority, JIT memory-policy overrides,
root, binfmt_misc registration or thermal safety changes are used. A compiled dynarec does not prove
executable memory/signals/cache invalidation work on Android; run both modes on the device before proceeding.

## Reproduce on Linux

Requires the Alpha 0.1 toolchain, Python >=3.12, Ninja, host Clang/LLD/llvm-dlltool for PE fixtures, and
host Wine only for the explicitly separate Linux test. Use JDK 17, not the system JDK 27.

```bash
export JAVA_HOME=/path/to/jdk-17
export ANDROID_HOME=/path/to/android-sdk
export PATH="$JAVA_HOME/bin:$PATH"
python3 scripts/build-box64.py --sdk "$ANDROID_HOME" --work "$PWD/build/box64-source" --jobs 4
./gradlew -PwithBox64=true :core:test :android-app:testDebugUnitTest \
  :android-app:assembleDebug :android-app:assembleDebugAndroidTest :android-app:lintDebug
cmake -S runtime/native -B build/native -G Ninja -DCMAKE_BUILD_TYPE=Debug
cmake --build build/native -j4
ctest --test-dir build/native --output-on-failure
scripts/build-windows-fixtures.sh "$PWD/build/windows-fixtures"
python3 benchmarks/run-host-wine.py --runner "$PWD/build/native/frostbox_wine_smoke" \
  --wine /usr/bin/wine --wineserver /usr/bin/wineserver \
  --profile "$PWD/build/private-wine-profile" --fixtures "$PWD/build/windows-fixtures" \
  --output "$PWD/build/host-wine-result.json"
```

Without `-PwithBox64=true`, the APK has only the original native helper and reports a missing Box64 package
on its CPU tests. With the property, packaging checks both source-lock metadata and binary SHA-256.
Source archive URL, commit and SHA-256 live in `third-party/runtime-lock.json`. No upstream source patches.
Upstream wrapper-table generation can modify generated files during compilation; this is not a local patch.
MIT notice is packaged with Box64. Upstream contribution policies still apply to any future upstream PR.

## POCO / ADB gate

```bash
adb devices -l
scripts/test-device.sh YOUR_ADB_SERIAL
```

This installs both APKs and runs 4 instrumentation tests as the app UID: native process, Box64 interpreter,
Box64 dynarec, cancellation. Missing Box64 is a failure, not a skipped success. Compilation of the test APK
is not test execution. dev.2 exports structured UID/PID results and releases JNI handles before completion.
A separate 20-launch stress class is gated by a passing baseline in the validation script. Running the executable from adb shell alone would not validate app-UID policy.

For manual inspection open Runtime, run the tests in order, save diagnostics in Settings. Record Android
fingerprint, page size (`adb shell getconf PAGESIZE`), actual GPU/driver and results. First isolate native
exec errors, then interpreter errors, then JIT-specific errors. Do not change SELinux or lower target SDK.
Keep all imported user EXEs disabled until a real Android Wine test passes.

## Required next gate

1. Run the four tests on ARM64 Android; resolve exact exec/JIT failures from real logs.
2. Qualify the Wine userspace and ABI using minimal Unix dependencies and thread/TLS/signal tests.
3. Implement packaged Wine launch and prefix service lifecycle, then run the same x64 PE on Android.
4. Independently test x86/WoW64; do not infer it from desktop Wine results.
5. Only then prototype a Windows display bridge, Vulkan WSI and D3D9/11. DXVK, Turnip and frame pacing
   must not conceal a failing console runtime. Long-session 10/30/60-minute benchmarks wait for real frames.

Primary references: [Box64 pinned source](https://github.com/ptitSeb/box64/tree/2f130fab1d6e1a4ee8a71dc60cfdfcc839ad192a),
[Android execution restrictions](https://developer.android.com/about/versions/10/behavior-changes-10),
[Bionic status](https://android.googlesource.com/platform/bionic/+/main/docs/status.md),
[linker namespaces](https://source.android.com/docs/core/architecture/vndk/linker-namespace),
[Wine](https://github.com/wine-mirror/wine), [FEX](https://github.com/FEX-Emu/FEX).
