#!/usr/bin/env python3
"""Isolated Android SONAME fallback build. Never overwrite qualified Box64 or Wine packages."""
import argparse,hashlib,json,pathlib,shutil,subprocess,tarfile
ROOT=pathlib.Path(__file__).resolve().parents[1]
p=argparse.ArgumentParser();p.add_argument('--sdk',type=pathlib.Path,required=True);p.add_argument('--archive',type=pathlib.Path,required=True);p.add_argument('--work',type=pathlib.Path,required=True);p.add_argument('--jobs',type=int,default=4)
a=p.parse_args();work=a.work.resolve();work.mkdir(parents=True,exist_ok=True)
lock=json.loads((ROOT/'third-party/runtime-lock.json').read_text())['box64']
assert hashlib.sha256(a.archive.read_bytes()).hexdigest()==lock['sha256']
source=work/('box64-'+lock['commit'])
if not source.exists():
 with tarfile.open(a.archive) as t:t.extractall(work,filter='data')
patch=ROOT/'third-party/patches/box64-0.4.4-android-vulkan-soname.patch'
patched=source/'src/wrapped/wrappedvulkan.c'
if '#define ALTNAME "libvulkan.so"' not in patched.read_text():subprocess.run(['patch','-p1','-i',str(patch)],cwd=source,check=True)
ndk=a.sdk.resolve()/'ndk'/lock['ndk'];cmake=a.sdk.resolve()/'cmake/3.22.1/bin/cmake';build=work/'build-android'
subprocess.run([str(cmake),'-S',str(source),'-B',str(build),'-G','Ninja','-DCMAKE_TOOLCHAIN_FILE='+str(ndk/'build/cmake/android.toolchain.cmake'),'-DANDROID_ABI=arm64-v8a','-DANDROID_PLATFORM=android-29','-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON','-DARM_DYNAREC=ON','-DNOLOADADDR=ON','-DBOX32=OFF','-DNOGIT=ON','-DCMAKE_BUILD_TYPE=Release'],check=True)
subprocess.run([str(cmake),'--build',str(build),'--target','box64','-j',str(a.jobs)],check=True)
out=ROOT/'build/box64-vulkan-package';libs=out/'jniLibs/arm64-v8a';assets=out/'assets/runtime';libs.mkdir(parents=True,exist_ok=True);assets.mkdir(parents=True,exist_ok=True)
binary=libs/'libbox64_vulkan.so';shutil.copy2(build/'box64',binary)
subprocess.run([str(ndk/'toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip'),'--strip-debug',str(binary)],check=True)
(assets/'box64-vulkan.json').write_text(json.dumps({'schema':1,'version':lock['version'],'commit':lock['commit'],'license':'MIT','sourceSha256':lock['sha256'],'patchSha256':hashlib.sha256(patch.read_bytes()).hexdigest(),'qualifiedBox64Sha256':hashlib.sha256((ROOT/'build/box64-package/jniLibs/arm64-v8a/libbox64.so').read_bytes()).hexdigest(),'binarySha256':hashlib.sha256(binary.read_bytes()).hexdigest(),'status':'compiled-not-device-validated'},indent=2)+'\n')
print('Separate experimental Box64:',binary)
