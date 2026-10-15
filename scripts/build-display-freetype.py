#!/usr/bin/env python3
"""Pinned FreeType for translated x64/Bionic Wine, not a host Linux library."""
import argparse
import hashlib
import json
import os
import pathlib
import subprocess
import tarfile
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parents[1]

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--work', type=pathlib.Path, required=True)
    parser.add_argument('--archive', type=pathlib.Path, help='Offline pinned upstream tar.xz')
    parser.add_argument('--jobs', type=int, default=4)
    args = parser.parse_args()
    lock = json.loads((ROOT / 'third-party/runtime-lock.json').read_text())['freetypeDisplay']
    work = args.work.resolve(); work.mkdir(parents=True, exist_ok=True)
    archive = args.archive or work / f"freetype-{lock['version']}.tar.xz"
    if not archive.is_file():
        if args.archive: raise RuntimeError('Offline archive not found')
        temporary = archive.with_suffix('.download')
        with urllib.request.urlopen(lock['source'], timeout=60) as source, temporary.open('wb') as target:
            while block := source.read(1024 * 1024): target.write(block)
        temporary.replace(archive)
    if hashlib.sha256(archive.read_bytes()).hexdigest() != lock['sha256']:
        raise RuntimeError('FreeType source checksum mismatch; archive kept for inspection')
    source = work / f"freetype-{lock['version']}"
    if not source.is_dir():
        with tarfile.open(archive) as tar:
            for entry in tar.getmembers():
                path = pathlib.PurePosixPath(entry.name)
                if path.is_absolute() or '..' in path.parts or path.parts[0] != source.name:
                    raise RuntimeError('Unsafe source archive path')
                if not (entry.isfile() or entry.isdir()): raise RuntimeError('Unexpected archive link/device')
            tar.extractall(work)
    ndk = pathlib.Path(os.environ['ANDROID_NDK_HOME']).resolve()
    build = work / 'build-x64'; install = work / 'install-x64'
    subprocess.run(['cmake', '-S', str(source), '-B', str(build), '-G', 'Ninja',
        f'-DCMAKE_TOOLCHAIN_FILE={ndk}/build/cmake/android.toolchain.cmake',
        '-DANDROID_ABI=x86_64', '-DANDROID_PLATFORM=android-29',
        '-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON', '-DCMAKE_BUILD_TYPE=Release',
        '-DBUILD_SHARED_LIBS=ON', f'-DCMAKE_INSTALL_PREFIX={install}',
        '-DFT_DISABLE_ZLIB=ON', '-DFT_DISABLE_BZIP2=ON', '-DFT_DISABLE_PNG=ON',
        '-DFT_DISABLE_HARFBUZZ=ON', '-DFT_DISABLE_BROTLI=ON'], check=True)
    subprocess.run(['cmake', '--build', str(build), '-j', str(args.jobs)], check=True)
    subprocess.run(['cmake', '--install', str(build)], check=True)
    library = install / 'lib/libfreetype.so'
    readelf = ndk / 'toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf'
    elf = subprocess.check_output([str(readelf), '-h', '-d', str(library)], text=True)
    if 'Advanced Micro Devices X86-64' not in elf: raise RuntimeError('Wrong FreeType ABI')
    import re
    soname = re.findall(r'\(SONAME\).*?\[(.*?)\]', elf)
    if soname != ['libfreetype.so']:
        raise RuntimeError(f'Unexpected Android FreeType SONAME: {soname}')
    needed = re.findall(r'\(NEEDED\).*?\[(.*?)\]', elf)
    if set(needed) - {'libc.so', 'libm.so', 'libdl.so'}:
        raise RuntimeError(f'Unexpected FreeType dependencies: {needed}')
    (work / 'elf-audit.txt').write_text(elf)
    (work / 'build-identity.json').write_text(json.dumps({
        'sourceSha256': lock['sha256'], 'version': lock['version'],
        'librarySha256': hashlib.sha256(library.read_bytes()).hexdigest(),
        'abi': lock['abi'], 'needed': needed, 'testedOnAndroid': False}, indent=2)+'\n')
    print(install)

if __name__ == '__main__': main()
