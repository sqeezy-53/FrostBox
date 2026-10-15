#!/usr/bin/env python3
import hashlib,json,pathlib
root=pathlib.Path(__file__).resolve().parents[1]
lock=json.loads((root/'third-party/runtime-lock.json').read_text())['box64']
pkg=root/'build/box64-package';metadata=json.loads((pkg/'assets/runtime/box64.json').read_text())
assert all(metadata.get(k)==v for k,v in lock.items()),'Box64 package differs from source lock'
assert hashlib.sha256((pkg/'jniLibs/arm64-v8a/libbox64.so').read_bytes()).hexdigest()==metadata['binarySha256'],'Box64 binary checksum mismatch'
print('Box64 package metadata and checksum verified')
