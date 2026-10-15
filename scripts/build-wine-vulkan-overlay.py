#!/usr/bin/env python3
"""Incremental, isolated win32u Vulkan host-loader experiment from the qualified display build.
The source/build are reflink copies (never hardlinks); the qualified ZIP and display assets are not modified.
"""
import argparse,hashlib,json,pathlib,shutil,subprocess,re
ROOT=pathlib.Path(__file__).resolve().parents[1]
p=argparse.ArgumentParser();p.add_argument('--qualified-work',type=pathlib.Path,required=True);p.add_argument('--work',type=pathlib.Path,required=True);p.add_argument('--sdk',type=pathlib.Path,required=True);p.add_argument('--jobs',type=int,default=4)
a=p.parse_args();original=a.qualified_work.resolve();work=a.work.resolve();work.mkdir(parents=True,exist_ok=True)
for name in ['source','build']:
 if not (work/name).exists():subprocess.run(['cp','-a','--reflink=auto',str(original/name),str(work/name)],check=True)
config=work/'build/include/config.h';content=config.read_text()
assert re.search(r'^.*SONAME_LIBVULKAN.*$',content,re.M)
content=re.sub(r'^.*SONAME_LIBVULKAN.*$', '#define SONAME_LIBVULKAN "libvulkan.so.1"',content,flags=re.M)
config.write_text(content)
assert re.search(r'^#define SONAME_LIBVULKAN "libvulkan.so.1"$',config.read_text(),re.M)
# Wine's generated Vulkan thunks already exist in this pinned build. Only win32u Unix host code changes.
subprocess.run(['make','-j'+str(a.jobs),'dlls/win32u/win32u.so'],cwd=work/'build',check=True)
out=ROOT/'build/wine-vulkan-package/assets/wine-vulkan';out.mkdir(parents=True,exist_ok=True)
library=out/'win32u.so';shutil.copy2(work/'build/dlls/win32u/win32u.so',library)
ndk=a.sdk.resolve()/'ndk/27.2.12479018/toolchains/llvm/prebuilt/linux-x86_64/bin'
subprocess.run([str(ndk/'llvm-strip'),'--strip-debug',str(library)],check=True)
symbols=subprocess.check_output([str(ndk/'llvm-strings'),str(library)],text=True)
assert 'libvulkan.so.1' in symbols and 'Wine was built without Vulkan support.' not in symbols
(out/'manifest.json').write_text(json.dumps({'schema':1,'wineVersion':'11.19','wineCommit':'455e3509b98a6919fd4ad1def4803e08c41c03b2','driverInterface':48,'license':'LGPL-2.1-or-later','soname':'libvulkan.so.1','win32uSha256':hashlib.sha256(library.read_bytes()).hexdigest(),'qualifiedZipSha256':hashlib.sha256((ROOT/'build/wine-probe-package/assets/runtime/wine-probe.zip').read_bytes()).hexdigest(),'configurationChange':'SONAME_LIBVULKAN in an isolated include/config.h; headless only','status':'compiled-not-device-validated'},indent=2)+'\n')
print('Experimental Wine overlay:',out)
