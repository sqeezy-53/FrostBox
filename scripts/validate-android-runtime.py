#!/usr/bin/env python3
"""Install and validate the runtime via app-UID instrumentation, retaining raw device evidence."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import time

ROOT = Path(__file__).resolve().parents[1]
p = argparse.ArgumentParser(description=__doc__)
p.add_argument('serial', help='Authorized serial from adb devices -l')
p.add_argument('--adb', default=os.environ.get('ADB', 'adb'))
p.add_argument('--output', type=Path, default=ROOT / 'build/device-results')
p.add_argument('--no-install', action='store_true', help='Use already installed APKs; their hashes may differ')
p.add_argument('--baseline', type=Path, help='Passing basic validation.json, required for stress')
p.add_argument('--phase', choices=('basic', 'stress', 'bionic', 'wine-version', 'wine-prefix', 'wine-exe', 'wine-stop', 'wine-stress'), default='basic')
a = p.parse_args()
out = a.output.resolve()
out.mkdir(parents=True, exist_ok=True)
base = [a.adb, '-s', a.serial]
report = {'schema': 1, 'serial': a.serial, 'phase': a.phase, 'passed': False,
          'execution': 'Android instrumentation -> private FrostBox runtime service; not adb shell execution',
          'startedUnixSeconds': time.time(), 'results': []}

def command(args, name, timeout=60, required=True):
    try:
        r = subprocess.run(base + args, capture_output=True, text=True, timeout=timeout)
    except subprocess.TimeoutExpired as e:
        def decode(value):
            return value.decode(errors='replace') if isinstance(value, bytes) else (value or '')
        (out / name).write_text(decode(e.stdout) + decode(e.stderr))
        raise
    (out / name).write_text(r.stdout + r.stderr)
    if required and r.returncode:
        raise RuntimeError(f'{name}: adb failed ({r.returncode}); inspect {out / name}')
    return r

try:
    if shutil.which(a.adb) is None:
        raise RuntimeError('ADB not found. Install Android platform-tools or use --adb /absolute/path/adb')
    command(['get-state'], 'adb-state.txt')
    properties = command(['shell', 'getprop'], 'getprop.txt').stdout
    def prop(key):
        m = re.search(r'^\[' + re.escape(key) + r'\]: \[(.*)\]$', properties, re.M)
        return m.group(1) if m else None
    report['device'] = {k: prop(k) for k in ('ro.product.model', 'ro.product.device', 'ro.build.fingerprint',
                                            'ro.build.version.sdk', 'ro.build.version.release', 'ro.product.cpu.abilist')}
    if 'arm64-v8a' not in (prop('ro.product.cpu.abilist') or ''):
        raise RuntimeError('ARM64 device required')
    report['pageSize'] = command(['shell', 'getconf', 'PAGESIZE'], 'pagesize.txt').stdout.strip()
    report['selinux'] = command(['shell', 'getenforce'], 'selinux.txt').stdout.strip()
    if a.phase == 'stress':
        if not a.baseline:
            raise RuntimeError('Stress requires --baseline /path/to/passing/basic/validation.json')
        baseline = json.loads(a.baseline.read_text())
        if not baseline.get('passed') or baseline.get('phase') != 'basic' or baseline.get('device') != report['device']:
            raise RuntimeError('Stress gate requires four passing basic tests on this exact device build')
    report['artifacts'] = {}
    artifacts = [ROOT / 'android-app/build/outputs/apk/debug/android-app-debug.apk',
                 ROOT / 'android-app/build/outputs/apk/androidTest/debug/android-app-debug-androidTest.apk']
    if not a.no_install:
        for i, apk in enumerate(artifacts):
            if not apk.is_file():
                raise RuntimeError('Build with -PwithBox64=true, including assembleDebugAndroidTest, first')
            report['artifacts'][apk.name] = hashlib.sha256(apk.read_bytes()).hexdigest()
            r = command(['install', '-r', '-t', str(apk)], f'install-{i}.txt', timeout=900 if a.phase == 'wine-stress' else 180, required=False)
            if r.returncode or not re.search(r'^Success\s*$', r.stdout, re.M):
                raise RuntimeError(f'APK installation failed: {r.stdout.strip()} {r.stderr.strip()}. '
                                   'For INSTALL_FAILED_USER_RESTRICTED unlock the phone and approve USB installation; '
                                   'do not bypass Android restrictions.')
    report['installedArtifacts'] = {}
    for package, artifact in zip(('org.frostbox.app','org.frostbox.app.test'), artifacts):
        path = command(['shell','pm','path',package], package + '-path.txt').stdout.strip().removeprefix('package:')
        if not re.fullmatch(r'/data/app/[A-Za-z0-9_~=/+.-]+\.apk', path):
            raise RuntimeError('Unexpected installed APK path or split APK package')
        digest = command(['shell','sha256sum',path], package + '-sha256.txt').stdout.split()[0]
        report['installedArtifacts'][package] = digest
        if artifact.is_file():
            local_digest = hashlib.sha256(artifact.read_bytes()).hexdigest()
            report['artifacts'][artifact.name] = local_digest
            if local_digest != digest:
                raise RuntimeError('Installed APK differs from locally built artifact: ' + package)
    command(['shell', 'dumpsys', 'package', 'org.frostbox.app'], 'package.txt')
    klass = {'basic':'RuntimeDeviceTest','stress':'RuntimeStressDeviceTest','bionic':'BionicDeviceTest','wine-version':'WineDeviceTest#version','wine-prefix':'WineDeviceTest#prefix','wine-exe':'WineDeviceTest#windowsConsole','wine-stop':'WineDeviceTest#stopServer','wine-stress':'WineStressDeviceTest'}[a.phase]
    with (out / 'logcat.txt').open('w') as log:
        capture = subprocess.Popen(base + ['logcat', '-v', 'threadtime', '-T', '1', '-s',
                                            'FrostBoxRuntime', 'FrostBox', 'FrostBoxNative', 'AndroidRuntime', 'libc', 'DEBUG'],
                                   stdout=log, stderr=subprocess.STDOUT)
        try:
            result = command(['shell', 'am', 'instrument', '-w', '-r', '-e', 'class',
                              'org.frostbox.app.runtime.' + klass,
                              'org.frostbox.app.test/androidx.test.runner.AndroidJUnitRunner'],
                             'instrumentation.txt', timeout=900 if a.phase == 'wine-stress' else 180, required=False)
        finally:
            capture.terminate()
            try:
                capture.wait(timeout=5)
            except subprocess.TimeoutExpired:
                capture.kill()
                capture.wait()
            command(['shell', 'dumpsys', 'activity', 'exit-info', 'org.frostbox.app'], 'exit-info.txt', timeout=15, required=False)
            command(['shell', 'ps', '-A', '-o', 'UID,PID,PPID,NAME'], 'processes-after.txt', timeout=15, required=False)
    text = result.stdout
    for line in text.splitlines():
        marker = 'INSTRUMENTATION_STATUS: frostbox.runtime.result='
        if line.startswith(marker):
            report['results'].append(json.loads(line[len(marker):]))
    expected_tests = 4 if a.phase == 'basic' else 1
    expected_results = {'basic':4,'stress':20,'wine-stress':20}.get(a.phase,1)
    summary_ok = re.search(r'^OK \(' + str(expected_tests) + r' tests?\)', text, re.M)
    if not summary_ok or re.search(r'FAILURES|INSTRUMENTATION_FAILED|Process crashed', text):
        raise RuntimeError('Instrumentation did not pass; inspect instrumentation.txt and logcat.txt')
    if len(report['results']) != expected_results:
        raise RuntimeError('Runner reported success without the expected structured runtime evidence')
    modes = sorted(event.get('mode', -1) for event in report['results'])
    expected_modes = {'basic':[0,1,2,3],'stress':[1]*10+[2]*10,'bionic':[4],'wine-version':[5],'wine-prefix':[6],'wine-exe':[7],'wine-stop':[8],'wine-stress':[7]*20}[a.phase]
    if modes != expected_modes:
        raise RuntimeError('Structured evidence has missing or duplicate test modes')
    for event in report['results']:
        if event['runtimeUid'] != event['testUid'] or event['runtimePid'] == event['testPid']:
            raise RuntimeError('Runtime app UID/separate process assertion failed')
        if event['running'] or event['execErrno'] or event['timedOut'] or event['error']:
            raise RuntimeError('Native runtime result failed')
        if event['mode'] == 3:
            if not event['cancelled'] or not event['signal']:
                raise RuntimeError('Cancellation was not observed')
        else:
            expected = {0:'FROSTBOX_NATIVE_RESULT=5050\n',4:'FROSTBOX_BIONIC_RESULT=5050\n',5:'wine-11.19\n',6:'',7:'FROSTBOX_PE_RESULT=5050\n',8:''}.get(event['mode'],'FROSTBOX_ELF_RESULT=5050\n')
            expected_exit = 0 if event['mode'] in (5,6,8) else 42
            if event['stdout'] != expected or event['exitCode'] != expected_exit or event['signal'] or event['cancelled']:
                raise RuntimeError('Computation/exit code assertion failed')
    report['passed'] = True
    print(f'PASS: {expected_tests} instrumented tests, {expected_results} runtime results; {out}')
except (OSError, RuntimeError, subprocess.TimeoutExpired, KeyError, ValueError) as e:
    report['error'] = str(e)
    print(f'FAIL: {e}', file=sys.stderr)
finally:
    report['finishedUnixSeconds'] = time.time()
    (out / 'validation.json').write_text(json.dumps(report, indent=2) + '\n')
sys.exit(0 if report['passed'] else 1)
