#!/usr/bin/env python3
"""Isolated GDI keyboard/mouse driver only. Neither base ZIP nor WSI tree is modified."""
import argparse,pathlib,subprocess,shutil,json,hashlib
root=pathlib.Path(__file__).resolve().parents[1]
p=argparse.ArgumentParser();p.add_argument('--source-work',type=pathlib.Path,required=True);p.add_argument('--work',type=pathlib.Path,required=True);a=p.parse_args();work=a.work.resolve();work.mkdir(exist_ok=True,parents=True)
for name in ['source','build']:
 if not (work/name).exists():subprocess.run(['cp','-a','--reflink=auto',str(a.source_work.resolve()/name),str(work/name)],check=True)
source=work/'source/dlls/winefrost.drv/frost.c';text=(root/'runtime/display/winefrost.drv/frost.c').read_text()
assert 'vulkan.c.inc' not in text
if source.read_text()!=text:source.write_text(text)
subprocess.run(['make','-j4','dlls/winefrost.drv/winefrost.so'],cwd=work/'build',check=True)
out=root/'build/wine-input-package/assets/wine-keyboard';out.mkdir(exist_ok=True,parents=True)
shutil.copy2(work/'build/dlls/winefrost.drv/winefrost.so',out/'winefrost.so')
(out/'manifest.json').write_text(json.dumps({'schema':1,'wine':'11.19','driverInterface':48,'status':'experimental-keyboard-mouse','files':{'winefrost.so':hashlib.sha256((out/'winefrost.so').read_bytes()).hexdigest()}},indent=2)+'\n')
print(out)
