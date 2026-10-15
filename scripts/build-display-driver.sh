#!/usr/bin/env bash
# Isolated overlay; never rebuild/repackage the qualified base Wine ZIP.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
BASE=${1:?Usage: build-display-driver.sh WINE_WORK_DIRECTORY}
BASE=$(realpath "$BASE")
DISPLAY_WORK=${2:-"$BASE/../wine-display"}
mkdir -p "$DISPLAY_WORK"
DISPLAY_WORK=$(realpath "$DISPLAY_WORK")
: "${ANDROID_NDK_HOME:?Set NDK path}"
NDK_BIN="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin"
if [[ ! -d "$DISPLAY_WORK/source" ]]; then cp -a --reflink=auto "$BASE/wine-wine-11.19" "$DISPLAY_WORK/source"; fi
cp -a "$ROOT/runtime/display/winefrost.drv" "$DISPLAY_WORK/source/dlls/"
python3 - "$DISPLAY_WORK/source" <<'PY'
import sys,pathlib
root=pathlib.Path(sys.argv[1]);p=root/'configure.ac';s=p.read_text()
if 'WINE_CONFIG_MAKEFILE(dlls/winefrost.drv)' not in s:
 s=s.replace('WINE_CONFIG_MAKEFILE(dlls/wineandroid.drv)', 'WINE_CONFIG_MAKEFILE(dlls/wineandroid.drv)\nWINE_CONFIG_MAKEFILE(dlls/winefrost.drv)');p.write_text(s)

PY
cp "$BASE/wine-wine-11.19/programs/explorer/desktop.c" "$DISPLAY_WORK/source/programs/explorer/desktop.c"
patch --silent -d "$DISPLAY_WORK/source" -p1 < "$ROOT/runtime/display/explorer-display-selection.patch"
(cd "$DISPLAY_WORK/source" && autoconf)
FREETYPE_WORK=${FREETYPE_WORK:-"$DISPLAY_WORK/freetype"}
python3 "$ROOT/scripts/build-display-freetype.py" --work "$FREETYPE_WORK" --jobs "${FROST_JOBS:-6}"
FREETYPE_WORK=$(realpath "$FREETYPE_WORK")
FT_INSTALL="$FREETYPE_WORK/install-x64"
mkdir -p "$DISPLAY_WORK/build"
cd "$DISPLAY_WORK/build"
CC="$NDK_BIN/x86_64-linux-android29-clang" CXX="$NDK_BIN/x86_64-linux-android29-clang++" AR="$NDK_BIN/llvm-ar" RANLIB="$NDK_BIN/llvm-ranlib" GRADLE="$ROOT/gradlew" PKG_CONFIG=/usr/bin/false FREETYPE_CFLAGS="-I$FT_INSTALL/include/freetype2" FREETYPE_LIBS="-L$FT_INSTALL/lib -lfreetype" LDFLAGS='-Wl,-z,max-page-size=16384' ../source/configure --enable-win64 --disable-tests --host=x86_64-linux-android --disable-wineandroid.drv --with-wine-tools="$BASE/host-tools" --prefix=/frostbox/wine --without-x --without-wayland --without-opengl --without-vulkan --without-gstreamer --without-alsa --without-pulse --without-oss --without-cups --without-sane --without-gphoto --without-pcap --without-gnutls --without-krb5 --without-fontconfig --with-freetype --without-gssapi --without-dbus --without-sdl --without-udev --without-usb --without-opencl --without-netapi > ../configure.log 2>&1
grep -q '^#define SONAME_LIBFREETYPE "libfreetype.so' include/config.h || { echo "Wine FreeType backend absent" >&2; exit 1; }
make -j"${FROST_JOBS:-6}" dlls/winefrost.drv/all programs/explorer/all dlls/win32u/all > ../build.log 2>&1
OUT="$ROOT/build/display-package/assets/display"
mkdir -p "$OUT/x86_64/lib/wine/x86_64-unix" "$OUT/x86_64/lib/wine/x86_64-windows" "$OUT/share/wine/fonts" "$OUT/licenses"
cp dlls/winefrost.drv/winefrost.so dlls/win32u/win32u.so "$OUT/x86_64/lib/wine/x86_64-unix/"
# Android's CMake FreeType build uses libfreetype.so; Wine config must agree.
cp -L "$FT_INSTALL/lib/libfreetype.so" "$OUT/x86_64/lib/wine/x86_64-unix/"
cp dlls/winefrost.drv/x86_64-windows/winefrost.drv programs/explorer/x86_64-windows/explorer.exe "$OUT/x86_64/lib/wine/x86_64-windows/"
for FONT in tahoma tahomabd ms_sans_serif fixedsys marlett; do
    cp "$DISPLAY_WORK/source/fonts/$FONT.ttf" "$OUT/share/wine/fonts/"
done
cp "$FREETYPE_WORK/freetype-2.14.3/LICENSE.TXT" "$OUT/licenses/FreeType-LICENSE.txt"
cp "$FREETYPE_WORK/freetype-2.14.3/docs/FTL.TXT" "$OUT/licenses/FreeType-FTL.txt"
cp "$ROOT/third-party/licenses/Wine-fonts-NOTICE.txt" "$OUT/licenses/Wine-fonts-NOTICE.txt"
cp "$DISPLAY_WORK/source/COPYING.LIB" "$OUT/licenses/Wine-LGPL.txt"
"$NDK_BIN/llvm-strip" --strip-debug "$OUT/x86_64/lib/wine/x86_64-unix/"*.so
# Loading the PE DLL succeeds even when its Unix init was accidentally hidden.
# Require the exported init and reject the Android TLS resolver unsupported by this backend.
ELF_AUDIT=$("$NDK_BIN/llvm-readelf" --dyn-syms "$OUT/x86_64/lib/wine/x86_64-unix/winefrost.so")
grep -q 'GLOBAL.*DEFAULT.*__wine_unix_lib_init' <<< "$ELF_AUDIT" || { echo "Display Unix init is not exported" >&2; exit 1; }
if grep -q '__tls_get_addr' <<< "$ELF_AUDIT"; then echo "Unsupported driver TLS dependency" >&2;exit 1;fi
python3 - "$OUT" <<'PYMANIFEST'
import pathlib,hashlib,json,sys
p=pathlib.Path(sys.argv[1])
# Whitelist prevents stale files from previous prototype layouts entering the package.
names=['x86_64/lib/wine/x86_64-unix/'+n for n in ('winefrost.so','win32u.so','libfreetype.so')]
names+=['x86_64/lib/wine/x86_64-windows/'+n for n in ('winefrost.drv','explorer.exe')]
names+=['share/wine/fonts/'+n+'.ttf' for n in ('tahoma','tahomabd','ms_sans_serif','fixedsys','marlett')]
names+=['licenses/'+n for n in ('FreeType-LICENSE.txt','FreeType-FTL.txt','Wine-LGPL.txt','Wine-fonts-NOTICE.txt')]
files={n:hashlib.sha256((p/n).read_bytes()).hexdigest() for n in names}
(p/'manifest.json').write_text(json.dumps({'schema':2,'wine':'11.19','protocol':1,'freetype':'2.14.3','license':'Wine LGPL-2.1-or-later; FreeType FTL; font per-file notices','files':files},indent=2)+'\n')
PYMANIFEST

python3 "$ROOT/scripts/verify-display-package.py" "$OUT"
