#!/usr/bin/env python3
"""Package the partial alpha with truthful gates and matching installed APK evidence."""
import argparse,pathlib,hashlib,json,shutil,zipfile
root=pathlib.Path(__file__).resolve().parents[1]
p=argparse.ArgumentParser();p.add_argument('--evidence',type=pathlib.Path,required=True);p.add_argument('--output',type=pathlib.Path,required=True);p.add_argument('--upstream-source-bundle',type=pathlib.Path,required=True);a=p.parse_args()
def sha(f):
 with f.open('rb') as s:return hashlib.file_digest(s,'sha256').hexdigest()
summary=json.loads((a.evidence/'summary.json').read_text())
assert summary['status']=='0.6.0-alpha-evolution-partial' and summary['physicalWindowsXInput'] and summary['baseIntegrity']['qualifiedBasePreserved']
out=a.output.resolve();out.mkdir(exist_ok=True,parents=True)
name='FrostBox-0.6.0-alpha-evolution'
paths={kind:out/(name+'-'+kind) for kind in ['arm64-debug.apk','androidTest.apk','source.zip','evidence.zip','XInput-Test.exe','Audio-Probe.exe','SHA256SUMS.txt']}
if any(f.exists() for f in paths.values()):raise RuntimeError('Preserve old artifacts: select a new directory')
app=root/'android-app/build/outputs/apk/debug/android-app-debug.apk';test=root/'android-app/build/outputs/apk/androidTest/debug/android-app-debug-androidTest.apk'
assert sha(app)==summary['appSha256'] and sha(test)==summary['testSha256']
for f,key in [(app,'arm64-debug.apk'),(test,'androidTest.apk'),(root/'build/xinput-fixture/xinput-x64.exe','XInput-Test.exe'),(root/'build/xinput-fixture/audio-x64.exe','Audio-Probe.exe')]:shutil.copy2(f,paths[key])
excluded={'.git','.gradle','.cxx','build','__pycache__','.idea'}
with zipfile.ZipFile(paths['source.zip'],'w',zipfile.ZIP_DEFLATED,compresslevel=6) as z:
 for f in sorted(root.rglob('*')):
  rel=f.relative_to(root)
  if any(x in excluded for x in rel.parts) or f.name=='local.properties' or '.bak' in f.name:continue
  if f.is_symlink():raise RuntimeError('Unexpected source link '+str(rel))
  if f.is_file():z.write(f,'FrostBox/'+rel.as_posix())
 with zipfile.ZipFile(a.upstream_source_bundle) as old:
  sources=[n for n in old.namelist() if n.startswith('FrostBox/third-party/sources/') and not n.endswith('/')]
  assert len(sources)==3
  for n in sources:
   info=zipfile.ZipInfo(n);info.compress_type=zipfile.ZIP_STORED
   with old.open(n) as src,z.open(info,'w') as dst:shutil.copyfileobj(src,dst,1024*1024)
with zipfile.ZipFile(paths['evidence.zip'],'w',zipfile.ZIP_DEFLATED,compresslevel=6) as z:
 for f in sorted(a.evidence.rglob('*')):
  if f.is_file() and f.suffix in ['.txt','.json','.log','.xml','.md','.png']:z.write(f,'evolution-evidence/'+f.relative_to(a.evidence).as_posix())
 for f in sorted((a.evidence.parent/'task01-validation').glob('*')):
  if f.is_file() and f.suffix in ['.txt','.json','.log','.sha256']:z.write(f,'evolution-evidence/task01/'+f.name)
for key in ['source.zip','evidence.zip']:
 with zipfile.ZipFile(paths[key]) as z:assert z.testzip() is None
paths['SHA256SUMS.txt'].write_text(''.join(sha(f)+'  '+f.name+'\n' for k,f in paths.items() if k!='SHA256SUMS.txt'))
for f in paths.values():print(f)
