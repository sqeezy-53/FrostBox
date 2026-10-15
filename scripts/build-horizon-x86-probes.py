#!/usr/bin/env python3
"""Genuine x86 PE probes, pinned DXVK release's x32 DLLs; no DXVK rebuild."""
import argparse, hashlib, json, shutil, struct, subprocess, tarfile
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
p=argparse.ArgumentParser();p.add_argument('--wine-build',type=Path,required=True);p.add_argument('--wine-source',type=Path,required=True);p.add_argument('--sdk',type=Path,required=True);p.add_argument('--dxvk-archive',type=Path,required=True);a=p.parse_args()
out=ROOT/'build/horizon-x86-probes';out.mkdir(parents=True,exist_ok=True)
assets=ROOT/'build/wow64-package/assets/wine-wow64';assets.mkdir(parents=True,exist_ok=True)
ndk=a.sdk.resolve()/'ndk/27.2.12479018/toolchains/llvm/prebuilt/linux-x86_64'
for name in ('vulkan','vk_video'):shutil.copytree(ndk/'sysroot/usr/include'/name,out/'include'/name,dirs_exist_ok=True)
subprocess.run(['glslangValidator','-V','--target-env','vulkan1.0',str(ROOT/'tests/vulkan/compute.comp'),'-o',str(out/'compute.spv')],check=True)
b=(out/'compute.spv').read_bytes();words=struct.unpack('<'+'I'*(len(b)//4),b)
(out/'compute_spv.h').write_text('static const uint32_t compute_spv[]={'+','.join(hex(w) for w in words)+'};\n')
def lib(dll,exports):
    f=out/(dll+'.def');f.write_text('LIBRARY '+dll+'.dll\nEXPORTS\n'+'\n'.join(exports)+'\n')
    subprocess.run(['llvm-dlltool','-m','i386','-k','-d',str(f),'-l',str(out/(dll+'.lib'))],check=True)
lib('kernel32',['LoadLibraryA@4','GetProcAddress@8','FreeLibrary@4','GetCurrentProcessId@0','GetModuleHandleW@4','ExitProcess@4','Sleep@4','GetStdHandle@4','WriteFile@20'])
lib('user32',['RegisterClassW@4','CreateWindowExW@48','DefWindowProcW@16','GetClientRect@8','PeekMessageW@20','TranslateMessage@4','DispatchMessageW@4','DestroyWindow@4'])
lib('msvcrt',['printf','fflush','malloc','free','memcpy','memset','strcmp','strstr'])
lib('d3d9',['Direct3DCreate9@4'])
for name,source,libs,extra in [('vulkan-compute-x86.exe','tests/vulkan/probe.c',['kernel32','msvcrt'],['-DFROST_PROBE_X86']),('d3d9-triangle-x86.exe','tests/d3d9/triangle.c',['kernel32','user32','msvcrt','d3d9'],[]),('sleep-x86.exe','tests/windows/sleep.c',['kernel32'],[])]:
    obj=out/(name+'.obj');exe=assets/name
    subprocess.run(['clang','--target=i686-pc-windows-msvc','-std=c11','-ffreestanding','-fno-stack-protector','-O1','-Wall','-Wextra','-Werror','-Wno-missing-field-initializers','-D__WINESRC__','-D__WINE_PE_BUILD','-I'+str(out),'-I'+str(out/'include'),'-I'+str(a.wine_build.resolve()/'include'),'-I'+str(a.wine_source.resolve()/'include'),'-I'+str(a.wine_source.resolve()/'include/msvcrt'),*extra,'-c',str(ROOT/source),'-o',str(obj)],check=True)
    subprocess.run(['lld-link','/entry:mainCRTStartup','/subsystem:console','/nodefaultlib','/timestamp:0','/out:'+str(exe),str(obj),*[str(out/(l+'.lib')) for l in libs]],check=True)
    (assets/(name+'.json')).write_text(json.dumps({'sha256':hashlib.sha256(exe.read_bytes()).hexdigest(),'architecture':'x86','expectedExit':42},indent=2)+'\n')
expected='d85ce7c79f57ecd765aaa1b9e7007cb875e6fde9f6d331df799bce73d513ce87'
assert hashlib.sha256(a.dxvk_archive.read_bytes()).hexdigest()==expected
dx=assets/'dxvk-x86';dx.mkdir(exist_ok=True)
with tarfile.open(a.dxvk_archive) as t:
    for name in ('d3d9.dll','d3d11.dll','dxgi.dll'):
        f=t.getmember('dxvk-2.7.1/x32/'+name);assert f.isfile() and f.size<32*1024*1024
        (dx/name).write_bytes(t.extractfile(f).read())
(dx/'manifest.json').write_text(json.dumps({'version':'2.7.1','archiveSha256':expected,'license':'Zlib','files':{f.name:hashlib.sha256(f.read_bytes()).hexdigest() for f in dx.glob('*.dll')}},indent=2)+'\n')
print(assets)
