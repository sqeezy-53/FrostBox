#!/usr/bin/env python3
"""Add locally built i386 PE DLLs to an isolated copy of qualified x64 Wine.

Does not touch wine-probe.zip, Box64, prefixes or existing release artifacts.
"""
import argparse
import hashlib
import json
from pathlib import Path
import zipfile

ROOT = Path(__file__).resolve().parents[1]
p = argparse.ArgumentParser(description=__doc__)
p.add_argument('--build', type=Path, required=True)
p.add_argument('--fixture', type=Path, required=True)
a = p.parse_args()
out = ROOT / 'build/wow64-package/assets/wine-wow64'
out.mkdir(parents=True, exist_ok=True)
files = {}
with zipfile.ZipFile(out / 'overlay.zip', 'w', zipfile.ZIP_DEFLATED, compresslevel=6) as z:
    for parent in ('dlls', 'programs'):
        for f in sorted((a.build / parent).glob('*/i386-windows/*')):
            if not f.is_file() or f.suffix not in ('.dll', '.exe', '.drv', '.cpl'):
                continue
            data = f.read_bytes()
            if data[:2] != b'MZ':
                continue
            pe = int.from_bytes(data[60:64], 'little')
            assert data[pe:pe+4] == b'PE\0\0' and int.from_bytes(data[pe+4:pe+6], 'little') == 0x14c
            name = 'x86_64/lib/wine/i386-windows/' + f.name
            assert name not in files, name
            files[name] = dict(sha256=hashlib.sha256(data).hexdigest(), size=len(data))
            z.writestr(name, data)
for name in ('ntdll.dll', 'kernel32.dll', 'kernelbase.dll'):
    assert 'x86_64/lib/wine/i386-windows/' + name in files, name
fixture = a.fixture.read_bytes()
(out / 'console-x86.exe').write_bytes(fixture)
base = json.loads((ROOT / 'build/wine-probe-package/assets/runtime/wine-probe.json').read_text())
manifest = dict(schema=1, wine='11.19', status='experimental-console-only',
    architecture='new-WoW64-i386-PE-on-x86_64-Bionic', baseSha256=base['sha256'],
    archiveSha256=hashlib.sha256((out / 'overlay.zip').read_bytes()).hexdigest(),
    unpackedBytes=sum(x['size'] for x in files.values()), files=files,
    fixtureSha256=hashlib.sha256(fixture).hexdigest(), sourceCommit=base['sourceCommit'],
    license='LGPL-2.1-or-later')
(out / 'manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
print(f"{len(files)} i386 PE files, {manifest['unpackedBytes']} bytes; {out}")
