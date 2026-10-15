#!/usr/bin/env python3
"""Build only the experimental Wine HID Unix backend; retain qualified Wine ZIP."""
import argparse,pathlib,subprocess,hashlib,json,shutil
root=pathlib.Path(__file__).resolve().parents[1]
p=argparse.ArgumentParser();p.add_argument('--wine-work',type=pathlib.Path,required=True);a=p.parse_args()
w=a.wine_work.resolve();src=w/'source/dlls/winebus.sys/bus_sdl.c'
s=src.read_text();marker='\n#else\n\nNTSTATUS sdl_bus_init(void *args)';start=s.find(marker)
if start>=0:s=s[:start]+'\n#else\n#include "android-pad.c.inc"\n\n#endif /* SONAME_LIBSDL2 */\n';src.write_text(s)
assert '#include "android-pad.c.inc"' in s
inc=w/'source/dlls/winebus.sys/android-pad.c.inc';new=(root/'input/winebus/android-pad.c.inc').read_text()
if not inc.exists() or inc.read_text()!=new:inc.write_text(new);src.touch()
subprocess.run(['make','-j4','dlls/winebus.sys/winebus.so'],cwd=w/'build',check=True)
out=root/'build/wine-input-package/assets/wine-input';out.mkdir(parents=True,exist_ok=True)
shutil.copy2(w/'build/dlls/winebus.sys/winebus.so',out/'winebus.so')
(out/'manifest.json').write_text(json.dumps({'schema':1,'wine':'11.19','driverInterface':48,'status':'experimental-unqualified','files':{'winebus.so':hashlib.sha256((out/'winebus.so').read_bytes()).hexdigest()}},indent=2)+'\n')
print(out)
