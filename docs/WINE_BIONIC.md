# Wine 11.19 / Bionic console probe

This is an experimental **fixed original Windows x64 console fixture**, not general EXE/game support.
Wine and Box64 run inside the private Android `:runtime` service under the application's UID.
No host Wine, remote PC, root, target SDK downgrade or SELinux change is involved.

## Build and package

Prerequisites: Linux, JDK 17, Gradle 8.13, Android SDK36, NDK27.2.12479018,
CMake3.22.1, GCC, clang with Windows PE target support, flex, bison, make, patch, curl, Python3.
This run used host clang23.1.1 and GCC16.2.1; exact local tool versions are retained in device evidence.
Build Box64 using the existing pinned recipe first. Then:

```sh
export ANDROID_NDK_HOME=/absolute/path/to/ndk/27.2.12479018
export GRADLE=/absolute/path/to/gradle-8.13/bin/gradle
scripts/build-wine-bionic.sh /absolute/path/to/wine-work
scripts/build-windows-fixtures.sh /absolute/path/to/fixtures
python3 scripts/package-wine-probe.py \
  --installed /absolute/path/to/wine-work/package/frostbox/wine \
  --exe /absolute/path/to/fixtures/console-x64.exe \
  --source /absolute/path/to/wine-work/wine-wine-11.19 \
  --strip "$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip"
./gradlew -PwithBox64=true -PwithWineProbe=true \
  :android-app:assembleDebug :android-app:assembleDebugAndroidTest
```

The APK contains an approximately 199 MiB compressed diagnostic userspace. Extraction needs about
637 MiB of private storage. This deliberately includes the full built runtime rather than an unproven
minimal dependency selection. Headers/developer libraries are not required in the APK. Tool caches and
Wine build directories are excluded from FrostBox source ZIPs. A normal build without `withWineProbe`
retains the smaller native process lab and does not show the Wine button.

Wine is cross-compiled for `x86_64-linux-android29` using NDK Bionic. Its host tools are compiled separately
with the same source version. `nls/locale.nls` is necessary for wrc; `__tooldeps__` alone was insufficient.
The loader requests `/system/bin/linker64`, with `libc.so` and `libdl.so`; Unix ntdll is an x86-64 Bionic ELF.
Box64 interprets/translates these files and wraps Android native libraries; they are not passed to kernel
execve. Native Box64 is installed by PackageManager in `nativeLibraryDir`.

## Local Wine patches

1. `wine-11.19-small-address-space.patch`: align the protection-table allocation to a host page.
   Wine assumed a sufficiently large x86-64 address space, but the ARM64 process uses a smaller range.
   The original alignment assertion remains enabled.
2. `wine-11.19-android-guest-pe-copy.patch`: use Wine's existing read/copy mapping path for Android PE
   images, retaining the shared-writable mapping semantics. Guest PE bytes are read into anonymous
   memory; file-backed executable mappings from writable private storage remain prohibited by Android.
   Box64 generates ARM instructions separately. No OS security rule is disabled.

These patches modify Wine, not Box64. Both apply to the pristine locked source and are distributed
as source alongside it. Box64's upstream source remains unmodified.

## Execution and limitations

`RuntimeProbeService` verifies the bundled ZIP digest, checks canonical extraction paths and caps total
uncompressed bytes at 1 GiB. The legacy bootstrap diagnostic prefix is `files/runtime-tests/wine-prefix`, separate
from game profiles. Native commands use explicit argv/environment without a shell. Wine's child
`posix_spawn` is routed by Box64 to APK-installed Box64, which loads guest wineserver. Diagnostics capture
stdout/stderr, native exit/signal, timeout and UID/PID. Output is bounded and dropped bytes counted.

The console probe currently uses `WINEBOOTSTRAPMODE=1` and built-in kernel32/ntdll. This is Wine's
internal prefix bootstrap lookup mode; it **does not prove ordinary prefix DLL installation**. It allows
our original PE to use real Wine Win32 API implementations. The fixture itself is unmodified and uses
GetStdHandle, WriteFile and ExitProcess, computing 1..100 at runtime. Success requires exact stdout
`FROSTBOX_PE_RESULT=5050\n`, exit42, no signal/timeout/exec error, equal app UID and a distinct runtime PID.

After completion/cancellation, service executes prefix-scoped `wineserver -k` followed by `-w` before
reporting completion; both are translated by Box64. `-k` returns1 when no server owns the lock, which is
not equivalent to a crash. The wait command must finish0. Android tests check the original child is reaped.
Registry presence is reported separately. Graceful shutdown is necessary because instrumentation force
stops the target app when the suite ends; without flushing, prefix registry files were absent.

No GUI/display bridge, WoW64 qualification, Wine network/resolver support, imported-EXE launch,
DXVK, graphics or controller-to-Windows integration is claimed. DNS support was disabled at build time
and Wine reports that explicitly. One runtime service was killed by HyperOS `normal_mem_pressure`;
repeat runs and diagnostics cannot establish long-duration stability.

## Sources and distribution

Pinned versions/digests are in `third-party/runtime-lock.json`. Wine source is
[Wine 11.19](https://github.com/wine-mirror/wine/tree/wine-11.19), primarily LGPL-2.1-or-later with
additional per-file notices. The delivered original source tarball plus both patches and this build
recipe form the corresponding source inputs. Preserve the full source archive and its per-file licenses
with binary releases. FrostBox's own code remains MIT; an APK containing Wine is not entirely MIT.
The package permits rebuilding/replacing the guest Wine component and rebuilding/reinstalling the APK.
No proprietary DLL, driver or game file is included. Windows fixture source is FrostBox MIT code.

## 0.3 managed-prefix path

The bootstrap description above remains applicable to the old diagnostic mode only. New
AndroidWineManager uses the same qualified Wine/Box64 builds and PE portability patches, provisions
the x64 system template and runs actual wineboot without WINEBOOTSTRAPMODE. Per-profile prefixes,
version checks, recovery and imported console execution are described in [PREFIX_RUNTIME.md](PREFIX_RUNTIME.md)
and [current device results](ANDROID_RUNTIME_VALIDATION.md). No upstream binary/library version
changed for this step. Native command timeout is currently 90 seconds for console operations,
15 seconds for server control; longer user sessions need an explicit configurable policy later.
