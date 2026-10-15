#!/usr/bin/env python3
"""One incremental Unix module build in a separate WoW64 Vulkan work tree."""
import argparse,hashlib,json,subprocess,shutil
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
p=argparse.ArgumentParser();p.add_argument('--qualified-work',type=Path,required=True);p.add_argument('--work',type=Path,required=True);p.add_argument('--sdk',type=Path,required=True);a=p.parse_args()
w=a.work.resolve();w.mkdir(parents=True,exist_ok=True)
for n in ('source','build'):
 if not (w/n).exists():subprocess.run(['cp','-a','--reflink=auto',str(a.qualified_work.resolve()/n),str(w/n)],check=True)
patch=ROOT/'third-party/patches/wine/wow64-placed-allocation-alignment.patch'
if subprocess.run(['patch','--dry-run','--silent','-p1','-i',str(patch)],cwd=w/'source').returncode==0:
 subprocess.run(['patch','--fuzz=0','-p1','-i',str(patch)],cwd=w/'source',check=True)
else:subprocess.run(['patch','--dry-run','--silent','-R','-p1','-i',str(patch)],cwd=w/'source',check=True)
subprocess.run(['make','-j4','dlls/win32u/win32u.so'],cwd=w/'build',check=True)
out=ROOT/'build/wow64-package/assets/wine-wow64';out.mkdir(parents=True,exist_ok=True)
shutil.copy2(w/'build/dlls/win32u/win32u.so',out/'win32u.so')
strip=a.sdk.resolve()/'ndk/27.2.12479018/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip'
subprocess.run([str(strip),'--strip-debug',str(out/'win32u.so')],check=True)
(out/'vulkan-overlay.json').write_text(json.dumps({'wine':'11.19','license':'LGPL-2.1-or-later','win32uSha256':hashlib.sha256((out/'win32u.so').read_bytes()).hexdigest(),'patchSha256':hashlib.sha256(patch.read_bytes()).hexdigest(),'status':'experimental-WoW64-ordinary-host-visible-allocations-only'},indent=2)+'\n')
print(out)
