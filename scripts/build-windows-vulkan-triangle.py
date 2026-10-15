#!/usr/bin/env python3
"""Build a separate, genuine Win64 Vulkan triangle fixture; does not claim WSI works."""
import argparse,hashlib,json,pathlib,shutil,struct,subprocess
ROOT=pathlib.Path(__file__).resolve().parents[1]
p=argparse.ArgumentParser();p.add_argument('--sdk',type=pathlib.Path,required=True);p.add_argument('--output',type=pathlib.Path,required=True);a=p.parse_args()
out=a.output.resolve();out.mkdir(parents=True,exist_ok=True);inc=out/'include';inc.mkdir(exist_ok=True)
headers=a.sdk.resolve()/'ndk/27.2.12479018/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/include'
for name in ['vulkan','vk_video']:shutil.copytree(headers/name,inc/name,dirs_exist_ok=True)
(inc/'windows.h').write_text('#pragma once\n#include "'+str(ROOT/'tests/windows/win32-minimal.h')+'"\ntypedef HANDLE HINSTANCE;typedef HANDLE HMONITOR;typedef struct { DWORD length;void* descriptor;BOOL inherit; } SECURITY_ATTRIBUTES;\n')
code=['// SPDX-License-Identifier: MIT','#pragma once','#include <stdint.h>']
for stage in ['vert','frag']:
 spv=out/(stage+'.spv');subprocess.run(['glslangValidator','-V','--target-env','vulkan1.0',str(ROOT/f'graphics/vulkan/shaders/triangle.{stage}'),'-o',str(spv)],check=True)
 words=struct.unpack('<'+'I'*(spv.stat().st_size//4),spv.read_bytes());code.append('static const uint32_t triangle_'+stage+'[]={'+','.join(hex(w) for w in words)+'};')
(inc/'triangle-spirv.h').write_text('\n'.join(code)+'\n')
exports={'kernel32':['LoadLibraryA','GetProcAddress','FreeLibrary','ExitProcess','GetModuleHandleW','GetLastError','GetEnvironmentVariableA'],'user32':['RegisterClassExW','CreateWindowExW','ShowWindow','DefWindowProcW','DestroyWindow','PostQuitMessage','PostMessageW','PeekMessageW','TranslateMessage','DispatchMessageW','GetClientRect','GetWindowRect','GetSystemMetrics','SystemParametersInfoW','SetWindowPos'],'msvcrt':['printf','fflush','strcmp','memcpy','memset']}
for dll,names in exports.items():
 definition=out/(dll+'.def');definition.write_text('LIBRARY '+dll+'.dll\nEXPORTS\n'+'\n'.join(names)+'\n');subprocess.run(['llvm-dlltool','-m','i386:x86-64','-d',str(definition),'-l',str(out/(dll+'.lib'))],check=True)
source=ROOT/'tests/vulkan/windows-triangle.c';obj=out/'triangle.obj';exe=out/'windows-vulkan-triangle-x64.exe'
subprocess.run(['clang','--target=x86_64-pc-windows-msvc','-std=c11','-ffreestanding','-fno-stack-protector','-O1','-Wall','-Wextra','-Werror','-Wno-missing-field-initializers','-I'+str(inc),'-c',str(source),'-o',str(obj)],check=True)
subprocess.run(['lld-link','/entry:mainCRTStartup','/subsystem:console','/nodefaultlib','/timestamp:0','/out:'+str(exe),str(obj)]+[str(out/(dll+'.lib')) for dll in exports],check=True)
(exe.with_suffix('.json')).write_text(json.dumps({'schema':1,'sha256':hashlib.sha256(exe.read_bytes()).hexdigest(),'sourceSha256':hashlib.sha256(source.read_bytes()).hexdigest(),'compiled':True,'testedOnAndroid':False,'expectedMissingWsiExit':44,'successExit':42,'requiredDrawPresentFrames':120,'actualWin32Window':True},indent=2)+'\n');print(exe)
