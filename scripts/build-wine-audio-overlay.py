#!/usr/bin/env python3
"""Compile only the narrow experimental Wine Unix audio module, no base runtime rebuild."""
import pathlib,argparse,subprocess,json,hashlib
r=pathlib.Path(__file__).resolve().parents[1]
p=argparse.ArgumentParser();p.add_argument('--wow64',action='store_true');p.add_argument('--wine-work',type=pathlib.Path,required=True);p.add_argument('--sdk',type=pathlib.Path,required=True);a=p.parse_args();w=a.wine_work.resolve();sdk=a.sdk.resolve()
subprocess.run(['make','-j4','include/audioclient.h','include/mmdeviceapi.h'],cwd=w/'build',check=True)
out=r/('build/empire-package/assets/wine-audio-wow64' if a.wow64 else 'build/remote-package/assets/wine-audio');out.mkdir(exist_ok=True,parents=True)
cc=sdk/'ndk/27.2.12479018/toolchains/llvm/prebuilt/linux-x86_64/bin/x86_64-linux-android29-clang'
subprocess.run([str(cc),'-std=gnu11','-O1','-g','-fPIC','-shared','-D_GNU_SOURCE','-D__WINESRC__','-DWINE_UNIX_LIB','-I'+str(w/'build/include'),'-I'+str(w/'source/include'),'-I'+str(w/'source/dlls/mmdevapi'),'-Wl,-Bsymbolic','-Wl,-z,defs','-Wl,-z,max-page-size=16384','-Wl,-soname,wineoss.so',str(r/('audio/wine/frost_audio_wow64.c' if a.wow64 else 'audio/wine/frost_audio.c')),str(w/'build/dlls/ntdll/ntdll.so'),'-o',str(out/'wineoss.so')],check=True)
(out/'manifest.json').write_text(json.dumps({'schema':1,'wine':'11.19','driverInterface':48,'status':'experimental-not-device-tested','backend':'FrostBox Android PCM, default OSS slot in isolated overlay only','files':{'wineoss.so':hashlib.sha256((out/'wineoss.so').read_bytes()).hexdigest()}},indent=2)+'\n')
