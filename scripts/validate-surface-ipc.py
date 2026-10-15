#!/usr/bin/env python3
"""POCO Surface ownership and optional genuine Windows WSI/D3D11 evidence. Never clears data."""
import argparse, hashlib, json, pathlib, re, subprocess, time
ROOT = pathlib.Path(__file__).resolve().parents[1]
DEFAULT = ['SurfaceIpcDeviceTest#parcelableSurfaceOwnershipAndLifecycle', 'SurfaceIpcDeviceTest#runtimeDeathAndFreshSurface',
           'SurfaceIpcDeviceTest#currentBox64HasNoExportedMain', 'SurfaceIpcDeviceTest#surfaceParcelCannotBeFlattenedForUnixSocket',
           'WsiCapabilityDeviceTest#currentWineDoesNotExposeWin32Surface']

def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('serial'); p.add_argument('--output', type=pathlib.Path, required=True)
    p.add_argument('--no-install', action='store_true'); p.add_argument('--methods', nargs='+', default=DEFAULT)
    a = p.parse_args(); a.output.mkdir(parents=True, exist_ok=False)
    adb = ['adb', '-s', a.serial]
    report = {'schema': 1, 'started': time.time(), 'serial': a.serial, 'tests': [], 'passed': False,
              'windowsPresentation': False, 'dxvk': False, 'gameplay': False,
              'execution': 'Separate Wine/Box64 guest; guest-local WSI image/SYNC_FD transport; Binder Android Surface and host Present in :runtime; results depend on selected method'}
    def command(args, path, timeout=60):
        r = subprocess.run(adb + args, text=True, capture_output=True, timeout=timeout)
        path.write_text(r.stdout + r.stderr)
        if r.returncode: raise RuntimeError(f'ADB failed: {path}')
        return r.stdout
    try:
        command(['get-state'], a.output / 'adb-state.txt')
        command(['shell', 'getprop'], a.output / 'getprop.txt')
        command(['shell', 'pm', 'list', 'instrumentation'], a.output / 'instrumentation-runners.txt')
        pairs = [('org.frostbox.app', ROOT / 'android-app/build/outputs/apk/debug/android-app-debug.apk'),
                 ('org.frostbox.app.test', ROOT / 'android-app/build/outputs/apk/androidTest/debug/android-app-debug-androidTest.apk')]
        for i, (package, apk) in enumerate(pairs):
            if not a.no_install:
                result = command(['install', '-r', '-t', str(apk)], a.output / f'install-{i}.txt', 900)
                if not re.search(r'^Success\s*$', result, re.M): raise RuntimeError('APK installation did not succeed')
            path = command(['shell', 'pm', 'path', package], a.output / f'apk-path-{i}.txt').strip().removeprefix('package:')
            if not re.fullmatch(r'/data/app/[A-Za-z0-9_~=/+.-]+\.apk', path): raise RuntimeError('Unexpected installed APK path')
            actual = command(['shell', 'sha256sum', path], a.output / f'apk-sha-{i}.txt').split()[0]
            expected = hashlib.file_digest(apk.open('rb'), 'sha256').hexdigest()
            if actual != expected: raise RuntimeError('Device/local APK SHA mismatch')
            report[package + 'Sha256'] = actual
        for method in a.methods:
            if not re.fullmatch(r'[A-Za-z]+DeviceTest#[A-Za-z0-9]+', method): raise RuntimeError('Invalid test method')
            folder = a.output / method.replace('#', '-'); folder.mkdir()
            started = time.time()
            with (folder / 'logcat.txt').open('w') as log:
                capture = subprocess.Popen(adb + ['logcat', '-v', 'threadtime', '-T', '1', '-s', 'FrostBoxSurfaceIPC',
                    'FrostBoxRuntime', 'FrostBoxWine', 'AndroidRuntime', 'libc', 'DEBUG'], stdout=log, stderr=subprocess.STDOUT)
                try:
                    text = command(['shell', 'am', 'instrument', '-w', '-r', '-e', 'class', 'org.frostbox.app.runtime.' + method,
                                    'org.frostbox.app.test/androidx.test.runner.AndroidJUnitRunner'], folder / 'instrumentation.txt', 240)
                finally:
                    capture.terminate(); capture.wait(timeout=10)
                    command(['shell', 'dumpsys', 'activity', 'exit-info', 'org.frostbox.app'], folder / 'exit-info.txt')
                    command(['shell', 'ps', '-A', '-o', 'UID,PID,PPID,NAME'], folder / 'processes-after.txt')
            events = []
            for line in text.splitlines():
                marker = 'INSTRUMENTATION_STATUS: frostbox.surface.result='
                if line.startswith(marker): events.append(json.loads(line[len(marker):]))
            passed = bool(re.search(r'^OK \(1 test\)', text, re.M)) and bool(events) and not re.search(r'FAILURES|INSTRUMENTATION_FAILED|Process crashed', text)
            pulled = subprocess.run(adb + ['exec-out', 'run-as', 'org.frostbox.app', 'tar', '-cf', '-', 'files/surface-ipc-evidence'], capture_output=True, timeout=60)
            if pulled.returncode == 0: (folder / 'private-evidence.tar').write_bytes(pulled.stdout)
            else: (folder / 'evidence-error.txt').write_bytes(pulled.stderr); passed = False
            item = {'method': method, 'passed': passed, 'elapsedSeconds': time.time() - started, 'events': events}
            (folder / 'validation.json').write_text(json.dumps(item, indent=2) + '\n')
            report['tests'].append({k: v for k, v in item.items() if k != 'events'})
            if passed and method in ['SurfaceIpcDeviceTest#windowsTriangleThroughGuestWsi','SurfaceIpcDeviceTest#d3d11CubeThroughDxvk']:
                results=[e.get('result',e) for e in events]
                proven=any(r.get('exitCode')==42 and r.get('distinctPixelCopies',0)>=2 and
                    r.get('hostWsi',{}).get('guestWsi',{}).get('frames',0)>=120 for r in results)
                report['windowsPresentation']=report['windowsPresentation'] or proven
                if method.endswith('#d3d11CubeThroughDxvk'):report['dxvk']=proven

            print(('PASS' if passed else 'FAIL') + ': ' + method, flush=True)
        report['passed'] = all(item['passed'] for item in report['tests'])
    except Exception as e:
        report['error'] = str(e); print('FAIL: ' + str(e), flush=True)
    finally:
        report['finished'] = time.time(); (a.output / 'validation.json').write_text(json.dumps(report, indent=2) + '\n')
    return 0 if report['passed'] else 1

if __name__ == '__main__': raise SystemExit(main())
