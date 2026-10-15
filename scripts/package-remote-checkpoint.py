#!/usr/bin/env python3
"""Package a partial host/device checkpoint without replacing previous artifacts."""
import argparse
import hashlib
import json
import os
import pathlib
import shutil
import zipfile

ROOT = pathlib.Path(__file__).resolve().parents[1]
p = argparse.ArgumentParser(description=__doc__)
p.add_argument('--evidence', type=pathlib.Path, required=True)
p.add_argument('--output', type=pathlib.Path, required=True)
p.add_argument('--upstream-source-bundle', type=pathlib.Path, required=True)
a = p.parse_args()
def sha(path):
    with path.open('rb') as f:
        return hashlib.file_digest(f, 'sha256').hexdigest()
summary = json.loads((a.evidence / 'summary.json').read_text())
assert summary['status'] in ['host-validated-device-unqualified', 'device-validated-partial']
assert summary['baseIntegrity']['qualifiedBasePreserved']
assert summary['deviceTested'] == (summary['status']=='device-validated-partial')
out = a.output.resolve()
out.mkdir(parents=True, exist_ok=True)
name = summary['checkpoint']
app = ROOT / 'android-app/build/outputs/apk/debug/android-app-debug.apk'
test = ROOT / 'android-app/build/outputs/apk/androidTest/debug/android-app-debug-androidTest.apk'
assert sha(app) == summary['appSha256'] and sha(test) == summary['testSha256']
sources = {name + '-arm64-debug.apk': app, name + '-androidTest.apk': test,
           'Raw-Input-Probe.exe': ROOT / 'build/remote-input/raw-input-x64.exe',
           'WASAPI-Audio-Probe.exe': ROOT / 'build/remote-audio/wasapi-audio-x64.exe'}
generated = [name + '-source-snapshot.zip', name + '-evidence.zip', 'validation.json', 'SHA256SUMS.txt']
if any((out / n).exists() for n in list(sources) + generated):
    raise RuntimeError('Artifacts already exist; select a new directory to preserve them')
for n, f in sources.items():
    shutil.copy2(f, out / n)
source_info = dict(path=os.path.relpath(a.upstream_source_bundle.resolve(), out), sha256=sha(a.upstream_source_bundle))
summary['upstreamSourceBundle'] = source_info
(out / 'validation.json').write_text(json.dumps(summary, indent=2) + '\n')
excluded = {'.git', '.gradle', '.kotlin', '.cxx', 'build', '__pycache__', '.idea', 'sources'}
with zipfile.ZipFile(out / generated[0], 'w', zipfile.ZIP_DEFLATED, compresslevel=6) as z:
    for current, dirs, files in os.walk(ROOT):
        dirs[:] = sorted(d for d in dirs if d not in excluded)
        for f in sorted(files):
            path = pathlib.Path(current) / f
            if f == 'local.properties' or '.bak' in f or path.suffix in {'.jks', '.keystore'}:
                continue
            if path.is_symlink():
                raise RuntimeError('Unexpected source symlink')
            z.write(path, 'FrostBox/' + path.relative_to(ROOT).as_posix())
    z.writestr('UPSTREAM-SOURCES.json', json.dumps(source_info, indent=2) + '\n')
with zipfile.ZipFile(out / generated[1], 'w', zipfile.ZIP_DEFLATED, compresslevel=6) as z:
    for path in sorted(a.evidence.rglob('*')):
        if path.is_file():
            z.write(path, 'remote-evidence/' + path.relative_to(a.evidence).as_posix())
    z.write(ROOT / 'docs/REMOTE_INPUT_AUDIO.md', 'REMOTE_INPUT_AUDIO.md')
    if (ROOT / 'docs/PHYSICAL_DEVICE_RECOVERY.md').is_file():
        z.write(ROOT / 'docs/PHYSICAL_DEVICE_RECOVERY.md', 'PHYSICAL_DEVICE_RECOVERY.md')
for n in generated[:2]:
    with zipfile.ZipFile(out / n) as z:
        assert z.testzip() is None
(out / 'SHA256SUMS.txt').write_text(''.join(sha(f) + '  ' + f.name + '\n' for f in sorted(out.iterdir()) if f.is_file() and f.name != 'SHA256SUMS.txt'))
for f in sorted(out.iterdir()):
    print(f'{f.name}: {f.stat().st_size} bytes')
