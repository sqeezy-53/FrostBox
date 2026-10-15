#!/usr/bin/env python3
"""Separate Win64 capability PE. Does not rebuild or replace qualified compute probes."""
import argparse, hashlib, json, pathlib, shutil, subprocess
ROOT = pathlib.Path(__file__).resolve().parents[1]
p = argparse.ArgumentParser(description=__doc__)
p.add_argument('--sdk', type=pathlib.Path, required=True)
p.add_argument('--output', type=pathlib.Path, required=True)
a = p.parse_args(); out = a.output.resolve(); out.mkdir(parents=True, exist_ok=True)
assets = out / 'assets/runtime'; assets.mkdir(parents=True, exist_ok=True)
include = a.sdk.resolve() / 'ndk/27.2.12479018/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/include'
for name in ['vulkan', 'vk_video']:
    shutil.copytree(include / name, out / 'include' / name, dirs_exist_ok=True)
exports = {'kernel32': ['LoadLibraryA', 'GetProcAddress', 'FreeLibrary', 'ExitProcess', 'GetCurrentProcessId'],
           'msvcrt': ['printf', 'fflush', 'strcmp']}
for dll, names in exports.items():
    definition = out / (dll + '.def')
    definition.write_text('LIBRARY ' + dll + '.dll\nEXPORTS\n' + '\n'.join(names) + '\n')
    subprocess.run(['llvm-dlltool', '-m', 'i386:x86-64', '-d', str(definition), '-l', str(out / (dll + '.lib'))], check=True)
source = ROOT / 'tests/vulkan/wsi-capability.c'
subprocess.run(['clang', '--target=x86_64-pc-windows-msvc', '-std=c11', '-ffreestanding', '-fno-stack-protector', '-O1',
                '-Wall', '-Wextra', '-Werror', '-Wno-missing-field-initializers', '-I' + str(out / 'include'),
                '-c', str(source), '-o', str(out / 'probe.obj')], check=True)
exe = assets / 'vulkan-wsi-capability-x64.exe'
subprocess.run(['lld-link', '/entry:mainCRTStartup', '/subsystem:console', '/nodefaultlib', '/timestamp:0',
                '/out:' + str(exe), str(out / 'probe.obj'), str(out / 'kernel32.lib'), str(out / 'msvcrt.lib')], check=True)
(assets / (exe.name + '.json')).write_text(json.dumps({'schema': 1, 'sha256': hashlib.sha256(exe.read_bytes()).hexdigest(),
    'sourceSha256': hashlib.sha256(source.read_bytes()).hexdigest(), 'expectedBlockedExitCode': 44,
    'presentationFixture': False}, indent=2) + '\n')
print(exe)
