#!/usr/bin/env python3
"""Dump decrypted DEXes from a rooted Android process (Yidun-style 4-byte offset).

Usage:
  ./macapk-dump.py <package> [outdir] [--serial SERIAL]

Requires: adb, root (adb root), Python 3.

It reads /proc/<pid>/maps, dumps anonymous rw-p regions in the DEX size range,
then recognizes:
  * a DEX header shifted 4 bytes past the page boundary (region starts "035\\0")
  * a normal/partitioned DEX header via the endian tag 0x12345678

Each recovered DEX gets its SHA-1 and adler32 recomputed.
"""
import hashlib
import os
import re
import struct
import subprocess
import sys
import zlib

SIZES = (0x100000, 0x3000000)  # 1 MiB .. 48 MiB


def adb(serial, *args, binary=False):
    cmd = ["adb"]
    if serial:
        cmd += ["-s", serial]
    cmd += list(args)
    return subprocess.check_output(cmd, stderr=subprocess.DEVNULL)


def u32(b, off):
    return struct.unpack("<I", b[off:off + 4])[0]


def fix_dex(raw):
    d = bytearray(raw)
    if len(d) < 0x70:
        return None
    d[12:32] = hashlib.sha1(d[32:]).digest()
    d[8:12] = struct.pack("<I", zlib.adler32(bytes(d[12:])) & 0xFFFFFFFF)
    return bytes(d)


def candidates(region):
    """Yield reconstructed DEX byte strings found in one anonymous region."""
    # 1) page starts at magic+4: "035\0" / "037\0" ...
    if region[:4] in (b"035\0", b"036\0", b"037\0", b"038\0", b"039\0"):
        fs = u32(region, 0x1C)
        hs = u32(region, 0x20)
        endian = u32(region, 0x24)
        if hs == 0x70 and endian == 0x12345678 and SIZES[0] <= fs <= SIZES[1] and fs - 4 <= len(region):
            yield b"dex\n" + region[:fs - 4]
            return

    # 2) endian-tag scan (works for a normal, unshifted header too)
    pos = 0
    while True:
        i = region.find(b"\x78\x56\x34\x12", pos)
        if i < 0:
            return
        pos = i + 1
        for base in (i - 0x28, i - 0x24):  # normal, or 4-byte-shifted page
            if base < 0 or base + 0x70 > len(region):
                continue
            fs = u32(region, base + 0x20)
            hs = u32(region, base + 0x24)
            if hs != 0x70 or not (SIZES[0] <= fs <= SIZES[1]) or base + fs > len(region):
                continue
            raw = region[base:base + fs]
            if base == i - 0x24:
                raw = b"dex\n" + raw
            if raw[:4] != b"dex\n":
                raw = b"dex\n" + raw
            yield raw[:fs]
            return


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        return 2
    pkg = sys.argv[1]
    outdir = sys.argv[2] if len(sys.argv) > 2 and not sys.argv[2].startswith("--") else "macapk-dump"
    serial = None
    if "--serial" in sys.argv:
        serial = sys.argv[sys.argv.index("--serial") + 1]

    pid = adb(serial, "shell", "pidof", pkg).decode().split()[0]
    maps = adb(serial, "exec-out", "cat", f"/proc/{pid}/maps").decode("utf-8", "replace").splitlines()
    os.makedirs(outdir, exist_ok=True)
    print(f"pid={pid} out={outdir}")
    n = 0
    for line in maps:
        m = re.match(r"([0-9a-f]+)-([0-9a-f]+) (\S+) \S+ \S+ \S+\s*(.*)", line)
        if not m:
            continue
        start, end, perm, path = int(m.group(1), 16), int(m.group(2), 16), m.group(3), m.group(4)
        size = end - start
        if perm != "rw-p" or path or not (SIZES[0] // 2 <= size <= SIZES[1]):
            continue
        skip = start // 4096
        count = (size + 4095) // 4096
        try:
            region = adb(serial, "exec-out", "dd", f"if=/proc/{pid}/mem",
                         "bs=4096", f"skip={skip}", f"count={count}")
        except subprocess.CalledProcessError:
            continue
        for dex in candidates(region):
            fixed = fix_dex(dex)
            if not fixed:
                continue
            n += 1
            path_out = os.path.join(outdir, f"classes{n}.dex")
            with open(path_out, "wb") as f:
                f.write(fixed)
            print(f"  [{n}] {hex(start)} size={len(fixed)} -> {path_out}")
            break
    print(f"dumped {n} DEX file(s)")
    return 0 if n else 1


if __name__ == "__main__":
    raise SystemExit(main())
