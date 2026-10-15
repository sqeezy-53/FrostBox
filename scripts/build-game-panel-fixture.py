#!/usr/bin/env python3
"""Build only a debug PE fixture. Does not rebuild or repackage Wine/DXVK/qualified probes."""
import argparse
from pathlib import Path
import subprocess
ROOT=Path(__file__).resolve().parents[1]
p=argparse.ArgumentParser()
p.add_argument('--wine-work',type=Path,required=True)
a=p.parse_args();w=a.wine_work.resolve();out=ROOT/'build/game-panel-fixture';out.mkdir(parents=True,exist_ok=True)
obj=out/'panel.obj';exe=ROOT/'build/launcher-fixtures/assets/launcher/game-panel-x64.exe';exe.parent.mkdir(parents=True,exist_ok=True)
subprocess.run(['clang','--target=x86_64-pc-windows-msvc','-std=c11','-D__WINESRC__','-D__WINE_PE_BUILD','-ffreestanding','-fno-stack-protector','-O1','-Wall','-Wextra','-Werror','-Wno-missing-field-initializers',
    '-I'+str(w/'build/include'),'-I'+str(w/'source/include'),'-I'+str(w/'source/include/msvcrt'),'-c',str(ROOT/'tests/windows/game-panel.c'),'-o',str(obj)],check=True)
exports={'kernel32':['GetModuleHandleW','GetLastError','ExitProcess','Sleep'],'user32':['RegisterClassExW','CreateWindowExW','ShowWindow','DefWindowProcW','DestroyWindow','PostQuitMessage','GetClientRect','PeekMessageW','TranslateMessage','DispatchMessageW'],'msvcrt':['printf','fflush','strlen','memcpy','memset'],'d3d11':['D3D11CreateDeviceAndSwapChain'],'d3dcompiler_47':['D3DCompile']}
for dll,names in exports.items():
    definition=out/(dll+'.def');definition.write_text('LIBRARY '+dll+'.dll\nEXPORTS\n'+'\n'.join(names)+'\n')
    subprocess.run(['llvm-dlltool','-m','i386:x86-64','-d',str(definition),'-l',str(out/(dll+'.lib'))],check=True)
subprocess.run(['lld-link','/entry:mainCRTStartup','/subsystem:console','/nodefaultlib','/timestamp:0','/out:'+str(exe),str(obj)]+[str(out/(dll+'.lib')) for dll in exports],check=True)
print(exe)
