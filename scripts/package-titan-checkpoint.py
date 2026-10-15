#!/usr/bin/env python3
"""Package a device-qualified Windows WSI/D3D11 checkpoint without overwriting releases.
Game completion is reported separately. Never includes games, dumps, prefixes or host work.
"""
import argparse,hashlib,json,pathlib,shutil,zipfile
ROOT=pathlib.Path(__file__).resolve().parents[1]
def sha(p):
 with p.open('rb') as f:return hashlib.file_digest(f,'sha256').hexdigest()
p=argparse.ArgumentParser(description=__doc__);p.add_argument('--evidence',type=pathlib.Path,required=True);p.add_argument('--output',type=pathlib.Path,required=True);p.add_argument('--upstream-source-bundle',type=pathlib.Path,required=True);a=p.parse_args()
evidence=a.evidence.resolve();summary=json.loads((evidence/'checkpoint-summary.json').read_text())
if not summary['windowsPresentation'] or not summary['dxvk']:raise RuntimeError('Windows Present and D3D11 device gates required')
output=a.output.resolve();output.mkdir(parents=True,exist_ok=True);name='FrostBox-0.5.0-dev.4-titan-checkpoint'
files={suffix:output/(name+'-'+suffix) for suffix in ['arm64-debug.apk','androidTest.apk','windows-vulkan-triangle-x64.exe','d3d11-cube-x64.exe','source.zip','evidence.zip','SHA256SUMS.txt']}
if any(p.exists() for p in files.values()):raise RuntimeError('Preserve all previous artifacts; choose a new output directory')
app=ROOT/'android-app/build/outputs/apk/debug/android-app-debug.apk';test=ROOT/'android-app/build/outputs/apk/androidTest/debug/android-app-debug-androidTest.apk'
if sha(app)!=summary['appSha256'] or sha(test)!=summary['testSha256']:raise RuntimeError('APK changed after device checks')
for source,suffix in [(app,'arm64-debug.apk'),(test,'androidTest.apk'),(ROOT/'build/windows-vulkan-triangle/windows-vulkan-triangle-x64.exe','windows-vulkan-triangle-x64.exe'),(ROOT/'build/d3d11-probe/d3d11-cube-x64.exe','d3d11-cube-x64.exe')]:shutil.copy2(source,files[suffix])
excluded={'.git','.gradle','.cxx','build','__pycache__','.idea'}
with zipfile.ZipFile(files['source.zip'],'w',zipfile.ZIP_DEFLATED,compresslevel=6) as z:
 for f in sorted(ROOT.rglob('*')):
  relative=f.relative_to(ROOT)
  if any(p in excluded for p in relative.parts) or f.name=='local.properties':continue
  if f.is_symlink():raise RuntimeError('Unexpected source link: '+str(relative))
  if f.is_file():z.write(f,'FrostBox/'+relative.as_posix())
 with zipfile.ZipFile(a.upstream_source_bundle) as old:
  sources=[n for n in old.namelist() if n.startswith('FrostBox/third-party/sources/') and not n.endswith('/')]
  if len(sources)!=3:raise RuntimeError('Wine/Box64/FreeType corresponding source archives required')
  for n in sources:
   info=zipfile.ZipInfo(n);info.compress_type=zipfile.ZIP_STORED
   with old.open(n) as src,z.open(info,'w') as dst:shutil.copyfileobj(src,dst,1024*1024)
with zipfile.ZipFile(files['evidence.zip'],'w',zipfile.ZIP_DEFLATED,compresslevel=6) as z:
 for f in sorted(evidence.rglob('*')):
  relative=f.relative_to(evidence)
  if any('checkpoint' in p and p!='checkpoint-summary.json' for p in relative.parts):continue
  if f.is_symlink():raise RuntimeError('Unexpected evidence link')
  if f.is_file() and f.suffix in ['.txt','.json','.log','.png','.xml','.md']:z.write(f,'titan-evidence/'+relative.as_posix())
for suffix in ['source.zip','evidence.zip']:
 with zipfile.ZipFile(files[suffix]) as z:
  if z.testzip() is not None:raise RuntimeError('ZIP integrity failure')
files['SHA256SUMS.txt'].write_text(''.join(sha(f)+'  '+f.name+'\n' for suffix,f in files.items() if suffix!='SHA256SUMS.txt'))
for f in files.values():print(f,flush=True)
