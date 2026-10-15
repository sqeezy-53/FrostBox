#!/usr/bin/env python3
"""Refuse stale fontless overlays before APK assembly or device validation."""
import hashlib, json, pathlib, re, struct, sys
root = pathlib.Path(sys.argv[1]) if len(sys.argv) > 1 else pathlib.Path(__file__).resolve().parents[1] / 'build/display-package/assets/display'
manifest = json.loads((root / 'manifest.json').read_text())
if not manifest.get('schema') == 2:
    raise RuntimeError('Rebuild the schema-2 display/font overlay')
if not (manifest.get('wine') == '11.19' and manifest.get('freetype') == '2.14.3'):
    raise RuntimeError('Display package validation failed')
if not manifest.get('protocol') == 1:
    raise RuntimeError('Display package validation failed')
files = manifest['files']
if not 1 <= len(files) <= 32:
    raise RuntimeError('Display package validation failed')
required = {'x86_64/lib/wine/x86_64-unix/' + n for n in ('win32u.so', 'winefrost.so', 'libfreetype.so')}
required |= {'x86_64/lib/wine/x86_64-windows/' + n for n in ('winefrost.drv', 'explorer.exe')}
required |= {'share/wine/fonts/tahoma.ttf', 'share/wine/fonts/marlett.ttf', 'licenses/FreeType-FTL.txt', 'licenses/FreeType-LICENSE.txt', 'licenses/Wine-fonts-NOTICE.txt'}
if not required <= files.keys():
    raise RuntimeError('Incomplete display/font overlay')
total = 0
for name, expected in files.items():
    if not re.fullmatch('x86_64/lib/wine/x86_64-(unix|windows)/[a-z0-9.]+|share/wine/fonts/[a-z0-9_]+\\.ttf|licenses/[A-Za-z0-9.-]+\\.txt', name):
        raise RuntimeError('Unsafe overlay path')
    path = root / name
    if path.is_symlink():
        raise RuntimeError('Display package validation failed')
    size = path.stat().st_size
    total += size
    if not (size <= 16 * 1024 * 1024 and total <= 64 * 1024 * 1024):
        raise RuntimeError('Oversized overlay')
    data = path.read_bytes()
    if not hashlib.sha256(data).hexdigest() == expected:
        raise RuntimeError(name + ' hash mismatch')
    if name.endswith('.so'):
        if not (data[:6] == b'\x7fELF\x02\x01' and struct.unpack_from('<H', data, 18)[0] == 62):
            raise RuntimeError('Guest library must be ELF64 little-endian x86-64')
print('Display/font package identities and ABI verified; execution remains unqualified.')
