#!/usr/bin/env python3
"""Mac.apk SCAN-001 offline read-only APK, DEX, AXML, and ELF dependency scanner.

Zero external dependencies (Python 3.9+ standard library only).
Never executes APK bytecode, DEX initializers, or native .so initializers.
Never performs network requests or modifies input APKs.

Exit codes (gap findings never cause a non-zero exit):
  0  Scan succeeded cleanly (even when compatibility gaps or unknowns are found)
  2  CLI argument error or missing input path
  3  Archive security/resource error (corrupt ZIP, zip-slip traversal, symlink, size cap)
  4  Binary format error (corrupt AXML manifest, DEX, or ELF)
  5  Capability registry / inventory load error
"""

from __future__ import annotations

import argparse
import hashlib
import io
import json
import os
import pathlib
import posixpath
import re
import resource
import struct
import subprocess
import sys
import time
import zipfile
import zlib
from typing import Any, Dict, Iterable, List, Optional, Sequence, Set, Tuple

SCRIPT_DIR = pathlib.Path(__file__).resolve().parent
if str(SCRIPT_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPT_DIR))

import cap_registry  # noqa: E402

SCANNER_VERSION = "1.0.0"

EXIT_OK = 0
EXIT_CLI_OR_IO = 2
EXIT_ARCHIVE_SECURITY = 3
EXIT_FORMAT_ERROR = 4
EXIT_REGISTRY_ERROR = 5

# Default safety / resource caps
DEFAULT_MAX_APK_BYTES = 512 * 1024 * 1024
DEFAULT_MAX_ZIP_ENTRIES = 50_000
DEFAULT_MAX_TOTAL_UNCOMPRESSED_BYTES = 1024 * 1024 * 1024
DEFAULT_MAX_MANIFEST_BYTES = 16 * 1024 * 1024
DEFAULT_MAX_DEX_BYTES = 64 * 1024 * 1024
DEFAULT_MAX_ELF_BYTES = 128 * 1024 * 1024

# DEX constants
DEX_ACCESS_PRIVATE = 0x0002
DEX_ACCESS_STATIC = 0x0008
DEX_ACCESS_NATIVE = 0x0100
DEX_ACCESS_INTERFACE = 0x0200
DEX_ACCESS_ANNOTATION = 0x2000
DEX_ACCESS_ENUM = 0x4000

# AXML constants
RES_STRING_POOL_TYPE = 0x0001
RES_XML_TYPE = 0x0003
RES_XML_START_NAMESPACE = 0x0100
RES_XML_END_NAMESPACE = 0x0101
RES_XML_START_ELEMENT = 0x0102
RES_XML_END_ELEMENT = 0x0103
RES_XML_CDATA = 0x0104
RES_XML_RESOURCE_MAP = 0x0180

TYPE_NULL = 0x00
TYPE_REFERENCE = 0x01
TYPE_ATTRIBUTE = 0x02
TYPE_STRING = 0x03
TYPE_FLOAT = 0x04
TYPE_INT_DEC = 0x10
TYPE_INT_HEX = 0x11
TYPE_INT_BOOLEAN = 0x12

WELL_KNOWN_ANDROID_ATTRS: Dict[int, str] = {
    0x01010000: "theme",
    0x01010001: "label",
    0x01010003: "name",
    0x0101000E: "enabled",
    0x01010010: "exported",
    0x01010011: "process",
    0x01010018: "authorities",
    0x0101001A: "initOrder",
    0x0101001B: "grantUriPermissions",
    0x01010024: "value",
    0x01010025: "resource",
    0x0101020C: "minSdkVersion",
    0x0101021B: "versionCode",
    0x0101021C: "versionName",
    0x01010270: "targetSdkVersion",
    0x01010271: "maxSdkVersion",
    0x01010281: "glEsVersion",
    0x0101028E: "required",
    0x010104EA: "extractNativeLibs",
    0x01010573: "versionCodeMajor",
}

# ELF constants
ELFCLASS32 = 1
ELFCLASS64 = 2
ELFDATA2LSB = 1
ET_DYN = 3
ET_EXEC = 2
EM_ARM = 40
EM_386 = 3
EM_X86_64 = 62
EM_AARCH64 = 183

ABI_TO_EMACHINE: Dict[str, int] = {
    "arm64-v8a": EM_AARCH64,
    "armeabi-v7a": EM_ARM,
    "armeabi": EM_ARM,
    "x86_64": EM_X86_64,
    "x86": EM_386,
}
EMACHINE_NAMES: Dict[int, str] = {
    EM_AARCH64: "EM_AARCH64",
    EM_ARM: "EM_ARM",
    EM_X86_64: "EM_X86_64",
    EM_386: "EM_386",
}

PT_LOAD = 1
PT_DYNAMIC = 2

SHT_DYNSYM = 11
SHT_STRTAB = 3
SHT_GNU_VERSYM = 0x6FFFFFFF
SHT_GNU_VERNEED = 0x6FFFFFFE

SHN_UNDEF = 0
STB_LOCAL = 0
STB_GLOBAL = 1
STB_WEAK = 2
BIND_NAMES = {0: "LOCAL", 1: "GLOBAL", 2: "WEAK"}
TYPE_NAMES = {
    0: "NOTYPE",
    1: "OBJECT",
    2: "FUNC",
    3: "SECTION",
    4: "FILE",
    5: "COMMON",
    6: "TLS",
    10: "GNU_IFUNC",
}

DT_NULL = 0
DT_NEEDED = 1
DT_HASH = 4
DT_STRTAB = 5
DT_SYMTAB = 6
DT_STRSZ = 10
DT_SYMENT = 11
DT_INIT = 12
DT_SONAME = 14
DT_INIT_ARRAY = 25
DT_FINI_ARRAY = 26
DT_INIT_ARRAYSZ = 27
DT_GNU_HASH = 0x6FFFFEF5
DT_VERSYM = 0x6FFFFFF0
DT_VERNEED = 0x6FFFFFFE
DT_VERNEEDNUM = 0x6FFFFFFF

# Standard Android NDK / Bionic system SONAMEs (for DT_NEEDED classification)
PLATFORM_NDK_SONAMES: Set[str] = {
    "libc.so",
    "libm.so",
    "libdl.so",
    "liblog.so",
    "libz.so",
    "libstdc++.so",
    "libc++_shared.so",
    "libandroid.so",
    "libEGL.so",
    "libGLESv1_CM.so",
    "libGLESv2.so",
    "libGLESv3.so",
    "libOpenSLES.so",
    "libOpenMAXAL.so",
    "libmediandk.so",
    "libjnigraphics.so",
    "libcamera2ndk.so",
    "libvulkan.so",
    "libneuralnetworks.so",
    "libnativewindow.so",
    "libbinder_ndk.so",
    "libaaudio.so",
    "libsync.so",
    "libicuuc.so",
    "libicui18n.so",
}

JDK_PACKAGE_PREFIXES = (
    "java/",
    "javax/",
    "jdk/",
    "sun/",
    "com/sun/",
    "org/w3c/",
    "org/xml/",
    "org/ietf/",
    "org/omg/",
)

ANDROIDX_PREFIXES = ("androidx/", "android/support/")
PLATFORM_ANDROID_PREFIXES = ("android/", "com/android/", "dalvik/", "libcore/")


class ScannerError(Exception):
    exit_code: int = EXIT_CLI_OR_IO


class ArchiveSecurityError(ScannerError):
    exit_code = EXIT_ARCHIVE_SECURITY


class BinaryFormatError(ScannerError):
    exit_code = EXIT_FORMAT_ERROR


class RegistryLoadError(ScannerError):
    exit_code = EXIT_REGISTRY_ERROR


def peak_rss_bytes() -> int:
    ru = resource.getrusage(resource.RUSAGE_SELF).ru_maxrss
    return ru * 1024 if sys.platform.startswith("linux") else ru


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def sha256_file(path: pathlib.Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as fh:
        for chunk in iter(lambda: fh.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


# ---------------------------------------------------------------------------
# Safe Bounded ZIP Reader
# ---------------------------------------------------------------------------

def validate_zip_member_name(name: str) -> None:
    if not name or "\x00" in name:
        raise ArchiveSecurityError(f"Invalid ZIP entry name: {name!r}")
    if name.startswith("/") or name.startswith("\\") or re.match(r"^[A-Za-z]:", name):
        raise ArchiveSecurityError(f"Absolute path / zip-slip entry rejected: {name!r}")
    parts = re.split(r"[/\\]", name)
    if any(p == ".." for p in parts):
        raise ArchiveSecurityError(f"Path traversal (zip-slip) entry rejected: {name!r}")


def read_zip_member_bounded(
    zf: zipfile.ZipFile,
    info: zipfile.ZipInfo,
    max_bytes: int,
    remaining_total: List[int],
) -> bytes:
    if info.file_size > max_bytes:
        raise ArchiveSecurityError(
            f"ZIP entry {info.filename!r} exceeds per-entry size cap ({info.file_size} > {max_bytes})"
        )
    if info.file_size > remaining_total[0]:
        raise ArchiveSecurityError(
            f"ZIP entry {info.filename!r} exceeds total uncompressed size cap"
        )
    buf = bytearray()
    try:
        with zf.open(info, "r") as fh:
            while True:
                chunk = fh.read(65536)
                if not chunk:
                    break
                buf.extend(chunk)
                if len(buf) > max_bytes:
                    raise ArchiveSecurityError(
                        f"ZIP entry {info.filename!r} decompressed size exceeds cap ({max_bytes} bytes)"
                    )
                if len(buf) > remaining_total[0]:
                    raise ArchiveSecurityError(
                        f"Total decompressed bytes across archive exceeds cap while reading {info.filename!r}"
                    )
    except ArchiveSecurityError:
        raise
    except (zipfile.BadZipFile, zlib.error, EOFError, RuntimeError, OSError) as exc:
        raise ArchiveSecurityError(f"Corrupt or unreadable ZIP entry {info.filename!r}: {exc}") from exc
    remaining_total[0] -= len(buf)
    return bytes(buf)


def open_apk_safely(
    apk_path: pathlib.Path,
    max_apk_bytes: int,
    max_entries: int,
    max_total_uncompressed: int,
) -> Tuple[zipfile.ZipFile, List[zipfile.ZipInfo]]:
    if not apk_path.is_file():
        raise ScannerError(f"APK file not found: {apk_path}")
    size = apk_path.stat().st_size
    if size > max_apk_bytes:
        raise ArchiveSecurityError(f"APK {apk_path} exceeds size cap ({size} > {max_apk_bytes})")
    try:
        zf = zipfile.ZipFile(apk_path, "r")
    except (zipfile.BadZipFile, OSError, EOFError) as exc:
        raise ArchiveSecurityError(f"Corrupt ZIP/APK {apk_path}: {exc}") from exc

    infos = zf.infolist()
    if len(infos) > max_entries:
        zf.close()
        raise ArchiveSecurityError(
            f"APK {apk_path} exceeds max ZIP entry count ({len(infos)} > {max_entries})"
        )
    declared_total = 0
    for info in infos:
        try:
            validate_zip_member_name(info.filename)
            mode = (info.external_attr >> 16) & 0o170000
            if mode == 0o120000:
                raise ArchiveSecurityError(f"Symlink ZIP entry rejected: {info.filename!r}")
            declared_total += max(0, info.file_size)
            if declared_total > max_total_uncompressed:
                raise ArchiveSecurityError(
                    f"APK {apk_path} declared uncompressed size exceeds cap ({max_total_uncompressed})"
                )
        except Exception:
            zf.close()
            raise
    return zf, infos


# ---------------------------------------------------------------------------
# Pure-Python Binary AndroidManifest.xml (AXML) Parser
# ---------------------------------------------------------------------------

def _decode_axml_utf8(data: bytes, off: int, end: int) -> str:
    if off >= end:
        raise BinaryFormatError("AXML UTF-8 string offset out of bounds")
    p = off
    u16_len = data[p]
    p += 1
    if u16_len & 0x80:
        if p >= end:
            raise BinaryFormatError("AXML UTF-8 length truncated")
        p += 1
    if p >= end:
        raise BinaryFormatError("AXML UTF-8 byte length missing")
    byte_len = data[p]
    p += 1
    if byte_len & 0x80:
        if p >= end:
            raise BinaryFormatError("AXML UTF-8 byte length extension truncated")
        byte_len = ((byte_len & 0x7F) << 8) | data[p]
        p += 1
    if p + byte_len > end:
        raise BinaryFormatError("AXML UTF-8 string exceeds chunk bounds")
    return data[p : p + byte_len].decode("utf-8", errors="replace")


def _decode_axml_utf16(data: bytes, off: int, end: int) -> str:
    if off + 2 > end:
        raise BinaryFormatError("AXML UTF-16 string offset out of bounds")
    p = off
    char_len = struct.unpack_from("<H", data, p)[0]
    p += 2
    if char_len & 0x8000:
        if p + 2 > end:
            raise BinaryFormatError("AXML UTF-16 length extension truncated")
        lo = struct.unpack_from("<H", data, p)[0]
        p += 2
        char_len = ((char_len & 0x7FFF) << 16) | lo
    byte_len = char_len * 2
    if p + byte_len > end:
        raise BinaryFormatError("AXML UTF-16 string exceeds chunk bounds")
    return data[p : p + byte_len].decode("utf-16-le", errors="replace")


def _parse_axml_string_pool(data: bytes, off: int, chunk_size: int) -> List[str]:
    if chunk_size < 28 or off + chunk_size > len(data):
        raise BinaryFormatError("AXML string pool header truncated")
    header_size = struct.unpack_from("<H", data, off + 2)[0]
    string_count, _style_count, flags, strings_start, _styles_start = struct.unpack_from(
        "<IIIII", data, off + 8
    )
    if header_size < 28 or string_count > 1_000_000:
        raise BinaryFormatError("Invalid AXML string pool header")
    idx_base = off + header_size
    if idx_base + string_count * 4 > off + chunk_size:
        raise BinaryFormatError("AXML string pool index table exceeds chunk bounds")
    str_base = off + strings_start
    if str_base > off + chunk_size:
        raise BinaryFormatError("AXML string pool strings_start exceeds chunk bounds")
    chunk_end = off + chunk_size
    is_utf8 = bool(flags & 0x100)
    out: List[str] = []
    for i in range(string_count):
        rel = struct.unpack_from("<I", data, idx_base + i * 4)[0]
        s_off = str_base + rel
        if s_off < str_base or s_off >= chunk_end:
            raise BinaryFormatError("AXML string pool entry offset out of bounds")
        out.append(_decode_axml_utf8(data, s_off, chunk_end) if is_utf8 else _decode_axml_utf16(data, s_off, chunk_end))
    return out


def parse_binary_manifest(data: bytes, sdk_policy: Dict[str, Any]) -> Dict[str, Any]:
    """Parse compiled AndroidManifest.xml (AXML) without executing anything."""
    if len(data) < 8:
        raise BinaryFormatError("AndroidManifest.xml too short for AXML header")
    root_type, root_hdr_sz, total_size = struct.unpack_from("<HHI", data, 0)
    if root_type != RES_XML_TYPE or root_hdr_sz < 8 or total_size < root_hdr_sz or total_size > len(data):
        raise BinaryFormatError(
            f"Invalid binary AndroidManifest.xml header (type=0x{root_type:04x}, size={total_size}, len={len(data)})"
        )

    pool: List[str] = []
    resource_map: List[int] = []
    elements: List[Tuple[str, Dict[str, Any], List[str]]] = []
    stack: List[str] = []

    def pool_str(idx: int) -> str:
        if 0 <= idx < len(pool):
            return pool[idx]
        return ""

    off = root_hdr_sz
    while off < total_size:
        if off + 8 > total_size:
            raise BinaryFormatError("Truncated AXML chunk header")
        ctype, hdr_sz, csize = struct.unpack_from("<HHI", data, off)
        if csize < 8 or hdr_sz > csize or off + csize > total_size:
            raise BinaryFormatError(
                f"Corrupt AXML chunk at 0x{off:x} (type=0x{ctype:04x}, hdr={hdr_sz}, size={csize})"
            )
        if ctype == RES_STRING_POOL_TYPE:
            pool = _parse_axml_string_pool(data, off, csize)
        elif ctype == RES_XML_RESOURCE_MAP:
            n = (csize - hdr_sz) // 4
            resource_map = list(struct.unpack_from(f"<{n}I", data, off + hdr_sz))
        elif ctype == RES_XML_START_ELEMENT:
            ext = off + hdr_sz
            if ext + 20 > off + csize:
                raise BinaryFormatError("AXML START_ELEMENT ext truncated")
            name_idx = struct.unpack_from("<i", data, ext + 4)[0]
            attr_start, attr_size, attr_count = struct.unpack_from("<HHH", data, ext + 8)
            if attr_size < 20:
                raise BinaryFormatError(f"AXML invalid attributeSize={attr_size}")
            tag = pool_str(name_idx) or "unnamed"
            attrs_base = ext + attr_start
            if attrs_base + attr_count * attr_size > off + csize:
                raise BinaryFormatError("AXML attribute array exceeds START_ELEMENT bounds")
            attrs: Dict[str, Any] = {}
            for i in range(attr_count):
                a_off = attrs_base + i * attr_size
                _ns_idx, a_name_idx, raw_idx = struct.unpack_from("<iii", data, a_off)
                data_type = data[a_off + 15]
                val_u32 = struct.unpack_from("<I", data, a_off + 16)[0]
                val_i32 = struct.unpack_from("<i", data, a_off + 16)[0]
                attr_id = resource_map[a_name_idx] if 0 <= a_name_idx < len(resource_map) else 0
                aname = pool_str(a_name_idx)
                if not aname and attr_id:
                    aname = WELL_KNOWN_ANDROID_ATTRS.get(attr_id, "")
                if not aname:
                    aname = f"attr_0x{attr_id:08x}" if attr_id else f"attr_idx_{a_name_idx}"

                if data_type == TYPE_STRING:
                    val: Any = pool_str(raw_idx) if raw_idx >= 0 else pool_str(val_i32)
                elif data_type == TYPE_INT_BOOLEAN:
                    val = bool(val_u32 != 0)
                elif data_type == TYPE_INT_DEC:
                    val = val_i32
                elif data_type == TYPE_INT_HEX:
                    val = f"0x{val_u32:08x}"
                elif data_type == TYPE_REFERENCE:
                    val = f"@0x{val_u32:08x}"
                elif data_type == TYPE_ATTRIBUTE:
                    val = f"?0x{val_u32:08x}"
                elif data_type == TYPE_FLOAT:
                    val = struct.unpack_from("<f", data, a_off + 16)[0]
                elif data_type == TYPE_NULL:
                    val = None
                else:
                    val = val_i32
                attrs[aname] = val
            elements.append((tag, attrs, list(stack)))
            stack.append(tag)
        elif ctype == RES_XML_END_ELEMENT:
            if stack:
                stack.pop()
        off += csize

    if not elements or elements[0][0] != "manifest":
        raise BinaryFormatError("AndroidManifest.xml missing root <manifest> element")

    manifest_attrs = elements[0][1]
    package_name = str(manifest_attrs.get("package") or "")
    split_name = str(manifest_attrs.get("split") or "")
    version_code = manifest_attrs.get("versionCode")
    version_name = manifest_attrs.get("versionName")

    min_sdk: Optional[int] = None
    target_sdk: Optional[int] = None
    max_sdk: Optional[int] = None
    uses_features: List[Dict[str, Any]] = []
    uses_libraries: List[Dict[str, Any]] = []
    uses_permissions: List[str] = []
    application_info: Dict[str, Any] = {}
    components: Dict[str, List[Dict[str, Any]]] = {
        "activities": [],
        "activity_aliases": [],
        "services": [],
        "receivers": [],
        "providers": [],
    }

    def resolve_comp_name(raw_name: Any) -> str:
        s = str(raw_name or "")
        if s.startswith(".") and package_name:
            return package_name + s
        if s and "." not in s and package_name:
            return package_name + "." + s
        return s

    def parse_int_opt(v: Any) -> Optional[int]:
        if isinstance(v, bool):
            return None
        if isinstance(v, int):
            return v
        if isinstance(v, str):
            try:
                return int(v, 0)
            except ValueError:
                return None
        return None

    for tag, attrs, parents in elements:
        if tag == "uses-sdk" and parents == ["manifest"]:
            min_sdk = parse_int_opt(attrs.get("minSdkVersion"))
            target_sdk = parse_int_opt(attrs.get("targetSdkVersion"))
            max_sdk = parse_int_opt(attrs.get("maxSdkVersion"))
        elif tag == "uses-feature" and parents == ["manifest"]:
            uses_features.append({
                "name": attrs.get("name"),
                "gl_es_version": attrs.get("glEsVersion"),
                "required": True if attrs.get("required") is None else bool(attrs.get("required")),
            })
        elif tag == "uses-library" and parents == ["manifest", "application"]:
            uses_libraries.append({
                "name": str(attrs.get("name") or ""),
                "required": True if attrs.get("required") is None else bool(attrs.get("required")),
            })
        elif tag == "uses-permission" and parents == ["manifest"]:
            pname = str(attrs.get("name") or "")
            if pname:
                uses_permissions.append(pname)
        elif tag == "application" and parents == ["manifest"]:
            application_info = {
                "name": resolve_comp_name(attrs.get("name")) if attrs.get("name") else None,
                "extract_native_libs": attrs.get("extractNativeLibs"),
            }
        elif parents == ["manifest", "application"] and tag in (
            "activity",
            "activity-alias",
            "service",
            "receiver",
            "provider",
        ):
            bucket = {
                "activity": "activities",
                "activity-alias": "activity_aliases",
                "service": "services",
                "receiver": "receivers",
                "provider": "providers",
            }[tag]
            comp = {
                "name": resolve_comp_name(attrs.get("name")),
                "exported": attrs.get("exported"),
                "process": attrs.get("process"),
            }
            components[bucket].append(comp)

    floor_sdk = int(sdk_policy.get("floor_sdk", 29))
    ceiling_sdk = int(sdk_policy.get("ceiling_sdk", 36))
    default_sdk = int(sdk_policy.get("default_sdk", 29))
    if target_sdk is not None and sdk_policy.get("sdk_from_target_default", True):
        presented_sdk = max(floor_sdk, min(ceiling_sdk, target_sdk))
    else:
        presented_sdk = default_sdk

    uses_features.sort(key=lambda x: (str(x.get("name") or ""), str(x.get("gl_es_version") or "")))
    uses_libraries.sort(key=lambda x: x["name"])
    for k in components:
        components[k].sort(key=lambda x: (x["name"], str(x.get("process") or "")))

    return {
        "package": package_name,
        "split": split_name or None,
        "version_code": version_code,
        "version_name": version_name,
        "min_sdk": min_sdk,
        "target_sdk": target_sdk,
        "max_sdk": max_sdk,
        "presented_sdk": presented_sdk,
        "uses_features": uses_features,
        "uses_libraries": uses_libraries,
        "uses_permissions": sorted(set(uses_permissions)),
        "application": application_info,
        "components": components,
    }


# ---------------------------------------------------------------------------
# Pure-Python Multi-DEX Parser & Dalvik Bytecode Scanner
# ---------------------------------------------------------------------------

def _read_uleb128(data: bytes, pos: int) -> Tuple[int, int]:
    result = 0
    shift = 0
    for _ in range(5):
        if pos >= len(data):
            raise BinaryFormatError("Truncated ULEB128 in DEX")
        b = data[pos]
        pos += 1
        result |= (b & 0x7F) << shift
        if (b & 0x80) == 0:
            return result, pos
        shift += 7
    raise BinaryFormatError("Overlong ULEB128 in DEX")


def _decode_dex_mutf8(raw: bytes) -> str:
    if b"\xc0\x80" in raw:
        raw = raw.replace(b"\xc0\x80", b"\x00")
    return raw.decode("utf-8", errors="replace")


def dex_descriptor_to_internal(desc: str) -> str:
    if desc.startswith("L") and desc.endswith(";"):
        return desc[1:-1]
    return desc


# Dalvik opcode widths in 16-bit code units (AOSP dex-format / OpcodeInfo)
def _build_dalvik_widths() -> List[int]:
    w = [1] * 256
    for op in (0x02, 0x05, 0x08):
        w[op] = 2
    for op in (0x03, 0x06, 0x09):
        w[op] = 3
    for op in (0x13, 0x15, 0x16, 0x19, 0x1A, 0x1C, 0x1F, 0x20, 0x22, 0x23, 0x29):
        w[op] = 2
    for op in (0x14, 0x17, 0x1B, 0x24, 0x25, 0x26, 0x2A, 0x2B, 0x2C):
        w[op] = 3
    w[0x18] = 5  # const-wide
    for op in range(0x2D, 0x3E):
        w[op] = 2
    for op in range(0x44, 0x6E):
        w[op] = 2
    for op in range(0x6E, 0x73):
        w[op] = 3
    for op in range(0x74, 0x79):
        w[op] = 3
    for op in range(0x90, 0xB0):
        w[op] = 2
    for op in range(0xD0, 0xE3):
        w[op] = 2
    w[0xFA] = 4  # invoke-polymorphic
    w[0xFB] = 4  # invoke-polymorphic/range
    w[0xFC] = 3  # invoke-custom
    w[0xFD] = 3  # invoke-custom/range
    w[0xFE] = 2  # const-method-handle
    w[0xFF] = 2  # const-method-type
    return w


DALVIK_OP_WIDTHS = _build_dalvik_widths()

INVOKE_OPCODES: Set[int] = set(range(0x6E, 0x73)) | set(range(0x74, 0x79))
FIELD_OPCODES: Set[int] = set(range(0x52, 0x6E))
TYPE_OPCODES: Set[int] = {0x1C, 0x1F, 0x20, 0x22, 0x23, 0x24, 0x25}

REFLECTION_METHODS: Set[Tuple[str, str]] = {
    ("java/lang/Class", "forName"),
    ("java/lang/Class", "getMethod"),
    ("java/lang/Class", "getDeclaredMethod"),
    ("java/lang/Class", "getField"),
    ("java/lang/Class", "getDeclaredField"),
    ("java/lang/Class", "getConstructor"),
    ("java/lang/Class", "getDeclaredConstructor"),
    ("java/lang/ClassLoader", "loadClass"),
    ("java/lang/reflect/Method", "invoke"),
    ("java/lang/reflect/Field", "get"),
    ("java/lang/reflect/Field", "set"),
    ("java/lang/reflect/Proxy", "newProxyInstance"),
}

DYNAMIC_DEX_LOADERS: Set[str] = {
    "dalvik/system/DexClassLoader",
    "dalvik/system/InMemoryDexClassLoader",
    "dalvik/system/PathClassLoader",
    "dalvik/system/BaseDexClassLoader",
    "dalvik/system/DexFile",
}


def parse_dex_bytes(data: bytes, entry_name: str) -> Dict[str, Any]:
    """Parse one DEX container without executing any bytecode."""
    if len(data) < 0x70:
        raise BinaryFormatError(f"{entry_name}: DEX too short ({len(data)} bytes)")
    if data[:4] == b"cdex":
        raise BinaryFormatError(f"{entry_name}: CompactDex (cdex) is not supported")
    if data[:4] != b"dex\n" or data[7] != 0 or not all(48 <= b <= 57 for b in data[4:7]):
        raise BinaryFormatError(f"{entry_name}: invalid DEX magic")
    version = int(data[4:7].decode("ascii"))
    header_size = struct.unpack_from("<I", data, 0x24)[0]
    if header_size != 0x70:
        raise BinaryFormatError(
            f"{entry_name}: unsupported DEX header_size 0x{header_size:x} (version {version:03d})"
        )
    endian_tag = struct.unpack_from("<I", data, 0x28)[0]
    if endian_tag != 0x12345678:
        raise BinaryFormatError(f"{entry_name}: unsupported DEX endian_tag 0x{endian_tag:08x}")
    file_size = struct.unpack_from("<I", data, 0x20)[0]
    if file_size != len(data):
        raise BinaryFormatError(f"{entry_name}: DEX file_size {file_size} != actual {len(data)}")
    stored_adler = struct.unpack_from("<I", data, 0x08)[0]
    actual_adler = zlib.adler32(data[12:]) & 0xFFFFFFFF
    if stored_adler != actual_adler:
        raise BinaryFormatError(
            f"{entry_name}: DEX Adler32 checksum mismatch (stored=0x{stored_adler:08x}, actual=0x{actual_adler:08x})"
        )

    (
        string_ids_size,
        string_ids_off,
        type_ids_size,
        type_ids_off,
        proto_ids_size,
        proto_ids_off,
        field_ids_size,
        field_ids_off,
        method_ids_size,
        method_ids_off,
        class_defs_size,
        class_defs_off,
    ) = struct.unpack_from("<12I", data, 0x38)

    def check_table(off: int, cnt: int, item_sz: int, label: str) -> None:
        if cnt == 0:
            return
        if off < 0x70 or off + cnt * item_sz > len(data):
            raise BinaryFormatError(f"{entry_name}: DEX {label} table out of bounds")

    check_table(string_ids_off, string_ids_size, 4, "string_ids")
    check_table(type_ids_off, type_ids_size, 4, "type_ids")
    check_table(proto_ids_off, proto_ids_size, 12, "proto_ids")
    check_table(field_ids_off, field_ids_size, 8, "field_ids")
    check_table(method_ids_off, method_ids_size, 8, "method_ids")
    check_table(class_defs_off, class_defs_size, 32, "class_defs")

    # Decode strings
    strings: List[str] = []
    for i in range(string_ids_size):
        s_off = struct.unpack_from("<I", data, string_ids_off + i * 4)[0]
        if s_off >= len(data):
            raise BinaryFormatError(f"{entry_name}: string_data_off out of bounds")
        _utf16_len, p = _read_uleb128(data, s_off)
        nul = data.find(b"\x00", p)
        if nul < 0:
            raise BinaryFormatError(f"{entry_name}: unterminated DEX string")
        strings.append(_decode_dex_mutf8(data[p:nul]))

    def str_at(idx: int) -> str:
        if idx < 0 or idx >= len(strings):
            raise BinaryFormatError(f"{entry_name}: invalid string_idx {idx}")
        return strings[idx]

    # Decode type_ids
    type_descs: List[str] = []
    for i in range(type_ids_size):
        desc_idx = struct.unpack_from("<I", data, type_ids_off + i * 4)[0]
        type_descs.append(str_at(desc_idx))

    def type_desc_at(idx: int) -> str:
        if idx < 0 or idx >= len(type_descs):
            raise BinaryFormatError(f"{entry_name}: invalid type_idx {idx}")
        return type_descs[idx]

    def read_type_list(off: int) -> List[str]:
        if off == 0:
            return []
        if off + 4 > len(data):
            raise BinaryFormatError(f"{entry_name}: type_list out of bounds")
        cnt = struct.unpack_from("<I", data, off)[0]
        if off + 4 + cnt * 2 > len(data):
            raise BinaryFormatError(f"{entry_name}: type_list items out of bounds")
        return [
            dex_descriptor_to_internal(type_desc_at(tidx))
            for tidx in struct.unpack_from(f"<{cnt}H", data, off + 4)
        ]

    # Decode proto_ids -> JVM descriptors
    proto_descs: List[str] = []
    for i in range(proto_ids_size):
        _shorty_idx, ret_type_idx, params_off = struct.unpack_from(
            "<III", data, proto_ids_off + i * 12
        )
        ret_desc = type_desc_at(ret_type_idx)
        if params_off == 0:
            params_desc = ""
        else:
            if params_off + 4 > len(data):
                raise BinaryFormatError(f"{entry_name}: proto parameters out of bounds")
            cnt = struct.unpack_from("<I", data, params_off)[0]
            if params_off + 4 + cnt * 2 > len(data):
                raise BinaryFormatError(f"{entry_name}: proto parameters items out of bounds")
            tidxs = struct.unpack_from(f"<{cnt}H", data, params_off + 4)
            params_desc = "".join(type_desc_at(t) for t in tidxs)
        proto_descs.append(f"({params_desc}){ret_desc}")

    # Decode field_ids
    field_ids: List[Tuple[str, str, str]] = []
    for i in range(field_ids_size):
        cls_idx, type_idx, name_idx = struct.unpack_from("<HHI", data, field_ids_off + i * 8)
        field_ids.append((
            dex_descriptor_to_internal(type_desc_at(cls_idx)),
            str_at(name_idx),
            type_desc_at(type_idx),
        ))

    # Decode method_ids
    method_ids: List[Tuple[str, str, str]] = []
    for i in range(method_ids_size):
        cls_idx, proto_idx, name_idx = struct.unpack_from("<HHI", data, method_ids_off + i * 8)
        if proto_idx >= len(proto_descs):
            raise BinaryFormatError(f"{entry_name}: invalid proto_idx {proto_idx}")
        method_ids.append((
            dex_descriptor_to_internal(type_desc_at(cls_idx)),
            str_at(name_idx),
            proto_descs[proto_idx],
        ))

    # Decode class_defs & scan bytecode instructions
    classes: Dict[str, Dict[str, Any]] = {}
    method_call_sites: Dict[Tuple[str, str, str], int] = {}
    field_ref_sites: Dict[Tuple[str, str, str], int] = {}
    type_ref_sites: Dict[str, int] = {}
    reflection_hits: List[Dict[str, Any]] = []
    dynamic_dex_hits: List[Dict[str, Any]] = []
    native_lib_hints: Set[str] = set()
    service_hints: Set[str] = set()
    sysprop_hints: Set[str] = set()

    def scan_code(caller_cls: str, caller_meth: str, code_off: int) -> None:
        if code_off == 0:
            return
        if code_off + 16 > len(data):
            raise BinaryFormatError(f"{entry_name}: code_item header out of bounds")
        insns_size = struct.unpack_from("<I", data, code_off + 12)[0]
        insns_start = code_off + 16
        if insns_start + insns_size * 2 > len(data):
            raise BinaryFormatError(f"{entry_name}: code_item insns out of bounds")

        units = struct.unpack_from(f"<{insns_size}H", data, insns_start)
        recent_strings: List[str] = []
        idx = 0
        while idx < insns_size:
            u0 = units[idx]
            op = u0 & 0xFF
            if op == 0x00 and u0 != 0x0000:
                if u0 == 0x0100 and idx + 1 < insns_size:
                    sz = units[idx + 1]
                    idx += sz * 2 + 4
                    continue
                if u0 == 0x0200 and idx + 1 < insns_size:
                    sz = units[idx + 1]
                    idx += sz * 4 + 2
                    continue
                if u0 == 0x0300 and idx + 3 < insns_size:
                    ew = units[idx + 1]
                    sz = units[idx + 2] | (units[idx + 3] << 16)
                    idx += ((sz * ew + 1) // 2) + 4
                    continue

            width = DALVIK_OP_WIDTHS[op]
            if idx + width > insns_size:
                raise BinaryFormatError(f"{entry_name}: truncated Dalvik instruction at {caller_cls}.{caller_meth}")

            if op == 0x1A:  # const-string
                s = str_at(units[idx + 1])
                recent_strings = (recent_strings + [s])[-3:]
            elif op == 0x1B:  # const-string/jumbo
                s_idx = units[idx + 1] | (units[idx + 2] << 16)
                s = str_at(s_idx)
                recent_strings = (recent_strings + [s])[-3:]
            elif op in INVOKE_OPCODES:
                m_idx = units[idx + 1]
                if m_idx < len(method_ids):
                    mref = method_ids[m_idx]
                    method_call_sites[mref] = method_call_sites.get(mref, 0) + 1
                    owner, mname, _mdesc = mref
                    if owner in DYNAMIC_DEX_LOADERS:
                        dynamic_dex_hits.append({
                            "caller": f"{caller_cls}#{caller_meth}",
                            "target": f"{owner}#{mname}",
                            "string_hints": list(recent_strings),
                        })
                    elif (owner, mname) in REFLECTION_METHODS:
                        reflection_hits.append({
                            "caller": f"{caller_cls}#{caller_meth}",
                            "target": f"{owner}#{mname}",
                            "string_hints": list(recent_strings),
                        })
                    elif owner in ("java/lang/System", "java/lang/Runtime") and mname in ("loadLibrary", "load"):
                        for rs in recent_strings:
                            if rs:
                                if mname == "loadLibrary" and not rs.endswith(".so"):
                                    native_lib_hints.add(f"lib{rs}.so")
                                else:
                                    native_lib_hints.add(posixpath.basename(rs))
                    elif owner == "android/os/ServiceManager":
                        for rs in recent_strings:
                            if rs:
                                service_hints.add(rs)
                    elif owner == "android/os/SystemProperties" and mname.startswith("get"):
                        if recent_strings and recent_strings[0]:
                            sysprop_hints.add(recent_strings[-1])
            elif op in FIELD_OPCODES:
                f_idx = units[idx + 1]
                if f_idx < len(field_ids):
                    fref = field_ids[f_idx]
                    field_ref_sites[fref] = field_ref_sites.get(fref, 0) + 1
            elif op in TYPE_OPCODES:
                t_idx = units[idx + 1]
                if t_idx < len(type_descs):
                    t_int = dex_descriptor_to_internal(type_descs[t_idx])
                    type_ref_sites[t_int] = type_ref_sites.get(t_int, 0) + 1
                    if t_int in DYNAMIC_DEX_LOADERS and op == 0x22:
                        dynamic_dex_hits.append({
                            "caller": f"{caller_cls}#{caller_meth}",
                            "target": t_int,
                            "string_hints": list(recent_strings),
                        })

            idx += width

    for i in range(class_defs_size):
        base = class_defs_off + i * 32
        cls_idx, acc_flags, super_idx, ifaces_off, _src_idx, _ann_off, class_data_off, _sv_off = (
            struct.unpack_from("<IIIIIIII", data, base)
        )
        cls_name = dex_descriptor_to_internal(type_desc_at(cls_idx))
        super_name = (
            None
            if super_idx == 0xFFFFFFFF
            else dex_descriptor_to_internal(type_desc_at(super_idx))
        )
        interfaces = read_type_list(ifaces_off)

        fields_out: List[Dict[str, Any]] = []
        methods_out: List[Dict[str, Any]] = []
        if class_data_off != 0:
            p = class_data_off
            static_fields_cnt, p = _read_uleb128(data, p)
            inst_fields_cnt, p = _read_uleb128(data, p)
            direct_meth_cnt, p = _read_uleb128(data, p)
            virt_meth_cnt, p = _read_uleb128(data, p)

            for cnt in (static_fields_cnt, inst_fields_cnt):
                f_idx = 0
                for _ in range(cnt):
                    delta, p = _read_uleb128(data, p)
                    f_acc, p = _read_uleb128(data, p)
                    f_idx += delta
                    if f_idx >= len(field_ids):
                        raise BinaryFormatError(f"{entry_name}: field_idx out of range")
                    _fcls, fname, fdesc = field_ids[f_idx]
                    fields_out.append({
                        "name": fname,
                        "descriptor": fdesc,
                        "access": f_acc,
                        "flags": f"0x{f_acc:04x}",
                    })

            for cnt in (direct_meth_cnt, virt_meth_cnt):
                m_idx = 0
                for _ in range(cnt):
                    delta, p = _read_uleb128(data, p)
                    m_acc, p = _read_uleb128(data, p)
                    code_off, p = _read_uleb128(data, p)
                    m_idx += delta
                    if m_idx >= len(method_ids):
                        raise BinaryFormatError(f"{entry_name}: method_idx out of range")
                    _mcls, mname, mdesc = method_ids[m_idx]
                    methods_out.append({
                        "name": mname,
                        "descriptor": mdesc,
                        "access": m_acc,
                        "flags": f"0x{m_acc:04x}",
                    })
                    scan_code(cls_name, f"{mname}{mdesc}", code_off)

        if acc_flags & DEX_ACCESS_ANNOTATION:
            kind = "annotation"
        elif acc_flags & DEX_ACCESS_INTERFACE:
            kind = "interface"
        elif acc_flags & DEX_ACCESS_ENUM:
            kind = "enum"
        else:
            kind = "class"

        classes[cls_name] = {
            "kind": kind,
            "is_interface": bool(acc_flags & DEX_ACCESS_INTERFACE),
            "access": acc_flags,
            "flags": f"0x{acc_flags:04x}",
            "super": super_name or "",
            "interfaces": interfaces,
            "fields": sorted(fields_out, key=lambda x: (x["name"], x["descriptor"])),
            "methods": sorted(methods_out, key=lambda x: (x["name"], x["descriptor"])),
            "source": entry_name,
        }

    # Reflection-like string hints across DEX string pool (hint only)
    string_hints: List[str] = []
    for s in strings:
        if re.match(r"^(android|com\.android|dalvik|com\.macapk)\.[A-Za-z0-9_.$]+$", s):
            string_hints.append(s)

    return {
        "entry": entry_name,
        "version": version,
        "classes": classes,
        "type_descs": [dex_descriptor_to_internal(d) for d in type_descs],
        "field_ids": field_ids,
        "method_ids": method_ids,
        "method_call_sites": method_call_sites,
        "field_ref_sites": field_ref_sites,
        "type_ref_sites": type_ref_sites,
        "reflection_hits": reflection_hits,
        "dynamic_dex_hits": dynamic_dex_hits,
        "native_lib_hints": sorted(native_lib_hints),
        "service_hints": sorted(service_hints),
        "sysprop_hints": sorted(sysprop_hints),
        "reflection_string_hints": sorted(set(string_hints)),
    }


# ---------------------------------------------------------------------------
# Pure-Python ELF64 & ELF32 Static Analyzer
# ---------------------------------------------------------------------------

def _read_cstr(buf: bytes, off: int) -> str:
    if off < 0 or off >= len(buf):
        return ""
    nul = buf.find(b"\x00", off)
    raw = buf[off:] if nul < 0 else buf[off:nul]
    return raw.decode("utf-8", errors="replace")


def parse_elf_bytes(data: bytes, archive_path: str, abi: str) -> Dict[str, Any]:
    """Parse ELF64 or ELF32 LE shared library statically without executing initializers."""
    if len(data) < 52 or data[:4] != b"\x7fELF":
        raise BinaryFormatError(f"{archive_path}: invalid ELF magic")
    ei_class = data[4]
    ei_data = data[5]
    if ei_class not in (ELFCLASS32, ELFCLASS64) or ei_data != ELFDATA2LSB:
        raise BinaryFormatError(
            f"{archive_path}: unsupported ELF class/endian (class={ei_class}, data={ei_data})"
        )

    is64 = ei_class == ELFCLASS64
    min_ehdr = 64 if is64 else 52
    if len(data) < min_ehdr:
        raise BinaryFormatError(f"{archive_path}: truncated ELF header")

    if is64:
        e_type, e_machine, _e_ver, _e_entry, e_phoff, e_shoff, _e_flags, _ehsz, e_phentsize, e_phnum, e_shentsize, e_shnum, e_shstrndx = (
            struct.unpack_from("<HHIQQQIHHHHHH", data, 16)
        )
        ph_min, sh_min, sym_sz, dyn_sz = 56, 64, 24, 16
    else:
        e_type, e_machine, _e_ver, _e_entry, e_phoff, e_shoff, _e_flags, _ehsz, e_phentsize, e_phnum, e_shentsize, e_shnum, e_shstrndx = (
            struct.unpack_from("<HHIIIIIHHHHHH", data, 16)
        )
        ph_min, sh_min, sym_sz, dyn_sz = 32, 40, 16, 8

    if e_phnum > 0:
        if e_phentsize < ph_min or e_phoff + e_phnum * e_phentsize > len(data):
            raise BinaryFormatError(f"{archive_path}: ELF program headers out of bounds")
    if e_shnum > 0:
        if e_shentsize < sh_min or e_shoff + e_shnum * e_shentsize > len(data):
            raise BinaryFormatError(f"{archive_path}: ELF section headers out of bounds")

    # Program headers -> PT_LOAD vaddr map and PT_DYNAMIC
    pt_loads: List[Tuple[int, int, int]] = []  # (vaddr, filesz, offset)
    dyn_off = -1
    dyn_filesz = 0
    for i in range(e_phnum):
        p = e_phoff + i * e_phentsize
        if is64:
            p_type, _p_flags, p_offset, p_vaddr, _p_paddr, p_filesz, _p_memsz, _p_align = (
                struct.unpack_from("<IIQQQQQQ", data, p)
            )
        else:
            p_type, p_offset, p_vaddr, _p_paddr, p_filesz, _p_memsz, _p_flags, _p_align = (
                struct.unpack_from("<IIIIIIII", data, p)
            )
        if p_offset + p_filesz > len(data):
            raise BinaryFormatError(f"{archive_path}: ELF PT segment #{i} out of bounds")
        if p_type == PT_LOAD:
            pt_loads.append((p_vaddr, p_filesz, p_offset))
        elif p_type == PT_DYNAMIC:
            dyn_off = p_offset
            dyn_filesz = p_filesz

    def vaddr_to_offset(vaddr: int) -> Optional[int]:
        for seg_v, seg_fsz, seg_off in pt_loads:
            if seg_v <= vaddr < seg_v + seg_fsz:
                return seg_off + (vaddr - seg_v)
        if 0 <= vaddr < len(data):
            return vaddr
        return None

    # Section headers (if present)
    shdrs: List[Tuple[int, int, int, int, int, int, int, int]] = []
    shstrtab = b""
    for i in range(e_shnum):
        p = e_shoff + i * e_shentsize
        if is64:
            sh_name, sh_type, _sh_flags, _sh_addr, sh_off, sh_size, sh_link, sh_info, _sh_align, sh_entsize = (
                struct.unpack_from("<IIQQQQIIQQ", data, p)
            )
        else:
            sh_name, sh_type, _sh_flags, _sh_addr, sh_off, sh_size, sh_link, sh_info, _sh_align, sh_entsize = (
                struct.unpack_from("<IIIIIIIIII", data, p)
            )
        if sh_type != 8 and sh_size > 0 and sh_off + sh_size > len(data):
            raise BinaryFormatError(f"{archive_path}: ELF section #{i} exceeds file bounds")
        shdrs.append((sh_name, sh_type, sh_off, sh_size, sh_link, sh_info, sh_entsize, _sh_addr))
    if 0 <= e_shstrndx < len(shdrs):
        _, _, ss_off, ss_sz, _, _, _, _ = shdrs[e_shstrndx]
        shstrtab = data[ss_off : ss_off + ss_sz]

    # Parse PT_DYNAMIC
    dt_needed_offs: List[int] = []
    dt_soname_off: Optional[int] = None
    dt_strtab_v: Optional[int] = None
    dt_strsz: Optional[int] = None
    dt_symtab_v: Optional[int] = None
    dt_syment: int = sym_sz
    dt_hash_v: Optional[int] = None
    dt_gnu_hash_v: Optional[int] = None
    dt_versym_v: Optional[int] = None
    dt_verneed_v: Optional[int] = None
    dt_verneednum: int = 0
    has_initializers = False

    if dyn_off >= 0:
        if dyn_off + dyn_filesz > len(data):
            raise BinaryFormatError(f"{archive_path}: PT_DYNAMIC exceeds file bounds")
        fmt = "<qQ" if is64 else "<iI"
        for pos in range(dyn_off, dyn_off + dyn_filesz - dyn_sz + 1, dyn_sz):
            tag, val = struct.unpack_from(fmt, data, pos)
            if tag == DT_NULL:
                break
            if tag == DT_NEEDED:
                dt_needed_offs.append(val)
            elif tag == DT_SONAME:
                dt_soname_off = val
            elif tag == DT_STRTAB:
                dt_strtab_v = val
            elif tag == DT_STRSZ:
                dt_strsz = val
            elif tag == DT_SYMTAB:
                dt_symtab_v = val
            elif tag == DT_SYMENT and val > 0:
                dt_syment = val
            elif tag == DT_HASH:
                dt_hash_v = val
            elif tag == DT_GNU_HASH:
                dt_gnu_hash_v = val
            elif tag == DT_VERSYM:
                dt_versym_v = val
            elif tag == DT_VERNEED:
                dt_verneed_v = val
            elif tag == DT_VERNEEDNUM:
                dt_verneednum = val
            elif tag in (DT_INIT, DT_INIT_ARRAY) and val != 0:
                has_initializers = True

    # Locate .dynsym, .dynstr, .gnu.version, .gnu.version_r (prefer SHT_DYNSYM, fallback to PT_DYNAMIC)
    dynsym_off: Optional[int] = None
    dynsym_size: int = 0
    dynsym_entsz: int = dt_syment
    dynstr_bytes: bytes = b""
    versym_off: Optional[int] = None
    versym_size: int = 0
    verneed_off: Optional[int] = None
    verneed_num: int = dt_verneednum
    rodata_bytes: bytes = b""

    for sh_name, sh_type, sh_off, sh_size, sh_link, sh_info, sh_entsize, _ in shdrs:
        sname = _read_cstr(shstrtab, sh_name) if shstrtab else ""
        if sh_type == SHT_DYNSYM:
            dynsym_off = sh_off
            dynsym_size = sh_size
            if sh_entsize > 0:
                dynsym_entsz = sh_entsize
            if 0 <= sh_link < len(shdrs):
                ls = shdrs[sh_link]
                dynstr_bytes = data[ls[2] : ls[2] + ls[3]]
        elif sh_type == SHT_GNU_VERSYM:
            versym_off = sh_off
            versym_size = sh_size
        elif sh_type == SHT_GNU_VERNEED:
            verneed_off = sh_off
            if sh_info > 0:
                verneed_num = sh_info
        elif sname == ".rodata":
            rodata_bytes = data[sh_off : sh_off + sh_size]

    if not dynstr_bytes and dt_strtab_v is not None and dt_strsz:
        st_off = vaddr_to_offset(dt_strtab_v)
        if st_off is None or st_off + dt_strsz > len(data):
            raise BinaryFormatError(f"{archive_path}: DT_STRTAB out of bounds")
        dynstr_bytes = data[st_off : st_off + dt_strsz]

    if dynsym_off is None and dt_symtab_v is not None:
        sym_off = vaddr_to_offset(dt_symtab_v)
        if sym_off is None:
            raise BinaryFormatError(f"{archive_path}: DT_SYMTAB unmapped")
        sym_count = 0
        if dt_hash_v is not None:
            h_off = vaddr_to_offset(dt_hash_v)
            if h_off is not None and h_off + 8 <= len(data):
                _nbucket, nchain = struct.unpack_from("<II", data, h_off)
                sym_count = nchain
        if sym_count == 0 and dt_gnu_hash_v is not None:
            gh_off = vaddr_to_offset(dt_gnu_hash_v)
            if gh_off is not None and gh_off + 16 <= len(data):
                nbuckets, symoffset, bloom_size, _bloom_shift = struct.unpack_from("<IIII", data, gh_off)
                bloom_word = 8 if is64 else 4
                buckets_off = gh_off + 16 + bloom_size * bloom_word
                if buckets_off + nbuckets * 4 <= len(data):
                    buckets = struct.unpack_from(f"<{nbuckets}I", data, buckets_off)
                    max_idx = max([b for b in buckets if b >= symoffset] + [0])
                    if max_idx >= symoffset:
                        chains_off = buckets_off + nbuckets * 4
                        cur = max_idx
                        while True:
                            c_pos = chains_off + (cur - symoffset) * 4
                            if c_pos + 4 > len(data):
                                break
                            val = struct.unpack_from("<I", data, c_pos)[0]
                            cur += 1
                            if val & 1:
                                break
                        sym_count = cur
                    else:
                        sym_count = symoffset
        if sym_count > 0:
            dynsym_off = sym_off
            dynsym_size = sym_count * dynsym_entsz
            if sym_off + dynsym_size > len(data):
                raise BinaryFormatError(f"{archive_path}: stripped .dynsym exceeds file bounds")

    if versym_off is None and dt_versym_v is not None and dynsym_size > 0:
        vo = vaddr_to_offset(dt_versym_v)
        if vo is not None:
            n_syms = dynsym_size // max(1, dynsym_entsz)
            if vo + n_syms * 2 <= len(data):
                versym_off = vo
                versym_size = n_syms * 2

    if verneed_off is None and dt_verneed_v is not None and verneed_num > 0:
        vno = vaddr_to_offset(dt_verneed_v)
        if vno is not None:
            verneed_off = vno

    # Parse .gnu.version_r -> {ver_index: (library_soname, version_name)}
    ver_map: Dict[int, Tuple[str, str]] = {}
    if verneed_off is not None and verneed_num > 0 and dynstr_bytes:
        cur = verneed_off
        for _ in range(verneed_num):
            if cur + 16 > len(data):
                break
            _vn_ver, vn_cnt, vn_file, vn_aux, vn_next = struct.unpack_from("<HHIII", data, cur)
            lib_name = _read_cstr(dynstr_bytes, vn_file)
            aux_cur = cur + vn_aux
            for _j in range(vn_cnt):
                if aux_cur + 16 > len(data):
                    break
                _vna_hash, _vna_flags, vna_other, vna_name, vna_next = struct.unpack_from(
                    "<IHHII", data, aux_cur
                )
                ver_name = _read_cstr(dynstr_bytes, vna_name)
                ver_map[vna_other & 0x7FFF] = (lib_name, ver_name)
                if vna_next == 0:
                    break
                aux_cur += vna_next
            if vn_next == 0:
                break
            cur += vn_next

    dt_needed = [_read_cstr(dynstr_bytes, o) for o in dt_needed_offs if _read_cstr(dynstr_bytes, o)]
    dt_soname = _read_cstr(dynstr_bytes, dt_soname_off) if dt_soname_off is not None else ""

    versym_vals: List[int] = []
    if versym_off is not None and versym_size >= 2:
        cnt = versym_size // 2
        versym_vals = list(struct.unpack_from(f"<{cnt}H", data, versym_off))

    exports: List[Dict[str, Any]] = []
    imports: List[Dict[str, Any]] = []

    if dynsym_off is not None and dynsym_size > 0 and dynsym_entsz >= sym_sz:
        n_syms = dynsym_size // dynsym_entsz
        for idx in range(n_syms):
            s_pos = dynsym_off + idx * dynsym_entsz
            if is64:
                st_name, st_info, _st_other, st_shndx, st_value, st_size = struct.unpack_from(
                    "<IBBHQQ", data, s_pos
                )
            else:
                st_name, st_value, st_size, st_info, _st_other, st_shndx = struct.unpack_from(
                    "<IIIBBH", data, s_pos
                )
            name = _read_cstr(dynstr_bytes, st_name)
            if not name:
                continue
            bind = st_info >> 4
            stype = st_info & 0xF
            ver_entry: Optional[Dict[str, str]] = None
            if idx < len(versym_vals):
                vidx = versym_vals[idx] & 0x7FFF
                if vidx >= 2 and vidx in ver_map:
                    vlib, vstr = ver_map[vidx]
                    ver_entry = {"library": vlib, "version": vstr}
            sym_rec: Dict[str, Any] = {
                "name": name,
                "bind": BIND_NAMES.get(bind, f"BIND_{bind}"),
                "is_weak": bind == STB_WEAK,
                "type": TYPE_NAMES.get(stype, f"TYPE_{stype}"),
                "shndx": st_shndx,
            }
            if ver_entry:
                sym_rec["gnu_version"] = ver_entry
            if st_shndx == SHN_UNDEF:
                imports.append(sym_rec)
            else:
                sym_rec["vaddr"] = f"0x{st_value:x}"
                sym_rec["size"] = st_size
                exports.append(sym_rec)

    exports.sort(key=lambda x: x["name"])
    imports.sort(key=lambda x: (x["name"], x["bind"]))

    # Static string hints in .dynstr / .rodata when dynamic loading is suspected
    import_names = {imp["name"] for imp in imports}
    export_names = {exp["name"] for exp in exports}
    has_dlsym = any(s in import_names for s in ("dlsym", "dlopen", "android_dlopen_ext"))
    has_jni_onload = "JNI_OnLoad" in export_names
    has_register_natives_ref = b"RegisterNatives" in dynstr_bytes or b"RegisterNatives" in rodata_bytes

    rodata_hints: List[str] = []
    if has_dlsym or has_jni_onload or has_register_natives_ref:
        raw_strs = re.findall(rb"[A-Za-z0-9_./-]{4,120}", rodata_bytes[: 2 * 1024 * 1024])
        for bstr in raw_strs:
            s = bstr.decode("ascii", errors="ignore")
            if s.endswith(".so") or s.startswith("Java_") or "/" in s:
                rodata_hints.append(s)
        rodata_hints = sorted(set(rodata_hints))[:50]

    expected_machine = ABI_TO_EMACHINE.get(abi)
    abi_machine_match = (expected_machine is None) or (e_machine == expected_machine)

    return {
        "archive_path": archive_path,
        "abi": abi,
        "elf_class": "ELF64" if is64 else "ELF32",
        "e_type": e_type,
        "e_machine": EMACHINE_NAMES.get(e_machine, f"EM_{e_machine}"),
        "abi_machine_match": abi_machine_match,
        "section_headers_present": e_shnum > 0,
        "soname": dt_soname or posixpath.basename(archive_path),
        "dt_needed": dt_needed,
        "has_initializers": has_initializers,
        "initializers_executed": False,
        "exports": exports,
        "imports": imports,
        "has_dlsym_or_dlopen": has_dlsym,
        "has_jni_onload": has_jni_onload,
        "has_register_natives_hint": has_register_natives_ref,
        "rodata_dynamic_hints": rodata_hints,
    }


# ---------------------------------------------------------------------------
# Host JDK jmod Indexer (Optional, Offline, Read-Only)
# ---------------------------------------------------------------------------

def discover_jdk_home(repo_root: pathlib.Path, explicit_jdk: Optional[str]) -> Optional[pathlib.Path]:
    if explicit_jdk:
        p = pathlib.Path(explicit_jdk).resolve()
        return p if (p / "jmods").is_dir() else None
    env_home = os.environ.get("JAVA_HOME")
    if env_home:
        p = pathlib.Path(env_home)
        if (p / "jmods").is_dir():
            return p
    select_sh = repo_root / "debug" / "jdk25_select.sh"
    if select_sh.is_file():
        try:
            proc = subprocess.run(
                ["bash", "-c", f". {select_sh} && macapk_jdk_home"],
                capture_output=True,
                text=True,
                timeout=5,
                check=False,
            )
            if proc.returncode == 0 and proc.stdout.strip():
                p = pathlib.Path(proc.stdout.strip())
                if (p / "jmods").is_dir():
                    return p
        except (OSError, subprocess.SubprocessError):
            pass
    for cand in (
        "/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home",
        "/usr/local/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home",
    ):
        p = pathlib.Path(cand)
        if (p / "jmods").is_dir():
            return p
    return None


def load_host_jdk_classes(
    jdk_home: Optional[pathlib.Path],
    inventory_classes: Dict[str, Dict[str, Any]],
) -> Tuple[Dict[str, Dict[str, Any]], Optional[str]]:
    if jdk_home is None or not (jdk_home / "jmods").is_dir():
        return {}, None
    # Index standard Java SE modules used by Android/Mac.apk runtime
    jmod_names = (
        "java.base.jmod",
        "java.logging.jmod",
        "java.xml.jmod",
        "java.sql.jmod",
        "java.desktop.jmod",
        "java.net.http.jmod",
        "java.management.jmod",
        "java.naming.jmod",
        "java.security.jgss.jmod",
    )
    jdk_classes: Dict[str, Dict[str, Any]] = {}
    for jname in jmod_names:
        jpath = jdk_home / "jmods" / jname
        if not jpath.is_file():
            continue
        raw = jpath.read_bytes()
        if len(raw) < 4 or raw[:2] != b"JM":
            continue
        with zipfile.ZipFile(io.BytesIO(raw[4:]), "r") as zf:
            for entry in zf.namelist():
                if not entry.startswith("classes/") or not entry.endswith(".class"):
                    continue
                internal = entry[len("classes/") : -6]
                if internal in inventory_classes or internal in jdk_classes:
                    continue
                parsed = cap_registry.parse_classfile(zf.read(entry))
                if parsed is not None:
                    parsed["source"] = f"host_jdk:{jname}"
                    jdk_classes[internal] = parsed
    return jdk_classes, str(jdk_home)


# ---------------------------------------------------------------------------
# Capability Matcher & Resolution Engine
# ---------------------------------------------------------------------------

class CapabilityMatcher:
    def __init__(self, cap_data: Dict[str, Any], inventory: Dict[str, Any]) -> None:
        self.cap_data = cap_data
        self.inventory = inventory
        self.by_id: Dict[str, Dict[str, Any]] = {
            c["id"]: c for c in cap_data.get("capabilities", [])
        }
        self.class_to_cap: Dict[str, str] = {}
        self.method_to_cap: Dict[Tuple[str, str, str], str] = {}
        self.bionic_to_cap: Dict[str, str] = {}
        self.jni_to_cap: Dict[Tuple[str, str, str], str] = {}

        for c in cap_data.get("capabilities", []):
            cid = c["id"]
            for ref in c.get("inventory_refs", []):
                if ref.startswith("class:"):
                    self.class_to_cap.setdefault(ref[6:], cid)
                elif ref.startswith("method:") or ref.startswith("jni:"):
                    spec = ref.split(":", 1)[1]
                    if "#" in spec and "(" in spec:
                        cls, msig = spec.split("#", 1)
                        mname, mdesc = msig.split("(", 1)
                        target = self.method_to_cap if ref.startswith("method:") else self.jni_to_cap
                        target.setdefault((cls, mname, "(" + mdesc), cid)
                elif ref.startswith("bionic:") or ref.startswith("bionic_direct:") or ref.startswith("adapter:"):
                    sym = ref.split(":", 1)[1]
                    self.bionic_to_cap.setdefault(sym, cid)

    def match_java(self, cls_name: str, mname: Optional[str] = None, mdesc: Optional[str] = None) -> Optional[Dict[str, Any]]:
        cid = None
        if mname is not None and mdesc is not None:
            cid = self.method_to_cap.get((cls_name, mname, mdesc))
        if cid is None:
            cid = self.class_to_cap.get(cls_name)
        if cid is None:
            return None
        c = self.by_id[cid]
        return {
            "capability_id": cid,
            "support_status": c["support_status"],
            "verification_status": c["verification_status"],
            "surface": c["surface"],
        }

    def match_native(self, sym: str) -> Optional[Dict[str, Any]]:
        cid = self.bionic_to_cap.get(sym)
        if cid is None and sym.startswith("ANativeWindow_"):
            cid = "NAT-005"
        elif cid is None and sym.startswith("AAsset"):
            cid = "RES-002"
        elif cid is None and sym.startswith("AMedia"):
            cid = "MED-001"
        if cid is None or cid not in self.by_id:
            return None
        c = self.by_id[cid]
        return {
            "capability_id": cid,
            "support_status": c["support_status"],
            "verification_status": c["verification_status"],
            "surface": c["surface"],
        }


def classify_class_origin(
    cls_name: str,
    app_classes: Dict[str, Dict[str, Any]],
    inv_classes: Dict[str, Dict[str, Any]],
    jdk_classes: Dict[str, Dict[str, Any]],
) -> str:
    if cls_name in app_classes:
        if cls_name.startswith(ANDROIDX_PREFIXES):
            return "androidx_bundled"
        return "app_owned"
    if cls_name.startswith(ANDROIDX_PREFIXES):
        return "public_sdk" if cls_name in inv_classes else "androidx_missing"
    if cls_name in inv_classes:
        src = inv_classes[cls_name].get("source", "")
        if cls_name in ("android/os/ServiceManager", "android/os/SystemProperties"):
            return "service_property"
        if cls_name.startswith("com/macapk/") or cls_name == "android/app/ActivityThread":
            return "hidden_api"
        if src in cap_registry.PATCH_DIRS_IN_ORDER:
            return "jdk_patched"
        return "public_sdk"
    if cls_name in jdk_classes:
        return "host_jdk"
    if cls_name.startswith(JDK_PACKAGE_PREFIXES):
        return "host_jdk_unindexed"
    if cls_name.startswith(PLATFORM_ANDROID_PREFIXES):
        return "platform_missing"
    return "unknown_external"


def resolve_member_in_hierarchy(
    owner: str,
    member_name: str,
    member_desc: str,
    is_method: bool,
    app_classes: Dict[str, Dict[str, Any]],
    inv_classes: Dict[str, Dict[str, Any]],
    jdk_classes: Dict[str, Dict[str, Any]],
) -> Dict[str, Any]:
    """Walk owner -> superclasses -> interfaces (app_classes -> inv_classes -> jdk_classes)."""
    lookup_owner = "java/lang/Object" if owner.startswith("[") else owner
    if lookup_owner in ("Z", "B", "S", "C", "I", "J", "F", "D", "V"):
        return {"found": False, "status": "unknown", "reason": "primitive_owner"}

    def get_cls(name: str) -> Optional[Tuple[Dict[str, Any], str]]:
        if name in app_classes:
            return app_classes[name], "app"
        if name in inv_classes:
            return inv_classes[name], "inventory"
        if name in jdk_classes:
            return jdk_classes[name], "host_jdk"
        return None

    owner_entry = get_cls(lookup_owner)
    if owner_entry is None:
        origin = classify_class_origin(lookup_owner, app_classes, inv_classes, jdk_classes)
        if origin == "host_jdk_unindexed":
            return {"found": False, "status": "unknown", "origin": origin, "reason": "host_jdk_unindexed"}
        if origin in ("platform_missing", "androidx_missing"):
            return {"found": False, "status": "missing", "origin": origin, "reason": "missing_class"}
        return {"found": False, "status": "unknown", "origin": origin, "reason": "unknown_external_class"}

    member_key = "methods" if is_method else "fields"
    visited: Set[str] = set()
    unresolved_ancestors: List[str] = []
    other_overloads: Set[str] = set()

    def scan_cls(cname: str, cls_dict: Dict[str, Any], layer: str, is_self: bool) -> Optional[Dict[str, Any]]:
        for m in cls_dict.get(member_key, []):
            if not is_self and is_method and m["name"] in ("<init>", "<clinit>"):
                continue
            if not is_self and (m.get("access", 0) & DEX_ACCESS_PRIVATE):
                continue
            if m["name"] == member_name:
                if m["descriptor"] == member_desc:
                    return {
                        "found": True,
                        "declared_in": cname,
                        "layer": layer,
                        "inherited": not is_self,
                        "origin": classify_class_origin(cname, app_classes, inv_classes, jdk_classes),
                    }
                other_overloads.add(f"{cname}#{m['name']}{m['descriptor']}")
        return None

    # 1. Self + superclass chain
    iface_queue: List[str] = []
    cur: Optional[str] = lookup_owner
    is_self = True
    while cur and cur not in visited:
        visited.add(cur)
        entry = get_cls(cur)
        if entry is None:
            unresolved_ancestors.append(cur)
            break
        cls_dict, layer = entry
        hit = scan_cls(cur, cls_dict, layer, is_self)
        if hit is not None:
            return hit
        iface_queue.extend(cls_dict.get("interfaces", []))
        sup = cls_dict.get("super") or ""
        cur = sup if sup else None
        is_self = False

    # 2. Interface hierarchy
    q_idx = 0
    while q_idx < len(iface_queue):
        iname = iface_queue[q_idx]
        q_idx += 1
        if not iname or iname in visited:
            continue
        visited.add(iname)
        entry = get_cls(iname)
        if entry is None:
            unresolved_ancestors.append(iname)
            continue
        cls_dict, layer = entry
        hit = scan_cls(iname, cls_dict, layer, False)
        if hit is not None:
            return hit
        iface_queue.extend(cls_dict.get("interfaces", []))

    origin = classify_class_origin(lookup_owner, app_classes, inv_classes, jdk_classes)
    # If an ancestor is missing only because host JDK wasn't indexed and no platform ancestor failed, mark unknown
    if unresolved_ancestors:
        if all(a.startswith(JDK_PACKAGE_PREFIXES) for a in unresolved_ancestors) and not jdk_classes:
            return {
                "found": False,
                "status": "unknown",
                "origin": origin,
                "unresolved_ancestors": sorted(unresolved_ancestors),
                "reason": "host_jdk_ancestor_unindexed",
            }
        # If the owner itself is an app class whose missing ancestor is external/unknown
        if any(not a.startswith(PLATFORM_ANDROID_PREFIXES) for a in unresolved_ancestors):
            return {
                "found": False,
                "status": "unknown",
                "origin": origin,
                "unresolved_ancestors": sorted(unresolved_ancestors),
                "reason": "unresolved_external_ancestor",
            }

    return {
        "found": False,
        "status": "missing",
        "origin": origin,
        "overload_mismatch": bool(other_overloads),
        "available_overloads": sorted(other_overloads)[:10],
        "reason": "overload_descriptor_mismatch" if other_overloads else "member_not_found",
    }


def mangle_jni_name(cls_internal: str, meth_name: str, desc: Optional[str] = None) -> str:
    def esc(s: str) -> str:
        out: List[str] = []
        for ch in s:
            if ch == "_":
                out.append("_1")
            elif ch == ";":
                out.append("_2")
            elif ch == "[":
                out.append("_3")
            elif ch == "/":
                out.append("_")
            elif ch.isalnum():
                out.append(ch)
            else:
                out.append(f"_0{ord(ch):04x}")
        return "".join(out)

    base = f"Java_{esc(cls_internal)}_{esc(meth_name)}"
    if desc is not None and desc.startswith("("):
        args = desc[1 : desc.index(")")]
        return f"{base}__{esc(args)}"
    return base


# ---------------------------------------------------------------------------
# Native Dependency & Symbol Resolution
# ---------------------------------------------------------------------------

def analyze_native_libs(
    elf_records: List[Dict[str, Any]],
    inventory: Dict[str, Any],
    matcher: CapabilityMatcher,
) -> Dict[str, Any]:
    be = inventory.get("bionic_exports", {})
    arm64_shim = {x["bionic_symbol"] for x in be.get("arm64_shim", [])}
    direct_c_ndk = set(be.get("direct_c_ndk", []))
    adapters = set(be.get("adapters_in_arm64_native", []))
    fmod_stubs = {x["target_symbol"] for x in be.get("fmod_stub", [])}
    per_image = {
        tag: {x["bionic_symbol"] for x in items}
        for tag, items in be.get("arm64_per_image", {}).items()
    }

    by_abi: Dict[str, List[Dict[str, Any]]] = {}
    for rec in elf_records:
        by_abi.setdefault(rec["abi"], []).append(rec)

    abi_reports: Dict[str, Any] = {}
    native_gaps: List[Dict[str, Any]] = []
    blind_spots: List[Dict[str, Any]] = []

    for abi in sorted(by_abi):
        libs = sorted(by_abi[abi], key=lambda x: x["archive_path"])
        lib_by_key: Dict[str, Dict[str, Any]] = {}
        exports_by_lib: Dict[str, Set[str]] = {}
        for lib in libs:
            bname = posixpath.basename(lib["archive_path"])
            soname = lib["soname"]
            lib_by_key[bname] = lib
            lib_by_key[soname] = lib
            exp_names = {e["name"] for e in lib["exports"]}
            exports_by_lib[lib["archive_path"]] = exp_names

        lib_summaries: List[Dict[str, Any]] = []
        for lib in libs:
            # Transitive DT_NEEDED closure over app-bundled libraries
            closure: List[str] = []
            missing_dt: List[str] = []
            platform_dt: List[str] = []
            visited_libs: Set[str] = {lib["archive_path"]}
            queue: List[str] = list(lib["dt_needed"])
            seen_q: Set[str] = set()
            while queue:
                dep = queue.pop(0)
                if dep in seen_q:
                    continue
                seen_q.add(dep)
                target = lib_by_key.get(dep)
                if target is not None:
                    tpath = target["archive_path"]
                    closure.append(tpath)
                    if tpath not in visited_libs:
                        visited_libs.add(tpath)
                        queue.extend(target["dt_needed"])
                elif dep in PLATFORM_NDK_SONAMES:
                    platform_dt.append(dep)
                else:
                    missing_dt.append(dep)

            closure_exports: Set[str] = set()
            for cpath in closure:
                closure_exports.update(exports_by_lib.get(cpath, set()))
            other_app_exports: Set[str] = set()
            for opath, exps in exports_by_lib.items():
                if opath != lib["archive_path"] and opath not in closure:
                    other_app_exports.update(exps)

            image_tag: Optional[str] = None
            for tag in per_image:
                if tag in lib["soname"]:
                    image_tag = tag
                    break

            has_vulkan = "libvulkan.so" in lib["dt_needed"]
            resolved_app: List[str] = []
            resolved_platform: List[str] = []
            weak_unresolved: List[Dict[str, Any]] = []
            missing_syms: List[Dict[str, Any]] = []
            unknown_syms: List[Dict[str, Any]] = []

            for imp in lib["imports"]:
                sname = imp["name"]
                if sname in closure_exports:
                    resolved_app.append(sname)
                    continue
                if sname in other_app_exports:
                    resolved_app.append(sname)
                    continue

                if abi == "arm64-v8a":
                    in_per_img = bool(image_tag and sname in per_image[image_tag])
                    in_platform = (
                        in_per_img
                        or sname in arm64_shim
                        or sname in direct_c_ndk
                        or sname in adapters
                        or (has_vulkan and sname.startswith("vk"))
                    )
                    in_fmod_stub = sname in fmod_stubs
                    if in_platform or in_fmod_stub:
                        resolved_platform.append(sname)
                        if sname in ("dlsym", "dlopen", "android_dlopen_ext"):
                            unknown_syms.append({
                                "symbol": sname,
                                "reason": "dynamic_loader_api",
                                "capability": matcher.match_native(sname),
                            })
                        continue
                else:
                    # Non-arm64 ABIs: arm64_shim inventory does not cover 32-bit/x86 directly
                    if imp["is_weak"]:
                        weak_unresolved.append({
                            "symbol": sname,
                            "bind": imp["bind"],
                            "gnu_version": imp.get("gnu_version"),
                            "capability": matcher.match_native(sname),
                        })
                    else:
                        unknown_syms.append({
                            "symbol": sname,
                            "reason": f"abi_{abi}_not_indexed_in_arm64_bionic_inventory",
                            "capability": matcher.match_native(sname),
                        })
                    continue

                cap_info = matcher.match_native(sname)
                entry = {
                    "symbol": sname,
                    "bind": imp["bind"],
                    "type": imp["type"],
                    "gnu_version": imp.get("gnu_version"),
                    "capability": cap_info,
                }
                if imp["is_weak"]:
                    weak_unresolved.append(entry)
                else:
                    missing_syms.append(entry)
                    native_gaps.append({
                        "kind": "missing_native_symbol",
                        "abi": abi,
                        "library": lib["archive_path"],
                        "symbol": sname,
                        "bind": imp["bind"],
                        "gnu_version": imp.get("gnu_version"),
                        "static_ref_count": 1,
                        "capability_id": cap_info["capability_id"] if cap_info else None,
                    })

            for mdt in sorted(set(missing_dt)):
                native_gaps.append({
                    "kind": "missing_dt_needed",
                    "abi": abi,
                    "library": lib["archive_path"],
                    "symbol": mdt,
                    "static_ref_count": 1,
                    "capability_id": "NAT-001",
                })

            if lib["has_dlsym_or_dlopen"]:
                blind_spots.append({
                    "kind": "native_dynamic_symbol_lookup",
                    "source": lib["archive_path"],
                    "detail": "Imports dlopen/dlsym/android_dlopen_ext; runtime symbol targets are unknown statically",
                    "hints": lib["rodata_dynamic_hints"],
                })
            if lib["has_jni_onload"] or lib["has_register_natives_hint"]:
                blind_spots.append({
                    "kind": "native_dynamic_jni_registration",
                    "source": lib["archive_path"],
                    "detail": "Exports JNI_OnLoad or references RegisterNatives; dynamically bound JNI methods are unknown statically",
                    "hints": lib["rodata_dynamic_hints"],
                })

            lib_summaries.append({
                "archive_path": lib["archive_path"],
                "soname": lib["soname"],
                "elf_class": lib["elf_class"],
                "e_machine": lib["e_machine"],
                "abi_machine_match": lib["abi_machine_match"],
                "section_headers_present": lib["section_headers_present"],
                "has_initializers": lib["has_initializers"],
                "initializers_executed": False,
                "dt_needed": lib["dt_needed"],
                "app_closure_libs": sorted(set(closure)),
                "platform_dt_needed": sorted(set(platform_dt)),
                "missing_dt_needed": sorted(set(missing_dt)),
                "exports_count": len(lib["exports"]),
                "imports_count": len(lib["imports"]),
                "resolved_by_app_count": len(resolved_app),
                "resolved_by_app": sorted(set(resolved_app)),
                "resolved_by_platform_count": len(resolved_platform),
                "weak_unresolved": weak_unresolved,
                "missing_symbols": missing_syms,
                "unknown_symbols": unknown_syms,
            })

        abi_reports[abi] = {
            "libraries_count": len(libs),
            "libraries": lib_summaries,
        }

    return {
        "abis": abi_reports,
        "native_gaps": sorted(native_gaps, key=lambda g: (g["kind"], g["abi"], g["library"], g["symbol"])),
        "blind_spots": blind_spots,
    }


# ---------------------------------------------------------------------------
# Full Logical App (Base + Optional Splits) Scanner
# ---------------------------------------------------------------------------

def scan_logical_app(
    apk_paths: List[pathlib.Path],
    inventory: Dict[str, Any],
    cap_data: Dict[str, Any],
    jdk_classes: Dict[str, Dict[str, Any]],
    matcher: CapabilityMatcher,
    scenario_map: Dict[str, str],
    limits: Dict[str, int],
) -> Dict[str, Any]:
    sdk_policy = cap_data.get("sdk_policy", {})
    inv_classes = inventory.get("classes", {})

    app_classes: Dict[str, Dict[str, Any]] = {}
    dex_parsed_list: List[Dict[str, Any]] = []
    elf_records: List[Dict[str, Any]] = []
    manifest_primary: Optional[Dict[str, Any]] = None
    split_manifests: List[Dict[str, Any]] = []
    apk_inputs_meta: List[Dict[str, Any]] = []
    blind_spots: List[Dict[str, Any]] = []

    for apk_path in apk_paths:
        zf, infos = open_apk_safely(
            apk_path,
            max_apk_bytes=limits["max_apk_bytes"],
            max_entries=limits["max_zip_entries"],
            max_total_uncompressed=limits["max_total_uncompressed"],
        )
        try:
            remaining_total = [limits["max_total_uncompressed"]]
            info_by_name = {info.filename: info for info in infos}

            if "AndroidManifest.xml" not in info_by_name:
                raise BinaryFormatError(f"{apk_path}: missing AndroidManifest.xml")

            manifest_bytes = read_zip_member_bounded(
                zf, info_by_name["AndroidManifest.xml"], limits["max_manifest_bytes"], remaining_total
            )
            mdata = parse_binary_manifest(manifest_bytes, sdk_policy)
            if manifest_primary is None and not mdata.get("split"):
                manifest_primary = mdata
            elif manifest_primary is None:
                manifest_primary = mdata
            else:
                split_manifests.append(mdata)

            # Discover and parse multi-DEX entries in deterministic order
            dex_names = sorted(
                (n for n in info_by_name if re.match(r"^classes(\d*)\.dex$", n)),
                key=lambda n: (0 if n == "classes.dex" else int(n[7:-4]), n),
            )
            for dname in dex_names:
                dex_bytes = read_zip_member_bounded(
                    zf, info_by_name[dname], limits["max_dex_bytes"], remaining_total
                )
                dp = parse_dex_bytes(dex_bytes, f"{apk_path.name}!/{dname}")
                dex_parsed_list.append(dp)
                for cname, cdict in dp["classes"].items():
                    app_classes.setdefault(cname, cdict)

            # Discover and parse ELF .so files under lib/<abi>/*.so
            so_names = sorted(
                n for n in info_by_name if n.startswith("lib/") and n.endswith(".so") and n.count("/") == 2
            )
            for sname in so_names:
                abi = sname.split("/")[1]
                so_bytes = read_zip_member_bounded(
                    zf, info_by_name[sname], limits["max_elf_bytes"], remaining_total
                )
                elf_records.append(parse_elf_bytes(so_bytes, f"{apk_path.name}!/{sname}", abi))

            # Blind spot check for hidden/embedded DEX/JAR/APK payloads in assets/ or res/raw/
            for name in sorted(info_by_name):
                if (name.startswith("assets/") or name.startswith("res/raw/")) and name.endswith(
                    (".dex", ".jar", ".apk", ".odex", ".vdex")
                ):
                    blind_spots.append({
                        "kind": "embedded_dynamic_code_payload",
                        "source": f"{apk_path.name}!/{name}",
                        "detail": "Embedded DEX/JAR/APK payload in assets/res; encrypted or dynamically loaded code is a static blind spot",
                        "hints": [name],
                    })

            apk_inputs_meta.append({
                "filename": apk_path.name,
                "size_bytes": apk_path.stat().st_size,
                "sha256": sha256_file(apk_path),
                "zip_entries": len(infos),
                "dex_files": dex_names,
                "native_libs": so_names,
            })
        finally:
            zf.close()

    assert manifest_primary is not None

    # Merge DEX references across all DEX files of this app
    method_call_counts: Dict[Tuple[str, str, str], int] = {}
    field_ref_counts: Dict[Tuple[str, str, str], int] = {}
    type_ref_counts: Dict[str, int] = {}
    native_lib_hints: Set[str] = set()
    service_hints: Set[str] = set()
    sysprop_hints: Set[str] = set()
    reflection_string_hints: Set[str] = set()

    for dp in dex_parsed_list:
        for mref in dp["method_ids"]:
            cnt = dp["method_call_sites"].get(mref, 0)
            method_call_counts[mref] = method_call_counts.get(mref, 0) + max(cnt, 1)
        for fref in dp["field_ids"]:
            cnt = dp["field_ref_sites"].get(fref, 0)
            field_ref_counts[fref] = field_ref_counts.get(fref, 0) + max(cnt, 1)
        for tref in dp["type_descs"]:
            if tref and not tref.startswith("[") and tref not in ("Z", "B", "S", "C", "I", "J", "F", "D", "V"):
                cnt = dp["type_ref_sites"].get(tref, 0)
                type_ref_counts[tref] = type_ref_counts.get(tref, 0) + max(cnt, 1)
        for rh in dp["reflection_hits"]:
            blind_spots.append({
                "kind": "reflection_api",
                "source": rh["caller"],
                "detail": f"Reflective invocation of {rh['target']}",
                "hints": rh["string_hints"],
            })
        for dh in dp["dynamic_dex_hits"]:
            blind_spots.append({
                "kind": "dynamic_dex_loading",
                "source": dh["caller"],
                "detail": f"Dynamic DEX/ClassLoader reference to {dh['target']}",
                "hints": dh["string_hints"],
            })
        native_lib_hints.update(dp["native_lib_hints"])
        service_hints.update(dp["service_hints"])
        sysprop_hints.update(dp["sysprop_hints"])
        reflection_string_hints.update(dp["reflection_string_hints"])

    # Classify & resolve referenced classes, methods, and fields
    origin_type_counts: Dict[str, int] = {}
    for tref in sorted(type_ref_counts):
        orig = classify_class_origin(tref, app_classes, inv_classes, jdk_classes)
        origin_type_counts[orig] = origin_type_counts.get(orig, 0) + 1

    java_gaps: List[Dict[str, Any]] = []
    java_unknowns: List[Dict[str, Any]] = []
    referenced_capabilities: Dict[str, Dict[str, Any]] = {}

    def record_cap(cap_info: Optional[Dict[str, Any]], ref_count: int) -> None:
        if not cap_info:
            return
        cid = cap_info["capability_id"]
        rec = referenced_capabilities.setdefault(
            cid,
            {
                "capability_id": cid,
                "support_status": cap_info["support_status"],
                "verification_status": cap_info["verification_status"],
                "surface": cap_info["surface"],
                "static_ref_count": 0,
            },
        )
        rec["static_ref_count"] += ref_count

    resolved_methods_summary = {
        "satisfied_by_app": 0,
        "present_in_platform": 0,
        "present_in_host_jdk": 0,
        "missing": 0,
        "unknown": 0,
    }

    for (owner, mname, mdesc), ref_cnt in sorted(method_call_counts.items()):
        # Skip references to app's own directly declared methods unless inherited from external
        res = resolve_member_in_hierarchy(
            owner, mname, mdesc, True, app_classes, inv_classes, jdk_classes
        )
        if res.get("found"):
            layer = res["layer"]
            declared_in = res["declared_in"]
            cap_info = matcher.match_java(declared_in, mname, mdesc)
            if cap_info is None:
                cap_info = matcher.match_java(owner, mname, mdesc)
            record_cap(cap_info, ref_cnt)
            if layer == "app":
                resolved_methods_summary["satisfied_by_app"] += 1
            elif layer == "inventory":
                resolved_methods_summary["present_in_platform"] += 1
                if cap_info and cap_info["support_status"] == "unsupported":
                    java_gaps.append({
                        "kind": "unsupported_platform_capability",
                        "signature": f"{declared_in}#{mname}{mdesc}",
                        "referenced_via": f"{owner}#{mname}{mdesc}",
                        "origin": res["origin"],
                        "static_ref_count": ref_cnt,
                        "capability_id": cap_info["capability_id"],
                        "support_status": cap_info["support_status"],
                    })
            else:
                resolved_methods_summary["present_in_host_jdk"] += 1
        elif res["status"] == "missing":
            resolved_methods_summary["missing"] += 1
            cap_info = matcher.match_java(owner, mname, mdesc)
            record_cap(cap_info, ref_cnt)
            java_gaps.append({
                "kind": "missing_method_overload" if res.get("overload_mismatch") else "missing_method",
                "signature": f"{owner}#{mname}{mdesc}",
                "origin": res.get("origin"),
                "reason": res.get("reason"),
                "overload_mismatch": bool(res.get("overload_mismatch")),
                "available_overloads": res.get("available_overloads", []),
                "static_ref_count": ref_cnt,
                "capability_id": cap_info["capability_id"] if cap_info else None,
            })
        else:
            resolved_methods_summary["unknown"] += 1
            cap_info = matcher.match_java(owner, mname, mdesc)
            record_cap(cap_info, ref_cnt)
            java_unknowns.append({
                "kind": "unknown_method",
                "signature": f"{owner}#{mname}{mdesc}",
                "origin": res.get("origin"),
                "reason": res.get("reason"),
                "unresolved_ancestors": res.get("unresolved_ancestors", []),
                "static_ref_count": ref_cnt,
                "capability_id": cap_info["capability_id"] if cap_info else None,
            })

    resolved_fields_summary = {
        "satisfied_by_app": 0,
        "present_in_platform": 0,
        "present_in_host_jdk": 0,
        "missing": 0,
        "unknown": 0,
    }
    for (owner, fname, fdesc), ref_cnt in sorted(field_ref_counts.items()):
        res = resolve_member_in_hierarchy(
            owner, fname, fdesc, False, app_classes, inv_classes, jdk_classes
        )
        if res.get("found"):
            layer = res["layer"]
            declared_in = res["declared_in"]
            cap_info = matcher.match_java(declared_in) or matcher.match_java(owner)
            record_cap(cap_info, ref_cnt)
            if layer == "app":
                resolved_fields_summary["satisfied_by_app"] += 1
            elif layer == "inventory":
                resolved_fields_summary["present_in_platform"] += 1
            else:
                resolved_fields_summary["present_in_host_jdk"] += 1
        elif res["status"] == "missing":
            resolved_fields_summary["missing"] += 1
            cap_info = matcher.match_java(owner)
            record_cap(cap_info, ref_cnt)
            java_gaps.append({
                "kind": "missing_field",
                "signature": f"{owner}#{fname}:{fdesc}",
                "origin": res.get("origin"),
                "reason": res.get("reason"),
                "static_ref_count": ref_cnt,
                "capability_id": cap_info["capability_id"] if cap_info else None,
            })
        else:
            resolved_fields_summary["unknown"] += 1
            cap_info = matcher.match_java(owner)
            java_unknowns.append({
                "kind": "unknown_field",
                "signature": f"{owner}#{fname}:{fdesc}",
                "origin": res.get("origin"),
                "reason": res.get("reason"),
                "static_ref_count": ref_cnt,
                "capability_id": cap_info["capability_id"] if cap_info else None,
            })

    # Native ELF analysis
    native_report = analyze_native_libs(elf_records, inventory, matcher)
    blind_spots.extend(native_report["blind_spots"])

    # Cross-check app ACC_NATIVE methods against bundled .so Java_* exports
    all_so_exports: Set[str] = set()
    any_dynamic_jni = any(
        rec["has_jni_onload"] or rec["has_register_natives_hint"] for rec in elf_records
    )
    for rec in elf_records:
        for exp in rec["exports"]:
            all_so_exports.add(exp["name"])

    app_native_methods: List[Dict[str, Any]] = []
    for cname in sorted(app_classes):
        for m in app_classes[cname]["methods"]:
            if m.get("access", 0) & DEX_ACCESS_NATIVE:
                short_sym = mangle_jni_name(cname, m["name"])
                long_sym = mangle_jni_name(cname, m["name"], m["descriptor"])
                if short_sym in all_so_exports or long_sym in all_so_exports:
                    status = "bound_static_jni_export"
                elif any_dynamic_jni:
                    status = "unknown_dynamic_jni_onload"
                elif not elf_records:
                    status = "missing_bundled_so"
                    java_gaps.append({
                        "kind": "unbound_app_native_method",
                        "signature": f"{cname}#{m['name']}{m['descriptor']}",
                        "origin": "app_owned",
                        "reason": "no_bundled_so_for_native_method",
                        "static_ref_count": 1,
                        "capability_id": "NAT-004",
                    })
                else:
                    status = "missing_jni_export"
                    java_gaps.append({
                        "kind": "unbound_app_native_method",
                        "signature": f"{cname}#{m['name']}{m['descriptor']}",
                        "origin": "app_owned",
                        "reason": "missing_java_jni_export_and_no_jni_onload",
                        "static_ref_count": 1,
                        "capability_id": "NAT-004",
                    })
                app_native_methods.append({
                    "class": cname,
                    "name": m["name"],
                    "descriptor": m["descriptor"],
                    "expected_symbols": [short_sym, long_sym],
                    "status": status,
                })

    # Manifest clues (multi-process services, non-empty uses-library, features)
    manifest_clues: List[Dict[str, Any]] = []
    pkg = manifest_primary["package"]
    for comp_type, clist in manifest_primary["components"].items():
        for comp in clist:
            proc = comp.get("process")
            if proc and proc != pkg:
                manifest_clues.append({
                    "kind": "multiprocess_component",
                    "component_type": comp_type,
                    "name": comp["name"],
                    "process": proc,
                    "capability_id": "IPC-003",
                    "note": "Remote process runs via ProcessBroker/Mach-port IPC without Android OS sandbox isolation",
                })
                record_cap(matcher.by_id.get("IPC-003") and {
                    "capability_id": "IPC-003",
                    "support_status": matcher.by_id["IPC-003"]["support_status"],
                    "verification_status": matcher.by_id["IPC-003"]["verification_status"],
                    "surface": matcher.by_id["IPC-003"]["surface"],
                }, 1)

    for lib_req in manifest_primary["uses_libraries"]:
        if lib_req["required"]:
            manifest_clues.append({
                "kind": "uses_library_required",
                "name": lib_req["name"],
                "capability_id": None,
                "note": "Required shared Java library declared in manifest",
            })

    # SystemProperties hints cross-check against inventory KNOWN keys
    known_sysprops = set(inventory.get("service_property", {}).get("system_properties_known_keys", []))
    sysprop_findings = [
        {"key": k, "in_known_store": k in known_sysprops}
        for k in sorted(sysprop_hints)
    ]

    # Deterministic blind_spots sorting
    blind_spots.sort(key=lambda b: (b["kind"], b["source"], b["detail"]))
    java_gaps.sort(key=lambda g: (g["kind"], g["signature"]))
    java_unknowns.sort(key=lambda u: (u["kind"], u["signature"]))

    scenario_id = scenario_map.get(pkg) or scenario_map.get(apk_paths[0].stem)
    app_id = scenario_id or pkg or apk_paths[0].stem

    return {
        "app_id": app_id,
        "scenario_id": scenario_id,
        "inputs": apk_inputs_meta,
        "manifest": manifest_primary,
        "split_manifests": split_manifests,
        "java_summary": {
            "app_defined_classes": len(app_classes),
            "referenced_types_by_origin": {k: origin_type_counts[k] for k in sorted(origin_type_counts)},
            "methods": resolved_methods_summary,
            "fields": resolved_fields_summary,
            "app_native_methods": app_native_methods,
            "native_lib_load_hints": sorted(native_lib_hints),
            "service_hints": sorted(service_hints),
            "system_property_hints": sysprop_findings,
        },
        "native_summary": {
            "abis": native_report["abis"],
        },
        "capabilities_touched": [
            referenced_capabilities[k] for k in sorted(referenced_capabilities)
        ],
        "gaps": {
            "java": java_gaps,
            "native": native_report["native_gaps"],
            "manifest_clues": manifest_clues,
        },
        "unknowns": java_unknowns,
        "blind_spots": blind_spots,
        "reflection_string_hints_count": len(reflection_string_hints),
        "reflection_string_hints_sample": sorted(reflection_string_hints)[:30],
    }


# ---------------------------------------------------------------------------
# Multi-APK Aggregator & Markdown Renderer
# ---------------------------------------------------------------------------

def aggregate_reports(
    app_reports: List[Dict[str, Any]],
    inventory_sha256: str,
    capabilities_sha256: str,
    scenarios_sha256: Optional[str],
    jdk_home_str: Optional[str],
) -> Dict[str, Any]:
    clue_map: Dict[Tuple[str, str], Dict[str, Any]] = {}

    def add_clue(
        surface: str,
        kind: str,
        key: str,
        cap_id: Optional[str],
        app_id: str,
        scenario_id: Optional[str],
        static_refs: int,
    ) -> None:
        ck = (surface, f"{kind}:{key}")
        rec = clue_map.setdefault(
            ck,
            {
                "surface": surface,
                "kind": kind,
                "api_or_symbol": key,
                "capability_id": cap_id,
                "static_ref_count": 0,
                "runtime_call_frequency": None,  # Populated by GAPLOG-001 at runtime
                "affected_apps": set(),
                "affected_scenarios": set(),
            },
        )
        rec["static_ref_count"] += static_refs
        rec["affected_apps"].add(app_id)
        if scenario_id:
            rec["affected_scenarios"].add(scenario_id)

    for rep in app_reports:
        aid = rep["app_id"]
        sid = rep.get("scenario_id")
        for g in rep["gaps"]["java"]:
            add_clue("java", g["kind"], g["signature"], g.get("capability_id"), aid, sid, g.get("static_ref_count", 1))
        for g in rep["gaps"]["native"]:
            add_clue("native", g["kind"], f"{g['abi']}:{g['symbol']}", g.get("capability_id"), aid, sid, g.get("static_ref_count", 1))
        for m in rep["gaps"]["manifest_clues"]:
            add_clue("manifest", m["kind"], m["name"], m.get("capability_id"), aid, sid, 1)

    clues: List[Dict[str, Any]] = []
    for rec in clue_map.values():
        clues.append({
            "surface": rec["surface"],
            "kind": rec["kind"],
            "api_or_symbol": rec["api_or_symbol"],
            "capability_id": rec["capability_id"],
            "static_ref_count": rec["static_ref_count"],
            "runtime_call_frequency": rec["runtime_call_frequency"],
            "affected_apks_count": len(rec["affected_apps"]),
            "affected_apps": sorted(rec["affected_apps"]),
            "affected_scenarios": sorted(rec["affected_scenarios"]),
        })
    clues.sort(key=lambda c: (-c["affected_apks_count"], -c["static_ref_count"], c["surface"], c["api_or_symbol"]))

    partial_cap_map: Dict[str, Dict[str, Any]] = {}
    for rep in app_reports:
        aid = rep["app_id"]
        sid = rep.get("scenario_id")
        for ct in rep.get("capabilities_touched", []):
            if ct["support_status"] in ("partial", "unsupported", "unknown") or ct["verification_status"] == "unverified":
                cid = ct["capability_id"]
                prec = partial_cap_map.setdefault(
                    cid,
                    {
                        "capability_id": cid,
                        "support_status": ct["support_status"],
                        "verification_status": ct["verification_status"],
                        "surface": ct["surface"],
                        "static_ref_count": 0,
                        "runtime_call_frequency": None,
                        "affected_apps": set(),
                        "affected_scenarios": set(),
                    },
                )
                prec["static_ref_count"] += ct["static_ref_count"]
                prec["affected_apps"].add(aid)
                if sid:
                    prec["affected_scenarios"].add(sid)

    partial_clues: List[Dict[str, Any]] = []
    for prec in partial_cap_map.values():
        partial_clues.append({
            "capability_id": prec["capability_id"],
            "support_status": prec["support_status"],
            "verification_status": prec["verification_status"],
            "surface": prec["surface"],
            "static_ref_count": prec["static_ref_count"],
            "runtime_call_frequency": None,
            "affected_apks_count": len(prec["affected_apps"]),
            "affected_apps": sorted(prec["affected_apps"]),
            "affected_scenarios": sorted(prec["affected_scenarios"]),
        })
    partial_clues.sort(key=lambda c: (-c["affected_apks_count"], -c["static_ref_count"], c["capability_id"]))

    return {
        "schema_version": "1.0.0",
        "scanner_version": SCANNER_VERSION,
        "anchors": {
            "inventory_sha256": inventory_sha256,
            "capabilities_sha256": capabilities_sha256,
            "scenarios_sha256": scenarios_sha256,
            "host_jdk_home": jdk_home_str,
        },
        "apps_scanned": len(app_reports),
        "apps": app_reports,
        "gaplog_priority_clues": clues,
        "partial_capability_clues": partial_clues,
    }


def render_scan_markdown(
    agg: Dict[str, Any],
    telemetry: Dict[str, Any],
) -> str:
    lines: List[str] = [
        "# Mac.apk Static Compatibility Scan Report (SCAN-001)",
        "",
        "<!-- Generated by scripts/scan_apk.py; static preflight only (no APK/DEX/.so execution). -->",
        "",
        "## 1. Anchors & Measured Resource Telemetry",
        "",
        f"- **Scanner Version**: `{agg['scanner_version']}` (pure-Python stdlib, offline, zero initializers executed)",
        f"- **Inventory SHA-256**: `{agg['anchors']['inventory_sha256']}`",
        f"- **Capabilities SHA-256**: `{agg['anchors']['capabilities_sha256']}`",
        f"- **Scenarios SHA-256**: `{agg['anchors']['scenarios_sha256'] or 'n/a'}`",
        f"- **Host JDK Indexed**: `{agg['anchors']['host_jdk_home'] or 'none (inventory-only)'}`",
        f"- **Measured Scan Wall Time**: **{telemetry['wall_time_ms']:.2f} ms** across {agg['apps_scanned']} app(s)",
        f"- **Measured Peak RSS**: **{telemetry['peak_rss_bytes']:,} bytes** ({telemetry['peak_rss_bytes'] / (1024 * 1024):.2f} MiB)",
        "",
        "## 2. Scanned APKs Summary",
        "",
        "| App / Scenario ID | Package | Target / Presented SDK | App Classes | Methods (Present / App / Missing / Unknown) | Native `.so` (Missing / Weak) | Blind Spots |",
        "|---|---|---:|---:|---|---:|---:|",
    ]
    for rep in agg["apps"]:
        m = rep["manifest"]
        js = rep["java_summary"]
        ms = js["methods"]
        plat_cnt = ms["present_in_platform"] + ms["present_in_host_jdk"]
        nat_missing = len(rep["gaps"]["native"])
        weak_cnt = sum(
            len(lib["weak_unresolved"])
            for abi in rep["native_summary"]["abis"].values()
            for lib in abi["libraries"]
        )
        lines.append(
            f"| `{rep['app_id']}` | `{m['package']}` | {m['target_sdk']} -> {m['presented_sdk']} | "
            f"{js['app_defined_classes']} | {plat_cnt} / {ms['satisfied_by_app']} / {ms['missing']} / {ms['unknown']} | "
            f"{nat_missing} / {weak_cnt} | {len(rep['blind_spots'])} |"
        )

    lines.extend([
        "",
        "## 3. Priority Clues for GAPLOG-001 (Static Refs vs Runtime Frequency)",
        "",
        "> Static reference counts come from DEX/ELF inspection; `runtime_call_frequency` remains `null` until GAPLOG-001 executes the scenarios.",
        "",
    ])
    clues = agg["gaplog_priority_clues"]
    if clues:
        lines.extend([
            "| Surface | Kind | API / Symbol | Capability ID | Static Refs | Runtime Freq | Affected APKs | Scenarios |",
            "|---|---|---|---|---:|---:|---:|---|",
        ])
        for c in clues:
            sc = ", ".join(f"`{s}`" for s in c["affected_scenarios"]) if c["affected_scenarios"] else "—"
            cid = f"`{c['capability_id']}`" if c["capability_id"] else "`unmapped`"
            lines.append(
                f"| `{c['surface']}` | `{c['kind']}` | `{c['api_or_symbol']}` | {cid} | "
                f"{c['static_ref_count']} | unmeasured | {c['affected_apks_count']} | {sc} |"
            )
    else:
        lines.append("No missing Java/native APIs detected across the scanned APKs.")

    pclues = agg.get("partial_capability_clues", [])
    if pclues:
        lines.extend([
            "",
            "### Touched Partial / Unverified Capabilities (Runtime Verification Candidates)",
            "",
            "| Capability ID | Surface | Support | Verification | Static Refs | Runtime Freq | Affected APKs | Scenarios |",
            "|---|---|---|---|---:|---:|---:|---|",
        ])
        for c in pclues:
            sc = ", ".join(f"`{s}`" for s in c["affected_scenarios"]) if c["affected_scenarios"] else "—"
            lines.append(
                f"| `{c['capability_id']}` | `{c['surface']}` | `{c['support_status']}` | "
                f"`{c['verification_status']}` | {c['static_ref_count']} | unmeasured | "
                f"{c['affected_apks_count']} | {sc} |"
            )

    lines.extend(["", "## 4. Per-App Details, Capabilities Touched & Blind Spots", ""])
    for rep in agg["apps"]:
        m = rep["manifest"]
        lines.append(f"### `{rep['app_id']}` (`{m['package']}`)")
        lines.append("")
        for inp in rep["inputs"]:
            lines.append(
                f"- **Input**: `{inp['filename']}` ({inp['size_bytes']} bytes, SHA-256 `{inp['sha256'][:16]}...`, "
                f"DEX: `{', '.join(inp['dex_files'])}`, `.so`: `{', '.join(inp['native_libs']) or 'none'}`)"
            )
        feats = [
            f"{f['name'] or f['gl_es_version']}(req={f['required']})"
            for f in m["uses_features"]
        ]
        lines.append(
            f"- **Manifest**: `minSdk={m['min_sdk']}`, `targetSdk={m['target_sdk']}`, "
            f"`presentedSdk={m['presented_sdk']}`, features=`{', '.join(feats) or 'none'}`"
        )
        if rep["capabilities_touched"]:
            caps_str = ", ".join(
                f"`{c['capability_id']}` ({c['support_status']}/{c['verification_status']}, refs={c['static_ref_count']})"
                for c in rep["capabilities_touched"]
            )
            lines.append(f"- **Capabilities Touched**: {caps_str}")
        if rep["gaps"]["java"] or rep["gaps"]["native"] or rep["gaps"]["manifest_clues"]:
            lines.append("- **Static Gaps & Manifest Clues**:")
            for g in rep["gaps"]["java"]:
                lines.append(
                    f"  - `[java/{g['kind']}]` `{g['signature']}` (cap=`{g.get('capability_id') or 'unmapped'}`, refs={g['static_ref_count']})"
                )
            for g in rep["gaps"]["native"]:
                lines.append(
                    f"  - `[native/{g['kind']}]` `{g['abi']}:{g['symbol']}` in `{g['library']}` (cap=`{g.get('capability_id') or 'unmapped'}`)"
                )
            for mc in rep["gaps"]["manifest_clues"]:
                lines.append(
                    f"  - `[manifest/{mc['kind']}]` `{mc['name']}` (`process={mc.get('process', 'n/a')}`, cap=`{mc.get('capability_id') or 'unmapped'}`)"
                )
        if rep["blind_spots"]:
            lines.append("- **Blind Spots (Unknown Without Runtime Execution)**:")
            for bs in rep["blind_spots"]:
                hints = f" hints=`{', '.join(bs['hints'][:5])}`" if bs.get("hints") else ""
                lines.append(f"  - `{bs['kind']}` at `{bs['source']}`: {bs['detail']}{hints}")
        lines.append("")

    return "\n".join(lines)


# ---------------------------------------------------------------------------
# CLI Entrypoint
# ---------------------------------------------------------------------------

def main(argv: Optional[List[str]] = None) -> int:
    parser = argparse.ArgumentParser(
        description="Mac.apk SCAN-001 offline read-only APK/DEX/ELF dependency scanner."
    )
    parser.add_argument(
        "inputs",
        nargs="*",
        help="APK path(s) to scan (each treated as an independent app unless --as-split-bundle is set).",
    )
    parser.add_argument(
        "--split",
        action="append",
        default=[],
        help="Split APK path(s) to merge with the single primary APK input.",
    )
    parser.add_argument(
        "--as-split-bundle",
        action="store_true",
        help="Treat all positional inputs as base + splits of a single logical app.",
    )
    parser.add_argument(
        "--inventory",
        default="docs/compat/inventory.json",
        help="Path to CAP-001 inventory.json",
    )
    parser.add_argument(
        "--capabilities",
        default="docs/compat/capabilities.json",
        help="Path to CAP-001 capabilities.json",
    )
    parser.add_argument(
        "--scenarios",
        default="docs/compat/scenarios.json",
        help="Optional COMP-001 scenarios.json for scenario attribution",
    )
    parser.add_argument("--jdk-home", default=None, help="Optional JDK home containing jmods/")
    parser.add_argument(
        "--no-host-jdk",
        action="store_true",
        help="Disable auto-indexing host JDK jmods; match strictly against inventory.json.",
    )
    parser.add_argument("--out-json", default=None, help="Write deterministic aggregated JSON report")
    parser.add_argument("--out-md", default=None, help="Write Markdown summary report")
    parser.add_argument("--raw-dir", default=None, help="Write deterministic per-APK JSON reports")
    parser.add_argument("--telemetry-out", default=None, help="Write measured wall time and peak RSS JSON")
    parser.add_argument("--max-apk-bytes", type=int, default=DEFAULT_MAX_APK_BYTES)
    parser.add_argument("--max-zip-entries", type=int, default=DEFAULT_MAX_ZIP_ENTRIES)
    parser.add_argument(
        "--max-total-uncompressed", type=int, default=DEFAULT_MAX_TOTAL_UNCOMPRESSED_BYTES
    )
    parser.add_argument("--max-manifest-bytes", type=int, default=DEFAULT_MAX_MANIFEST_BYTES)
    parser.add_argument("--max-dex-bytes", type=int, default=DEFAULT_MAX_DEX_BYTES)
    parser.add_argument("--max-elf-bytes", type=int, default=DEFAULT_MAX_ELF_BYTES)

    args = parser.parse_args(argv)
    if not args.inputs:
        parser.print_usage(sys.stderr)
        return EXIT_CLI_OR_IO
    if args.split and (len(args.inputs) != 1 or args.as_split_bundle):
        sys.stderr.write("ERROR: --split requires exactly 1 primary APK input without --as-split-bundle\n")
        return EXIT_CLI_OR_IO

    t0 = time.perf_counter()
    repo_root = cap_registry.find_repo_root()

    def resolve_path(p: str) -> pathlib.Path:
        pp = pathlib.Path(p)
        return pp if pp.is_absolute() else (repo_root / pp)

    try:
        inv_path = resolve_path(args.inventory)
        cap_path = resolve_path(args.capabilities)
        if not inv_path.is_file():
            raise RegistryLoadError(f"Missing inventory file: {inv_path}")
        if not cap_path.is_file():
            raise RegistryLoadError(f"Missing capabilities file: {cap_path}")
        inv_bytes = inv_path.read_bytes()
        cap_bytes = cap_path.read_bytes()
        inventory = json.loads(inv_bytes.decode("utf-8"))
        cap_data = json.loads(cap_bytes.decode("utf-8"))
        inv_sha = sha256_bytes(inv_bytes)
        cap_sha = sha256_bytes(cap_bytes)

        scenario_map: Dict[str, str] = {}
        scen_sha: Optional[str] = None
        if args.scenarios:
            sp = resolve_path(args.scenarios)
            if sp.is_file():
                sbytes = sp.read_bytes()
                scen_sha = sha256_bytes(sbytes)
                sdata = json.loads(sbytes.decode("utf-8"))
                for sc in sdata.get("scenarios", []):
                    if sc.get("package") and sc.get("id"):
                        scenario_map[sc["package"]] = sc["id"]
                    if sc.get("id"):
                        scenario_map[sc["id"]] = sc["id"]
    except RegistryLoadError as exc:
        sys.stderr.write(f"ERROR: {exc}\n")
        return EXIT_REGISTRY_ERROR
    except (OSError, json.JSONDecodeError, UnicodeDecodeError) as exc:
        sys.stderr.write(f"ERROR: failed to load registry/inventory: {exc}\n")
        return EXIT_REGISTRY_ERROR

    jdk_classes: Dict[str, Dict[str, Any]] = {}
    jdk_home_str: Optional[str] = None
    if not args.no_host_jdk:
        jdk_home = discover_jdk_home(repo_root, args.jdk_home)
        jdk_classes, jdk_home_str = load_host_jdk_classes(jdk_home, inventory.get("classes", {}))

    matcher = CapabilityMatcher(cap_data, inventory)
    limits = {
        "max_apk_bytes": args.max_apk_bytes,
        "max_zip_entries": args.max_zip_entries,
        "max_total_uncompressed": args.max_total_uncompressed,
        "max_manifest_bytes": args.max_manifest_bytes,
        "max_dex_bytes": args.max_dex_bytes,
        "max_elf_bytes": args.max_elf_bytes,
    }

    if args.as_split_bundle:
        app_groups = [[pathlib.Path(p) for p in args.inputs]]
    elif args.split:
        app_groups = [[pathlib.Path(args.inputs[0])] + [pathlib.Path(s) for s in args.split]]
    else:
        app_groups = [[pathlib.Path(p)] for p in args.inputs]

    app_reports: List[Dict[str, Any]] = []
    try:
        for group in app_groups:
            rep = scan_logical_app(
                group, inventory, cap_data, jdk_classes, matcher, scenario_map, limits
            )
            app_reports.append(rep)
    except ScannerError as exc:
        sys.stderr.write(f"ERROR: {exc}\n")
        return exc.exit_code

    agg = aggregate_reports(app_reports, inv_sha, cap_sha, scen_sha, jdk_home_str)

    if args.raw_dir:
        rdir = resolve_path(args.raw_dir)
        rdir.mkdir(parents=True, exist_ok=True)
        for rep in app_reports:
            cap_registry.write_deterministic_json(rdir / f"{rep['app_id']}.json", rep)

    agg_sha = sha256_bytes(cap_registry.canonical_json_bytes(agg))
    if args.out_json:
        out_json_path = resolve_path(args.out_json)
        agg_sha = cap_registry.write_deterministic_json(out_json_path, agg)

    wall_ms = (time.perf_counter() - t0) * 1000.0
    rss_bytes = peak_rss_bytes()
    telemetry = {
        "apps_scanned": len(app_reports),
        "wall_time_ms": round(wall_ms, 3),
        "peak_rss_bytes": rss_bytes,
        "peak_rss_mib": round(rss_bytes / (1024 * 1024), 3),
        "report_sha256": agg_sha,
    }

    if args.telemetry_out:
        cap_registry.write_deterministic_json(resolve_path(args.telemetry_out), telemetry)

    if args.out_md:
        out_md_path = resolve_path(args.out_md)
        out_md_path.parent.mkdir(parents=True, exist_ok=True)
        out_md_path.write_text(render_scan_markdown(agg, telemetry), encoding="utf-8")

    print(
        f"SCANNED {len(app_reports)} app(s) in {wall_ms:.2f} ms, "
        f"peak_rss={rss_bytes / (1024 * 1024):.2f} MiB, report_sha256={agg_sha[:16]}"
    )
    return EXIT_OK


if __name__ == "__main__":
    raise SystemExit(main())
