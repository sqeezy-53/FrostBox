#!/usr/bin/env python3
"""Run explicit same-app-UID storage checks; does not install, clear or delete app data."""
import argparse
import json
import os
from pathlib import Path
import re
import shutil
import subprocess

p=argparse.ArgumentParser(description=__doc__)
p.add_argument('--serial',required=True)
p.add_argument('--adb',default=shutil.which('adb') or str(Path(os.environ.get('ANDROID_HOME','/android-sdk'))/'platform-tools/adb'))
p.add_argument('--output',type=Path,default=Path('build/device-storage-validation'))
p.add_argument('--apply',action='store_true',help='Apply an idle-only verified plan after reviewing a previous dry run')
p.add_argument('--safety',action='store_true',help='Run isolated scratch-data boundary checks first')
a=p.parse_args();a.output.mkdir(parents=True,exist_ok=True)
base=[a.adb,'-s',a.serial]
subprocess.run(base+['get-state'],check=True,capture_output=True)
runner='org.frostbox.app.test/androidx.test.runner.AndroidJUnitRunner'
steps=[]
if a.safety:
 methods=['createsSharedDataForNewViewAndKeepsSource','sharesIdenticalPayloadPreservesPrefixAndRejectsBusyLease','rejectsStalePlanBeforeMutation','rejectsRuntimeSymlinks','recoversOnlyVerifiedUnpublishedReference']
 steps.append(('safety',','.join('org.frostbox.app.runtime.RuntimeStorageDeviceTest#'+x for x in methods),[]))
steps.append(('apply' if a.apply else 'dry-run','org.frostbox.app.runtime.RuntimeStorageDeviceTest#installedRuntimeSharingDryRunOrApply',
              ['-e','storageQa','true']+(['-e','applyStorageSharing','true'] if a.apply else [])))
results=[]
for label,cls,extra in steps:
 r=subprocess.run(base+['shell','am','instrument','-w','-r',*extra,'-e','class',cls,runner],capture_output=True,text=True,timeout=900)
 output=r.stdout+r.stderr;(a.output/(label+'.txt')).write_text(output)
 passed=r.returncode==0 and re.search(r'\bOK \(\d+ tests?\)',output) is not None and 'FAILURES!!!' not in output
 results.append({'phase':label,'passed':passed})
 (a.output/'summary.json').write_text(json.dumps(results,indent=2)+'\n')
 print(label+': '+('PASS' if passed else 'FAIL'))
 if not passed:raise SystemExit('See the private local log; no app data reset is attempted.')
print('Raw logs are private diagnostics. Review/redact before sharing. Games and Wine prefixes are excluded from reconciliation.')
