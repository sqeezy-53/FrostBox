#!/usr/bin/env python3
"""Box64 Vulkan qualification under the application UID. Preserves all app data and qualified runtimes."""
import argparse,hashlib,json,pathlib,re,subprocess,time
ROOT=pathlib.Path(__file__).resolve().parents[1]
p=argparse.ArgumentParser(description=__doc__)
p.add_argument('serial');p.add_argument('--output',type=pathlib.Path,default=ROOT/'build/box64-vulkan-device-results')
p.add_argument('--no-install',action='store_true');p.add_argument('--methods',nargs='+',default=['computeDynarec','computeInterpreter','dispatchNegativeControl','repeatedCompute'])
a=p.parse_args();a.output.mkdir(parents=True,exist_ok=False)
base=['adb','-s',a.serial];report={'schema':1,'serial':a.serial,'passed':False,'started':time.time(),'tests':[],'execution':'Android instrumentation -> Binder :runtime under app UID; ELF -> Box64 -> system ARM64 Vulkan; optional Windows PE -> isolated Wine -> Box64 -> Vulkan; no root'}
def command(args,path,timeout=60):
 r=subprocess.run(base+args,text=True,capture_output=True,timeout=timeout)
 path.write_text(r.stdout+r.stderr)
 if r.returncode: raise RuntimeError(f'ADB failed: {path}')
 return r.stdout
try:
 command(['get-state'],a.output/'adb-state.txt')
 import zipfile
 app=ROOT/'android-app/build/outputs/apk/debug/android-app-debug.apk'
 with zipfile.ZipFile(app) as z:
  if 'assets/runtime/vulkan-compute-x64.elf' not in z.namelist(): raise RuntimeError('APK lacks x64 Vulkan ELF probe')
 command(['shell','getprop'],a.output/'getprop.txt')
 command(['shell','pm','list','instrumentation'],a.output/'instrumentation-runners.txt')
 for i,(package,apk) in enumerate([('org.frostbox.app',ROOT/'android-app/build/outputs/apk/debug/android-app-debug.apk'),('org.frostbox.app.test',ROOT/'android-app/build/outputs/apk/androidTest/debug/android-app-debug-androidTest.apk')]):
  if not a.no_install:
   installed=command(['install','-r','-t',str(apk)],a.output/f'install-{i}.txt',900)
   if not re.search(r'^Success\s*$',installed,re.M): raise RuntimeError('Installation did not succeed')
  path=command(['shell','pm','path',package],a.output/f'path-{i}.txt').strip().removeprefix('package:')
  if not re.fullmatch(r'/data/app/[A-Za-z0-9_~=/+.-]+\.apk',path): raise RuntimeError('Unexpected APK path')
  actual=command(['shell','sha256sum',path],a.output/f'apk-sha-{i}.txt').split()[0]
  expected=hashlib.sha256(apk.read_bytes()).hexdigest()
  if actual!=expected: raise RuntimeError('Installed APK differs from local build')
  report[package+'Sha256']=actual
 for method in a.methods:
  folder=a.output/method;folder.mkdir(exist_ok=True)
  with (folder/'logcat.txt').open('w') as log:
   capture=subprocess.Popen(base+['logcat','-v','threadtime','-T','1','-s','FrostBoxRuntime','FrostBoxWine','Vulkan','FrostBoxNative','AndroidRuntime','libc','DEBUG'],stdout=log,stderr=subprocess.STDOUT)
   try:
    text=command(['shell','am','instrument','-w','-r','-e','class','org.frostbox.app.runtime.Box64VulkanDeviceTest#'+method,'org.frostbox.app.test/androidx.test.runner.AndroidJUnitRunner'],folder/'instrumentation.txt',600)
   finally:
    capture.terminate();capture.wait(timeout=10)
    command(['shell','dumpsys','activity','exit-info','org.frostbox.app'],folder/'exit-info.txt')
    command(['shell','ps','-A','-o','UID,PID,PPID,NAME'],folder/'processes-after.txt')
  events=[]
  for line in text.splitlines():
   marker='INSTRUMENTATION_STATUS: frostbox.bridge.result='
   if line.startswith(marker):events.append(json.loads(line[len(marker):]))
  passed=bool(re.search(r'^OK \(1 test\)',text,re.M)) and not re.search(r'FAILURES|INSTRUMENTATION_FAILED|Process crashed',text)
  expected_events=10 if method=='repeatedCompute' else 1
  if len(events)!=expected_events: passed=False
  # Reject truncated stdout/malformed reports. Bounded stderr truncation is explicitly recorded.
  for event in events:
   if event.get('error') or event.get('droppedOut',0): passed=False
  # Private process reports contain real stdout/stderr, UID/PID, exit code and timings.
  pull=subprocess.run(base+['exec-out','run-as','org.frostbox.app','tar','-cf','-','files/vulkan-bridge-evidence'],capture_output=True,timeout=60)
  if pull.returncode==0: (folder/'bridge-evidence.tar').write_bytes(pull.stdout)
  else: (folder/'bridge-evidence-error.txt').write_bytes(pull.stderr);passed=False
  command(['shell','dumpsys','meminfo','org.frostbox.app'],folder/'meminfo-after.txt')
  item={'method':method,'passed':passed,'events':events}
  (folder/'validation.json').write_text(json.dumps(item,indent=2)+'\n');report['tests'].append({'method':method,'passed':passed,'eventCount':len(events)})
  print(('PASS' if passed else 'FAIL')+': '+method,flush=True)
 report['passed']=all(r['passed'] for r in report['tests'])
except Exception as e:report['error']=str(e);print('FAIL: '+str(e),flush=True)
finally:
 report['finished']=time.time();(a.output/'validation.json').write_text(json.dumps(report,indent=2)+'\n')
raise SystemExit(0 if report['passed'] else 1)
