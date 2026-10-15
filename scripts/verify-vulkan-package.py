#!/usr/bin/env python3
"""Integrity/architecture gates. This is a build check, never a device success verdict."""
import argparse,hashlib,json,pathlib,struct
ROOT=pathlib.Path(__file__).resolve().parents[1]
p=argparse.ArgumentParser();p.add_argument('--wine',action='store_true');p.add_argument('--wsi',action='store_true');a=p.parse_args()
def sha(f):return hashlib.sha256(f.read_bytes()).hexdigest()
def elf(f,machine):
 data=f.read_bytes()[:64];assert data[:6]==b'\x7fELF\x02\x01' and struct.unpack_from('<H',data,18)[0]==machine,str(f)
probe=ROOT/'build/vulkan-bridge/assets/runtime'
for name in ['vulkan-compute-x64.elf','vulkan-compute-x64.exe']:
 assert sha(probe/name)==json.loads((probe/(name+'.json')).read_text())['sha256']
elf(probe/'vulkan-compute-x64.elf',62)
box=ROOT/'build/box64-vulkan-package';meta=json.loads((box/'assets/runtime/box64-vulkan.json').read_text())
assert sha(box/'jniLibs/arm64-v8a/libbox64_vulkan.so')==meta['binarySha256'];elf(box/'jniLibs/arm64-v8a/libbox64_vulkan.so',183)
assert sha(ROOT/'third-party/patches/box64-0.4.4-android-vulkan-soname.patch')==meta['patchSha256']
# Pinned qualified packages are immutable, regardless of the candidate result.
assert sha(ROOT/'build/box64-package/jniLibs/arm64-v8a/libbox64.so')==meta['qualifiedBox64Sha256']
qualified_wine=sha(ROOT/'build/wine-probe-package/assets/runtime/wine-probe.zip')
if a.wine:
 out=ROOT/'build/wine-vulkan-package/assets/wine-vulkan';meta=json.loads((out/'manifest.json').read_text())
 assert qualified_wine==meta['qualifiedZipSha256']
 assert b'libvulkan.so.1\x00' in (out/'win32u.so').read_bytes() and b'Wine was built without Vulkan support.' not in (out/'win32u.so').read_bytes()
 assert sha(out/'win32u.so')==meta['win32uSha256'];elf(out/'win32u.so',62)
if a.wsi:
 assert a.wine
 out=ROOT/'build/wsi-capability/assets/runtime';name='vulkan-wsi-capability-x64.exe';meta=json.loads((out/(name+'.json')).read_text())
 data=(out/name).read_bytes();assert data[:2]==b'MZ'
 pe=struct.unpack_from('<I',data,60)[0];assert data[pe:pe+4]==b'PE\0\0' and struct.unpack_from('<H',data,pe+4)[0]==0x8664
 assert sha(out/name)==meta['sha256'] and sha(ROOT/'tests/vulkan/wsi-capability.c')==meta['sourceSha256']
 assert meta['expectedBlockedExitCode']==44 and meta['presentationFixture'] is False
print('Vulkan candidate integrity and qualified package preservation PASS (not Android validation)')
