#!/usr/bin/env bash
# Build only a candidate. Passing this script does not qualify Wine on Android.
set -euo pipefail
: "${ANDROID_NDK_HOME:?Set ANDROID_NDK_HOME to NDK 27.2.12479018}"
: "${GRADLE:?Set GRADLE to a runnable Gradle 8.13 executable}"
FROST_WINE_SCRIPT_DIR=$(cd "$(dirname "$0")" && pwd)
FROST_WINE_WORK="${1:?Usage: build-wine-bionic.sh WORK_DIRECTORY}"
mkdir -p "$FROST_WINE_WORK"
FROST_WINE_WORK=$(realpath "$FROST_WINE_WORK")
FROST_NDK_BIN="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin"
cd "$FROST_WINE_WORK"
if [[ ! -f wine-11.19.tar.gz ]]; then
  curl --fail --location https://codeload.github.com/wine-mirror/wine/tar.gz/refs/tags/wine-11.19 -o wine-11.19.tar.gz
fi
sha256sum --check <<'SHA'
73eb0b02dfc0cb0ff4f4d8d36c5786261e9725ac91968ae4d4e4b9a498c4087f  wine-11.19.tar.gz
SHA
if [[ ! -d wine-wine-11.19 ]]; then tar -xzf wine-11.19.tar.gz; fi
for FROST_PATCH in "$FROST_WINE_SCRIPT_DIR"/../third-party/patches/wine-11.19-*.patch; do
  if patch --dry-run --silent -d wine-wine-11.19 -p1 < "$FROST_PATCH"; then
    patch -d wine-wine-11.19 -p1 < "$FROST_PATCH"
  elif ! patch --dry-run --silent --reverse -d wine-wine-11.19 -p1 < "$FROST_PATCH"; then
    echo "Unexpected Wine source state: $FROST_PATCH" >&2; exit 1
  fi
done
FROST_OPTIONS=(--enable-win64 --disable-tests --without-x --without-wayland --without-opengl --without-vulkan --without-gstreamer --without-alsa --without-pulse --without-oss --without-cups --without-sane --without-gphoto --without-pcap --without-gnutls --without-krb5 --without-fontconfig --without-freetype)
mkdir -p host-tools build-x64
cd host-tools
../wine-wine-11.19/configure "${FROST_OPTIONS[@]}" > ../configure-host.log 2>&1
make -j4 __tooldeps__ nls/locale.nls > ../host-tools.log 2>&1
cd ../build-x64
CC="$FROST_NDK_BIN/x86_64-linux-android29-clang" CXX="$FROST_NDK_BIN/x86_64-linux-android29-clang++" AR="$FROST_NDK_BIN/llvm-ar" RANLIB="$FROST_NDK_BIN/llvm-ranlib" PKG_CONFIG=/usr/bin/false LDFLAGS='-Wl,-z,max-page-size=16384' ../wine-wine-11.19/configure "${FROST_OPTIONS[@]}" --host=x86_64-linux-android --disable-wineandroid.drv --with-wine-tools="$FROST_WINE_WORK/host-tools" --prefix=/frostbox/wine --without-gssapi --without-dbus --without-sdl --without-udev --without-usb --without-opencl --without-netapi > ../configure-android.log 2>&1
make -j"${FROST_JOBS:-6}" > ../build-android.log 2>&1
make install DESTDIR="$FROST_WINE_WORK/package" > ../install-android.log 2>&1
