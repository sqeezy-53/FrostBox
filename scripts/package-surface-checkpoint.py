#!/usr/bin/env python3
"""Package an explicitly incomplete checkpoint, preserving prior releases and corresponding source."""
import argparse, hashlib, json, pathlib, shutil, zipfile
ROOT = pathlib.Path(__file__).resolve().parents[1]
NAME = 'FrostBox-0.5.0-dev.3-incomplete'

def sha(path):
    with path.open('rb') as stream: return hashlib.file_digest(stream, 'sha256').hexdigest()

def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--variant', choices=['surface', 'image-ipc', 'persistent-ipc'], default='surface')
    p.add_argument('--evidence', type=pathlib.Path, required=True)
    p.add_argument('--output', type=pathlib.Path, required=True)
    p.add_argument('--upstream-source-bundle', type=pathlib.Path, required=True)
    a = p.parse_args(); evidence = a.evidence.resolve(); output = a.output.resolve()
    summary = json.loads((evidence / 'checkpoint-summary.json').read_text())
    if not summary['checkpointChecksPassed'] or summary['windowsPresentation']:
        raise RuntimeError('This packager only supports the tested incomplete ownership checkpoint')
    output.mkdir(parents=True, exist_ok=True)
    name = {'surface': NAME, 'image-ipc': 'FrostBox-0.5.0-dev.3-image-ipc-incomplete', 'persistent-ipc': 'FrostBox-0.5.0-dev.3-persistent-ipc-incomplete'}[a.variant]
    fixture = 'wsi-capability-x64.exe' if a.variant == 'surface' else 'wsi-export-x64.elf'
    suffixes = ['arm64-debug.apk', 'androidTest.apk', fixture, 'source.zip', 'evidence.zip']
    if a.variant == 'persistent-ipc': suffixes.append('windows-vulkan-triangle-x64.exe')
    suffixes.append('SHA256SUMS.txt')
    targets = [output / (name + '-' + suffix) for suffix in suffixes]
    if any(path.exists() for path in targets): raise RuntimeError('Never overwrite checkpoint/release artifacts; choose a new directory')
    originals = [ROOT / 'android-app/build/outputs/apk/debug/android-app-debug.apk',
                 ROOT / 'android-app/build/outputs/apk/androidTest/debug/android-app-debug-androidTest.apk',
                 ROOT / ('build/wsi-capability/assets/runtime/vulkan-wsi-capability-x64.exe' if a.variant == 'surface' else 'build/wsi-image-probe/assets/runtime/wsi-export-x64.elf')]
    if sha(originals[0]) != summary['appSha256'] or sha(originals[1]) != summary['testSha256']:
        raise RuntimeError('The build changed after device qualification')
    for source, target in zip(originals, targets): shutil.copy2(source, target)
    excluded = {'.git', '.gradle', '.cxx', 'build', '__pycache__', '.idea'}
    with zipfile.ZipFile(targets[3], 'w', compression=zipfile.ZIP_DEFLATED, compresslevel=6) as archive:
        for path in sorted(ROOT.rglob('*')):
            relative = path.relative_to(ROOT)
            if any(part in excluded for part in relative.parts) or path.name == 'local.properties': continue
            if path.is_symlink(): raise RuntimeError('Unexpected source symlink: ' + str(path))
            if path.is_file(): archive.write(path, 'FrostBox/' + relative.as_posix())
        # Already pinned original upstream source, not a source-link-only replacement.
        with zipfile.ZipFile(a.upstream_source_bundle) as previous:
            sources = [name for name in previous.namelist() if name.startswith('FrostBox/third-party/sources/') and not name.endswith('/')]
            if len(sources) != 3: raise RuntimeError('Expected Wine, Box64 and FreeType corresponding upstream archives')
            for name in sources:
                info = zipfile.ZipInfo(name); info.compress_type = zipfile.ZIP_STORED
                with previous.open(name) as source, archive.open(info, 'w') as destination:
                    shutil.copyfileobj(source, destination, length=1024 * 1024)
    with zipfile.ZipFile(targets[4], 'w', compression=zipfile.ZIP_DEFLATED, compresslevel=6) as archive:
        for path in sorted(evidence.rglob('*')):
            if path.is_symlink(): raise RuntimeError('Unexpected evidence symlink')
            if path.is_file(): archive.write(path, 'wsi-validation/' + path.relative_to(evidence).as_posix())
    for path in targets[3:5]:
        with zipfile.ZipFile(path) as archive:
            if archive.testzip() is not None: raise RuntimeError('Archive integrity failure: ' + str(path))
    if a.variant == 'persistent-ipc':
        triangle = ROOT / 'build/windows-vulkan-triangle/windows-vulkan-triangle-x64.exe'
        if sha(triangle) != summary['windowsTriangle']['sha256']: raise RuntimeError('Triangle fixture changed after compilation record')
        shutil.copy2(triangle, targets[5])
    targets[-1].write_text(''.join(sha(path) + '  ' + path.name + '\n' for path in targets[:-1]))
    for path in targets: print(path)
    return 0

if __name__ == '__main__': raise SystemExit(main())
