#!/usr/bin/env python3
"""Reproducible offline synthetic APK fixture builder for COMP-001.

Builds three distinct synthetic APKs from the checked-in sources in debug/fixtures/
using local offline tools (`apktool`, `clang`, `ffmpeg`) without Android SDK,
network access, or signing keys:

  1. comp-ui-resources.apk   -- targetSdk 36 UI/resources/vector-icon/features
  2. comp-surface-media.apk  -- SurfaceView + TextureView + Canvas + synthetic audio/video assets
  3. comp-service-ipc-abi.apk -- Service (:worker) + Binder/Parcel + arm64-v8a ET_DYN JNI .so

Outputs only to the specified --out directory (default: work/upgrade/COMP-001/fixtures,
which is gitignored) plus a fixtures_manifest.json recording SHA-256 and tool versions.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import shutil
import struct
import subprocess
import sys
import tempfile
from pathlib import Path
from typing import Any


REPO_ROOT = Path(__file__).resolve().parents[2]
FIXTURE_SRC_ROOT = Path(__file__).resolve().parent
DEFAULT_OUT_DIR = REPO_ROOT / "work" / "upgrade" / "COMP-001" / "fixtures"
DEFAULT_FRAMEWORK_APK = REPO_ROOT / "thirdparty" / "framework-res" / "framework-res-api36.apk"

# ELF64 constants (System V ABI + AArch64)
EI_NIDENT = 16
ET_DYN = 3
EM_AARCH64 = 183
PT_LOAD = 1
PT_DYNAMIC = 2
PF_X = 1
PF_W = 2
PF_R = 4
SHT_NULL = 0
SHT_PROGBITS = 1
SHT_SYMTAB = 2
SHT_STRTAB = 3
SHT_HASH = 5
SHT_DYNAMIC = 6
SHT_DYNSYM = 11
SHF_WRITE = 1
SHF_ALLOC = 2
SHF_EXECINSTR = 4
STB_GLOBAL = 1
STT_FUNC = 2
DT_NULL = 0
DT_SONAME = 14
DT_HASH = 4
DT_STRTAB = 5
DT_SYMTAB = 6
DT_STRSZ = 10
DT_SYMENT = 11
PAGE_SIZE = 0x1000


def align_up(value: int, alignment: int) -> int:
    return (value + alignment - 1) & ~(alignment - 1)


def elf_sysv_hash(name: bytes) -> int:
    h = 0
    for b in name:
        h = ((h << 4) + b) & 0xFFFFFFFF
        g = h & 0xF0000000
        if g:
            h ^= g >> 24
        h &= ~g & 0xFFFFFFFF
    return h


def sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        while True:
            chunk = f.read(65536)
            if not chunk:
                break
            h.update(chunk)
    return h.hexdigest()


def run_checked(cmd: list[str], cwd: Path | None = None) -> subprocess.CompletedProcess[str]:
    proc = subprocess.run(
        cmd,
        cwd=str(cwd) if cwd else None,
        text=True,
        capture_output=True,
    )
    if proc.returncode != 0:
        raise RuntimeError(
            f"Command failed (exit {proc.returncode}): {' '.join(cmd)}\n"
            f"--- stdout ---\n{proc.stdout}\n--- stderr ---\n{proc.stderr}"
        )
    return proc


def read_cstr(buf: bytes, offset: int) -> bytes:
    end = buf.find(b"\x00", offset)
    return buf[offset:] if end == -1 else buf[offset:end]


def build_arm64_et_dyn_from_object(
    c_src: Path, out_so: Path, clang_bin: str = "clang", soname: str = "libcompprobe.so"
) -> dict[str, Any]:
    """Compile a freestanding C file to aarch64 .o and emit a minimal ET_DYN ELF64 .so."""
    with tempfile.TemporaryDirectory(prefix="compprobe_elf_") as tmp:
        obj_path = Path(tmp) / "compprobe.o"
        run_checked(
            [
                clang_bin,
                "-target",
                "aarch64-linux-android29",
                "-fPIC",
                "-O2",
                "-ffreestanding",
                "-nostdlib",
                "-c",
                str(c_src),
                "-o",
                str(obj_path),
            ]
        )
        obj = obj_path.read_bytes()

    # Parse ELF64 .o header and section headers
    if obj[:4] != b"\x7fELF" or obj[4] != 2 or obj[5] != 1:
        raise RuntimeError(f"Expected 64-bit little-endian ELF object from {c_src}")
    e_machine, _e_version, _e_entry, _e_phoff, e_shoff, _e_flags, _e_ehsize, _e_phentsize, _e_phnum, e_shentsize, e_shnum, e_shstrndx = struct.unpack_from(
        "<HIQQQIHHHHHH", obj, 18
    )
    if e_machine != EM_AARCH64:
        raise RuntimeError(f"Expected EM_AARCH64 (183), got {e_machine}")

    shdrs: list[tuple[int, int, int, int, int, int, int, int, int, int]] = []
    for i in range(e_shnum):
        shdrs.append(struct.unpack_from("<IIQQQQIIQQ", obj, e_shoff + i * e_shentsize))

    shstr_off, shstr_sz = shdrs[e_shstrndx][4], shdrs[e_shstrndx][5]
    shstrtab = obj[shstr_off : shstr_off + shstr_sz]

    text_idx = -1
    text_bytes = b""
    symtab_shdr = None
    strtab_bytes = b""
    rela_text_shdr = None

    for idx, sh in enumerate(shdrs):
        sh_name, sh_type, _sh_flags, _sh_addr, sh_off, sh_size, sh_link, _sh_info, _sh_align, _sh_entsize = sh
        name = read_cstr(shstrtab, sh_name).decode("ascii", errors="replace")
        if name == ".text" and sh_type == SHT_PROGBITS:
            text_idx = idx
            text_bytes = obj[sh_off : sh_off + sh_size]
        elif name == ".rela.text":
            rela_text_shdr = sh
        elif sh_type == SHT_SYMTAB:
            symtab_shdr = sh
            str_sh = shdrs[sh_link]
            strtab_bytes = obj[str_sh[4] : str_sh[4] + str_sh[5]]

    if text_idx < 0 or not text_bytes or symtab_shdr is None:
        raise RuntimeError("Object file missing .text or .symtab")
    if rela_text_shdr is not None and rela_text_shdr[5] > 0:
        raise RuntimeError(
            "compprobe.c emitted .rela.text relocations; keep functions freestanding/self-contained"
        )

    sym_off, sym_size, sym_entsize = symtab_shdr[4], symtab_shdr[5], symtab_shdr[9] or 24
    exported: list[tuple[bytes, int, int]] = []
    for pos in range(0, sym_size, sym_entsize):
        st_name, st_info, _st_other, st_shndx, st_value, st_size = struct.unpack_from(
            "<IBBHQQ", obj, sym_off + pos
        )
        bind = st_info >> 4
        stype = st_info & 0xF
        if st_shndx == text_idx and bind == STB_GLOBAL and stype == STT_FUNC and st_name != 0:
            sym_name = read_cstr(strtab_bytes, st_name)
            if sym_name:
                exported.append((sym_name, st_value, st_size))

    if not exported:
        raise RuntimeError("No global STT_FUNC symbols exported from compprobe.c")

    # Build .dynstr
    dynstr = bytearray(b"\x00")
    soname_off = len(dynstr)
    dynstr.extend(soname.encode("ascii") + b"\x00")
    sym_name_offs: list[int] = []
    for sym_name, _, _ in exported:
        sym_name_offs.append(len(dynstr))
        dynstr.extend(sym_name + b"\x00")

    # Layout inside PT_LOAD #0 (R+X at vaddr 0)
    ehdr_size = 64
    phnum = 3
    phdrs_size = phnum * 56
    hash_off = align_up(ehdr_size + phdrs_size, 8)

    nchain = 1 + len(exported)
    nbucket = max(3, nchain)
    buckets = [0] * nbucket
    chains = [0] * nchain
    for sym_idx, (sym_name, _, _) in enumerate(exported, start=1):
        b = elf_sysv_hash(sym_name) % nbucket
        chains[sym_idx] = buckets[b]
        buckets[b] = sym_idx
    hash_bytes = struct.pack(f"<{2 + nbucket + nchain}I", nbucket, nchain, *buckets, *chains)

    dynsym_off = align_up(hash_off + len(hash_bytes), 8)
    dynsym_size = nchain * 24
    dynstr_off = align_up(dynsym_off + dynsym_size, 8)
    text_off = align_up(dynstr_off + len(dynstr), 16)

    # Populate .dynsym with actual vaddr = text_off + st_value
    dynsym = bytearray(24)  # STN_UNDEF
    exported_info: dict[str, str] = {}
    for idx, ((sym_name, st_value, st_size), name_off) in enumerate(
        zip(exported, sym_name_offs), start=1
    ):
        vaddr = text_off + st_value
        info = (STB_GLOBAL << 4) | STT_FUNC
        dynsym.extend(struct.pack("<IBBHQQ", name_off, info, 0, 4, vaddr, st_size or 4))
        exported_info[sym_name.decode("ascii")] = f"0x{vaddr:x}"

    seg0_filesz = text_off + len(text_bytes)
    if seg0_filesz > PAGE_SIZE:
        raise RuntimeError(f"PT_LOAD #0 exceeded 1 page ({seg0_filesz} bytes)")

    # Layout inside PT_LOAD #1 (R+W at offset/vaddr PAGE_SIZE)
    dyn_off = PAGE_SIZE
    dyn_entries = [
        (DT_SONAME, soname_off),
        (DT_HASH, hash_off),
        (DT_STRTAB, dynstr_off),
        (DT_SYMTAB, dynsym_off),
        (DT_STRSZ, len(dynstr)),
        (DT_SYMENT, 24),
        (DT_NULL, 0),
    ]
    dyn_bytes = b"".join(struct.pack("<qQ", tag, val) for tag, val in dyn_entries)

    # Minimal section header table (SHT_NULL, .hash, .dynsym, .dynstr, .text, .dynamic, .shstrtab)
    shstrtab = (
        b"\x00.hash\x00.dynsym\x00.dynstr\x00.text\x00.dynamic\x00.shstrtab\x00"
    )
    sh_name_offs = {}
    pos = 1
    for sname in [".hash", ".dynsym", ".dynstr", ".text", ".dynamic", ".shstrtab"]:
        sh_name_offs[sname] = pos
        pos += len(sname) + 1

    shstrtab_off = dyn_off + len(dyn_bytes)
    shoff = align_up(shstrtab_off + len(shstrtab), 8)
    shentsize = 64
    shnum = 7
    shstrndx = 6
    total_size = shoff + shnum * shentsize

    image = bytearray(total_size)
    # Elf64_Ehdr
    e_ident = b"\x7fELF" + bytes([2, 1, 1, 0]) + b"\x00" * 8
    struct.pack_into(
        "<16sHHIQQQIHHHHHH",
        image,
        0,
        e_ident,
        ET_DYN,
        EM_AARCH64,
        1,
        0,
        ehdr_size,
        shoff,
        0,
        ehdr_size,
        56,
        phnum,
        shentsize,
        shnum,
        shstrndx,
    )
    # Elf64_Phdr: PT_LOAD #0 (R|X), PT_LOAD #1 (R|W), PT_DYNAMIC
    phdrs = [
        (PT_LOAD, PF_R | PF_X, 0, 0, 0, seg0_filesz, seg0_filesz, PAGE_SIZE),
        (PT_LOAD, PF_R | PF_W, dyn_off, dyn_off, dyn_off, len(dyn_bytes), len(dyn_bytes), PAGE_SIZE),
        (PT_DYNAMIC, PF_R | PF_W, dyn_off, dyn_off, dyn_off, len(dyn_bytes), len(dyn_bytes), 8),
    ]
    for i, ph in enumerate(phdrs):
        struct.pack_into("<IIQQQQQQ", image, ehdr_size + i * 56, *ph)

    image[hash_off : hash_off + len(hash_bytes)] = hash_bytes
    image[dynsym_off : dynsym_off + len(dynsym)] = dynsym
    image[dynstr_off : dynstr_off + len(dynstr)] = dynstr
    image[text_off : text_off + len(text_bytes)] = text_bytes
    image[dyn_off : dyn_off + len(dyn_bytes)] = dyn_bytes
    image[shstrtab_off : shstrtab_off + len(shstrtab)] = shstrtab

    shdrs_out = [
        (0, SHT_NULL, 0, 0, 0, 0, 0, 0, 0, 0),
        (sh_name_offs[".hash"], SHT_HASH, SHF_ALLOC, hash_off, hash_off, len(hash_bytes), 2, 0, 4, 4),
        (sh_name_offs[".dynsym"], SHT_DYNSYM, SHF_ALLOC, dynsym_off, dynsym_off, len(dynsym), 3, 1, 8, 24),
        (sh_name_offs[".dynstr"], SHT_STRTAB, SHF_ALLOC, dynstr_off, dynstr_off, len(dynstr), 0, 0, 1, 0),
        (sh_name_offs[".text"], SHT_PROGBITS, SHF_ALLOC | SHF_EXECINSTR, text_off, text_off, len(text_bytes), 0, 0, 16, 0),
        (sh_name_offs[".dynamic"], SHT_DYNAMIC, SHF_ALLOC | SHF_WRITE, dyn_off, dyn_off, len(dyn_bytes), 3, 0, 8, 16),
        (sh_name_offs[".shstrtab"], SHT_STRTAB, 0, 0, shstrtab_off, len(shstrtab), 0, 0, 1, 0),
    ]
    for i, sh in enumerate(shdrs_out):
        struct.pack_into("<IIQQQQIIQQ", image, shoff + i * shentsize, *sh)

    out_so.parent.mkdir(parents=True, exist_ok=True)
    out_so.write_bytes(bytes(image))
    return {
        "soname": soname,
        "archive_path": "lib/arm64-v8a/" + soname,
        "size": len(image),
        "sha256": sha256_file(out_so),
        "exports": exported_info,
    }


def generate_synthetic_media_assets(assets_dir: Path, ffmpeg_bin: str) -> dict[str, dict[str, Any]]:
    """Generate small deterministic AAC (.m4a) and baseline H.264 Annex-B (.h264) assets."""
    assets_dir.mkdir(parents=True, exist_ok=True)
    m4a_path = assets_dir / "probe_tone.m4a"
    h264_path = assets_dir / "probe_clip.h264"

    run_checked(
        [
            ffmpeg_bin,
            "-y",
            "-hide_banner",
            "-loglevel",
            "error",
            "-f",
            "lavfi",
            "-i",
            "sine=frequency=440:sample_rate=44100:duration=0.25",
            "-c:a",
            "aac",
            "-b:a",
            "64k",
            "-movflags",
            "+faststart",
            str(m4a_path),
        ]
    )

    run_checked(
        [
            ffmpeg_bin,
            "-y",
            "-hide_banner",
            "-loglevel",
            "error",
            "-f",
            "lavfi",
            "-i",
            "testsrc2=size=64x64:rate=10:duration=0.5",
            "-c:v",
            "libx264",
            "-profile:v",
            "baseline",
            "-pix_fmt",
            "yuv420p",
            "-f",
            "h264",
            str(h264_path),
        ]
    )

    return {
        "probe_tone.m4a": {
            "size": m4a_path.stat().st_size,
            "sha256": sha256_file(m4a_path),
        },
        "probe_clip.h264": {
            "size": h264_path.stat().st_size,
            "sha256": sha256_file(h264_path),
        },
    }


def build_apk_with_apktool(
    src_dir: Path,
    out_apk: Path,
    apktool_bin: str,
    framework_dir: Path | None = None,
    extra_populate=None,
) -> dict[str, Any]:
    with tempfile.TemporaryDirectory(prefix=f"apkbuild_{src_dir.name}_") as tmp:
        stage_dir = Path(tmp) / src_dir.name
        shutil.copytree(src_dir, stage_dir)
        extra_meta = extra_populate(stage_dir) if extra_populate else {}
        out_apk.parent.mkdir(parents=True, exist_ok=True)
        cmd = [apktool_bin, "b"]
        if framework_dir is not None:
            cmd.extend(["-p", str(framework_dir)])
        cmd.extend([str(stage_dir), "-o", str(out_apk)])
        run_checked(cmd)
    return {
        "apk_path": str(out_apk),
        "size": out_apk.stat().st_size,
        "sha256": sha256_file(out_apk),
        "extra": extra_meta,
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Build COMP-001 synthetic APK fixtures offline.")
    parser.add_argument(
        "--out",
        type=Path,
        default=DEFAULT_OUT_DIR,
        help=f"Output directory for built APKs (default: {DEFAULT_OUT_DIR})",
    )
    parser.add_argument(
        "--framework-apk",
        type=Path,
        default=DEFAULT_FRAMEWORK_APK,
        help=f"Framework resources APK for apktool -p (default: {DEFAULT_FRAMEWORK_APK})",
    )
    args = parser.parse_args(argv)

    apktool_bin = shutil.which(os.environ.get("APKTOOL", "apktool"))
    clang_bin = shutil.which(os.environ.get("CLANG", "clang"))
    ffmpeg_bin = shutil.which(os.environ.get("FFMPEG", "ffmpeg"))
    missing = [name for name, val in [("apktool", apktool_bin), ("clang", clang_bin), ("ffmpeg", ffmpeg_bin)] if not val]
    if missing:
        print(f"ERROR: missing required offline build tools: {', '.join(missing)}", file=sys.stderr)
        return 1
    if not args.framework_apk.is_file():
        print(f"ERROR: framework-res APK missing: {args.framework_apk}", file=sys.stderr)
        return 1

    out_dir = args.out.resolve()
    out_dir.mkdir(parents=True, exist_ok=True)

    apktool_ver = run_checked([apktool_bin, "--version"]).stdout.strip()
    clang_ver = run_checked([clang_bin, "--version"]).stdout.splitlines()[0].strip()
    ffmpeg_ver = run_checked([ffmpeg_bin, "-version"]).stdout.splitlines()[0].strip()

    fixtures: dict[str, Any] = {}
    with tempfile.TemporaryDirectory(prefix="apktool_fw_") as fw_tmp:
        fw_dir = Path(fw_tmp)
        run_checked([apktool_bin, "if", str(args.framework_apk), "-p", str(fw_dir)])

        fixtures["comp-ui-resources"] = build_apk_with_apktool(
            FIXTURE_SRC_ROOT / "ui_resources",
            out_dir / "comp-ui-resources.apk",
            apktool_bin,
            framework_dir=fw_dir,
        )

        fixtures["comp-surface-media"] = build_apk_with_apktool(
            FIXTURE_SRC_ROOT / "surface_media",
            out_dir / "comp-surface-media.apk",
            apktool_bin,
            framework_dir=fw_dir,
            extra_populate=lambda stage: {
                "assets": generate_synthetic_media_assets(stage / "assets", ffmpeg_bin)
            },
        )

        fixtures["comp-service-ipc-abi"] = build_apk_with_apktool(
            FIXTURE_SRC_ROOT / "service_ipc_abi",
            out_dir / "comp-service-ipc-abi.apk",
            apktool_bin,
            framework_dir=fw_dir,
            extra_populate=lambda stage: {
                "native_lib": build_arm64_et_dyn_from_object(
                    FIXTURE_SRC_ROOT / "native" / "compprobe.c",
                    stage / "lib" / "arm64-v8a" / "libcompprobe.so",
                    clang_bin=clang_bin,
                )
            },
        )

    manifest = {
        "tools": {
            "apktool": apktool_ver,
            "clang": clang_ver,
            "ffmpeg": ffmpeg_ver,
            "framework_apk": str(args.framework_apk),
            "framework_sha256": sha256_file(args.framework_apk),
        },
        "fixtures": fixtures,
    }
    manifest_path = out_dir / "fixtures_manifest.json"
    manifest_path.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")

    for name, info in fixtures.items():
        print(f"BUILT {name}: {info['apk_path']} ({info['size']} bytes, sha256={info['sha256']})")
    print(f"MANIFEST: {manifest_path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
