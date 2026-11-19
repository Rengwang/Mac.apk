#!/usr/bin/env python3
"""Self-contained synthetic fixture tests for SCAN-001 (scripts/scan_apk.py).

Builds minimal binary AXML manifests, DEX files, ELF64/ELF32 .so binaries,
and APKs dynamically in temporary directories (never committing .apk/.so to Git).
"""

from __future__ import annotations

import hashlib
import json
import pathlib
import struct
import subprocess
import sys
import tempfile
import unittest
import zipfile
import zlib

REPO_ROOT = pathlib.Path(__file__).resolve().parents[1]
SCAN_APK = REPO_ROOT / "scripts" / "scan_apk.py"
INVENTORY = REPO_ROOT / "docs" / "compat" / "inventory.json"
CAPABILITIES = REPO_ROOT / "docs" / "compat" / "capabilities.json"


# ---------------------------------------------------------------------------
# Minimal Pure-Python Binary Builders (AXML, DEX, ELF64, ELF32)
# ---------------------------------------------------------------------------

def _encode_uleb128(val: int) -> bytes:
    out = bytearray()
    while True:
        b = val & 0x7F
        val >>= 7
        if val != 0:
            b |= 0x80
        out.append(b)
        if val == 0:
            break
    return bytes(out)


def _pad4(buf: bytearray) -> None:
    while len(buf) % 4 != 0:
        buf.append(0)


def build_minimal_axml(
    package: str = "org.macapk.test.scan",
    min_sdk: int = 24,
    target_sdk: int = 36,
    split: str | None = None,
) -> bytes:
    """Build a valid binary AndroidManifest.xml (RES_XML_TYPE)."""
    strings: list[str] = [
        "manifest",
        "uses-sdk",
        "application",
        "package",
        "minSdkVersion",
        "targetSdkVersion",
    ]
    if split:
        strings.append("split")

    def sidx(s: str) -> int:
        if s not in strings:
            strings.append(s)
        return strings.index(s)

    sidx(package)
    if split:
        sidx(split)

    # Resource map aligned to strings indices (android attr IDs for minSdkVersion / targetSdkVersion)
    attr_res_ids = [0] * len(strings)
    attr_res_ids[sidx("minSdkVersion")] = 0x0101020C
    attr_res_ids[sidx("targetSdkVersion")] = 0x01010270

    # Build UTF-16LE string pool
    pool_data = bytearray()
    offsets: list[int] = []
    for s in strings:
        offsets.append(len(pool_data))
        enc = s.encode("utf-16-le")
        pool_data.extend(struct.pack("<H", len(s)))
        pool_data.extend(enc)
        pool_data.extend(b"\x00\x00")
    _pad4(pool_data)

    sp_hdr_size = 28
    strings_start = sp_hdr_size + 4 * len(strings)
    sp_chunk_size = strings_start + len(pool_data)
    sp_chunk = bytearray()
    sp_chunk.extend(struct.pack("<HHIIIIII", 0x0001, sp_hdr_size, sp_chunk_size, len(strings), 0, 0, strings_start, 0))
    for off in offsets:
        sp_chunk.extend(struct.pack("<I", off))
    sp_chunk.extend(pool_data)

    # Resource map chunk
    rm_size = 8 + 4 * len(attr_res_ids)
    rm_chunk = bytearray(struct.pack("<HHI", 0x0180, 8, rm_size))
    for rid in attr_res_ids:
        rm_chunk.extend(struct.pack("<I", rid))

    def start_el(tag: str, attrs: list[tuple[str, int, int]]) -> bytes:
        attr_bytes = bytearray()
        for aname, dtype, val in attrs:
            raw_i = val if dtype == 0x03 else -1
            attr_bytes.extend(
                struct.pack("<iiiHBBi", -1, sidx(aname), raw_i, 8, 0, dtype, val)
            )
        ext = struct.pack("<iiHHHHHH", -1, sidx(tag), 20, 20, len(attrs), 0, 0, 0)
        csize = 16 + len(ext) + len(attr_bytes)
        hdr = struct.pack("<HHIIi", 0x0102, 16, csize, 1, -1)
        return hdr + ext + attr_bytes

    def end_el_clean(tag: str) -> bytes:
        return struct.pack("<HHIIiii", 0x0103, 16, 24, 1, -1, -1, sidx(tag))

    m_attrs = [("package", 0x03, sidx(package))]
    if split:
        m_attrs.append(("split", 0x03, sidx(split)))

    body = bytearray()
    body.extend(sp_chunk)
    body.extend(rm_chunk)
    body.extend(start_el("manifest", m_attrs))
    body.extend(start_el("uses-sdk", [("minSdkVersion", 0x10, min_sdk), ("targetSdkVersion", 0x10, target_sdk)]))
    body.extend(end_el_clean("uses-sdk"))
    body.extend(start_el("application", []))
    body.extend(end_el_clean("application"))
    body.extend(end_el_clean("manifest"))

    total_size = 8 + len(body)
    return struct.pack("<HHI", 0x0003, 8, total_size) + bytes(body)


def build_synthetic_dex(
    classes_spec: list[dict],
    extra_strings: list[str] | None = None,
    extra_methods: list[tuple[str, str, str, list[str]]] | None = None,
) -> bytes:
    """Build a valid DEX v035 file with class_defs, class_data_item, and Dalvik code_item.

    classes_spec item:
      {
        "desc": "Lcom/example/Foo;",
        "access": 1,
        "super": "Landroid/app/Activity;",
        "interfaces": [...],
        "methods": [
           {"name": "foo", "ret": "V", "params": ["I"], "access": 1,
            "invokes": [("L...;", "bar", "V", ["I"], ["optional_const_str"])]}
        ]
      }
    """
    strings_set: set[str] = set(extra_strings or [])
    types_set: set[str] = set()
    protos_set: set[tuple[str, tuple[str, ...]]] = set()
    methods_set: set[tuple[str, str, str, tuple[str, ...]]] = set()

    for mcls, mname, mret, mparams in (extra_methods or []):
        types_set.add(mcls)
        types_set.add(mret)
        for p in mparams:
            types_set.add(p)
        protos_set.add((mret, tuple(mparams)))
        strings_set.add(mname)
        methods_set.add((mcls, mname, mret, tuple(mparams)))

    for cs in classes_spec:
        types_set.add(cs["desc"])
        if cs.get("super"):
            types_set.add(cs["super"])
        for iface in cs.get("interfaces", []):
            types_set.add(iface)
        for m in cs.get("methods", []):
            strings_set.add(m["name"])
            types_set.add(m["ret"])
            for p in m.get("params", []):
                types_set.add(p)
            protos_set.add((m["ret"], tuple(m.get("params", []))))
            methods_set.add((cs["desc"], m["name"], m["ret"], tuple(m.get("params", []))))
            for inv_cls, inv_name, inv_ret, inv_params, inv_strs in m.get("invokes", []):
                types_set.add(inv_cls)
                types_set.add(inv_ret)
                for p in inv_params:
                    types_set.add(p)
                strings_set.add(inv_name)
                for s in inv_strs:
                    strings_set.add(s)
                protos_set.add((inv_ret, tuple(inv_params)))
                methods_set.add((inv_cls, inv_name, inv_ret, tuple(inv_params)))

    for t in types_set:
        strings_set.add(t)
    for ret, params in protos_set:
        shorty = (ret[0] if ret[0] not in ("L", "[") else "L") + "".join(
            p[0] if p[0] not in ("L", "[") else "L" for p in params
        )
        strings_set.add(shorty)

    strings = sorted(strings_set)
    str_to_i = {s: i for i, s in enumerate(strings)}
    types = sorted(types_set, key=lambda t: str_to_i[t])
    type_to_i = {t: i for i, t in enumerate(types)}
    protos = sorted(protos_set, key=lambda pr: (type_to_i[pr[0]], [type_to_i[p] for p in pr[1]]))
    proto_to_i = {pr: i for i, pr in enumerate(protos)}
    methods = sorted(
        methods_set,
        key=lambda md: (type_to_i[md[0]], str_to_i[md[1]], proto_to_i[(md[2], md[3])]),
    )
    meth_to_i = {md: i for i, md in enumerate(methods)}

    # Build data section starting at a fixed offset after header + ID tables
    hdr_sz = 0x70
    str_ids_off = hdr_sz
    type_ids_off = str_ids_off + len(strings) * 4
    proto_ids_off = type_ids_off + len(types) * 4
    field_ids_off = proto_ids_off + len(protos) * 12
    method_ids_off = field_ids_off
    class_defs_off = method_ids_off + len(methods) * 8
    data_off = class_defs_off + len(classes_spec) * 32

    data_buf = bytearray()

    def cur_off() -> int:
        return data_off + len(data_buf)

    def align4() -> None:
        while (data_off + len(data_buf)) % 4 != 0:
            data_buf.append(0)

    # 1. String data
    string_data_offs: list[int] = []
    for s in strings:
        string_data_offs.append(cur_off())
        raw = s.encode("utf-8")
        data_buf.extend(_encode_uleb128(len(s)))
        data_buf.extend(raw)
        data_buf.append(0)

    # 2. Type lists for protos & interfaces
    proto_param_offs: dict[tuple[str, tuple[str, ...]], int] = {}
    for ret, params in protos:
        if not params:
            proto_param_offs[(ret, params)] = 0
            continue
        align4()
        proto_param_offs[(ret, params)] = cur_off()
        data_buf.extend(struct.pack("<I", len(params)))
        for p in params:
            data_buf.extend(struct.pack("<H", type_to_i[p]))

    iface_offs: list[int] = []
    for cs in classes_spec:
        ifaces = cs.get("interfaces", [])
        if not ifaces:
            iface_offs.append(0)
            continue
        align4()
        iface_offs.append(cur_off())
        data_buf.extend(struct.pack("<I", len(ifaces)))
        for iface in ifaces:
            data_buf.extend(struct.pack("<H", type_to_i[iface]))

    # 3. code_items + class_data_items
    class_data_offs: list[int] = []
    for cs in classes_spec:
        direct_methods = []
        virtual_methods = []
        for m in cs.get("methods", []):
            acc = m.get("access", 1)
            m_key = (cs["desc"], m["name"], m["ret"], tuple(m.get("params", [])))
            m_idx = meth_to_i[m_key]
            if acc & 0x0100:  # ACC_NATIVE
                code_off = 0
            else:
                insns: list[int] = []
                for inv_cls, inv_name, inv_ret, inv_params, inv_strs in m.get("invokes", []):
                    for s in inv_strs:
                        # const-string v0, string@idx (format 21c: 0x001a, idx)
                        insns.append(0x001A)
                        insns.append(str_to_i[s])
                    inv_midx = meth_to_i[(inv_cls, inv_name, inv_ret, tuple(inv_params))]
                    # invoke-static {v0} or invoke-virtual {v0} (format 35c: 0x1071 / 0x106e)
                    op = 0x1071 if inv_name in ("forName", "loadLibrary", "getService") else 0x106E
                    insns.append(op)
                    insns.append(inv_midx)
                    insns.append(0x0000)
                insns.append(0x000E)  # return-void
                align4()
                code_off = cur_off()
                data_buf.extend(struct.pack("<HHHHII", 2, 1, 1, 0, 0, len(insns)))
                for u in insns:
                    data_buf.extend(struct.pack("<H", u))
            rec = (m_idx, acc, code_off)
            if acc & (0x0002 | 0x0008 | 0x10000):  # private/static/constructor
                direct_methods.append(rec)
            else:
                virtual_methods.append(rec)

        direct_methods.sort(key=lambda r: r[0])
        virtual_methods.sort(key=lambda r: r[0])

        class_data_offs.append(cur_off())
        data_buf.extend(_encode_uleb128(0))  # static_fields
        data_buf.extend(_encode_uleb128(0))  # instance_fields
        data_buf.extend(_encode_uleb128(len(direct_methods)))
        data_buf.extend(_encode_uleb128(len(virtual_methods)))
        for lst in (direct_methods, virtual_methods):
            prev = 0
            for m_idx, acc, coff in lst:
                data_buf.extend(_encode_uleb128(m_idx - prev))
                data_buf.extend(_encode_uleb128(acc))
                data_buf.extend(_encode_uleb128(coff))
                prev = m_idx

    total_len = data_off + len(data_buf)
    img = bytearray(total_len)
    for i, off in enumerate(string_data_offs):
        struct.pack_into("<I", img, str_ids_off + i * 4, off)
    for i, t in enumerate(types):
        struct.pack_into("<I", img, type_ids_off + i * 4, str_to_i[t])
    for i, (ret, params) in enumerate(protos):
        shorty = (ret[0] if ret[0] not in ("L", "[") else "L") + "".join(
            p[0] if p[0] not in ("L", "[") else "L" for p in params
        )
        struct.pack_into(
            "<III",
            img,
            proto_ids_off + i * 12,
            str_to_i[shorty],
            type_to_i[ret],
            proto_param_offs[(ret, params)],
        )
    for i, (mcls, mname, mret, mparams) in enumerate(methods):
        struct.pack_into(
            "<HHI",
            img,
            method_ids_off + i * 8,
            type_to_i[mcls],
            proto_to_i[(mret, mparams)],
            str_to_i[mname],
        )
    for i, cs in enumerate(classes_spec):
        sup_idx = type_to_i[cs["super"]] if cs.get("super") else 0xFFFFFFFF
        struct.pack_into(
            "<IIIIIIII",
            img,
            class_defs_off + i * 32,
            type_to_i[cs["desc"]],
            cs.get("access", 1),
            sup_idx,
            iface_offs[i],
            0xFFFFFFFF,
            0,
            class_data_offs[i],
            0,
        )
    img[data_off:] = data_buf

    # DEX header
    img[0:8] = b"dex\n035\x00"
    struct.pack_into("<II", img, 0x20, total_len, 0x70)
    struct.pack_into("<I", img, 0x28, 0x12345678)
    struct.pack_into("<II", img, 0x38, len(strings), str_ids_off if strings else 0)
    struct.pack_into("<II", img, 0x40, len(types), type_ids_off if types else 0)
    struct.pack_into("<II", img, 0x48, len(protos), proto_ids_off if protos else 0)
    struct.pack_into("<II", img, 0x50, 0, 0)
    struct.pack_into("<II", img, 0x58, len(methods), method_ids_off if methods else 0)
    struct.pack_into("<II", img, 0x60, len(classes_spec), class_defs_off if classes_spec else 0)
    struct.pack_into("<II", img, 0x68, len(data_buf), data_off)

    sha1 = hashlib.sha1(img[32:]).digest()
    img[12:32] = sha1
    adler = zlib.adler32(img[12:]) & 0xFFFFFFFF
    struct.pack_into("<I", img, 0x08, adler)
    return bytes(img)


def build_synthetic_elf(
    soname: str,
    dt_needed: list[str] | None = None,
    exports: list[tuple[str, int]] | None = None,  # (name, bind)
    imports: list[tuple[str, int, tuple[str, str] | None]] | None = None,  # (name, bind, (lib, ver))
    is64: bool = True,
    stripped: bool = False,
) -> bytes:
    """Build a minimal valid ELF64/ELF32 ET_DYN shared library in pure Python."""
    dt_needed = dt_needed or []
    exports = exports or []
    imports = imports or []

    dynstr = bytearray(b"\x00")

    def add_str(s: str) -> int:
        off = len(dynstr)
        dynstr.extend(s.encode("utf-8") + b"\x00")
        return off

    soname_off = add_str(soname)
    needed_offs = [add_str(dep) for dep in dt_needed]

    all_syms: list[tuple[int, int, int, int, tuple[str, str] | None]] = [
        (0, 0, 0, 0, None)
    ]
    for name, bind in exports:
        info = (bind << 4) | 2  # STT_FUNC
        all_syms.append((add_str(name), info, 1, 0x1000, None))
    for name, bind, ver in imports:
        info = (bind << 4) | 2
        all_syms.append((add_str(name), info, 0, 0, ver))

    # Build .gnu.version and .gnu.version_r if any import carries version info
    ver_tuples: list[tuple[str, str]] = []
    for _, _, _, _, ver in all_syms:
        if ver and ver not in ver_tuples:
            ver_tuples.append(ver)
    ver_to_idx = {vt: i + 2 for i, vt in enumerate(ver_tuples)}

    versym_bytes = bytearray()
    verneed_bytes = bytearray()
    verneed_num = 0
    if ver_tuples:
        for _, _, shndx, _, ver in all_syms:
            v = ver_to_idx[ver] if ver else (1 if shndx != 0 else 0)
            versym_bytes.extend(struct.pack("<H", v))
        by_lib: dict[str, list[tuple[str, int]]] = {}
        for lib, vstr in ver_tuples:
            by_lib.setdefault(lib, []).append((vstr, ver_to_idx[(lib, vstr)]))
        verneed_num = len(by_lib)
        libs_list = list(by_lib.items())
        for l_idx, (lib, entries) in enumerate(libs_list):
            lib_off = add_str(lib)
            aux_bytes = bytearray()
            for a_idx, (vstr, vidx) in enumerate(entries):
                vstr_off = add_str(vstr)
                nxt = 16 if a_idx + 1 < len(entries) else 0
                aux_bytes.extend(struct.pack("<IHHII", 0, 0, vidx, vstr_off, nxt))
            vn_next = 16 + len(aux_bytes) if l_idx + 1 < len(libs_list) else 0
            verneed_bytes.extend(struct.pack("<HHIII", 1, len(entries), lib_off, 16, vn_next))
            verneed_bytes.extend(aux_bytes)

    ehdr_sz = 64 if is64 else 52
    ph_sz = 56 if is64 else 32
    sym_sz = 24 if is64 else 16
    dyn_sz = 16 if is64 else 8
    sh_sz = 64 if is64 else 40
    phnum = 2

    dynsym_bytes = bytearray()
    for name_off, info, shndx, val, _ in all_syms:
        if is64:
            dynsym_bytes.extend(struct.pack("<IBBHQQ", name_off, info, 0, shndx, val, 4 if shndx else 0))
        else:
            dynsym_bytes.extend(struct.pack("<IIIBBH", name_off, val, 4 if shndx else 0, info, 0, shndx))

    # Minimal SysV hash table (gives nchain = len(all_syms) for stripped ELF)
    nchain = len(all_syms)
    nbucket = 1
    hash_bytes = struct.pack(f"<{2 + nbucket + nchain}I", nbucket, nchain, 0, *([0] * nchain))

    cur = ehdr_sz + phnum * ph_sz
    hash_off = cur
    cur += len(hash_bytes)
    dynsym_off = (cur + 7) & ~7
    cur = dynsym_off + len(dynsym_bytes)
    dynstr_off = cur
    cur += len(dynstr)
    versym_off = (cur + 3) & ~3
    cur = versym_off + len(versym_bytes)
    verneed_off = (cur + 3) & ~3
    cur = verneed_off + len(verneed_bytes)
    dyn_off = (cur + 7) & ~7

    dyn_entries = [(14, soname_off)] + [(1, o) for o in needed_offs] + [
        (4, hash_off),
        (5, dynstr_off),
        (6, dynsym_off),
        (10, len(dynstr)),
        (11, sym_sz),
    ]
    if ver_tuples:
        dyn_entries.extend([
            (0x6FFFFFF0, versym_off),
            (0x6FFFFFFE, verneed_off),
            (0x6FFFFFFF, verneed_num),
        ])
    dyn_entries.append((0, 0))
    dyn_fmt = "<qQ" if is64 else "<iI"
    dyn_bytes = b"".join(struct.pack(dyn_fmt, t, v) for t, v in dyn_entries)

    cur = dyn_off + len(dyn_bytes)
    shstrtab = b"\x00.dynsym\x00.dynstr\x00.hash\x00.dynamic\x00.gnu.version\x00.gnu.version_r\x00.shstrtab\x00"
    shstr_off = cur
    cur += len(shstrtab)
    shoff = (cur + 7) & ~7

    sh_specs = []
    if not stripped:
        sh_specs = [
            (0, 0, 0, 0, 0, 0, 0, 0),
            (1, 11, dynsym_off, dynsym_off, len(dynsym_bytes), 2, 1, sym_sz),
            (9, 3, dynstr_off, dynstr_off, len(dynstr), 0, 0, 0),
            (17, 5, hash_off, hash_off, len(hash_bytes), 1, 0, 4),
            (23, 6, dyn_off, dyn_off, len(dyn_bytes), 2, 0, dyn_sz),
        ]
        if ver_tuples:
            sh_specs.append((32, 0x6FFFFFFF, versym_off, versym_off, len(versym_bytes), 1, 0, 2))
            sh_specs.append((45, 0x6FFFFFFE, verneed_off, verneed_off, len(verneed_bytes), 2, verneed_num, 0))
        sh_specs.append((60, 3, 0, shstr_off, len(shstrtab), 0, 0, 0))
        shstrndx = len(sh_specs) - 1
        total_sz = shoff + len(sh_specs) * sh_sz
    else:
        shoff = 0
        shstrndx = 0
        total_sz = dyn_off + len(dyn_bytes)

    img = bytearray(total_sz)
    e_ident = b"\x7fELF" + bytes([2 if is64 else 1, 1, 1, 0]) + b"\x00" * 8
    e_machine = 183 if is64 else 40
    if is64:
        struct.pack_into(
            "<16sHHIQQQIHHHHHH",
            img,
            0,
            e_ident,
            3,
            e_machine,
            1,
            0,
            ehdr_sz,
            shoff,
            0,
            ehdr_sz,
            ph_sz,
            phnum,
            sh_sz if not stripped else 0,
            len(sh_specs),
            shstrndx,
        )
        struct.pack_into("<IIQQQQQQ", img, ehdr_sz, 1, 5, 0, 0, 0, total_sz, total_sz, 0x1000)
        struct.pack_into("<IIQQQQQQ", img, ehdr_sz + ph_sz, 2, 6, dyn_off, dyn_off, dyn_off, len(dyn_bytes), len(dyn_bytes), 8)
    else:
        struct.pack_into(
            "<16sHHIIIIIHHHHHH",
            img,
            0,
            e_ident,
            3,
            e_machine,
            1,
            0,
            ehdr_sz,
            shoff,
            0,
            ehdr_sz,
            ph_sz,
            phnum,
            sh_sz if not stripped else 0,
            len(sh_specs),
            shstrndx,
        )
        struct.pack_into("<IIIIIIII", img, ehdr_sz, 1, 0, 0, 0, total_sz, total_sz, 5, 0x1000)
        struct.pack_into("<IIIIIIII", img, ehdr_sz + ph_sz, 2, dyn_off, dyn_off, dyn_off, len(dyn_bytes), len(dyn_bytes), 6, 8)

    img[hash_off : hash_off + len(hash_bytes)] = hash_bytes
    img[dynsym_off : dynsym_off + len(dynsym_bytes)] = dynsym_bytes
    img[dynstr_off : dynstr_off + len(dynstr)] = dynstr
    if versym_bytes:
        img[versym_off : versym_off + len(versym_bytes)] = versym_bytes
    if verneed_bytes:
        img[verneed_off : verneed_off + len(verneed_bytes)] = verneed_bytes
    img[dyn_off : dyn_off + len(dyn_bytes)] = dyn_bytes
    if not stripped:
        img[shstr_off : shstr_off + len(shstrtab)] = shstrtab
        for i, (sname, stype, saddr, soff, ssz, slink, sinfo, sentsz) in enumerate(sh_specs):
            if is64:
                struct.pack_into(
                    "<IIQQQQIIQQ", img, shoff + i * sh_sz, sname, stype, 0, saddr, soff, ssz, slink, sinfo, 8, sentsz
                )
            else:
                struct.pack_into(
                    "<IIIIIIIIII", img, shoff + i * sh_sz, sname, stype, 0, saddr, soff, ssz, slink, sinfo, 4, sentsz
                )
    return bytes(img)


def write_apk(path: pathlib.Path, entries: dict[str, bytes]) -> pathlib.Path:
    with zipfile.ZipFile(path, "w", compression=zipfile.ZIP_DEFLATED) as zf:
        for name, data in entries.items():
            zf.writestr(name, data)
    return path


def run_scanner(args: list[str]) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        [sys.executable, str(SCAN_APK)] + args,
        cwd=str(REPO_ROOT),
        capture_output=True,
        text=True,
        check=False,
    )


class ScanApkAcceptanceTests(unittest.TestCase):
    def test_1_inherited_method_and_capability_classification(self) -> None:
        with tempfile.TemporaryDirectory(prefix="scan_t1_") as tmp:
            td = pathlib.Path(tmp)
            dex = build_synthetic_dex(
                classes_spec=[
                    {
                        "desc": "Lorg/macapk/test/MyActivity;",
                        "super": "Landroid/app/Activity;",
                        "methods": [
                            {
                                "name": "onCreate",
                                "ret": "V",
                                "params": ["Landroid/os/Bundle;"],
                                "access": 1,
                                "invokes": [
                                    ("Lorg/macapk/test/MyActivity;", "setContentView", "V", ["I"], []),
                                    ("Landroid/os/ServiceManager;", "getService", "Landroid/os/IBinder;", ["Ljava/lang/String;"], ["activity"]),
                                ],
                            }
                        ],
                    },
                    {
                        "desc": "Landroidx/custom/BundledWidget;",
                        "super": "Ljava/lang/Object;",
                        "methods": [],
                    },
                ]
            )
            apk = write_apk(td / "t1.apk", {"AndroidManifest.xml": build_minimal_axml(), "classes.dex": dex})
            out_json = td / "out.json"
            proc = run_scanner([str(apk), "--out-json", str(out_json)])
            self.assertEqual(proc.returncode, 0, proc.stderr)
            report = json.loads(out_json.read_text())
            app = report["apps"][0]
            # Inherited setContentView(I)V must resolve via android/app/Activity, NOT missing!
            missing_sigs = {g["signature"] for g in app["gaps"]["java"] if g["kind"].startswith("missing_")}
            self.assertNotIn("org/macapk/test/MyActivity#setContentView(I)V", missing_sigs)
            self.assertEqual(app["java_summary"]["methods"]["missing"], 0)
            # AndroidX bundled vs app-owned vs public SDK vs service_property classification
            origins = app["java_summary"]["referenced_types_by_origin"]
            self.assertIn("androidx_bundled", origins)
            self.assertIn("app_owned", origins)
            self.assertIn("public_sdk", origins)
            self.assertIn("service_property", origins)
            # ServiceManager.getService is unsupported in capabilities.json (IPC-004)
            unsup = [g for g in app["gaps"]["java"] if g["kind"] == "unsupported_platform_capability"]
            self.assertTrue(any(g["capability_id"] == "IPC-004" for g in unsup))

    def test_2_overload_wrong_descriptor_missing(self) -> None:
        with tempfile.TemporaryDirectory(prefix="scan_t2_") as tmp:
            td = pathlib.Path(tmp)
            dex = build_synthetic_dex(
                classes_spec=[
                    {
                        "desc": "Lorg/macapk/test/BadOverloadActivity;",
                        "super": "Landroid/app/Activity;",
                        "methods": [
                            {
                                "name": "go",
                                "ret": "V",
                                "params": [],
                                "access": 1,
                                "invokes": [
                                    # Wrong descriptor! Activity.setContentView takes (I)V or (View)V, NOT (String)V
                                    ("Landroid/app/Activity;", "setContentView", "V", ["Ljava/lang/String;"], []),
                                ],
                            }
                        ],
                    }
                ]
            )
            apk = write_apk(td / "t2.apk", {"AndroidManifest.xml": build_minimal_axml(), "classes.dex": dex})
            out_json = td / "out.json"
            proc = run_scanner([str(apk), "--out-json", str(out_json)])
            self.assertEqual(proc.returncode, 0, proc.stderr)
            app = json.loads(out_json.read_text())["apps"][0]
            self.assertEqual(app["java_summary"]["methods"]["missing"], 1)
            gap = app["gaps"]["java"][0]
            self.assertEqual(gap["kind"], "missing_method_overload")
            self.assertEqual(gap["signature"], "android/app/Activity#setContentView(Ljava/lang/String;)V")
            self.assertTrue(gap["overload_mismatch"])
            self.assertIn("android/app/Activity#setContentView(I)V", gap["available_overloads"])
            self.assertEqual(gap["capability_id"], "LIFE-001")

    def test_3_app_bundled_so_satisfies_dt_needed_and_elf32_stripped(self) -> None:
        with tempfile.TemporaryDirectory(prefix="scan_t3_") as tmp:
            td = pathlib.Path(tmp)
            dex = build_synthetic_dex(classes_spec=[{"desc": "Lorg/macapk/test/Stub;", "super": "Ljava/lang/Object;", "methods": []}])
            lib_dep = build_synthetic_elf("libdep.so", exports=[("dep_compute", 1)], is64=True)
            lib_main = build_synthetic_elf(
                "libmain.so",
                dt_needed=["libdep.so", "libc.so"],
                imports=[
                    ("dep_compute", 1, None),
                    ("malloc", 1, ("libc.so", "LIBC")),
                ],
                is64=True,
                stripped=True,
            )
            lib_arm32 = build_synthetic_elf(
                "libarm32.so",
                dt_needed=["libc.so"],
                exports=[("arm32_entry", 1)],
                imports=[("malloc", 1, None)],
                is64=False,
            )
            apk = write_apk(
                td / "t3.apk",
                {
                    "AndroidManifest.xml": build_minimal_axml(),
                    "classes.dex": dex,
                    "lib/arm64-v8a/libdep.so": lib_dep,
                    "lib/arm64-v8a/libmain.so": lib_main,
                    "lib/armeabi-v7a/libarm32.so": lib_arm32,
                },
            )
            out_json = td / "out.json"
            proc = run_scanner([str(apk), "--out-json", str(out_json)])
            self.assertEqual(proc.returncode, 0, proc.stderr)
            app = json.loads(out_json.read_text())["apps"][0]
            self.assertEqual(app["gaps"]["native"], [])
            libs64 = {lib["soname"]: lib for lib in app["native_summary"]["abis"]["arm64-v8a"]["libraries"]}
            self.assertFalse(libs64["libmain.so"]["section_headers_present"])
            self.assertIn("dep_compute", libs64["libmain.so"]["resolved_by_app"])
            self.assertEqual(libs64["libmain.so"]["resolved_by_platform_count"], 1)
            self.assertEqual(libs64["libmain.so"]["missing_symbols"], [])
            self.assertIn("t3.apk!/lib/arm64-v8a/libdep.so", libs64["libmain.so"]["app_closure_libs"])
            # Check .gnu.version_r was parsed via DT_VERNEED on stripped ELF64
            malloc_ver = [
                sym for sym in libs64["libmain.so"].get("weak_unresolved", [])
            ]
            self.assertEqual(malloc_ver, [])
            libs32 = app["native_summary"]["abis"]["armeabi-v7a"]["libraries"]
            self.assertEqual(libs32[0]["elf_class"], "ELF32")
            self.assertEqual(libs32[0]["e_machine"], "EM_ARM")

    def test_4_weak_symbol_vs_hard_missing(self) -> None:
        with tempfile.TemporaryDirectory(prefix="scan_t4_") as tmp:
            td = pathlib.Path(tmp)
            dex = build_synthetic_dex(classes_spec=[{"desc": "Lorg/macapk/test/Stub;", "super": "Ljava/lang/Object;", "methods": []}])
            lib_weak = build_synthetic_elf(
                "libweak.so",
                dt_needed=["libc.so"],
                imports=[
                    ("optional_weak_hook", 2, None),   # STB_WEAK = 2
                    ("missing_hard_api", 1, ("libc.so", "LIBC_N")),  # STB_GLOBAL = 1
                ],
                is64=True,
            )
            apk = write_apk(
                td / "t4.apk",
                {
                    "AndroidManifest.xml": build_minimal_axml(),
                    "classes.dex": dex,
                    "lib/arm64-v8a/libweak.so": lib_weak,
                },
            )
            out_json = td / "out.json"
            proc = run_scanner([str(apk), "--out-json", str(out_json)])
            self.assertEqual(proc.returncode, 0, proc.stderr)
            app = json.loads(out_json.read_text())["apps"][0]
            lib = app["native_summary"]["abis"]["arm64-v8a"]["libraries"][0]
            self.assertEqual([w["symbol"] for w in lib["weak_unresolved"]], ["optional_weak_hook"])
            self.assertEqual([m["symbol"] for m in lib["missing_symbols"]], ["missing_hard_api"])
            self.assertEqual(lib["missing_symbols"][0]["gnu_version"], {"library": "libc.so", "version": "LIBC_N"})
            self.assertEqual(len(app["gaps"]["native"]), 1)
            self.assertEqual(app["gaps"]["native"][0]["symbol"], "missing_hard_api")

    def test_5_dynamic_load_and_blind_spots_marked_unknown(self) -> None:
        with tempfile.TemporaryDirectory(prefix="scan_t5_") as tmp:
            td = pathlib.Path(tmp)
            dex = build_synthetic_dex(
                classes_spec=[
                    {
                        "desc": "Lorg/macapk/test/Dyn;",
                        "super": "Ljava/lang/Object;",
                        "methods": [
                            {
                                "name": "loadDynamic",
                                "ret": "V",
                                "params": [],
                                "access": 1,
                                "invokes": [
                                    ("Ljava/lang/Class;", "forName", "Ljava/lang/Class;", ["Ljava/lang/String;"], ["android.os.SystemProperties"]),
                                    ("Ldalvik/system/DexClassLoader;", "<init>", "V", ["Ljava/lang/String;", "Ljava/lang/String;", "Ljava/lang/String;", "Ljava/lang/ClassLoader;"], ["assets/hidden.dex"]),
                                ],
                            }
                        ],
                    }
                ],
                extra_strings=["com.android.internal.HiddenThing"],
            )
            lib_dyn = build_synthetic_elf(
                "libdyn.so",
                dt_needed=["libdl.so"],
                exports=[("JNI_OnLoad", 1)],
                imports=[("dlsym", 1, None), ("RegisterNatives", 1, None)],
                is64=True,
            )
            # Remove RegisterNatives from imports? Keep it or export JNI_OnLoad + import dlsym!
            apk = write_apk(
                td / "t5.apk",
                {
                    "AndroidManifest.xml": build_minimal_axml(),
                    "classes.dex": dex,
                    "assets/hidden.dex": b"encrypted-payload",
                    "lib/arm64-v8a/libdyn.so": lib_dyn,
                },
            )
            out_json = td / "out.json"
            proc = run_scanner([str(apk), "--out-json", str(out_json)])
            self.assertEqual(proc.returncode, 0, proc.stderr)
            app = json.loads(out_json.read_text())["apps"][0]
            bs_kinds = {b["kind"] for b in app["blind_spots"]}
            self.assertIn("reflection_api", bs_kinds)
            self.assertIn("dynamic_dex_loading", bs_kinds)
            self.assertIn("embedded_dynamic_code_payload", bs_kinds)
            self.assertIn("native_dynamic_symbol_lookup", bs_kinds)
            self.assertIn("native_dynamic_jni_registration", bs_kinds)
            self.assertIn("com.android.internal.HiddenThing", app["reflection_string_hints_sample"])

    def test_6_split_apk_and_multidex_and_determinism(self) -> None:
        with tempfile.TemporaryDirectory(prefix="scan_t6_") as tmp:
            td = pathlib.Path(tmp)
            dex1 = build_synthetic_dex(
                classes_spec=[
                    {
                        "desc": "Lorg/macapk/test/BaseHelper;",
                        "super": "Ljava/lang/Object;",
                        "methods": [{"name": "ping", "ret": "V", "params": [], "access": 1, "invokes": []}],
                    }
                ]
            )
            dex2 = build_synthetic_dex(
                classes_spec=[
                    {
                        "desc": "Lorg/macapk/test/ChildHelper;",
                        "super": "Lorg/macapk/test/BaseHelper;",
                        "methods": [
                            {
                                "name": "run",
                                "ret": "V",
                                "params": [],
                                "access": 1,
                                "invokes": [("Lorg/macapk/test/ChildHelper;", "ping", "V", [], [])],
                            }
                        ],
                    }
                ]
            )
            base_apk = write_apk(
                td / "base.apk",
                {"AndroidManifest.xml": build_minimal_axml(), "classes.dex": dex1, "classes2.dex": dex2},
            )
            split_apk = write_apk(
                td / "split_arm64.apk",
                {
                    "AndroidManifest.xml": build_minimal_axml(split="config.arm64_v8a"),
                    "lib/arm64-v8a/libsplit.so": build_synthetic_elf("libsplit.so", exports=[("split_fn", 1)]),
                },
            )
            out1 = td / "r1.json"
            out2 = td / "r2.json"
            p1 = run_scanner([str(base_apk), "--split", str(split_apk), "--out-json", str(out1)])
            p2 = run_scanner([str(base_apk), "--split", str(split_apk), "--out-json", str(out2)])
            self.assertEqual(p1.returncode, 0, p1.stderr)
            self.assertEqual(p2.returncode, 0, p2.stderr)
            self.assertEqual(out1.read_bytes(), out2.read_bytes())
            app = json.loads(out1.read_text())["apps"][0]
            self.assertEqual(app["java_summary"]["app_defined_classes"], 2)
            self.assertEqual(app["java_summary"]["methods"]["missing"], 0)
            self.assertEqual(app["native_summary"]["abis"]["arm64-v8a"]["libraries_count"], 1)

    def test_7_corrupt_inputs_zip_slip_and_size_caps(self) -> None:
        with tempfile.TemporaryDirectory(prefix="scan_t7_") as tmp:
            td = pathlib.Path(tmp)
            valid_dex = build_synthetic_dex(classes_spec=[{"desc": "Lorg/macapk/test/A;", "super": "Ljava/lang/Object;", "methods": []}])
            valid_axml = build_minimal_axml()

            # 1. Corrupt ZIP
            bad_zip = td / "bad.apk"
            bad_zip.write_bytes(b"not a zip file")
            self.assertEqual(run_scanner([str(bad_zip)]).returncode, 3)

            # 2. Zip-slip traversal
            slip_apk = write_apk(td / "slip.apk", {"AndroidManifest.xml": valid_axml, "../evil.txt": b"boom"})
            self.assertEqual(run_scanner([str(slip_apk)]).returncode, 3)

            # 3. Symlink entry in ZIP
            sym_apk = td / "sym.apk"
            with zipfile.ZipFile(sym_apk, "w") as zf:
                zf.writestr("AndroidManifest.xml", valid_axml)
                zi = zipfile.ZipInfo("assets/link")
                zi.external_attr = (0o120777 << 16)
                zf.writestr(zi, b"/etc/passwd")
            self.assertEqual(run_scanner([str(sym_apk)]).returncode, 3)

            # 4. Size cap exceeded
            cap_apk = write_apk(td / "cap.apk", {"AndroidManifest.xml": valid_axml, "classes.dex": valid_dex})
            self.assertEqual(run_scanner([str(cap_apk), "--max-dex-bytes", "32"]).returncode, 3)

            # 5. Corrupt AXML manifest
            bad_axml = write_apk(td / "bad_axml.apk", {"AndroidManifest.xml": b"<manifest/>"})
            self.assertEqual(run_scanner([str(bad_axml)]).returncode, 4)

            # 6. Corrupt DEX checksum + cdex magic
            bad_dex_bytes = bytearray(valid_dex)
            bad_dex_bytes[-1] ^= 0xFF
            bad_dex_apk = write_apk(td / "bad_dex.apk", {"AndroidManifest.xml": valid_axml, "classes.dex": bytes(bad_dex_bytes)})
            self.assertEqual(run_scanner([str(bad_dex_apk)]).returncode, 4)

            cdex_apk = write_apk(td / "cdex.apk", {"AndroidManifest.xml": valid_axml, "classes.dex": b"cdex001\x00" + b"\x00" * 120})
            self.assertEqual(run_scanner([str(cdex_apk)]).returncode, 4)

            # 7. Corrupt ELF
            bad_elf_apk = write_apk(
                td / "bad_elf.apk",
                {"AndroidManifest.xml": valid_axml, "classes.dex": valid_dex, "lib/arm64-v8a/libbad.so": b"\x7fELF\x02\x01\x01\x00" + b"\x00" * 16},
            )
            self.assertEqual(run_scanner([str(bad_elf_apk)]).returncode, 4)


if __name__ == "__main__":
    unittest.main(verbosity=2)
