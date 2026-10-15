#!/usr/bin/env python3
"""Opt-in app-local input and actual AudioTrack tests. No root, INJECT_EVENTS or data wipe."""
import argparse
import hashlib
import json
import pathlib
import re
import subprocess
import time

ROOT = pathlib.Path(__file__).resolve().parents[1]
RUNNER = 'org.frostbox.app.test/androidx.test.runner.AndroidJUnitRunner'
TEST = 'org.frostbox.app.library.RemoteInputAudioDeviceTest'
p = argparse.ArgumentParser(description=__doc__)
p.add_argument('--serial')
p.add_argument('--output', type=pathlib.Path, default=ROOT / 'build' / ('remote-device-' + time.strftime('%Y%m%d-%H%M%S')))
p.add_argument('--no-install', action='store_true')
p.add_argument('--regressions', action='store_true', help='Also run five bounded existing regression methods')
a = p.parse_args()
a.output.mkdir(parents=True, exist_ok=True)
report = dict(schema=1, passed=False, deviceTested=False, physicalInputTested=False,
              gameplayVerified=False, audibilityVerified=False, tests=[])
base = ['adb']

def run(args, name, timeout=60):
    result = subprocess.run(base + args, capture_output=True, text=True, timeout=timeout)
    (a.output / name).write_text(result.stdout + result.stderr)
    if result.returncode:
        raise RuntimeError(f'Command failed; see {name}')
    return result.stdout

try:
    devices = run(['devices', '-l'], 'adb-devices.txt')
    online = re.findall(r'^(\S+)\s+device(?:\s|$)', devices, re.M)
    if not online:
        report['blocked'] = 'No online ADB device. No Android tests executed.'
    else:
        serial = a.serial or (online[0] if len(online) == 1 else None)
        if serial not in online:
            raise RuntimeError('Select one online device with --serial')
        base += ['-s', serial]
        report['serial'] = serial
        run(['shell', 'getprop'], 'getprop.txt')
        for package, apk in [('org.frostbox.app', ROOT / 'android-app/build/outputs/apk/debug/android-app-debug.apk'),
                             ('org.frostbox.app.test', ROOT / 'android-app/build/outputs/apk/androidTest/debug/android-app-debug-androidTest.apk')]:
            if not a.no_install:
                if not re.search(r'^Success\s*$', run(['install', '-r', '-t', str(apk)], package + '-install.txt', 900), re.M):
                    raise RuntimeError('APK installation did not succeed')
            path = run(['shell', 'pm', 'path', package], package + '-path.txt').strip().removeprefix('package:')
            if not re.fullmatch(r'/data/app/[A-Za-z0-9_~=/+.-]+\.apk', path):
                raise RuntimeError('Unexpected APK path')
            sha = run(['shell', 'sha256sum', path], package + '-sha256.txt').split()[0]
            with apk.open('rb') as f:
                expected = hashlib.file_digest(f, 'sha256').hexdigest()
            if sha != expected:
                raise RuntimeError('Installed APK differs from the local build')
            report[package + 'Sha256'] = sha
        if RUNNER not in run(['shell', 'pm', 'list', 'instrumentation'], 'runners.txt'):
            raise RuntimeError('Instrumentation runner is missing')
        methods = [TEST + '#rawKeyboardAndRelativeMouseInternalBridge', TEST + '#wasapiPcmToActualAndroidOutput']
        if a.regressions:
            methods += ['org.frostbox.app.runtime.LibraryDeviceTest#addPlayRepeatLogsAndRecreate',
                        'org.frostbox.app.runtime.Box64VulkanDeviceTest#windowsCompute',
                        'org.frostbox.app.runtime.DisplayDeviceTest#editUnicodeBridgeExtended',
                        'org.frostbox.app.runtime.DisplayDeviceTest#touchControlsReachWin32',
                        'org.frostbox.app.runtime.SurfaceIpcDeviceTest#d3d11CubeThroughDxvk']
        for index, method in enumerate(methods):
            with (a.output / f'{index}-logcat.txt').open('w') as log:
                capture = subprocess.Popen(base + ['logcat', '-v', 'threadtime', '-T', '1', '-s',
                    'FrostBoxWine', 'FrostBoxRuntime', 'FrostBoxSurfaceIPC', 'AndroidRuntime', 'libc', 'DEBUG'], stdout=log, stderr=subprocess.STDOUT)
                try:
                    text = run(['shell', 'am', 'instrument', '-w', '-r', '-e', 'frostbox.audioQa', 'true',
                                '-e', 'class', method, RUNNER], f'{index}-instrumentation.txt', 600)
                finally:
                    capture.terminate()
                    try:
                        capture.wait(timeout=10)
                    except subprocess.TimeoutExpired:
                        capture.kill(); capture.wait()
                    run(['shell', 'dumpsys', 'activity', 'exit-info', 'org.frostbox.app'], f'{index}-exit-info.txt')
                    run(['shell', 'ps', '-A', '-o', 'UID,PID,PPID,NAME'], f'{index}-processes.txt')
            report['deviceTested'] = True
            events = [json.loads(line.split('=', 1)[1]) for line in text.splitlines()
                      if line.startswith('INSTRUMENTATION_STATUS: frostbox.remote.result=')]
            passed = bool(re.search(r'^OK \(1 test\)', text, re.M)) and not re.search(r'FAILURES|INSTRUMENTATION_FAILED|Process crashed', text)
            if method.startswith(TEST):
                passed = passed and len(events) == 1 and events[0].get('exitCode') == 42
            report['tests'].append(dict(method=method, passed=passed, events=events))
            print(('PASS ' if passed else 'FAIL ') + method, flush=True)
        report['passed'] = all(row['passed'] for row in report['tests'])
except Exception as e:
    report['error'] = str(e)
finally:
    (a.output / 'validation.json').write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps({key: report[key] for key in ['passed', 'deviceTested', 'physicalInputTested', 'gameplayVerified', 'audibilityVerified']}, indent=2))
raise SystemExit(0 if report['passed'] else 2 if report.get('blocked') else 1)
