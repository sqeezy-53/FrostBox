#!/usr/bin/env python3
"""Isolated experimental Wine interface48 + x64/Bionic WSI provider. No base ZIP edits."""
import argparse,hashlib,json,pathlib,subprocess,shutil,re
ROOT=pathlib.Path(__file__).resolve().parents[1]
p=argparse.ArgumentParser();p.add_argument('--source-work',type=pathlib.Path,required=True);p.add_argument('--work',type=pathlib.Path,required=True);p.add_argument('--sdk',type=pathlib.Path,required=True);a=p.parse_args()
work=a.work.resolve();work.mkdir(parents=True,exist_ok=True)
for part in ['source','build']:
 if not (work/part).exists():subprocess.run(['cp','-a','--reflink=auto',str(a.source_work.resolve()/part),str(work/part)],check=True)
def write_changed(p,text):
 if not p.exists() or p.read_text()!=text:p.write_text(text)
config=work/'build/include/config.h';write_changed(config,re.sub(r'^.*SONAME_LIBVULKAN.*$','#define SONAME_LIBVULKAN "libfrostvk.so"',config.read_text(),flags=re.M))
source=(ROOT/'runtime/display/winefrost.drv/frost.c').read_text()
source=source.replace('static const struct user_driver_funcs', '#include "vulkan.c.inc"\n\nstatic const struct user_driver_funcs')
assert '#include "vulkan.c.inc"' in source
source=source.replace('.pCreateDesktop', '.pVulkanInit = frost_vulkan_init,\n    .pCreateClientSurface = frost_create_client,\n    .pCreateDesktop',1)
write_changed(work/'source/dlls/winefrost.drv/frost.c',source)
include=work/'source/dlls/winefrost.drv/vulkan.c.inc'
include_text=(ROOT/'runtime/display/wine-wsi/vulkan.c.inc').read_text()
include_changed=not include.exists() or include.read_text()!=include_text
write_changed(include,include_text)
if include_changed:(work/'source/dlls/winefrost.drv/frost.c').touch()
sysparams=work/'source/dlls/win32u/sysparams.c'
if '*metric = (TEXTMETRICW){.tmHeight = -1};' not in sysparams.read_text():
 subprocess.run(['patch','--fuzz=0','-p1','-i',str(ROOT/'third-party/patches/wine/win32u-text-metrics-initialization.patch')],cwd=work/'source',check=True)
subprocess.run(['make','-j4','dlls/win32u/win32u.so','dlls/winefrost.drv/winefrost.so'],cwd=work/'build',check=True)
out=ROOT/'build/wine-wsi-package/assets/wine-wsi';out.mkdir(parents=True,exist_ok=True)
ndk=a.sdk.resolve()/'ndk/27.2.12479018/toolchains/llvm/prebuilt/linux-x86_64/bin'
for name,path in [('win32u.so','dlls/win32u/win32u.so'),('winefrost.so','dlls/winefrost.drv/winefrost.so')]:
 shutil.copy2(work/'build'/path,out/name);subprocess.run([str(ndk/'llvm-strip'),'--strip-debug',str(out/name)],check=True)
subprocess.run([str(ndk/'x86_64-linux-android29-clang'),'-std=c11','-D_GNU_SOURCE','-O1','-g','-Wall','-Wextra','-Werror','-Wno-missing-field-initializers','-fPIC','-shared','-Wl,-Bsymbolic','-Wl,-z,max-page-size=16384','-I'+str(ROOT/'graphics/wsi'),str(ROOT/'graphics/wsi/guest_wsi.c'),'-ldl','-o',str(out/'libfrostvk.so')],check=True)
files={n:hashlib.sha256((out/n).read_bytes()).hexdigest() for n in ['win32u.so','winefrost.so','libfrostvk.so']}
(out/'manifest.json').write_text(json.dumps({'schema':1,'wine':'11.19','driverInterface':48,'files':files,'status':'experimental-not-qualified'},indent=2)+'\n')
fixture=ROOT/'build/windows-vulkan-triangle';subprocess.run(['python3',str(ROOT/'scripts/build-windows-vulkan-triangle.py'),'--sdk',str(a.sdk),'--output',str(fixture)],check=True)
assets=out.parent/'runtime';assets.mkdir(exist_ok=True)
shutil.copy2(fixture/'windows-vulkan-triangle-x64.exe',assets/'windows-vulkan-triangle-x64.exe')
shutil.copy2(fixture/'windows-vulkan-triangle-x64.json',assets/'windows-vulkan-triangle-x64.exe.json')
print(out)
