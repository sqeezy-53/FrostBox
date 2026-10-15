#!/usr/bin/env python3
"""Check only publishable source paths; never print suspected credential values."""
import argparse
import pathlib
import re
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser()
parser.add_argument('--staged', action='store_true')
args = parser.parse_args()
command = ['git', 'ls-files', '-z']
if not args.staged:
    command += ['--cached', '--others', '--exclude-standard']
paths = sorted(set(filter(None, subprocess.check_output(command, cwd=ROOT).decode().split('\0'))))
errors = []
total = 0
for name in paths:
    path = pathlib.PurePosixPath(name)
    if any(p in {'.git', '.gradle', '.cxx', 'build', 'node_modules'} for p in path.parts):
        errors.append((name, 'generated or cached data'))
    if name.startswith('docs/evidence/') or path.suffix.lower() in {
        '.apk', '.aab', '.exe', '.dll', '.msi', '.so', '.elf', '.jks', '.keystore', '.pem', '.p12', '.log'
    } or '.bak' in path.name or path.name == 'local.properties' or path.name.startswith('.env'):
        errors.append((name, 'private, generated or executable artifact'))
    if args.staged:
        data = subprocess.check_output(['git', 'show', ':' + name], cwd=ROOT)
    else:
        source = ROOT / name
        if source.is_symlink():
            errors.append((name, 'source symlinks require explicit review'))
            continue
        if not source.is_file():
            continue
        data = source.read_bytes()
    total += len(data)
    if len(data) > 4 * 1024 * 1024:
        errors.append((name, 'file exceeds source size gate'))
    if data.startswith((b'\x7fELF', b'MZ')):
        errors.append((name, 'executable content'))
    text = data.decode('utf8', errors='replace')
    patterns = {
        'credential': r'gh[pousr]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,}|-----BEGIN (?:RSA |OPENSSH |EC )?PRIVATE KEY-----',
        'personal absolute path': r'/home/(?!YOUR_USER|user|example)[A-Za-z0-9_.-]+/',
    }
    for label, pattern in patterns.items():
        if re.search(pattern, text):
            errors.append((name, label))
if errors:
    for name, reason in errors:
        print(f'{name}: {reason}', file=sys.stderr)
    sys.exit(1)
print(f'Source gate PASS: {len(paths)} paths, {total} bytes; no detected forbidden artifacts or credentials.')
print('This targeted gate supplements manual license/privacy review; it is not a general secret scanner.')
