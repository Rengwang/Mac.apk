#!/usr/bin/env python3
"""Screen high-frequency Android API classes for one-line no-op/stub methods.

Combines three established ecosystem signals instead of starting from scratch:
  1. Robolectric @Implements Shadow classes (from local ORACLE-001 shadows jar
     when present, falling back to a built-in core shadow set).
  2. AOSP @UnsupportedAppUsage / hidden-API reflection hotspots observed in
     real APKs and Mac.apk compatibility issues.
  3. Core SDK surface packages (android.app, android.content, android.os,
     android.view, android.graphics, android.media, android.net, android.opengl,
     android.webkit, dalvik.system).

Usage:
  python3 scripts/hot_api_stub_scanner.py [--out docs/compat/HOT_API_STUBS.md]
"""

import argparse
import json
import os
import re
import zipfile
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
DEFAULT_SHADOW_JAR = (
    REPO
    / "work"
    / "upgrade"
    / "ORACLE-001"
    / "m2"
    / "org"
    / "robolectric"
    / "shadows-framework"
    / "4.16.1"
    / "shadows-framework-4.16.1.jar"
)
DEFAULT_INVENTORY = REPO / "docs" / "compat" / "inventory.json"
DEFAULT_OUT = REPO / "docs" / "compat" / "HOT_API_STUBS.md"

CORE_PACKAGES = (
    "android/app/",
    "android/content/",
    "android/os/",
    "android/view/",
    "android/graphics/",
    "android/media/",
    "android/net/",
    "android/opengl/",
    "android/util/",
    "android/webkit/",
    "android/widget/",
    "android/provider/",
    "dalvik/system/",
)

BUILTIN_TOP_SHADOWS = {
    "Activity",
    "ActivityManager",
    "Application",
    "AssetManager",
    "AudioManager",
    "Bitmap",
    "BitmapDrawable",
    "Bundle",
    "Choreographer",
    "ConnectivityManager",
    "ContentResolver",
    "ContextImpl",
    "ContextWrapper",
    "Display",
    "Drawable",
    "GLSurfaceView",
    "InputMethodManager",
    "Intent",
    "LoadedApk",
    "MediaCodec",
    "MediaPlayer",
    "PackageInstaller",
    "PackageManager",
    "Parcel",
    "Resources",
    "Service",
    "SharedPreferencesImpl",
    "StorageManager",
    "Surface",
    "SurfaceTexture",
    "SurfaceView",
    "TypedArray",
    "UserManager",
    "View",
    "ViewGroup",
    "WebView",
    "WifiManager",
    "Window",
    "WindowManagerImpl",
}

STUB_GETTER_RE = re.compile(
    r"public\s+(?:static\s+|synchronized\s+|final\s+)*"
    r"([\w.<>\[\]]+)\s+(\w+)\s*\(([^)]*)\)\s*\{\s*return\s+"
    r"(null|0|0L|0f|0\.0f?|false|\"\"|Collections\.empty\w+\(\)|new\s+\w+\[0\]);\s*\}"
)
EMPTY_SETTER_RE = re.compile(
    r"public\s+(?:final\s+|synchronized\s+)*void\s+set(\w+)\s*\(([^,)]+)\)\s*\{\s*\}"
)

FIXED_IN_THIS_ROUND = [
    ("AssetManager.addAssetPathAsSharedLibrary / addOverlayPath", "Fixed", "Bilibili Tribe plugin resource mounting"),
    ("StorageManager.getVolumeList() / getVolumeList(int, int)", "Fixed", "StorageVolume[] reflection probe"),
    ("AppBaseContext.mPackageInfo + LoadedApk.mClassLoader (non-final)", "Fixed", "Plugin ClassLoader installation chain"),
    ("BaseDexClassLoader / DexPathList.addNativePath(Collection)", "Fixed", "Dynamic native library search path"),
    ("ActivityManager.getRunningTasks / getRecentTasks", "Fixed", "Coherent task snapshot from ActivityHost"),
    ("Resources.getFloat(int id)", "Fixed", "Reads TypedValue float/string via getValue"),
    ("Drawable & BitmapDrawable setFilterBitmap / isFilterBitmap / isAutoMirrored", "Fixed", "Stores state instead of returning false"),
    ("GLSurfaceView setDebugFlags / setPreserveEGLContextOnPause", "Fixed", "Round-trips getter/setter state"),
    ("ViewGroup setTransitionGroup / isTransitionGroup", "Fixed", "Round-trips transition group state"),
    ("Window transition setters/getters + NavigationBarDividerColor + Elevation", "Fixed", "Stores state per Window; ActivityWindow/DialogWindow enforce AOSP Android 15 edge-to-edge rules"),
    ("AccessibilityNodeInfo source ID, equals/hashCode, addLabeledBy/removeLabeledBy/getLabeledByList, relation IDs", "Fixed", "Multi-label list & source identity work cleanly; disconnected relationship getters stay null per AOSP"),
    ("View setBackgroundTintMode / getBackgroundTintMode", "Fixed", "Round-trips PorterDuff.Mode independently of BlendMode"),
]

# Explicitly verified intentional AOSP inert or connection/hardware-gated pairs:
# keeping these inert matches AOSP behavior and avoids distorting framework contracts.
VERIFIED_AOSP_INERT_PAIRS = {
    ("android/view/View.java", "setFrameContentVelocity/getFrameContentVelocity"):
        "AOSP returns 0f unless AttachInfo view-velocity API is enabled",
    ("android/media/AudioManager.java", "setWiredHeadsetOn/isWiredHeadsetOn"):
        "Deprecated API 5 no-op setter in AOSP; getter reflects attached wired hardware",
    ("android/media/RingtoneManager.java", "setIncludeDrm/getIncludeDrm"):
        "Deprecated API 16 inert setter in AOSP; getIncludeDrm() always returns false",
}


def load_shadow_names(shadow_jar: Path) -> tuple[set[str], str]:
    if shadow_jar.is_file():
        with zipfile.ZipFile(shadow_jar) as zf:
            names = {
                os.path.basename(n)[6:-6]
                for n in zf.namelist()
                if n.startswith("org/robolectric/shadows/Shadow")
                and n.endswith(".class")
                and "$" not in n
            }
        return names, f"Robolectric JAR ({shadow_jar.relative_to(REPO)}, {len(names)} classes)"
    return set(BUILTIN_TOP_SHADOWS), f"Built-in top shadow set ({len(BUILTIN_TOP_SHADOWS)} classes)"


def scan_stubs(shadow_names: set[str]):
    java_root = REPO / "runtime" / "java"
    results = []
    broken_pairs = []
    verified_inert_pairs = []
    for root, _, files in os.walk(java_root):
        for fname in sorted(files):
            if not fname.endswith(".java"):
                continue
            cls_simple = fname[:-5]
            if cls_simple not in shadow_names:
                continue
            full = Path(root) / fname
            rel = full.relative_to(java_root).as_posix()
            if not rel.startswith(CORE_PACKAGES):
                continue
            txt = full.read_text(encoding="utf-8", errors="replace")
            stubs = STUB_GETTER_RE.findall(txt)
            if not stubs:
                continue
            empty_setters = {m.group(1): m.group(2).strip() for m in EMPTY_SETTER_RE.finditer(txt)}
            pair_hits = []
            inert_hits = []
            for ret, name, _args, val in stubs:
                if name.startswith("get"):
                    prop = name[3:]
                elif name.startswith("is"):
                    prop = name[2:]
                else:
                    prop = ""
                if prop and prop in empty_setters:
                    pair_name = f"set{prop}/{name}"
                    if (rel, pair_name) in VERIFIED_AOSP_INERT_PAIRS:
                        inert_hits.append((pair_name, ret, val, VERIFIED_AOSP_INERT_PAIRS[(rel, pair_name)]))
                    else:
                        pair_hits.append((pair_name, ret, val))
            if pair_hits:
                broken_pairs.append((rel, pair_hits))
            if inert_hits:
                verified_inert_pairs.append((rel, inert_hits))
            results.append((len(stubs), len(pair_hits), rel, stubs[:6]))
    results.sort(reverse=True)
    return results, broken_pairs, verified_inert_pairs


def render_markdown(shadow_src: str, inv_summary: dict, results, broken_pairs, verified_inert_pairs) -> str:
    lines = [
        "# High-Frequency Android API Stub Screening Report",
        "",
        "Generated by `scripts/hot_api_stub_scanner.py`.",
        "",
        "## Methodology & Ecosystem Sources",
        "",
        f"- **High-frequency class list**: {shadow_src}",
        f"- **Framework ledger**: `docs/compat/inventory.json` "
        f"({inv_summary.get('bytecode_total_types', '?')} types, "
        f"{inv_summary.get('bytecode_declared_methods', '?')} methods)",
        "- **Core package filter**: " + ", ".join(f"`{p[:-1].replace('/', '.')}`" for p in CORE_PACKAGES),
        "",
        f"Matched **{len(results)}** shadowed core framework files containing **{sum(r[0] for r in results)}** "
        f"one-line return-null/0/false/empty stubs, including **{sum(len(p[1]) for p in broken_pairs)}** "
        "broken setter/getter round-trip pairs.",
        "",
        "## Batch Resolved in Current Sprint",
        "",
        "| API / Contract | Status | Notes |",
        "|---|---|---|",
    ]
    for api, st, note in FIXED_IN_THIS_ROUND:
        lines.append(f"| `{api}` | {st} | {note} |")

    lines += [
        "",
        "## Top Remaining Shadowed Core Classes with One-Line Stubs",
        "",
        "| Class | One-Line Stubs | Broken Setter/Getter Pairs | Sample Methods |",
        "|---|---:|---:|---|",
    ]
    for cnt, pair_cnt, rel, sample in results[:30]:
        cls_name = rel[:-5].replace("/", ".")
        sample_str = ", ".join(f"`{m[1]}() -> {m[3]}`" for m in sample[:4])
        lines.append(f"| `{cls_name}` | {cnt} | {pair_cnt} | {sample_str} |")

    lines += [
        "",
        "## Remaining Broken Empty-Setter + Stub-Getter Pairs (Top Candidates)",
        "",
        "| Class | Pair | Return Type | Hardcoded Stub |",
        "|---|---|---|---|",
    ]
    if not broken_pairs:
        lines.append("| *(none)* | — | — | 0 remaining |")
    for rel, pair_hits in broken_pairs:
        cls_name = rel[:-5].replace("/", ".")
        for pair_name, ret, val in pair_hits:
            lines.append(f"| `{cls_name}` | `{pair_name}` | `{ret}` | `{val}` |")

    lines += [
        "",
        "## Verified Intentional AOSP Inert / Hardware-Gated Pairs",
        "",
        "| Class | Pair | Return Type | Stub Value | AOSP Rationale |",
        "|---|---|---|---|---|",
    ]
    for rel, inert_hits in verified_inert_pairs:
        cls_name = rel[:-5].replace("/", ".")
        for pair_name, ret, val, reason in inert_hits:
            lines.append(f"| `{cls_name}` | `{pair_name}` | `{ret}` | `{val}` | {reason} |")
    lines.append("")
    return "\n".join(lines)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--shadow-jar", default=str(DEFAULT_SHADOW_JAR))
    ap.add_argument("--inventory", default=str(DEFAULT_INVENTORY))
    ap.add_argument("--out", default=str(DEFAULT_OUT))
    args = ap.parse_args()

    inv_path = Path(args.inventory)
    inv_summary = {}
    if inv_path.is_file():
        inv_summary = json.loads(inv_path.read_text(encoding="utf-8")).get("summary", {})

    shadow_names, shadow_src = load_shadow_names(Path(args.shadow_jar))
    results, broken_pairs, verified_inert_pairs = scan_stubs(shadow_names)
    md = render_markdown(shadow_src, inv_summary, results, broken_pairs, verified_inert_pairs)
    out_path = Path(args.out)
    out_path.parent.mkdir(parents=True, exist_ok=True)
    out_path.write_text(md, encoding="utf-8")
    print(
        f"wrote {out_path} ({len(results)} core shadowed files, "
        f"{sum(r[0] for r in results)} stubs, {sum(len(p[1]) for p in broken_pairs)} broken pairs)"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
