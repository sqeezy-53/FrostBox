#!/usr/bin/env python3
"""Records host-only evidence; never labels x86 Linux Wine as Android CPU translation."""
import argparse, json, pathlib, subprocess, time
p=argparse.ArgumentParser()
p.add_argument('--runner',type=pathlib.Path,required=True)
p.add_argument('--wine',type=pathlib.Path,required=True)
p.add_argument('--wineserver',type=pathlib.Path,required=True)
p.add_argument('--profile',type=pathlib.Path,required=True)
p.add_argument('--fixtures',type=pathlib.Path,required=True)
p.add_argument('--output',type=pathlib.Path,required=True)
a=p.parse_args();started=time.monotonic()
args=[a.runner,a.wine,a.wineserver,a.profile,a.fixtures/'console-x64.exe',a.fixtures/'console-x86.exe']
r=subprocess.run([str(v.resolve()) for v in args],capture_output=True,text=True,timeout=240)
events=[json.loads(line) for line in r.stdout.splitlines() if line.startswith('{')]
report={'platform':'Linux x86_64 host; no ARM translation; no Android validation','elapsedMs':round((time.monotonic()-started)*1000,3),'returnCode':r.returncode,'events':events,'stderr':r.stderr}
a.output.parent.mkdir(parents=True,exist_ok=True);a.output.write_text(json.dumps(report,indent=2)+'\n')
if r.returncode:raise SystemExit(r.returncode)
for stage in ('pe-x64','pe-x86'):
    event=next(e['result'] for e in events if e['stage']==stage)
    assert event['stdout']=='FROSTBOX_PE_RESULT=5050\n' and event['exitCode']==42
print('Host Wine x64 and x86 evidence recorded; no Android compatibility claim.')
