#!/usr/bin/env python3
"""Exercise native --peek-manifest without private APK fixtures.

Usage: python3 debug/manifest_unicode_test.py runtime/build/bin/macapk-runtime
"""
import json
from pathlib import Path
import struct
import subprocess
import sys
import tempfile
import zipfile


def chunk(kind, header_size, body):
    return struct.pack('<HHI', kind, header_size, 8 + len(body)) + body


def length8(n):
    return bytes([n]) if n < 128 else bytes([0x80 | (n >> 8), n & 255])


def manifest(label, utf8):
    strings = ['manifest', 'package', 'example.unicode', 'application', 'label', label]
    data = bytearray()
    offsets = []
    for value in strings:
        offsets.append(len(data))
        units = len(value.encode('utf-16-le')) // 2
        if utf8:
            encoded = value.encode('utf-8')
            data += length8(units) + length8(len(encoded)) + encoded + b'\0'
        else:
            encoded = value.encode('utf-16-le')
            data += struct.pack('<H', units) + encoded + b'\0\0'
    data += b'\0' * (-len(data) % 4)
    pool = chunk(1, 28, struct.pack('<IIIII', len(strings), 0,
                 0x100 if utf8 else 0, 28 + 4 * len(strings), 0)
                 + struct.pack('<' + 'I' * len(strings), *offsets) + data)

    def element(name, attribute, value):
        attr = struct.pack('<IIIHBBI', 0xffffffff, attribute, value, 8, 0, 3, value)
        ext = struct.pack('<IIHHHHHH', 0xffffffff, name, 20, 20, 1, 0, 0, 0)
        return chunk(0x102, 16, struct.pack('<II', 1, 0xffffffff) + ext + attr)

    body = pool + element(0, 1, 2) + element(3, 4, 5)
    return chunk(3, 8, body)


def main():
    runtime = str(Path(sys.argv[1]).resolve())
    labels = ['Plain ASCII', '原版对比·离线', '中文 🎲 日本語 한국어', '测试' * 100]
    with tempfile.TemporaryDirectory(prefix='macapk-unicode-') as directory:
        for utf8 in (False, True):
            for index, label in enumerate(labels):
                apk = Path(directory) / f'{utf8}-{index}.apk'
                with zipfile.ZipFile(apk, 'w') as archive:
                    archive.writestr('AndroidManifest.xml', manifest(label, utf8))
                result = subprocess.run([runtime, '--peek-manifest', str(apk)],
                                        check=True, capture_output=True, text=True)
                parsed = json.loads(result.stdout)
                assert parsed['package'] == 'example.unicode', parsed
                assert parsed['label'] == label, parsed
    print('PASS: native manifest labels preserve UTF-8/UTF-16, CJK, emoji and long strings')


if __name__ == '__main__':
    main()
