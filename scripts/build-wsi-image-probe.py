#!/usr/bin/env python3
"""Build isolated x86-64/Bionic GPU image-export fixture; preserve compute/Wine/Box64."""
import argparse, hashlib, json, pathlib, subprocess
ROOT=pathlib.Path(__file__).resolve().parents[1]
p=argparse.ArgumentParser();p.add_argument('--sdk',type=pathlib.Path,required=True);p.add_argument('--output',type=pathlib.Path,required=True)
a=p.parse_args();out=a.output.resolve()/'assets/runtime';out.mkdir(parents=True,exist_ok=True)
cc=a.sdk.resolve()/'ndk/27.2.12479018/toolchains/llvm/prebuilt/linux-x86_64/bin/x86_64-linux-android29-clang'
exe=out/'wsi-export-x64.elf'
subprocess.run([str(cc),'-std=c11','-D_GNU_SOURCE','-O1','-Wall','-Wextra','-Werror','-Wno-missing-field-initializers','-fPIE','-pie','-Wl,-z,max-page-size=16384','-I'+str(ROOT/'graphics/wsi'),str(ROOT/'tests/wsi/export-image.c'),'-ldl','-o',str(exe)],check=True)
(out/'wsi-export-x64.elf.json').write_text(json.dumps({'schema':1,'sha256':hashlib.sha256(exe.read_bytes()).hexdigest(),'sourceSha256':hashlib.sha256((ROOT/'tests/wsi/export-image.c').read_bytes()).hexdigest(),'exitCode':42,'windowsPresentation':False},indent=2)+'\n')
print(exe)
