#!/usr/bin/env python3
"""Build only original fixtures. No downloads and no host-global installations."""
import argparse, hashlib, json, os, pathlib, subprocess
ROOT=pathlib.Path(__file__).resolve().parents[1]
p=argparse.ArgumentParser();p.add_argument('--output',type=pathlib.Path,required=True);p.add_argument('--sdk',type=pathlib.Path)
a=p.parse_args()
def sdk_root():
    if a.sdk:return a.sdk.resolve()
    for key in ('ANDROID_HOME','ANDROID_SDK_ROOT'):
        if os.getenv(key):return pathlib.Path(os.environ[key]).resolve()
    local=ROOT/'local.properties'
    if local.exists():
        for line in local.read_text().splitlines():
            if line.startswith('sdk.dir='):return pathlib.Path(line.split('=',1)[1]).resolve()
    raise SystemExit('Set ANDROID_HOME or local.properties sdk.dir')
ndk=sdk_root()/'ndk/27.2.12479018'
bin=ndk/'toolchains/llvm/prebuilt/linux-x86_64/bin'
out=a.output.resolve();libs=out/'jniLibs/arm64-v8a';assets=out/'assets/runtime'
libs.mkdir(parents=True,exist_ok=True);assets.mkdir(parents=True,exist_ok=True)
def run(*args):subprocess.run([str(v) for v in args],check=True)
run(bin/'aarch64-linux-android29-clang++','-std=c++20','-O1','-fPIE','-pie','-static-libstdc++','-Wl,-z,max-page-size=16384',ROOT/'runtime/process/probe.cpp','-o',libs/'libfrostbox_probe.so')
run(bin/'clang','--target=x86_64-linux-gnu','-c',ROOT/'tests/guest/compute-x64.S','-o',out/'compute.o')
run(bin/'ld.lld','-m','elf_x86_64','-static','-z','max-page-size=16384','-e','_start',out/'compute.o','-o',assets/'compute-x64.elf')
hash=hashlib.sha256((assets/'compute-x64.elf').read_bytes()).hexdigest()
(assets/'fixture.json').write_text(json.dumps({'schema':1,'sha256':hash,'expectedStdout':'FROSTBOX_ELF_RESULT=5050\n','exitCode':42},indent=2)+'\n')

run(bin/"x86_64-linux-android29-clang", "-O1", "-fPIE", "-pie", "-Wl,-z,max-page-size=16384", ROOT/"tests/guest/bionic-probe.c", "-o", assets/"bionic-probe.elf")
(assets/"bionic-fixture.json").write_text(json.dumps({"sha256":hashlib.sha256((assets/"bionic-probe.elf").read_bytes()).hexdigest()},indent=2)+"\n")
