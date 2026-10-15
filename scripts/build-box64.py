#!/usr/bin/env python3
"""Pinned upstream source build; does not download prebuilt executables or modify upstream code."""
import argparse, hashlib, json, pathlib, shutil, subprocess, tarfile, urllib.request
ROOT=pathlib.Path(__file__).resolve().parents[1]
p=argparse.ArgumentParser();p.add_argument('--sdk',type=pathlib.Path,required=True);p.add_argument('--work',type=pathlib.Path,required=True);p.add_argument('--jobs',type=int,default=4)
a=p.parse_args();work=a.work.resolve();work.mkdir(parents=True,exist_ok=True)
lock=json.loads((ROOT/'third-party/runtime-lock.json').read_text())['box64']
archive=work/'box64-source.tar.gz'
if not archive.exists():
    part=archive.with_suffix('.part');urllib.request.urlretrieve(lock['source'],part);part.rename(archive)
if hashlib.sha256(archive.read_bytes()).hexdigest()!=lock['sha256']:raise SystemExit('Box64 archive SHA-256 mismatch')
source=work/('box64-'+lock['commit'])
if not source.exists():
    with tarfile.open(archive) as t:
        # Python >=3.12 data filter rejects escaping paths and unsafe special files.
        t.extractall(work,filter='data')
ndk=a.sdk.resolve()/'ndk'/lock['ndk'];build=work/'build-android'
cmake=a.sdk.resolve()/'cmake/3.22.1/bin/cmake'
subprocess.run([str(cmake),'-S',str(source),'-B',str(build),'-G','Ninja',
    '-DCMAKE_TOOLCHAIN_FILE='+str(ndk/'build/cmake/android.toolchain.cmake'),
    '-DANDROID_ABI=arm64-v8a','-DANDROID_PLATFORM=android-29','-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON',
    '-DARM_DYNAREC=ON','-DNOLOADADDR=ON','-DBOX32=OFF','-DNOGIT=ON','-DCMAKE_BUILD_TYPE=Release'],check=True)
subprocess.run([str(cmake),'--build',str(build),'--target','box64','-j',str(a.jobs)],check=True)
output=ROOT/'build/box64-package';libs=output/'jniLibs/arm64-v8a';assets=output/'assets/runtime';licenses=output/'assets/licenses'
for d in (libs,assets,licenses):d.mkdir(parents=True,exist_ok=True)
binary=libs/'libbox64.so';shutil.copy2(build/'box64',binary)
subprocess.run([str(ndk/'toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip'),'--strip-debug',str(binary)],check=True)
metadata=dict(lock,binarySha256=hashlib.sha256(binary.read_bytes()).hexdigest(),status='compiled-not-device-validated')
(assets/'box64.json').write_text(json.dumps(metadata,indent=2)+'\n')
shutil.copy2(source/'LICENSE',licenses/'Box64-MIT.txt')
print('Box64 package prepared:',output)
