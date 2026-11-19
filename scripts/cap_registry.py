#!/usr/bin/env python3
"""Mac.apk CAP-001 capability registry, bytecode/native inventory, and validator.

Zero external dependencies (Python 3.9+ standard library only).
Produces deterministic, byte-identical sorted outputs for the same inputs.
"""

from __future__ import annotations

import argparse
import glob
import hashlib
import json
import os
import pathlib
import re
import struct
import subprocess
import sys
import zipfile
from typing import Any, Dict, Iterable, List, Optional, Set, Tuple

# Classfile access flags (JVMS 4.1, 4.5, 4.6)
ACC_PUBLIC = 0x0001
ACC_PRIVATE = 0x0002
ACC_PROTECTED = 0x0004
ACC_STATIC = 0x0008
ACC_FINAL = 0x0010
ACC_SYNCHRONIZED = 0x0020
ACC_BRIDGE = 0x0040
ACC_VARARGS = 0x0080
ACC_NATIVE = 0x0100
ACC_INTERFACE = 0x0200
ACC_ABSTRACT = 0x0400
ACC_STRICT = 0x0800
ACC_SYNTHETIC = 0x1000
ACC_ANNOTATION = 0x2000
ACC_ENUM = 0x4000

_CP_FIXED = {
    3: 4,   # Integer
    4: 4,   # Float
    9: 4,   # Fieldref
    10: 4,  # Methodref
    11: 4,  # InterfaceMethodref
    12: 4,  # NameAndType
    17: 4,  # Dynamic
    18: 4,  # InvokeDynamic
    8: 2,   # String
    16: 2,  # MethodType
    19: 2,  # Module
    20: 2,  # Package
    15: 3,  # MethodHandle
}

SUPPORT_STATUSES = ("implemented", "partial", "unsupported", "missing", "unknown")
VERIFICATION_STATUSES = ("unverified", "contract-tested", "android-compared")
STALENESS_STATES = ("fresh", "stale", "unknown")
SUBSYSTEMS = (
    "resources",
    "lifecycle",
    "parcel_binder",
    "surface_scheduling",
    "media",
    "native_abi",
)
SURFACES = (
    "public_sdk",
    "hidden_api",
    "jni_registration",
    "bionic_abi",
    "service_property",
)

JARS_IN_ORDER = ("android-framework.jar", "macapk-loader.jar")
PATCH_DIRS_IN_ORDER = ("java_base_patch", "jdk_unsupported_patch")


def find_repo_root(start: Optional[pathlib.Path] = None) -> pathlib.Path:
    cur = (start or pathlib.Path(__file__).resolve()).parent
    for cand in [cur] + list(cur.parents):
        if (cand / "scripts" / "run_tests.py").is_file() and (cand / "runtime").is_dir():
            return cand
    return pathlib.Path.cwd()


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def sha256_file(path: pathlib.Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as fh:
        for chunk in iter(lambda: fh.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def canonical_json_bytes(obj: Any) -> bytes:
    return (json.dumps(obj, indent=2, sort_keys=True, ensure_ascii=False) + "\n").encode("utf-8")


def write_deterministic_json(path: pathlib.Path, obj: Any) -> str:
    data = canonical_json_bytes(obj)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(data)
    return sha256_bytes(data)


# ---------------------------------------------------------------------------
# JVMS Chapter 4 .class Parser
# ---------------------------------------------------------------------------

def _decode_mutf8(raw: bytes) -> str:
    # JVM modified UTF-8 uses 0xC0 0x80 for U+0000; standard UTF-8 otherwise
    # (surrogates are replaced safely if present).
    if b"\xc0\x80" in raw:
        raw = raw.replace(b"\xc0\x80", b"\x00")
    return raw.decode("utf-8", errors="replace")


def parse_classfile(data: bytes) -> Optional[Dict[str, Any]]:
    """Parse a JVM .class file into structured class/interface, field, and method metadata."""
    if len(data) < 10 or data[:4] != b"\xca\xfe\xba\xbe":
        return None
    try:
        minor, major, cp_count = struct.unpack_from(">HHH", data, 4)
        p = 10
        utf: List[Optional[str]] = [None] * cp_count
        cls_name_idx: List[int] = [0] * cp_count
        i = 1
        while i < cp_count:
            tag = data[p]
            p += 1
            if tag == 1:  # CONSTANT_Utf8
                ln = (data[p] << 8) | data[p + 1]
                p += 2
                utf[i] = _decode_mutf8(data[p:p + ln])
                p += ln
            elif tag == 7:  # CONSTANT_Class
                cls_name_idx[i] = (data[p] << 8) | data[p + 1]
                p += 2
            elif tag in (5, 6):  # Long, Double occupy two slots
                p += 8
                i += 1
            else:
                sz = _CP_FIXED.get(tag)
                if sz is None:
                    return None
                p += sz
            i += 1

        def utf_at(idx: int) -> str:
            if 0 < idx < cp_count and utf[idx] is not None:
                return utf[idx] or ""
            return ""

        def cls_at(idx: int) -> str:
            if idx <= 0 or idx >= cp_count:
                return ""
            return utf_at(cls_name_idx[idx])

        access_flags, this_idx, super_idx, n_ifaces = struct.unpack_from(">HHHH", data, p)
        p += 8
        interfaces: List[str] = []
        for _ in range(n_ifaces):
            (iface_idx,) = struct.unpack_from(">H", data, p)
            p += 2
            interfaces.append(cls_at(iface_idx))

        def read_members() -> List[Dict[str, Any]]:
            nonlocal p
            (cnt,) = struct.unpack_from(">H", data, p)
            p += 2
            members: List[Dict[str, Any]] = []
            for _ in range(cnt):
                m_access, m_name_i, m_desc_i, n_attrs = struct.unpack_from(">HHHH", data, p)
                p += 8
                deprecated = False
                has_constant_value = False
                for _ in range(n_attrs):
                    attr_name_i, attr_len = struct.unpack_from(">HI", data, p)
                    p += 6
                    aname = utf_at(attr_name_i)
                    if aname == "Deprecated":
                        deprecated = True
                    elif aname == "ConstantValue":
                        has_constant_value = True
                    p += attr_len
                entry: Dict[str, Any] = {
                    "name": utf_at(m_name_i),
                    "descriptor": utf_at(m_desc_i),
                    "flags": f"0x{m_access:04x}",
                    "access": m_access,
                }
                if deprecated:
                    entry["deprecated"] = True
                if has_constant_value:
                    entry["constant_value"] = True
                members.append(entry)
            members.sort(key=lambda m: (m["name"], m["descriptor"], m["flags"]))
            return members

        fields = read_members()
        methods = read_members()

        (cls_attrs,) = struct.unpack_from(">H", data, p)
        p += 2
        cls_deprecated = False
        for _ in range(cls_attrs):
            attr_name_i, attr_len = struct.unpack_from(">HI", data, p)
            p += 6
            if utf_at(attr_name_i) == "Deprecated":
                cls_deprecated = True
            p += attr_len

        if access_flags & ACC_ANNOTATION:
            kind = "annotation"
        elif access_flags & ACC_INTERFACE:
            kind = "interface"
        elif access_flags & ACC_ENUM:
            kind = "enum"
        else:
            kind = "class"

        out: Dict[str, Any] = {
            "kind": kind,
            "is_interface": bool(access_flags & ACC_INTERFACE),
            "access_flags": f"0x{access_flags:04x}",
            "access": access_flags,
            "major_version": major,
            "minor_version": minor,
            "this": cls_at(this_idx),
            "super": cls_at(super_idx),
            "interfaces": interfaces,
            "fields": fields,
            "methods": methods,
        }
        if cls_deprecated:
            out["deprecated"] = True
        return out
    except (IndexError, ValueError, struct.error):
        return None


def collect_bytecode_inventory(lib_dir: pathlib.Path) -> Dict[str, Any]:
    """Collect deduplicated class/interface/method/field inventory in JVM classpath order."""
    classes: Dict[str, Dict[str, Any]] = {}
    shadowed: List[Dict[str, str]] = []
    source_counts: Dict[str, int] = {}

    for jar_name in JARS_IN_ORDER:
        jar_path = lib_dir / jar_name
        if not jar_path.is_file():
            raise FileNotFoundError(f"Missing required jar: {jar_path}")
        cnt = 0
        with zipfile.ZipFile(jar_path, "r") as zf:
            for entry_name in sorted(zf.namelist()):
                if not entry_name.endswith(".class"):
                    continue
                internal = entry_name[:-6]
                parsed = parse_classfile(zf.read(entry_name))
                if parsed is None:
                    raise ValueError(f"Unparseable classfile {entry_name} in {jar_path}")
                parsed["source"] = jar_name
                if internal in classes:
                    shadowed.append({
                        "class": internal,
                        "winner": classes[internal]["source"],
                        "shadowed": jar_name,
                    })
                else:
                    classes[internal] = parsed
                    cnt += 1
        source_counts[jar_name] = cnt

    for patch_name in PATCH_DIRS_IN_ORDER:
        patch_root = lib_dir / patch_name
        if not patch_root.is_dir():
            raise FileNotFoundError(f"Missing required patch directory: {patch_root}")
        cnt = 0
        class_files: List[pathlib.Path] = []
        for root, _dirs, files in os.walk(patch_root):
            for fname in sorted(files):
                if fname.endswith(".class"):
                    class_files.append(pathlib.Path(root) / fname)
        for full_path in sorted(class_files):
            internal = full_path.relative_to(patch_root).as_posix()[:-6]
            parsed = parse_classfile(full_path.read_bytes())
            if parsed is None:
                raise ValueError(f"Unparseable classfile {full_path}")
            parsed["source"] = patch_name
            if internal in classes:
                shadowed.append({
                    "class": internal,
                    "winner": patch_name,
                    "shadowed": classes[internal]["source"],
                })
            classes[internal] = parsed
            cnt += 1
        source_counts[patch_name] = cnt

    ordered_classes = {k: classes[k] for k in sorted(classes)}
    shadowed.sort(key=lambda s: (s["class"], s["winner"], s["shadowed"]))
    return {
        "classes": ordered_classes,
        "shadowed": shadowed,
        "source_counts": source_counts,
    }


def normalize_internal_name(name: str) -> str:
    return name.strip().replace(".", "/")


def effective_methods(
    classes: Dict[str, Dict[str, Any]],
    class_name: str,
) -> Dict[Tuple[str, str], Dict[str, Any]]:
    """Resolve declared + inherited non-private, non-constructor methods for a type.

    Keys are (method_name, descriptor) so all overloads are preserved distinctly.
    Class hierarchy precedes interfaces; own declarations shadow inherited ones.
    """
    target = normalize_internal_name(class_name)
    if target not in classes:
        return {}

    resolved: Dict[Tuple[str, str], Dict[str, Any]] = {}
    target_cls = classes[target]

    # 1. Own declared methods (including <init>, <clinit>, private)
    for m in target_cls.get("methods", []):
        key = (m["name"], m["descriptor"])
        resolved[key] = {
            "name": m["name"],
            "descriptor": m["descriptor"],
            "flags": m["flags"],
            "access": m["access"],
            "declared_in": target,
            "declared_kind": target_cls["kind"],
            "inherited": False,
        }

    # 2. Walk superclasses (skip <init>/<clinit> and private methods)
    visited_types: Set[str] = {target}
    iface_queue: List[str] = list(target_cls.get("interfaces", []))

    cur_name = target_cls.get("super", "")
    while cur_name and cur_name not in visited_types:
        visited_types.add(cur_name)
        sup_cls = classes.get(cur_name)
        if sup_cls is None:
            break
        for m in sup_cls.get("methods", []):
            if m["name"] in ("<init>", "<clinit>"):
                continue
            if m["access"] & ACC_PRIVATE:
                continue
            key = (m["name"], m["descriptor"])
            if key not in resolved:
                resolved[key] = {
                    "name": m["name"],
                    "descriptor": m["descriptor"],
                    "flags": m["flags"],
                    "access": m["access"],
                    "declared_in": cur_name,
                    "declared_kind": sup_cls["kind"],
                    "inherited": True,
                }
        iface_queue.extend(sup_cls.get("interfaces", []))
        cur_name = sup_cls.get("super", "")

    # 3. Walk transitive interfaces (and java/lang/Object public methods if present)
    idx = 0
    while idx < len(iface_queue):
        iname = iface_queue[idx]
        idx += 1
        if not iname or iname in visited_types:
            continue
        visited_types.add(iname)
        icls = classes.get(iname)
        if icls is None:
            continue
        for m in icls.get("methods", []):
            if m["name"] in ("<init>", "<clinit>"):
                continue
            if m["access"] & ACC_PRIVATE:
                continue
            key = (m["name"], m["descriptor"])
            if key not in resolved:
                resolved[key] = {
                    "name": m["name"],
                    "descriptor": m["descriptor"],
                    "flags": m["flags"],
                    "access": m["access"],
                    "declared_in": iname,
                    "declared_kind": icls["kind"],
                    "inherited": True,
                }
        iface_queue.extend(icls.get("interfaces", []))

    return resolved


def resolve_method(
    classes: Dict[str, Dict[str, Any]],
    class_name: str,
    method_name: str,
    descriptor: str,
) -> Optional[Dict[str, Any]]:
    eff = effective_methods(classes, class_name)
    return eff.get((method_name, descriptor))


# ---------------------------------------------------------------------------
# JNI Registration & Native Export Parsers
# ---------------------------------------------------------------------------

def _strip_cpp_comments(src: str) -> str:
    out: List[str] = []
    i = 0
    n = len(src)
    while i < n:
        c = src[i]
        if c in ('"', "'"):
            q = c
            out.append(c)
            i += 1
            while i < n:
                ch = src[i]
                out.append(ch)
                i += 1
                if ch == "\\":
                    if i < n:
                        out.append(src[i])
                        i += 1
                elif ch == q:
                    break
        elif c == "/" and i + 1 < n and src[i + 1] == "/":
            i += 2
            while i < n and src[i] != "\n":
                i += 1
        elif c == "/" and i + 1 < n and src[i + 1] == "*":
            i += 2
            while i + 1 < n and not (src[i] == "*" and src[i + 1] == "/"):
                i += 1
            i += 2
        else:
            out.append(c)
            i += 1
    return "".join(out)


def _extract_brace_block(src: str, open_pos: int) -> Tuple[str, int]:
    depth = 1
    pos = open_pos
    n = len(src)
    while pos < n and depth > 0:
        ch = src[pos]
        if ch == "{":
            depth += 1
        elif ch == "}":
            depth -= 1
        elif ch == '"':
            pos += 1
            while pos < n:
                if src[pos] == "\\":
                    pos += 2
                elif src[pos] == '"':
                    break
                else:
                    pos += 1
        pos += 1
    return src[open_pos:pos - 1], pos


def _split_top_level_commas(text: str) -> List[str]:
    parts: List[str] = []
    cur: List[str] = []
    depth = 0
    in_q = False
    i = 0
    n = len(text)
    while i < n:
        ch = text[i]
        if in_q:
            cur.append(ch)
            if ch == "\\":
                i += 1
                if i < n:
                    cur.append(text[i])
            elif ch == '"':
                in_q = False
        else:
            if ch == '"':
                in_q = True
                cur.append(ch)
            elif ch in "([{":
                depth += 1
                cur.append(ch)
            elif ch in ")]}":
                depth -= 1
                cur.append(ch)
            elif ch == "," and depth == 0:
                parts.append("".join(cur).strip())
                cur = []
                i += 1
                continue
            else:
                cur.append(ch)
        i += 1
    if cur:
        parts.append("".join(cur).strip())
    return parts


def _join_c_strings(expr: str) -> str:
    tokens = re.findall(r'"((?:[^"\\]|\\.)*)"', expr)
    return "".join(tokens)


def _parse_jni_tables(clean_src: str) -> Dict[str, List[Tuple[str, str]]]:
    tables: Dict[str, List[Tuple[str, str]]] = {}
    for m in re.finditer(r"JNINativeMethod\s+(\w+)\s*(\[\s*\])?\s*=\s*\{", clean_src):
        var = m.group(1)
        is_arr = bool(m.group(2))
        body, _ = _extract_brace_block(clean_src, m.end())
        items: List[str] = []
        if is_arr:
            j = 0
            while j < len(body):
                if body[j] == "{":
                    item_body, next_j = _extract_brace_block(body, j + 1)
                    items.append(item_body)
                    j = next_j
                else:
                    j += 1
        else:
            items = [body]
        entries: List[Tuple[str, str]] = []
        for item in items:
            parts = _split_top_level_commas(item)
            if len(parts) >= 2:
                name = _join_c_strings(parts[0])
                desc = _join_c_strings(parts[1])
                if name and desc:
                    entries.append((name, desc))
        tables[var] = entries
    return tables


def _run_nm_exports(binary_path: pathlib.Path) -> List[str]:
    if not binary_path.is_file():
        return []
    proc = subprocess.run(
        ["nm", "-gU", str(binary_path)],
        capture_output=True,
        text=True,
        check=True,
    )
    syms: List[str] = []
    for line in proc.stdout.splitlines():
        parts = line.strip().split()
        if parts:
            syms.append(parts[-1])
    return sorted(set(syms))


def _unmangle_jni_java_symbol(sym: str) -> Tuple[str, str]:
    # Mach-O prepends "_" before "Java_"
    raw = sym[1:] if sym.startswith("_Java_") else sym
    if not raw.startswith("Java_"):
        return "", ""
    rest = raw[5:]
    if "__" in rest:
        rest = rest.split("__", 1)[0]
    parts = rest.split("_")
    if len(parts) < 2:
        return "", rest
    # Last component is method name unless inner class/escaped underscores exist;
    # exact matching uses bytecode lookup below.
    cls_guess = "/".join(parts[:-1])
    meth_guess = parts[-1]
    return cls_guess, meth_guess


def collect_jni_registrations(
    repo_root: pathlib.Path,
    bin_dir: pathlib.Path,
    classes: Dict[str, Dict[str, Any]],
) -> Dict[str, Any]:
    src_globs = (
        glob.glob(str(repo_root / "runtime" / "src" / "*.cpp"))
        + glob.glob(str(repo_root / "runtime" / "src" / "*.mm"))
        + glob.glob(str(repo_root / "runtime" / "src" / "*.inc"))
        + glob.glob(str(repo_root / "runtime" / "translator" / "src" / "*.cpp"))
    )
    src_files = sorted(set(pathlib.Path(p) for p in src_globs))

    # First parse all JNINativeMethod tables per file (and across .inc includes)
    file_clean: Dict[pathlib.Path, str] = {}
    all_tables_by_file: Dict[pathlib.Path, Dict[str, List[Tuple[str, str]]]] = {}
    global_inc_tables: Dict[str, Tuple[str, List[Tuple[str, str]]]] = {}
    for fpath in src_files:
        clean = _strip_cpp_comments(fpath.read_text(errors="replace"))
        file_clean[fpath] = clean
        tbls = _parse_jni_tables(clean)
        all_tables_by_file[fpath] = tbls
        if fpath.suffix == ".inc":
            rel = fpath.relative_to(repo_root).as_posix()
            for tname, entries in tbls.items():
                global_inc_tables[tname] = (rel, entries)

    registrations: List[Dict[str, Any]] = []
    seen_reg: Set[Tuple[str, str, str, str]] = set()
    jni_source_files: Set[str] = set()

    def add_reg(
        cls_name: str,
        meth_name: str,
        desc: str,
        binding_mode: str,
        source_rel: str,
    ) -> None:
        key = (cls_name, meth_name, desc, source_rel)
        if key in seen_reg:
            return
        seen_reg.add(key)
        declared = False
        if cls_name in classes:
            for cm in classes[cls_name]["methods"]:
                if cm["name"] == meth_name and cm["descriptor"] == desc:
                    declared = True
                    break
        registrations.append({
            "class": cls_name,
            "name": meth_name,
            "descriptor": desc,
            "registration": "RegisterNatives",
            "binding_mode": binding_mode,
            "source": source_rel,
            "declared_in_bytecode": declared,
        })

    for fpath in src_files:
        clean = file_clean[fpath]
        if "RegisterNatives" not in clean and "Bind(" not in clean:
            continue
        rel = fpath.relative_to(repo_root).as_posix()
        local_tbls = all_tables_by_file[fpath]

        def resolve_table(tvar: str) -> Tuple[str, List[Tuple[str, str]]]:
            if tvar in local_tbls:
                return rel, local_tbls[tvar]
            if tvar in global_inc_tables:
                return global_inc_tables[tvar]
            return rel, []

        # 1. Bind(env, "cls", table, ...)
        for bm in re.finditer(r'\bBind\s*\(\s*\w+\s*,\s*"([^"]+)"\s*,\s*(\w+)\s*,', clean):
            cls_name, tvar = bm.group(1), bm.group(2)
            t_src, entries = resolve_table(tvar)
            if entries:
                jni_source_files.add(rel)
                jni_source_files.add(t_src)
                for mname, desc in entries:
                    add_reg(cls_name, mname, desc, "fatal_bind", t_src)

        # 2. register_methods(env, "cls", table, ...)
        for rm in re.finditer(r'\bregister_methods\s*\(\s*\w+\s*,\s*"([^"]+)"\s*,\s*(\w+)\s*,', clean):
            cls_name, tvar = rm.group(1), rm.group(2)
            t_src, entries = resolve_table(tvar)
            if entries:
                jni_source_files.add(rel)
                jni_source_files.add(t_src)
                for mname, desc in entries:
                    add_reg(cls_name, mname, desc, "soft_fail", t_src)

        # 3. Track FindClass assignments and direct RegisterNatives(var, [&]table, ...)
        # Collect events by position in file so each var maps to its most recent FindClass.
        events: List[Tuple[int, str, str, str]] = []
        for fc in re.finditer(r'(\w+)\s*=\s*(?:\([^)]+\))?\s*\w+->FindClass\s*\(\s*"([^"]+)"\s*\)', clean):
            events.append((fc.start(), "findclass", fc.group(1), fc.group(2)))
        for rn in re.finditer(r'->RegisterNatives\s*\(\s*(\w+)\s*,\s*&?\s*(?:(?:\w+::)*)(\w+)\s*,', clean):
            events.append((rn.start(), "register", rn.group(1), rn.group(2)))
        events.sort(key=lambda e: e[0])

        var_to_cls: Dict[str, str] = {}
        for pos, kind, arg1, arg2 in events:
            if kind == "findclass":
                var_to_cls[arg1] = arg2
            else:
                cls_var, tvar = arg1, arg2
                t_src, entries = resolve_table(tvar)
                if not entries:
                    continue
                jni_source_files.add(rel)
                jni_source_files.add(t_src)
                cls_name = var_to_cls.get(cls_var, "<dynamic_target>")
                snippet = clean[pos:pos + 250]
                mode = "dynamic_rebind" if cls_name == "<dynamic_target>" else (
                    "fatal_abort" if "abort()" in snippet else "soft_fail"
                )
                for mname, desc in entries:
                    add_reg(cls_name, mname, desc, mode, t_src)

    registrations.sort(key=lambda r: (r["class"], r["name"], r["descriptor"], r["source"]))

    # Binary Java_* exports via nm -gU
    jni_binaries = [
        "macapk-runtime",
        "libmacapk-binder.dylib",
        "libmacapk-skia-metal.dylib",
        "libmacapk-bionic-shim.dylib",
        "libmacapk-app-stub.dylib",
    ]
    jni_exports: List[Dict[str, Any]] = []
    exported_cls_meth: Set[Tuple[str, str]] = set()
    for bname in jni_binaries:
        bpath = bin_dir / bname
        for sym in _run_nm_exports(bpath):
            if sym.startswith("_Java_") or sym.startswith("Java_"):
                cls_guess, meth_guess = _unmangle_jni_java_symbol(sym)
                if cls_guess and meth_guess:
                    exported_cls_meth.add((cls_guess, meth_guess))
                jni_exports.append({
                    "symbol": sym,
                    "binary": bname,
                    "class_guess": cls_guess,
                    "method_guess": meth_guess,
                })
    jni_exports.sort(key=lambda x: (x["binary"], x["symbol"]))

    # Cross-check bytecode ACC_NATIVE declarations against RegisterNatives & Java_* exports
    reg_keys = {(r["class"], r["name"], r["descriptor"]) for r in registrations}
    native_declared: List[Dict[str, str]] = []
    unbound_native: List[Dict[str, str]] = []
    for cname, cdata in classes.items():
        for m in cdata["methods"]:
            if m["access"] & ACC_NATIVE:
                entry = {
                    "class": cname,
                    "name": m["name"],
                    "descriptor": m["descriptor"],
                    "source": cdata["source"],
                }
                native_declared.append(entry)
                if (cname, m["name"], m["descriptor"]) not in reg_keys and (cname, m["name"]) not in exported_cls_meth:
                    unbound_native.append(entry)

    native_declared.sort(key=lambda x: (x["class"], x["name"], x["descriptor"]))
    unbound_native.sort(key=lambda x: (x["class"], x["name"], x["descriptor"]))

    return {
        "source_files": sorted(jni_source_files),
        "registrations": registrations,
        "binary_java_exports": jni_exports,
        "bytecode_native_methods_total": len(native_declared),
        "unbound_bytecode_native_methods": unbound_native,
    }


# ---------------------------------------------------------------------------
# Bionic Shim Exports & Adapters
# ---------------------------------------------------------------------------

def collect_bionic_exports(repo_root: pathlib.Path, bin_dir: pathlib.Path) -> Dict[str, Any]:
    dylib_path = bin_dir / "libmacapk-bionic-shim.dylib"
    if not dylib_path.is_file():
        raise FileNotFoundError(f"Missing required bionic shim dylib: {dylib_path}")

    raw_syms = _run_nm_exports(dylib_path)
    arm64_shim: List[Dict[str, str]] = []
    arm64_per_image: Dict[str, List[Dict[str, str]]] = {}
    fmod_stub: List[Dict[str, str]] = []
    direct_c_ndk: List[str] = []
    cxx_internal: List[str] = []

    per_image_tags = ("libunity_", "libcocos2dcpp_")

    for sym in raw_syms:
        stripped = sym[1:] if sym.startswith("_") else sym
        if stripped.startswith("__macapk_arm64_"):
            rest = stripped[len("__macapk_arm64_"):]
            matched_tag = None
            for tag_prefix in per_image_tags:
                if rest.startswith(tag_prefix):
                    matched_tag = tag_prefix[:-1]
                    bionic_name = rest[len(tag_prefix):]
                    arm64_per_image.setdefault(matched_tag, []).append({
                        "export": sym,
                        "bionic_symbol": bionic_name,
                    })
                    break
            if matched_tag is None:
                arm64_shim.append({
                    "export": sym,
                    "bionic_symbol": rest,
                })
        elif stripped.startswith("__macapk_fmodstub_"):
            fmod_stub.append({
                "export": sym,
                "target_symbol": stripped[len("__macapk_fmodstub_"):],
            })
        elif stripped.startswith("_Z"):
            cxx_internal.append(sym)
        else:
            direct_c_ndk.append(stripped)

    arm64_shim.sort(key=lambda x: x["bionic_symbol"])
    for k in list(arm64_per_image):
        arm64_per_image[k].sort(key=lambda x: x["bionic_symbol"])
    arm64_per_image = {k: arm64_per_image[k] for k in sorted(arm64_per_image)}
    fmod_stub.sort(key=lambda x: x["target_symbol"])
    direct_c_ndk = sorted(set(direct_c_ndk))
    cxx_internal = sorted(set(cxx_internal))

    # Also parse macapk_bionic_adapter table in arm64_native.cpp
    adapters: List[str] = []
    arm64_native = repo_root / "runtime" / "translator" / "src" / "arm64_native.cpp"
    if arm64_native.is_file():
        txt = _strip_cpp_comments(arm64_native.read_text(errors="replace"))
        m = re.search(r"void\*\s+macapk_bionic_adapter\b.*?\{(.*?)\n\}", txt, re.S)
        if m:
            adapters = sorted(set(re.findall(r'\{\s*"([^"]+)"\s*,', m.group(1))))

    return {
        "dylib": "bin/libmacapk-bionic-shim.dylib",
        "total_nm_exports": len(raw_syms),
        "arm64_shim": arm64_shim,
        "arm64_per_image": arm64_per_image,
        "fmod_stub": fmod_stub,
        "direct_c_ndk": direct_c_ndk,
        "cxx_internal_count": len(cxx_internal),
        "adapters_in_arm64_native": adapters,
    }


def collect_service_property_inventory(
    repo_root: pathlib.Path,
    classes: Dict[str, Dict[str, Any]],
) -> Dict[str, Any]:
    sm = classes.get("android/os/ServiceManager", {})
    sm_methods = [
        f"{m['name']}{m['descriptor']}"
        for m in sm.get("methods", [])
        if m["name"] != "<init>"
    ]

    sysprop_file = repo_root / "runtime" / "java" / "android" / "os" / "SystemProperties.java"
    known_props: List[str] = []
    if sysprop_file.is_file():
        txt = _strip_cpp_comments(sysprop_file.read_text(errors="replace"))
        known_props = sorted(set(re.findall(r'KNOWN\.put\(\s*"([^"]+)"', txt)))

    return {
        "service_manager_methods": sorted(sm_methods),
        "system_properties_known_keys": known_props,
    }


def read_build_id(runtime_build_dir: pathlib.Path) -> Dict[str, str]:
    path = runtime_build_dir / "build-id.txt"
    out: Dict[str, str] = {}
    if path.is_file():
        for line in path.read_text(errors="replace").splitlines():
            if "=" in line:
                k, v = line.split("=", 1)
                out[k.strip()] = v.strip()
    return out


# ---------------------------------------------------------------------------
# Full Inventory Builder
# ---------------------------------------------------------------------------

def build_inventory(repo_root: pathlib.Path, runtime_build_dir: pathlib.Path) -> Dict[str, Any]:
    lib_dir = runtime_build_dir / "lib"
    bin_dir = runtime_build_dir / "bin"

    bc = collect_bytecode_inventory(lib_dir)
    classes = bc["classes"]
    jni = collect_jni_registrations(repo_root, bin_dir, classes)
    bionic = collect_bionic_exports(repo_root, bin_dir)
    svc_prop = collect_service_property_inventory(repo_root, classes)
    build_id = read_build_id(runtime_build_dir)

    total_types = len(classes)
    iface_count = sum(1 for c in classes.values() if c["is_interface"])
    class_count = total_types - iface_count
    declared_methods = sum(len(c["methods"]) for c in classes.values())
    declared_fields = sum(len(c["fields"]) for c in classes.values())

    # Strip raw integer access field inside serialized classes if desired? Keeping it makes
    # downstream queries trivial and lossless.
    summary = {
        "bytecode_total_types": total_types,
        "bytecode_classes": class_count,
        "bytecode_interfaces": iface_count,
        "bytecode_declared_methods": declared_methods,
        "bytecode_declared_fields": declared_fields,
        "bytecode_native_methods": jni["bytecode_native_methods_total"],
        "bytecode_shadowed_duplicates": len(bc["shadowed"]),
        "jni_register_natives_total": len(jni["registrations"]),
        "jni_exported_java_symbols": len(jni["binary_java_exports"]),
        "jni_unbound_native_methods": len(jni["unbound_bytecode_native_methods"]),
        "bionic_exports_total": bionic["total_nm_exports"],
        "bionic_arm64_shims": len(bionic["arm64_shim"]),
        "bionic_arm64_per_image_total": sum(len(v) for v in bionic["arm64_per_image"].values()),
        "bionic_fmod_stubs": len(bionic["fmod_stub"]),
        "bionic_direct_c_ndk": len(bionic["direct_c_ndk"]),
        "bionic_cxx_internal": bionic["cxx_internal_count"],
        "bionic_adapters_in_source": len(bionic["adapters_in_arm64_native"]),
        "service_manager_methods": len(svc_prop["service_manager_methods"]),
        "system_properties_known_keys": len(svc_prop["system_properties_known_keys"]),
    }

    return {
        "schema_version": "1.0.0",
        "runtime_build_id": build_id,
        "sources": {
            "jars": list(JARS_IN_ORDER),
            "patch_dirs": list(PATCH_DIRS_IN_ORDER),
            "source_counts": bc["source_counts"],
            "bionic_shim_dylib": bionic["dylib"],
            "jni_source_files": jni["source_files"],
        },
        "summary": summary,
        "classes": classes,
        "shadowed_classes": bc["shadowed"],
        "jni_registrations": jni["registrations"],
        "jni_binary_java_exports": jni["binary_java_exports"],
        "unbound_bytecode_native_methods": jni["unbound_bytecode_native_methods"],
        "bionic_exports": bionic,
        "service_property": svc_prop,
    }


# ---------------------------------------------------------------------------
# Test Manifest Loader (from scripts/run_tests.py)
# ---------------------------------------------------------------------------

def load_registered_tests(
    repo_root: pathlib.Path,
    test_manifest_path: Optional[pathlib.Path] = None,
) -> Dict[str, Dict[str, str]]:
    if test_manifest_path is not None:
        raw = json.loads(test_manifest_path.read_text(encoding="utf-8"))
        if isinstance(raw, dict) and "tests" in raw:
            return {t["id"]: t for t in raw["tests"]}
        if isinstance(raw, list):
            return {t["id"]: t for t in raw}
        raise ValueError(f"Unsupported test manifest format: {test_manifest_path}")

    run_tests_py = repo_root / "scripts" / "run_tests.py"
    if not run_tests_py.is_file():
        raise FileNotFoundError(f"Missing test runner: {run_tests_py}")

    proc = subprocess.run(
        [sys.executable, str(run_tests_py), "--list"],
        capture_output=True,
        text=True,
        check=True,
    )
    tests: Dict[str, Dict[str, str]] = {}
    for line in proc.stdout.splitlines():
        line = line.strip()
        if not line or line.startswith("Total registered tests:"):
            continue
        # Format from run_tests.py --list:
        # <group> <id> <kind> <source> -- <notes>
        m = re.match(r"^(\S+)\s+(\S+)\s+(\S+)\s+(\S+)\s+--\s+(.*)$", line)
        if m:
            tests[m.group(2)] = {
                "id": m.group(2),
                "group": m.group(1),
                "kind": m.group(3),
                "source": m.group(4),
                "notes": m.group(5),
            }
    if not tests:
        raise RuntimeError("Failed to parse any test IDs from scripts/run_tests.py --list")
    return tests


def compute_test_manifest_sha256(tests: Dict[str, Dict[str, str]]) -> str:
    canonical = [
        {"id": k, "group": tests[k]["group"], "kind": tests[k]["kind"], "source": tests[k]["source"]}
        for k in sorted(tests)
    ]
    return sha256_bytes(canonical_json_bytes(canonical))


# ---------------------------------------------------------------------------
# Schema & Semantic Validator
# ---------------------------------------------------------------------------

def _is_int(v: Any) -> bool:
    return isinstance(v, int) and not isinstance(v, bool)


def validate_registry(
    data: Any,
    schema: Dict[str, Any],
    registered_tests: Dict[str, Dict[str, str]],
    repo_root: pathlib.Path,
    inventory: Optional[Dict[str, Any]] = None,
    inventory_sha256: Optional[str] = None,
    runtime_build_dir: Optional[pathlib.Path] = None,
    check_staleness: bool = False,
) -> Tuple[List[str], List[str]]:
    errors: List[str] = []
    warnings: List[str] = []

    if not isinstance(data, dict):
        return ["Top-level capabilities document must be a JSON object"], warnings

    for req_key in schema.get("required", []):
        if req_key not in data:
            errors.append(f"Missing required top-level key: {req_key}")

    if data.get("schema_version") != "1.0.0":
        errors.append(f"Unsupported schema_version: {data.get('schema_version')!r}")

    # Validate api_anchor
    api_anchor = data.get("api_anchor")
    if not isinstance(api_anchor, dict):
        errors.append("api_anchor must be an object")
    else:
        for k in ("aosp_tag", "api_level", "api_index_gap"):
            if k not in api_anchor:
                errors.append(f"api_anchor missing required key: {k}")
        if not _is_int(api_anchor.get("api_level")):
            errors.append("api_anchor.api_level must be an integer")

    # Validate sdk_policy
    sdk_policy = data.get("sdk_policy")
    if not isinstance(sdk_policy, dict):
        errors.append("sdk_policy must be an object")
    else:
        for k in ("default_sdk", "floor_sdk", "ceiling_sdk", "max_known_sdk", "sdk_from_target_default"):
            if k not in sdk_policy:
                errors.append(f"sdk_policy missing required key: {k}")

    # Validate surfaces
    surfaces = data.get("surfaces")
    if not isinstance(surfaces, dict):
        errors.append("surfaces must be an object")
    else:
        for sname in SURFACES:
            if sname not in surfaces:
                errors.append(f"surfaces missing required surface: {sname}")
            else:
                sobj = surfaces[sname]
                if not isinstance(sobj, dict):
                    errors.append(f"surfaces.{sname} must be an object")
                    continue
                for sk in ("source", "denominator", "counts", "blind_spots"):
                    if sk not in sobj:
                        errors.append(f"surfaces.{sname} missing key: {sk}")
                if not isinstance(sobj.get("blind_spots"), list) or not sobj.get("blind_spots"):
                    errors.append(f"surfaces.{sname}.blind_spots must be a non-empty list")

    # Validate capabilities list
    caps = data.get("capabilities")
    if not isinstance(caps, list):
        errors.append("capabilities must be an array")
        return errors, warnings

    seen_ids: Set[str] = set()
    id_re = re.compile(r"^[A-Z]+-[0-9]{3,}$")
    date_re = re.compile(r"^[0-9]{4}-[0-9]{2}-[0-9]{2}$")

    surface_computed: Dict[str, Dict[str, int]] = {
        s: {st: 0 for st in SUPPORT_STATUSES} | {"verified": 0}
        for s in SURFACES
    }

    for idx, entry in enumerate(caps):
        ctx = f"capabilities[{idx}]"
        if not isinstance(entry, dict):
            errors.append(f"{ctx} must be an object")
            continue
        eid = entry.get("id")
        if not isinstance(eid, str) or not id_re.match(eid):
            errors.append(f"{ctx}.id invalid: {eid!r}")
        elif eid in seen_ids:
            errors.append(f"Duplicate capability id: {eid}")
        else:
            seen_ids.add(eid)
            ctx = f"capability {eid}"

        for req_f in (
            "id", "subsystem", "surface", "name", "api_abi_version",
            "signatures", "implementation", "aosp_source", "support_status",
            "verification_status", "staleness", "limitations", "test_ids",
            "evidence", "verified_version", "verified_date", "gap_ids",
            "related_gates", "inventory_refs",
        ):
            if req_f not in entry:
                errors.append(f"{ctx} missing required field: {req_f}")

        sub = entry.get("subsystem")
        if sub not in SUBSYSTEMS:
            errors.append(f"{ctx} invalid subsystem: {sub!r}")

        surf = entry.get("surface")
        if surf not in SURFACES:
            errors.append(f"{ctx} invalid surface: {surf!r}")

        sup = entry.get("support_status")
        if sup not in SUPPORT_STATUSES:
            errors.append(f"{ctx} invalid support_status: {sup!r}")

        ver = entry.get("verification_status")
        if ver not in VERIFICATION_STATUSES:
            errors.append(f"{ctx} invalid verification_status: {ver!r}")

        stl = entry.get("staleness")
        if stl not in STALENESS_STATES:
            errors.append(f"{ctx} invalid staleness: {stl!r}")

        for list_field in ("signatures", "implementation"):
            val = entry.get(list_field)
            if not isinstance(val, list) or not val or not all(isinstance(x, str) and x for x in val):
                errors.append(f"{ctx}.{list_field} must be a non-empty list of strings")

        for list_field in ("limitations", "test_ids", "evidence", "gap_ids", "related_gates", "inventory_refs"):
            val = entry.get(list_field)
            if not isinstance(val, list) or not all(isinstance(x, str) and x for x in val):
                errors.append(f"{ctx}.{list_field} must be a list of non-empty strings")

        # Validate test_ids against registered tests (reject dangling test IDs)
        tids = entry.get("test_ids", [])
        if isinstance(tids, list):
            for tid in tids:
                if tid not in registered_tests:
                    errors.append(f"{ctx} references dangling test_id: {tid!r}")

        # Verified entries must carry non-empty test_ids, evidence, verified_version, verified_date
        if ver in ("contract-tested", "android-compared"):
            if not tids:
                errors.append(f"{ctx} has verification_status={ver!r} but empty test_ids")
            ev = entry.get("evidence", [])
            if not ev:
                errors.append(f"{ctx} has verification_status={ver!r} but empty evidence")
            vver = entry.get("verified_version")
            if not isinstance(vver, str) or not vver.strip():
                errors.append(f"{ctx} has verification_status={ver!r} but missing verified_version")
            vdate = entry.get("verified_date")
            if not isinstance(vdate, str) or not date_re.match(vdate):
                errors.append(f"{ctx} has verification_status={ver!r} but invalid verified_date: {vdate!r}")
        elif ver == "unverified":
            # Optional verified_date format check if non-null
            vdate = entry.get("verified_date")
            if vdate is not None and (not isinstance(vdate, str) or not date_re.match(vdate)):
                errors.append(f"{ctx} invalid verified_date: {vdate!r}")

        # Validate inventory_refs if inventory is available
        if inventory is not None and isinstance(entry.get("inventory_refs"), list):
            classes = inventory.get("classes", {})
            jni_set = {
                f"{r['class']}#{r['name']}{r['descriptor']}"
                for r in inventory.get("jni_registrations", [])
            }
            jni_export_set = {
                f"{r['binary']}#{r['symbol']}"
                for r in inventory.get("jni_binary_java_exports", [])
            }
            bionic_arm64 = {x["bionic_symbol"] for x in inventory.get("bionic_exports", {}).get("arm64_shim", [])}
            bionic_direct = set(inventory.get("bionic_exports", {}).get("direct_c_ndk", []))
            bionic_adapters = set(inventory.get("bionic_exports", {}).get("adapters_in_arm64_native", []))

            for ref in entry["inventory_refs"]:
                if ref.startswith("class:"):
                    cname = ref[len("class:"):]
                    if cname not in classes:
                        errors.append(f"{ctx} inventory_ref missing class: {ref}")
                elif ref.startswith("method:"):
                    spec = ref[len("method:"):]
                    if "#" not in spec or "(" not in spec:
                        errors.append(f"{ctx} malformed method inventory_ref: {ref}")
                    else:
                        cname, msig = spec.split("#", 1)
                        mname, mdesc = msig.split("(", 1)
                        mdesc = "(" + mdesc
                        if resolve_method(classes, cname, mname, mdesc) is None:
                            errors.append(f"{ctx} inventory_ref method not found (even inherited): {ref}")
                elif ref.startswith("jni:"):
                    spec = ref[len("jni:"):]
                    if spec not in jni_set:
                        errors.append(f"{ctx} inventory_ref JNI registration not found: {ref}")
                elif ref.startswith("jni_export:"):
                    spec = ref[len("jni_export:"):]
                    if spec not in jni_export_set:
                        errors.append(f"{ctx} inventory_ref JNI binary export not found: {ref}")
                elif ref.startswith("bionic:"):
                    sym = ref[len("bionic:"):]
                    if sym not in bionic_arm64:
                        errors.append(f"{ctx} inventory_ref bionic arm64 shim not found: {ref}")
                elif ref.startswith("bionic_direct:"):
                    sym = ref[len("bionic_direct:"):]
                    if sym not in bionic_direct:
                        errors.append(f"{ctx} inventory_ref bionic direct export not found: {ref}")
                elif ref.startswith("adapter:"):
                    sym = ref[len("adapter:"):]
                    if sym not in bionic_adapters:
                        errors.append(f"{ctx} inventory_ref bionic adapter not found: {ref}")
                else:
                    errors.append(f"{ctx} unknown inventory_ref prefix: {ref}")

        if surf in surface_computed and sup in SUPPORT_STATUSES:
            surface_computed[surf][sup] += 1
            if ver in ("contract-tested", "android-compared"):
                surface_computed[surf]["verified"] += 1

    # Check surface counts consistency
    if isinstance(surfaces, dict):
        for sname, expected in surface_computed.items():
            sobj = surfaces.get(sname)
            if isinstance(sobj, dict) and isinstance(sobj.get("counts"), dict):
                counts = sobj["counts"]
                for k, v in expected.items():
                    if k in counts and counts[k] != v:
                        errors.append(
                            f"surfaces.{sname}.counts.{k}={counts[k]} != computed {v} from capabilities[]"
                        )

    # Check staleness
    if check_staleness:
        anchor = data.get("staleness_anchor", {})
        if not isinstance(anchor, dict):
            errors.append("staleness_anchor must be an object")
        else:
            if inventory_sha256 and anchor.get("inventory_sha256") != inventory_sha256:
                warnings.append(
                    f"STALE inventory_sha256: anchor={anchor.get('inventory_sha256')} != current={inventory_sha256}"
                )
            cur_test_sha = compute_test_manifest_sha256(registered_tests)
            if anchor.get("test_manifest_sha256") != cur_test_sha:
                warnings.append(
                    f"STALE test_manifest_sha256: anchor={anchor.get('test_manifest_sha256')} != current={cur_test_sha}"
                )
            if runtime_build_dir is not None:
                cur_bid = read_build_id(runtime_build_dir)
                if cur_bid and anchor.get("build_id") != cur_bid:
                    warnings.append(
                        f"STALE build_id: anchor={anchor.get('build_id')} != current={cur_bid}"
                    )
            tracked = anchor.get("tracked_file_hashes", {})
            if isinstance(tracked, dict):
                for rel_p, recorded_sha in tracked.items():
                    fp = repo_root / rel_p
                    if not fp.is_file():
                        warnings.append(f"STALE missing tracked file: {rel_p}")
                    else:
                        cur_sha = sha256_file(fp)
                        if cur_sha != recorded_sha:
                            warnings.append(f"STALE tracked file changed: {rel_p}")

    return errors, warnings


# ---------------------------------------------------------------------------
# Tracked File Hash & Surface Updater
# ---------------------------------------------------------------------------

def collect_tracked_files(
    capabilities: List[Dict[str, Any]],
    registered_tests: Dict[str, Dict[str, str]],
    repo_root: pathlib.Path,
) -> Dict[str, str]:
    rels: Set[str] = set()
    for entry in capabilities:
        for p in entry.get("implementation", []):
            rels.add(p)
        for tid in entry.get("test_ids", []):
            t = registered_tests.get(tid)
            if t and t.get("source"):
                rels.add(t["source"])
    out: Dict[str, str] = {}
    for rel in sorted(rels):
        fp = repo_root / rel
        if fp.is_file():
            out[rel] = sha256_file(fp)
    return out


def refresh_capabilities_metadata(
    cap_data: Dict[str, Any],
    inventory: Dict[str, Any],
    inventory_sha256: str,
    registered_tests: Dict[str, Dict[str, str]],
    repo_root: pathlib.Path,
) -> Dict[str, Any]:
    updated = json.loads(json.dumps(cap_data))
    caps = sorted(updated["capabilities"], key=lambda e: e["id"])
    updated["capabilities"] = caps

    inv_summary = inventory["summary"]

    surface_counts: Dict[str, Dict[str, int]] = {
        s: {st: 0 for st in SUPPORT_STATUSES} | {"verified": 0}
        for s in SURFACES
    }
    for e in caps:
        s = e["surface"]
        st = e["support_status"]
        surface_counts[s][st] += 1
        if e["verification_status"] in ("contract-tested", "android-compared"):
            surface_counts[s]["verified"] += 1

    # Update local denominators from real inventory
    surfaces = updated["surfaces"]
    surfaces["public_sdk"]["denominator"]["local_total"] = inv_summary["bytecode_total_types"]
    surfaces["public_sdk"]["denominator"]["local_methods_total"] = inv_summary["bytecode_declared_methods"]
    surfaces["public_sdk"]["counts"] = surface_counts["public_sdk"]

    surfaces["hidden_api"]["denominator"]["local_total"] = len(
        [c for c in inventory["classes"] if c.startswith("com/macapk/")]
    ) + len(inventory["service_property"]["service_manager_methods"])
    surfaces["hidden_api"]["counts"] = surface_counts["hidden_api"]

    surfaces["jni_registration"]["denominator"]["local_total"] = inv_summary["jni_register_natives_total"]
    surfaces["jni_registration"]["denominator"]["bytecode_native_methods"] = inv_summary["bytecode_native_methods"]
    surfaces["jni_registration"]["denominator"]["binary_java_exports"] = inv_summary["jni_exported_java_symbols"]
    surfaces["jni_registration"]["denominator"]["unbound_bytecode_native_methods"] = inv_summary["jni_unbound_native_methods"]
    surfaces["jni_registration"]["counts"] = surface_counts["jni_registration"]

    surfaces["bionic_abi"]["denominator"]["local_total"] = inv_summary["bionic_exports_total"]
    surfaces["bionic_abi"]["denominator"]["arm64_shims"] = inv_summary["bionic_arm64_shims"]
    surfaces["bionic_abi"]["denominator"]["direct_c_ndk"] = inv_summary["bionic_direct_c_ndk"]
    surfaces["bionic_abi"]["denominator"]["adapters_in_source"] = inv_summary["bionic_adapters_in_source"]
    surfaces["bionic_abi"]["counts"] = surface_counts["bionic_abi"]

    surfaces["service_property"]["denominator"]["local_total"] = (
        inv_summary["service_manager_methods"] + inv_summary["system_properties_known_keys"]
    )
    surfaces["service_property"]["denominator"]["service_manager_methods"] = inv_summary["service_manager_methods"]
    surfaces["service_property"]["denominator"]["system_properties_known_keys"] = inv_summary["system_properties_known_keys"]
    surfaces["service_property"]["counts"] = surface_counts["service_property"]

    updated["staleness_anchor"] = {
        "build_id": inventory["runtime_build_id"],
        "inventory_sha256": inventory_sha256,
        "test_manifest_sha256": compute_test_manifest_sha256(registered_tests),
        "tracked_file_hashes": collect_tracked_files(caps, registered_tests, repo_root),
        "status": "fresh",
    }
    return updated


# ---------------------------------------------------------------------------
# Markdown Renderer
# ---------------------------------------------------------------------------

def render_capabilities_md(
    cap_data: Dict[str, Any],
    inventory: Optional[Dict[str, Any]] = None,
) -> str:
    anchor = cap_data["api_anchor"]
    policy = cap_data["sdk_policy"]
    stale = cap_data["staleness_anchor"]
    caps = sorted(cap_data["capabilities"], key=lambda e: (SUBSYSTEMS.index(e["subsystem"]), e["id"]))

    lines: List[str] = [
        "# Mac.apk Compatibility Capabilities Registry",
        "",
        "<!-- Generated by scripts/cap_registry.py render-md from docs/compat/capabilities.json -->",
        "",
        "## 1. Research Anchor & SDK Presentation Policy",
        "",
        f"- **AOSP Reference Anchor**: Android 16 / API {anchor['api_level']} (`{anchor['aosp_tag']}`). "
        "Used solely as the reference contract anchor for new entries, **not** a claim of full API 36 completeness.",
        f"- **`android-api.idx.gz` Gap**: {anchor['api_index_gap']}",
        f"- **Presented SDK Policy (`PresentedSdk` / `Build`)**:",
        f"  - `DEFAULT_SDK = {policy['default_sdk']}`, `FLOOR = {policy['floor_sdk']}`, "
        f"`CEILING = {policy['ceiling_sdk']}`, `MAX_KNOWN_SDK = {policy['max_known_sdk']}`.",
        f"  - `MACAPK_SDK_FROM_TARGET`: default `{policy['sdk_from_target_default']}` "
        "(clamps app `targetSdkVersion` to `[29, 36]`, falls back to `29` if manifest unreadable or disabled with `=0`).",
        f"  - Overrides: `{', '.join(policy['overrides'])}`.",
        f"  - Unverified preview constant: `{', '.join(policy['version_codes_unverified_names'])}` "
        "(kept for reflective lookups; awaiting finalized AOSP branch).",
        "",
        "## 2. Build & Staleness Anchor",
        "",
        f"- **Inventory SHA-256**: `{stale.get('inventory_sha256', 'unknown')}`",
        f"- **Test Manifest SHA-256**: `{stale.get('test_manifest_sha256', 'unknown')}`",
        f"- **Runtime Build ID**: `{json.dumps(stale.get('build_id', {}), sort_keys=True)}`",
        f"- **Tracked Source/Test Files**: {len(stale.get('tracked_file_hashes', {}))} files hashed for automatic staleness detection.",
        "",
        "## 3. Separated Surfaces, Denominators & Blind Spots",
        "",
        "| Surface | Local Denominator | Upstream Denominator | Registry Entries (Verified) | Primary Blind Spots |",
        "|---|---:|---:|---:|---|",
    ]
    for sname in SURFACES:
        sobj = cap_data["surfaces"][sname]
        denom = sobj["denominator"]
        up = str(denom["upstream_total"]) if denom.get("upstream_total") is not None else f"unknown ({denom.get('upstream_note', 'n/a')})"
        c = sobj["counts"]
        total_reg = sum(c[st] for st in SUPPORT_STATUSES)
        lines.append(
            f"| `{sname}` | {denom['local_total']} | {up} | {total_reg} ({c['verified']} verified) | "
            + "; ".join(sobj["blind_spots"][:2]) + " |"
        )

    if inventory is not None:
        sm = inventory["summary"]
        lines.extend([
            "",
            "### Bytecode & Native Inventory Snapshot (`docs/compat/inventory.json`)",
            "",
            f"- Built types: **{sm['bytecode_total_types']}** "
            f"({sm['bytecode_classes']} classes, {sm['bytecode_interfaces']} interfaces/annotations; "
            f"{sm['bytecode_shadowed_duplicates']} classpath-shadowed duplicates)",
            f"- Declared JVM methods / fields: **{sm['bytecode_declared_methods']}** methods "
            f"(**{sm['bytecode_native_methods']}** `native`), **{sm['bytecode_declared_fields']}** fields",
            f"- JNI `RegisterNatives` entries: **{sm['jni_register_natives_total']}**; "
            f"Mach-O `Java_*` exports: **{sm['jni_exported_java_symbols']}**; "
            f"bytecode `native` methods without static JNI/export: **{sm['jni_unbound_native_methods']}**",
            f"- `libmacapk-bionic-shim.dylib` `nm -gU` exports: **{sm['bionic_exports_total']}** "
            f"(`__macapk_arm64_*`: {sm['bionic_arm64_shims']}, per-image: {sm['bionic_arm64_per_image_total']}, "
            f"FMOD stubs: {sm['bionic_fmod_stubs']}, direct C/NDK: {sm['bionic_direct_c_ndk']}, "
            f"C++ internals: {sm['bionic_cxx_internal']}); `arm64_native.cpp` adapters: **{sm['bionic_adapters_in_source']}**",
            f"- `ServiceManager` `@hide` methods: **{sm['service_manager_methods']}**; "
            f"`SystemProperties.KNOWN` keys: **{sm['system_properties_known_keys']}**",
        ])

    sup_totals = {st: sum(1 for e in caps if e["support_status"] == st) for st in SUPPORT_STATUSES}
    ver_totals = {vt: sum(1 for e in caps if e["verification_status"] == vt) for vt in VERIFICATION_STATUSES}
    lines.extend([
        "",
        "## 4. Manually Verified Seed Capabilities (6 Subsystems)",
        "",
        f"- **Total Registry Entries**: {len(caps)} "
        f"(support: `implemented`={sup_totals['implemented']}, `partial`={sup_totals['partial']}, "
        f"`unsupported`={sup_totals['unsupported']}, `missing`={sup_totals['missing']}, "
        f"`unknown`={sup_totals['unknown']}; "
        f"verification: `contract-tested`={ver_totals['contract-tested']}, "
        f"`android-compared`={ver_totals['android-compared']}, `unverified`={ver_totals['unverified']}).",
        "- **Coverage Disclaimer**: Local denominators and seed counts only reflect inspected Mac.apk build "
        "surfaces (`android-framework.jar`, `macapk-loader.jar`, patch modules, `libmacapk-bionic-shim.dylib`, "
        "JNI registrations) and do **not** claim full Android API 36 completeness; upstream AOSP API index "
        "denominator remains unmeasured (`unknown`) until `tools/api-index/gen.py` is supplied.",
        "",
        "| ID | Subsystem | Surface | Name | Support | Verification | Tests | Key Limitations / Notes |",
        "|---|---|---|---|---|---|---|---|",
    ])
    for e in caps:
        tests_str = ", ".join(f"`{t}`" for t in e["test_ids"]) if e["test_ids"] else "—"
        lim = "; ".join(e["limitations"][:2]) if e["limitations"] else "—"
        lines.append(
            f"| `{e['id']}` | `{e['subsystem']}` | `{e['surface']}` | `{e['name']}` | "
            f"`{e['support_status']}` | `{e['verification_status']}` | {tests_str} | {lim} |"
        )

    lines.extend([
        "",
        "## 5. `android.os.ServiceManager` Method Matrix (`IPC-004`)",
        "",
        "All 13 `@hide` static methods in `runtime/java/android/os/ServiceManager.java`:",
        "",
        "- `getService(String)` -> `null` (`unsupported` / `unverified`)",
        "- `checkService(String)` -> `null` (`unsupported` / `unverified`)",
        "- `getServiceOrThrow(String)` -> returns `null` instead of throwing `ServiceNotFoundException` (`unsupported` / `unverified` — contract gap)",
        "- `waitForService(String)` -> `null` (`unsupported` / `unverified`)",
        "- `waitForDeclaredService(String)` -> `null` (`unsupported` / `unverified`)",
        "- `addService(String, IBinder)` / `(String, IBinder, boolean)` / `(String, IBinder, boolean, int)` -> no-op (`unsupported` / `unverified`)",
        "- `listServices()` / `listServices(int)` -> `new String[0]` (`unsupported` / `unverified`)",
        "- `isDeclared(String)` -> `false` (`unsupported` / `unverified`)",
        "- `getDeclaredInstances(String)` -> `new String[0]` (`unsupported` / `unverified`)",
        "- `initServiceCache(Map<String, IBinder>)` -> no-op (`unsupported` / `unverified`)",
        "",
    ])
    return "\n".join(lines)


# ---------------------------------------------------------------------------
# CLI Entrypoint
# ---------------------------------------------------------------------------

def main(argv: Optional[List[str]] = None) -> int:
    parser = argparse.ArgumentParser(description="Mac.apk CAP-001 capability registry & inventory tool.")
    sub = parser.add_subparsers(dest="cmd", required=True)

    p_gen = sub.add_parser("generate", help="Generate deterministic bytecode/native inventory JSON.")
    p_gen.add_argument("--runtime-build-dir", default="runtime/build", help="Path to runtime/build (default: ./runtime/build)")
    p_gen.add_argument("--output-inventory", default="docs/compat/inventory.json")
    p_gen.add_argument("--capabilities", default="docs/compat/capabilities.json")
    p_gen.add_argument("--update-capabilities", action="store_true", help="Deterministically refresh surface counts and staleness anchor in capabilities.json")
    p_gen.add_argument("--output-md", default=None, help="Optional path to render CAPABILITIES.md")

    p_val = sub.add_parser("validate", help="Validate capabilities.json against schema, test manifest, and inventory.")
    p_val.add_argument("--capabilities", default="docs/compat/capabilities.json")
    p_val.add_argument("--schema", default="docs/compat/capabilities.schema.json")
    p_val.add_argument("--inventory", default="docs/compat/inventory.json")
    p_val.add_argument("--test-manifest", default=None, help="Optional JSON test manifest instead of scripts/run_tests.py --list")
    p_val.add_argument("--runtime-build-dir", default=None)
    p_val.add_argument("--check-staleness", action="store_true")
    p_val.add_argument("--strict-staleness", action="store_true")

    p_qry = sub.add_parser("query", help="Query class/interface, overloads, and inherited methods from inventory.")
    p_qry.add_argument("--inventory", default="docs/compat/inventory.json")
    p_qry.add_argument("--class", dest="cls_name", required=True)
    p_qry.add_argument("--method", default=None)
    p_qry.add_argument("--descriptor", default=None)
    p_qry.add_argument("--no-inherited", action="store_true")

    p_md = sub.add_parser("render-md", help="Render docs/compat/CAPABILITIES.md from capabilities.json and inventory.json.")
    p_md.add_argument("--capabilities", default="docs/compat/capabilities.json")
    p_md.add_argument("--inventory", default="docs/compat/inventory.json")
    p_md.add_argument("--output-md", default="docs/compat/CAPABILITIES.md")

    args = parser.parse_args(argv)
    repo_root = find_repo_root()

    if args.cmd == "generate":
        build_dir = pathlib.Path(args.runtime_build_dir)
        if not build_dir.is_absolute():
            build_dir = (repo_root / build_dir).resolve()
        inv = build_inventory(repo_root, build_dir)
        inv_path = pathlib.Path(args.output_inventory)
        if not inv_path.is_absolute():
            inv_path = repo_root / inv_path
        inv_sha = write_deterministic_json(inv_path, inv)
        print(f"wrote inventory: {inv_path} (sha256={inv_sha[:16]}, types={inv['summary']['bytecode_total_types']}, "
              f"jni={inv['summary']['jni_register_natives_total']}, bionic={inv['summary']['bionic_exports_total']})")

        cap_path = pathlib.Path(args.capabilities)
        if not cap_path.is_absolute():
            cap_path = repo_root / cap_path
        if args.update_capabilities and cap_path.is_file():
            cap_data = json.loads(cap_path.read_text(encoding="utf-8"))
            tests = load_registered_tests(repo_root)
            updated = refresh_capabilities_metadata(cap_data, inv, inv_sha, tests, repo_root)
            cap_sha = write_deterministic_json(cap_path, updated)
            print(f"updated capabilities: {cap_path} (sha256={cap_sha[:16]}, entries={len(updated['capabilities'])})")
            cap_data = updated
        else:
            cap_data = json.loads(cap_path.read_text(encoding="utf-8")) if cap_path.is_file() else None

        if args.output_md and cap_data is not None:
            md_path = pathlib.Path(args.output_md)
            if not md_path.is_absolute():
                md_path = repo_root / md_path
            md_path.parent.mkdir(parents=True, exist_ok=True)
            md_path.write_text(render_capabilities_md(cap_data, inv), encoding="utf-8")
            print(f"wrote markdown: {md_path}")
        return 0

    if args.cmd == "validate":
        cap_path = pathlib.Path(args.capabilities)
        schema_path = pathlib.Path(args.schema)
        if not cap_path.is_absolute():
            cap_path = repo_root / cap_path
        if not schema_path.is_absolute():
            schema_path = repo_root / schema_path
        cap_data = json.loads(cap_path.read_text(encoding="utf-8"))
        schema_data = json.loads(schema_path.read_text(encoding="utf-8"))

        inv_data = None
        inv_sha = None
        if args.inventory:
            inv_path = pathlib.Path(args.inventory)
            if not inv_path.is_absolute():
                inv_path = repo_root / inv_path
            if inv_path.is_file():
                inv_bytes = inv_path.read_bytes()
                inv_sha = sha256_bytes(inv_bytes)
                inv_data = json.loads(inv_bytes.decode("utf-8"))

        tmanifest = pathlib.Path(args.test_manifest) if args.test_manifest else None
        tests = load_registered_tests(repo_root, tmanifest)
        bdir = pathlib.Path(args.runtime_build_dir).resolve() if args.runtime_build_dir else None

        errors, warnings = validate_registry(
            cap_data,
            schema_data,
            tests,
            repo_root,
            inventory=inv_data,
            inventory_sha256=inv_sha,
            runtime_build_dir=bdir,
            check_staleness=args.check_staleness or args.strict_staleness,
        )
        for w in warnings:
            sys.stderr.write(f"WARNING: {w}\n")
        for e in errors:
            sys.stderr.write(f"ERROR: {e}\n")
        if errors:
            return 1
        if args.strict_staleness and warnings:
            return 2
        print(f"VALID: {len(cap_data.get('capabilities', []))} capability entries passed schema/test/inventory checks"
              + (f" ({len(warnings)} staleness warnings)" if warnings else ""))
        return 0

    if args.cmd == "query":
        inv_path = pathlib.Path(args.inventory)
        if not inv_path.is_absolute():
            inv_path = repo_root / inv_path
        inv = json.loads(inv_path.read_text(encoding="utf-8"))
        classes = inv["classes"]
        cname = normalize_internal_name(args.cls_name)
        if cname not in classes:
            sys.stderr.write(f"Class not found in inventory: {cname}\n")
            return 1
        cinfo = classes[cname]
        if args.method and args.descriptor:
            if args.no_inherited:
                found = None
                for m in cinfo["methods"]:
                    if m["name"] == args.method and m["descriptor"] == args.descriptor:
                        found = dict(m, declared_in=cname, declared_kind=cinfo["kind"], inherited=False)
                        break
            else:
                found = resolve_method(classes, cname, args.method, args.descriptor)
            print(json.dumps({"class": cname, "kind": cinfo["kind"], "match": found}, indent=2, sort_keys=True))
            return 0 if found else 1
        eff = effective_methods(classes, cname) if not args.no_inherited else {
            (m["name"], m["descriptor"]): dict(m, declared_in=cname, declared_kind=cinfo["kind"], inherited=False)
            for m in cinfo["methods"]
        }
        matches = [
            v for (mn, _md), v in sorted(eff.items())
            if args.method is None or mn == args.method
        ]
        print(json.dumps({
            "class": cname,
            "kind": cinfo["kind"],
            "is_interface": cinfo["is_interface"],
            "super": cinfo["super"],
            "interfaces": cinfo["interfaces"],
            "method_count": len(matches),
            "methods": matches,
        }, indent=2, sort_keys=True))
        return 0

    if args.cmd == "render-md":
        cap_path = pathlib.Path(args.capabilities)
        if not cap_path.is_absolute():
            cap_path = repo_root / cap_path
        cap_data = json.loads(cap_path.read_text(encoding="utf-8"))
        inv_data = None
        if args.inventory:
            inv_path = pathlib.Path(args.inventory)
            if not inv_path.is_absolute():
                inv_path = repo_root / inv_path
            if inv_path.is_file():
                inv_data = json.loads(inv_path.read_text(encoding="utf-8"))
        md_path = pathlib.Path(args.output_md)
        if not md_path.is_absolute():
            md_path = repo_root / md_path
        md_path.parent.mkdir(parents=True, exist_ok=True)
        md_path.write_text(render_capabilities_md(cap_data, inv_data), encoding="utf-8")
        print(f"wrote markdown: {md_path}")
        return 0

    return 2


if __name__ == "__main__":
    sys.exit(main())
