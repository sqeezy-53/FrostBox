#!/usr/bin/env python3
"""Send bounded Android input to the running karlsonGameplayAttempt instrumentation test only.
The mailbox is read by test code, not by FrostBox release code. No arbitrary shell/EXE input.
"""
import argparse,json,shlex,subprocess
p=argparse.ArgumentParser(description=__doc__);p.add_argument('serial');p.add_argument('--sequence',type=int,required=True)
g=p.add_mutually_exclusive_group(required=True);g.add_argument('--tap',type=float,nargs=2);g.add_argument('--key',choices=['ENTER','W','A','S','D','SPACE','ESC'])
p.add_argument('--hold-ms',type=int,default=100);a=p.parse_args()
if a.sequence<2 or not 1<=a.hold_ms<=3000:p.error('Sequence must be >=2, hold 1..3000 ms')
command={'sequence':a.sequence,'holdMs':a.hold_ms}
if a.tap:
 if not all(0<=v<=1 for v in a.tap):p.error('Normalized coordinates must be 0..1')
 command.update(x=a.tap[0],y=a.tap[1])
else:command['key']={'ENTER':66,'W':51,'A':29,'S':47,'D':32,'SPACE':62,'ESC':111}[a.key]
remote='run-as org.frostbox.app sh -c '+shlex.quote("printf '%s' "+shlex.quote(json.dumps(command))+' > cache/titan-qa-input.json')
subprocess.run(['adb','-s',a.serial,'shell',remote],check=True,timeout=10)
print('Queued test input; inspect final androidInputEvents for delivery confirmation')
