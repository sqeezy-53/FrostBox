#!/usr/bin/env python3
"""Build original x64/Bionic ELF and Win64 PE compute probes, never modify runtime packages."""
import argparse,hashlib,json,pathlib,struct,subprocess,shutil
ROOT=pathlib.Path(__file__).resolve().parents[1]
p=argparse.ArgumentParser();p.add_argument('--sdk',type=pathlib.Path,required=True);p.add_argument('--output',type=pathlib.Path,required=True)
a=p.parse_args();out=a.output.resolve();out.mkdir(parents=True,exist_ok=True)
bin=a.sdk.resolve()/'ndk/27.2.12479018/toolchains/llvm/prebuilt/linux-x86_64/bin'
assets=out/'assets/runtime';assets.mkdir(parents=True,exist_ok=True)
subprocess.run(['glslangValidator','-V','--target-env','vulkan1.0',str(ROOT/'tests/vulkan/compute.comp'),'-o',str(out/'compute.spv')],check=True)
data=(out/'compute.spv').read_bytes();words=struct.unpack('<'+'I'*(len(data)//4),data)
(out/'compute_spv.h').write_text('// SPDX-License-Identifier: MIT\nstatic const uint32_t compute_spv[]={'+','.join(hex(w) for w in words)+'};\n')
subprocess.run([str(bin/'x86_64-linux-android29-clang'),'-std=c11','-O1','-Wall','-Wextra','-Werror','-Wno-missing-field-initializers','-fPIE','-pie','-Wl,-z,max-page-size=16384','-I'+str(out),str(ROOT/'tests/vulkan/probe.c'),'-ldl','-o',str(assets/'vulkan-compute-x64.elf')],check=True)
# NDK supplies platform-independent Vulkan headers, not a Windows runtime/library.
include=bin.parent/'sysroot/usr/include'
for directory in ['vulkan','vk_video']:shutil.copytree(include/directory,out/'include'/directory,dirs_exist_ok=True)
(out/'kernel32.def').write_text('LIBRARY kernel32.dll\nEXPORTS\nLoadLibraryA\nGetProcAddress\nFreeLibrary\nExitProcess\nGetCurrentProcessId\n')
(out/'msvcrt.def').write_text('LIBRARY msvcrt.dll\nEXPORTS\nprintf\nfflush\nmalloc\nfree\nmemcpy\nmemset\nstrcmp\nstrstr\n')
for dll in ['kernel32','msvcrt']:
 subprocess.run(['llvm-dlltool','-m','i386:x86-64','-d',str(out/(dll+'.def')),'-l',str(out/(dll+'.lib'))],check=True)
subprocess.run(['clang','--target=x86_64-pc-windows-msvc','-std=c11','-ffreestanding','-fno-stack-protector','-O1','-Wall','-Wextra','-Werror','-Wno-missing-field-initializers','-I'+str(out),'-I'+str(out/'include'),'-c',str(ROOT/'tests/vulkan/probe.c'),'-o',str(out/'probe.obj')],check=True)
subprocess.run(['lld-link','/entry:mainCRTStartup','/subsystem:console','/nodefaultlib','/timestamp:0','/out:'+str(assets/'vulkan-compute-x64.exe'),str(out/'probe.obj'),str(out/'kernel32.lib'),str(out/'msvcrt.lib')],check=True)
for name in ['vulkan-compute-x64.elf','vulkan-compute-x64.exe']:
 (assets/(name+'.json')).write_text(json.dumps({'schema':1,'sha256':hashlib.sha256((assets/name).read_bytes()).hexdigest(),'expectedResult':10800,'exitCode':42,'shaderSha256':hashlib.sha256(data).hexdigest()},indent=2)+'\n')
print('Probes built:',assets)
