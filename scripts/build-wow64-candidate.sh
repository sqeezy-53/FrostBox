#!/usr/bin/env bash
# Existing Wine source and host tools only; independent dual-PE/Bionic build.
set -euo pipefail
FROST_REPO=$(cd "$(dirname "$0")/.." && pwd)
: "${ANDROID_NDK_HOME:?NDK 27.2.12479018 required}"
: "${FROST_WINE_SOURCE:?Path to the pinned patched Wine 11.19 source}"
: "${FROST_WINE_TOOLS:?Path to matching Wine host tools}"
: "${GRADLE:?Absolute Gradle executable required by Wine Android configure}"
FROST_WOW_WORK=$(realpath -m "${1:?Usage: build-wow64-candidate.sh SEPARATE_WORK_DIRECTORY}")
FROST_NDK_BIN="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin"
mkdir -p "$FROST_WOW_WORK/build"
cd "$FROST_WOW_WORK/build"
if [[ ! -f Makefile ]]; then
    CC="$FROST_NDK_BIN/x86_64-linux-android29-clang" CXX="$FROST_NDK_BIN/x86_64-linux-android29-clang++" \
    AR="$FROST_NDK_BIN/llvm-ar" RANLIB="$FROST_NDK_BIN/llvm-ranlib" PKG_CONFIG=/usr/bin/false \
    LDFLAGS='-Wl,-z,max-page-size=16384' i386_CC=clang x86_64_CC=clang \
    "$FROST_WINE_SOURCE/configure" --enable-archs=i386,x86_64 --disable-tests --host=x86_64-linux-android \
    --disable-wineandroid.drv --with-wine-tools="$FROST_WINE_TOOLS" --prefix=/frostbox/wine \
    --without-x --without-wayland --without-opengl --without-vulkan --without-gstreamer \
    --without-alsa --without-pulse --without-oss --without-cups --without-sane --without-gphoto \
    --without-pcap --without-gnutls --without-krb5 --without-fontconfig --without-freetype \
    --without-gssapi --without-dbus --without-sdl --without-udev --without-usb --without-opencl --without-netapi \
    > "$FROST_WOW_WORK/configure.log" 2>&1
fi
make -j"${FROST_JOBS:-6}" > "$FROST_WOW_WORK/build.log" 2>&1
python3 "$FROST_REPO/scripts/package-wow64-overlay.py" --build "$FROST_WOW_WORK/build" \
    --fixture "$FROST_REPO/build/windows-fixtures/console-x86.exe"
