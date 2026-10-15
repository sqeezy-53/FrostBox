#!/usr/bin/env python3
"""Compile genuine Windows D3D11 fixture; package pinned official DXVK in an isolated asset set."""
import argparse,hashlib,json,pathlib,subprocess,tarfile,shutil
ROOT=pathlib.Path(__file__).resolve().parents[1]
p=argparse.ArgumentParser();p.add_argument('--wine-work',type=pathlib.Path,required=True);p.add_argument('--dxvk-work',type=pathlib.Path,required=True);a=p.parse_args()
w=a.wine_work.resolve();dx=a.dxvk_work.resolve();out=ROOT/'build/d3d11-probe';out.mkdir(parents=True,exist_ok=True)
subprocess.run(['make','include/d3d12shader.h'],cwd=w/'build',check=True)
obj=out/'cube.obj';exe=out/'d3d11-cube-x64.exe'
subprocess.run(['clang','--target=x86_64-pc-windows-msvc','-std=c11','-D__WINESRC__','-D__WINE_PE_BUILD','-ffreestanding','-fno-stack-protector','-O1','-Wall','-Wextra','-Werror','-Wno-missing-field-initializers','-I'+str(w/'build/include'),'-I'+str(w/'source/include'),'-I'+str(w/'source/include/msvcrt'),'-c',str(ROOT/'tests/d3d11/cube.c'),'-o',str(obj)],check=True)
exports={'kernel32':['GetModuleHandleW','GetLastError','ExitProcess','Sleep'],'user32':['RegisterClassExW','CreateWindowExW','ShowWindow','DefWindowProcW','DestroyWindow','PostQuitMessage','GetClientRect','PeekMessageW','TranslateMessage','DispatchMessageW'],'msvcrt':['printf','fflush','strlen','memcpy','memset'],'d3d11':['D3D11CreateDeviceAndSwapChain'],'d3dcompiler_47':['D3DCompile']}
for dll,names in exports.items():
 definition=out/(dll+'.def');definition.write_text('LIBRARY '+dll+'.dll\nEXPORTS\n'+'\n'.join(names)+'\n');subprocess.run(['llvm-dlltool','-m','i386:x86-64','-d',str(definition),'-l',str(out/(dll+'.lib'))],check=True)
subprocess.run(['lld-link','/entry:mainCRTStartup','/subsystem:console','/nodefaultlib','/timestamp:0','/out:'+str(exe),str(obj)]+[str(out/(dll+'.lib')) for dll in exports],check=True)
assets=ROOT/'build/d3d11-package/assets';runtime=assets/'runtime';runtime.mkdir(parents=True,exist_ok=True)
shutil.copy2(exe,runtime/exe.name);(runtime/(exe.name+'.json')).write_text(json.dumps({'schema':1,'sha256':hashlib.sha256(exe.read_bytes()).hexdigest(),'sourceSha256':hashlib.sha256((ROOT/'tests/d3d11/cube.c').read_bytes()).hexdigest(),'expectedExit':42,'draws':120},indent=2)+'\n')
dx.mkdir(parents=True,exist_ok=True)
archive=dx/'dxvk-2.7.1.tar.gz';expected='d85ce7c79f57ecd765aaa1b9e7007cb875e6fde9f6d331df799bce73d513ce87'
if not archive.exists():
 import urllib.request
 urllib.request.urlretrieve('https://github.com/doitsujin/dxvk/releases/download/v2.7.1/dxvk-2.7.1.tar.gz',archive)
assert hashlib.sha256(archive.read_bytes()).hexdigest()==expected
package=assets/'dxvk';package.mkdir(exist_ok=True)
with tarfile.open(archive) as t:
 for name in ['d3d11.dll','dxgi.dll']:
  member=t.getmember('dxvk-2.7.1/x64/'+name);assert member.isfile() and member.size<32*1024*1024
  (package/name).write_bytes(t.extractfile(member).read())
shutil.copy2(ROOT/'third-party/licenses/DXVK-Zlib.txt',package/'LICENSE')
(package/'manifest.json').write_text(json.dumps({'schema':1,'version':'2.7.1','license':'Zlib','url':'https://github.com/doitsujin/dxvk/releases/tag/v2.7.1','archiveSha256':expected,'files':{name:hashlib.sha256((package/name).read_bytes()).hexdigest() for name in ['d3d11.dll','dxgi.dll','LICENSE']}},indent=2)+'\n')
print(exe)
