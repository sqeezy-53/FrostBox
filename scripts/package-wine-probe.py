#!/usr/bin/env python3
"""Package a locally built Bionic Wine candidate, never a downloaded runtime."""
import argparse, hashlib, json, shutil, subprocess, tempfile, zipfile
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('--installed',type=Path,required=True);p.add_argument('--exe',type=Path,required=True);p.add_argument('--source',type=Path,required=True);p.add_argument('--strip',type=Path,required=True);a=p.parse_args()
out=Path(__file__).resolve().parents[1]/'build/wine-probe-package/assets/runtime';out.mkdir(parents=True,exist_ok=True)
with tempfile.TemporaryDirectory() as td:
    root=Path(td)
    for tree in ('x86_64','share'):
        shutil.copytree(a.installed/tree,root/tree,symlinks=False)
    shutil.copy2(a.exe,root/'console-x64.exe')
    for name in ('COPYING.LIB','LICENSE','AUTHORS'):
        if (a.source/name).is_file(): shutil.copy2(a.source/name,root/name)
    for f in root.rglob('*'):
        if f.is_file() and f.read_bytes()[:4]==b'\x7fELF': subprocess.run([str(a.strip),'--strip-debug',str(f)],check=True)
    with zipfile.ZipFile(out/'wine-probe.zip','w',zipfile.ZIP_DEFLATED,compresslevel=6) as z:
        for f in sorted(root.rglob('*')):
            if f.is_file(): z.write(f,f.relative_to(root))
sha=hashlib.sha256((out/'wine-probe.zip').read_bytes()).hexdigest()
(out/'wine-probe.json').write_text(json.dumps({'version':'11.19','status':'unqualified Bionic candidate','sha256':sha,'sourceCommit':'455e3509b98a6919fd4ad1def4803e08c41c03b2','sourceSha256':'73eb0b02dfc0cb0ff4f4d8d36c5786261e9725ac91968ae4d4e4b9a498c4087f','fixtureSha256':hashlib.sha256(a.exe.read_bytes()).hexdigest(),'abi':'x86_64-linux-android29','license':'LGPL-2.1-or-later','patches':{f.name:hashlib.sha256(f.read_bytes()).hexdigest() for f in (Path(__file__).resolve().parents[1]/'third-party/patches').glob('wine-11.19-*.patch')}},indent=2)+'\n')
print(sha)

# Lifecycle fixture is independent of the versioned Wine userspace ZIP.
sleep=a.exe.parent/'sleep-x64.exe'
if sleep.is_file():
    shutil.copy2(sleep,out/'sleep-x64.exe')
    (out/'sleep-fixture.json').write_text(json.dumps({'sha256':hashlib.sha256(sleep.read_bytes()).hexdigest()})+'\n')
