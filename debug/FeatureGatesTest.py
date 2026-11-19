#!/usr/bin/env python3
"""Process-isolated verification harness for init-sensitive and CLI feature gates (GATE-001)."""

from __future__ import annotations

import argparse
import json
import os
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path
from typing import Any

REPO_ROOT = Path(__file__).resolve().parent.parent
DEFAULT_RUNTIME_BUILD_DIR = REPO_ROOT / "runtime" / "build"

AUTOMATED_CASE_IDS = [
    "FG-SDK-001_default_no_apk",
    "FG-SDK-002_from_target_clamped",
    "FG-SDK-003_ceiling_clamp_34",
    "FG-SDK-004_ceiling_raise_37",
    "FG-SDK-005_from_target_optout",
    "FG-SDK-006_explicit_sdk_int_wins",
    "FG-SDK-007_sdk_int_above_max_known",
    "FG-SDK-008_sdk_int_invalid_fallback",
    "FG-LOG-001_unset_defaults",
    "FG-LOG-002_level_4_info",
    "FG-LOG-003_level_7_silent",
    "FG-LOG-004_level_2_verbose",
    "FG-LOG-005_invalid_fallback",
    "FG-SURF-001_aosp_surface_order",
    "FG-SURF-002_present_fastpath",
    "FG-SCHED-001_choreo_gates",
    "FG-CACHE-001_dexfile_define_in_loader",
    "FG-CACHE-002_fw_xml_cache",
    "FG-SYS-001_service_registry_gates",
    "FG-SYS-004_process_split",
    "FG-DEX-001_inner_class_public",
    "FG-CLI-001_peek_manifest",
    "FG-CLI-002_gles_scan",
]

NOT_RUN_CASES = [
    {
        "id": "FG-INST-001_launcher_panel",
        "status": "NOT_RUN",
        "reason": "Requires interactive GUI Control Panel Mac.apk.app; forbidden in automated headless GATE-001 check.",
    },
    {
        "id": "FG-INST-002_dock_stub",
        "status": "NOT_RUN",
        "reason": "Requires installed App Stub bundle launch (/Applications); installed-state check not executed.",
    },
    {
        "id": "FG-INST-003_system_broker",
        "status": "NOT_RUN",
        "reason": "Requires live launchd org.macapk.system LaunchAgent; user launchctl environment untouched.",
    },
    {
        "id": "FG-INST-004_experimental_live_render",
        "status": "NOT_RUN",
        "reason": "Live rendering pixel and frame-time verification of the 4 MACAPK_EXPERIMENTAL_* gates deferred to GATE-002.",
    },
]

JAVA_PROBE_SOURCE = r"""
import com.macapk.loader.Boot;
import com.macapk.loader.Manifest;
import com.macapk.loader.NativeLog;
import com.macapk.loader.PresentedSdk;
import com.macapk.loader.SystemClient;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;

public final class FeatureGateProbe {
    private static boolean readStaticBool(String clsName, String fieldName) throws Exception {
        Class<?> cls = Class.forName(clsName);
        Field f = cls.getDeclaredField(fieldName);
        f.setAccessible(true);
        return f.getBoolean(null);
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            throw new IllegalArgumentException("Usage: FeatureGateProbe <mode> [arg]");
        }
        String mode = args[0];
        switch (mode) {
            case "sdk": {
                int presented = PresentedSdk.get();
                boolean explicit = PresentedSdk.isExplicit();
                String rel = PresentedSdk.releaseString();
                int buildInt = android.os.Build.VERSION.SDK_INT;
                String buildRel = android.os.Build.VERSION.RELEASE;
                System.out.printf(
                    "{\"presented\":%d,\"explicit\":%s,\"release\":\"%s\",\"build_sdk_int\":%d,\"build_release\":\"%s\"}%n",
                    presented, explicit, rel, buildInt, buildRel
                );
                break;
            }
            case "log": {
                boolean nl0 = NativeLog.isLoggable(0);
                boolean nl1 = NativeLog.isLoggable(1);
                boolean nl2 = NativeLog.isLoggable(2);
                boolean nl3 = NativeLog.isLoggable(3);
                boolean nl4 = NativeLog.isLoggable(4);
                boolean nl6 = NativeLog.isLoggable(6);
                boolean al2 = android.util.Log.isLoggable("FG", 2);
                boolean al3 = android.util.Log.isLoggable("FG", 3);
                boolean al4 = android.util.Log.isLoggable("FG", 4);
                boolean al6 = android.util.Log.isLoggable("FG", 6);
                System.out.printf(
                    "{\"nl\":[%s,%s,%s,%s,%s,%s],\"al\":{\"2\":%s,\"3\":%s,\"4\":%s,\"6\":%s}}%n",
                    nl0, nl1, nl2, nl3, nl4, nl6, al2, al3, al4, al6
                );
                break;
            }
            case "surface_order": {
                boolean aospOrder = readStaticBool("android.view.SurfaceView", "sAospSurfaceOrder");
                System.out.printf("{\"aosp_surface_order\":%s}%n", aospOrder);
                break;
            }
            case "present_fastpath": {
                boolean liveArgb = readStaticBool("android.graphics.Bitmap", "LIVE_ARGB");
                System.out.printf("{\"bitmap_live_argb\":%s}%n", liveArgb);
                break;
            }
            case "choreo": {
                boolean vsyncThreads = readStaticBool("android.view.Choreographer", "VSYNC_THREADS");
                boolean nanotimeBase = readStaticBool("android.view.Choreographer", "NANOTIME_BASE");
                System.out.printf(
                    "{\"vsync_threads\":%s,\"nanotime_base\":%s}%n",
                    vsyncThreads, nanotimeBase
                );
                break;
            }
            case "dexfile": {
                boolean defineInLoader = readStaticBool("dalvik.system.DexFile", "DEFINE_IN_LOADER");
                System.out.printf("{\"define_in_loader\":%s}%n", defineInLoader);
                break;
            }
            case "fw_xml_cache": {
                boolean fwXmlCache = readStaticBool("android.content.res.Resources", "MACAPK_FW_XML_CACHE");
                System.out.printf("{\"fw_xml_cache\":%s}%n", fwXmlCache);
                break;
            }
            case "service_registry": {
                boolean bindAsync = readStaticBool("android.app.ServiceRegistry", "BIND_ASYNC");
                boolean stopAsync = readStaticBool("android.app.ServiceRegistry", "STOP_ASYNC");
                boolean reentryDefer = readStaticBool("android.app.ServiceRegistry", "REENTRY_DEFER");
                System.out.printf(
                    "{\"bind_async\":%s,\"stop_async\":%s,\"reentry_defer\":%s}%n",
                    bindAsync, stopAsync, reentryDefer
                );
                break;
            }
            case "process_split": {
                Path root = Path.of(args[1]);
                Files.createDirectories(root);
                Path xml = root.resolve("processes.xml");
                Files.writeString(xml,
                    "<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\" package=\"test.fg.process\">\n"
                    + "  <application android:process=\":ui\">\n"
                    + "    <service android:name=\"test.fg.process.Worker\" android:process=\":worker\"/>\n"
                    + "  </application>\n"
                    + "</manifest>\n"
                );
                Manifest multi = Manifest.parse(xml.toFile());
                Method chooseProc = Boot.class.getDeclaredMethod("chooseProcessName", Manifest.class, String.class);
                chooseProc.setAccessible(true);
                String procName = (String) chooseProc.invoke(null, multi, null);
                boolean sysEnabled = SystemClient.enabled();
                Method sysNameM = SystemClient.class.getDeclaredMethod("systemName");
                sysNameM.setAccessible(true);
                String sysName = (String) sysNameM.invoke(null);
                System.out.printf(
                    "{\"process_name\":\"%s\",\"system_client_enabled\":%s,\"system_name\":\"%s\"}%n",
                    procName, sysEnabled, sysName
                );
                break;
            }
            case "dex_inner_public": {
                boolean innerPublic = readStaticBool("com.macapk.dex.DexConverter", "INNER_CLASS_PUBLIC");
                System.out.printf("{\"inner_class_public\":%s}%n", innerPublic);
                break;
            }
            default:
                throw new IllegalArgumentException("Unknown mode: " + mode);
        }
    }
}
"""


def find_jdk_home(repo_root: Path) -> Path:
    selector = repo_root / "debug" / "jdk25_select.sh"
    proc = subprocess.run(
        ["bash", str(selector)],
        capture_output=True,
        text=True,
    )
    if proc.returncode != 0 or not proc.stdout.strip():
        raise RuntimeError(f"Failed to locate JDK via {selector}: {proc.stderr.strip()}")
    return Path(proc.stdout.strip().splitlines()[-1])


def clean_subprocess_env(isolated_home: Path, overrides: dict[str, str] | None = None) -> dict[str, str]:
    env = {
        k: v
        for k, v in os.environ.items()
        if not k.startswith("MACAPK_")
        and not k.startswith("DEX2JVM_")
        and k not in ("JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS")
    }
    env["HOME"] = str(isolated_home)
    env["USERPROFILE"] = str(isolated_home)
    if overrides:
        env.update(overrides)
    return env


def build_synthetic_apk(
    apktool_bin: str,
    fw_dir: Path,
    stage_root: Path,
    pkg: str,
    target_sdk: int,
    out_apk: Path,
) -> Path:
    proj = stage_root / f"proj_{target_sdk}"
    if proj.exists():
        shutil.rmtree(proj)
    (proj / "res" / "values").mkdir(parents=True, exist_ok=True)
    (proj / "AndroidManifest.xml").write_text(
        f'<?xml version="1.0" encoding="utf-8"?>\n'
        f'<manifest xmlns:android="http://schemas.android.com/apk/res/android"\n'
        f'    package="{pkg}" android:versionCode="1" android:versionName="1.0">\n'
        f'    <application android:label="FGTarget{target_sdk}"/>\n'
        f'</manifest>\n',
        encoding="utf-8",
    )
    (proj / "res" / "values" / "strings.xml").write_text(
        '<?xml version="1.0" encoding="utf-8"?>\n<resources/>\n',
        encoding="utf-8",
    )
    (proj / "apktool.yml").write_text(
        f"version: 3.0.3\n"
        f"apkFileName: {out_apk.name}\n"
        f"sdkInfo:\n"
        f"  minSdkVersion: 24\n"
        f"  targetSdkVersion: {target_sdk}\n"
        f"versionInfo:\n"
        f"  versionCode: 1\n"
        f"  versionName: '1.0'\n"
        f"resourcesInfo:\n"
        f"  packageId: 127\n"
        f"usesFramework:\n"
        f"  ids:\n"
        f"  - 1\n",
        encoding="utf-8",
    )
    proc = subprocess.run(
        [apktool_bin, "b", str(proj), "-p", str(fw_dir), "-o", str(out_apk)],
        capture_output=True,
        text=True,
    )
    if proc.returncode != 0 or not out_apk.is_file():
        raise RuntimeError(f"apktool failed building {out_apk}: {proc.stdout}\n{proc.stderr}")
    return out_apk


def run_probe_json(
    java_bin: Path,
    classpath: str,
    isolated_home: Path,
    env_overrides: dict[str, str],
    probe_args: list[str],
) -> tuple[dict[str, Any], dict[str, Any]]:
    env = clean_subprocess_env(isolated_home, env_overrides)
    cmd = [
        str(java_bin),
        "-Djava.awt.headless=true",
        f"-Duser.home={isolated_home}",
        "-cp",
        classpath,
        "FeatureGateProbe",
        *probe_args,
    ]
    proc = subprocess.run(cmd, env=env, capture_output=True, text=True, timeout=30)
    raw = {
        "cmd": cmd,
        "env_overrides": env_overrides,
        "exit_code": proc.returncode,
        "stdout": proc.stdout.strip(),
        "stderr": proc.stderr.strip(),
    }
    if proc.returncode != 0:
        raise RuntimeError(f"Probe failed (exit {proc.returncode}): {proc.stderr.strip() or proc.stdout.strip()}")
    lines = [ln.strip() for ln in proc.stdout.splitlines() if ln.strip().startswith("{")]
    if not lines:
        raise RuntimeError(f"No JSON output from probe: stdout={proc.stdout!r}, stderr={proc.stderr!r}")
    return json.loads(lines[-1]), raw


def run_native_cli_json(
    runtime_bin: Path,
    isolated_home: Path,
    cli_args: list[str],
) -> tuple[dict[str, Any], dict[str, Any]]:
    env = clean_subprocess_env(isolated_home)
    cmd = [str(runtime_bin), *cli_args]
    proc = subprocess.run(cmd, env=env, capture_output=True, text=True, timeout=30)
    raw = {
        "cmd": cmd,
        "env_overrides": {},
        "exit_code": proc.returncode,
        "stdout": proc.stdout.strip(),
        "stderr": proc.stderr.strip(),
    }
    if proc.returncode != 0:
        raise RuntimeError(f"CLI failed (exit {proc.returncode}): {proc.stderr.strip() or proc.stdout.strip()}")
    lines = [ln.strip() for ln in proc.stdout.splitlines() if ln.strip().startswith("{")]
    if not lines:
        raise RuntimeError(f"No JSON line in stdout: {proc.stdout!r}")
    return json.loads(lines[-1]), raw


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Run isolated subprocess feature gate checks.")
    parser.add_argument(
        "--runtime-build-dir",
        type=Path,
        default=DEFAULT_RUNTIME_BUILD_DIR,
        help=f"Path to built runtime directory (default: {DEFAULT_RUNTIME_BUILD_DIR})",
    )
    parser.add_argument(
        "--out-dir",
        type=Path,
        default=None,
        help="Optional directory to persist synthetic APKs and feature_gates_test_report.json.",
    )
    parser.add_argument("--list", action="store_true", help="List registered FG-* check IDs and exit.")
    parser.add_argument("--json", action="store_true", help="Print JSON report to stdout.")
    args = parser.parse_args(argv)

    if args.list:
        for cid in AUTOMATED_CASE_IDS:
            print(f"AUTO\t{cid}")
        for item in NOT_RUN_CASES:
            print(f"NOT_RUN\t{item['id']}\t{item['reason']}")
        return 0

    rt_dir = args.runtime_build_dir.resolve()
    fw_jar = rt_dir / "lib" / "android-framework.jar"
    loader_jar = rt_dir / "lib" / "macapk-loader.jar"
    rt_bin = rt_dir / "bin" / "macapk-runtime"
    fw_res_apk = rt_dir / "bin" / "tools" / "framework-res.apk"

    missing = [str(p) for p in (fw_jar, loader_jar, rt_bin, fw_res_apk) if not p.is_file()]
    if missing:
        print(f"ERROR: missing runtime build artifacts under {rt_dir}: {missing}", file=sys.stderr)
        return 2

    apktool_bin = shutil.which(os.environ.get("APKTOOL", "apktool"))
    if not apktool_bin:
        print("ERROR: apktool not found on PATH", file=sys.stderr)
        return 2

    jdk_home = find_jdk_home(REPO_ROOT)
    javac_bin = jdk_home / "bin" / "javac"
    java_bin = jdk_home / "bin" / "java"

    temp_ctx = None
    if args.out_dir is not None:
        work_root = args.out_dir.resolve()
        work_root.mkdir(parents=True, exist_ok=True)
    else:
        temp_ctx = tempfile.TemporaryDirectory(prefix="fg_test_")
        work_root = Path(temp_ctx.name)

    try:
        scratch = work_root / "_scratch"
        if scratch.exists():
            shutil.rmtree(scratch)
        classes_dir = scratch / "classes"
        src_dir = scratch / "src"
        fw_dir = scratch / "apktool_fw"
        stage_dir = scratch / "apk_stage"
        apk_dir = scratch / "apks"
        isolated_home = scratch / "isolated_home"
        for d in (classes_dir, src_dir, fw_dir, stage_dir, apk_dir, isolated_home):
            d.mkdir(parents=True, exist_ok=True)

        # Install framework-res into isolated apktool directory and build synthetic target36 & target37 APKs.
        if_proc = subprocess.run(
            [apktool_bin, "if", str(fw_res_apk), "-p", str(fw_dir)],
            capture_output=True,
            text=True,
        )
        if if_proc.returncode != 0:
            raise RuntimeError(f"apktool if failed: {if_proc.stderr}")

        apk24 = build_synthetic_apk(
            apktool_bin, fw_dir, stage_dir, "org.macapk.fg.target24", 24, apk_dir / "target24.apk"
        )
        apk36 = build_synthetic_apk(
            apktool_bin, fw_dir, stage_dir, "org.macapk.fg.target36", 36, apk_dir / "target36.apk"
        )
        apk37 = build_synthetic_apk(
            apktool_bin, fw_dir, stage_dir, "org.macapk.fg.target37", 37, apk_dir / "target37.apk"
        )

        # Compile Java probe against android-framework.jar + macapk-loader.jar.
        probe_src = src_dir / "FeatureGateProbe.java"
        probe_src.write_text(JAVA_PROBE_SOURCE, encoding="utf-8")
        cp = f"{classes_dir}:{fw_jar}:{loader_jar}"
        cproc = subprocess.run(
            [str(javac_bin), "-cp", f"{fw_jar}:{loader_jar}", "-d", str(classes_dir), str(probe_src)],
            capture_output=True,
            text=True,
        )
        if cproc.returncode != 0:
            raise RuntimeError(f"javac failed:\n{cproc.stderr}")

        results: list[dict[str, Any]] = []

        def run_case(case_id: str, fn: Any) -> None:
            try:
                evidence = fn()
                results.append({"id": case_id, "status": "PASS", "evidence": evidence})
            except Exception as exc:
                results.append({"id": case_id, "status": "FAIL", "error": str(exc)})

        # --- SDK/ABI isolated subprocess checks ---
        def c_sdk_001() -> dict[str, Any]:
            d, raw = run_probe_json(java_bin, cp, isolated_home, {}, ["sdk"])
            assert d["presented"] == 29 and d["build_sdk_int"] == 29, d
            assert d["explicit"] is False and d["build_release"] == "10", d
            return {"observed": d, "subprocess": raw}

        def c_sdk_002() -> dict[str, Any]:
            d36, r36 = run_probe_json(java_bin, cp, isolated_home, {"MACAPK_APK_PATH": str(apk36)}, ["sdk"])
            d24, r24 = run_probe_json(java_bin, cp, isolated_home, {"MACAPK_APK_PATH": str(apk24)}, ["sdk"])
            assert d36["presented"] == 36 and d36["build_sdk_int"] == 36, d36
            assert d36["explicit"] is True and d36["build_release"] == "16", d36
            assert d24["presented"] == 29 and d24["build_sdk_int"] == 29 and d24["explicit"] is True, d24
            return {"target36": d36, "target24_floor_clamped": d24, "subprocesses": [r36, r24]}

        def c_sdk_003() -> dict[str, Any]:
            d, raw = run_probe_json(
                java_bin, cp, isolated_home, {"MACAPK_APK_PATH": str(apk36), "MACAPK_SDK_CEILING": "34"}, ["sdk"]
            )
            assert d["presented"] == 34 and d["build_sdk_int"] == 34 and d["build_release"] == "14", d
            return {"observed": d, "subprocess": raw}

        def c_sdk_004() -> dict[str, Any]:
            d_def, r_def = run_probe_json(java_bin, cp, isolated_home, {"MACAPK_APK_PATH": str(apk37)}, ["sdk"])
            d_37, r_37 = run_probe_json(
                java_bin, cp, isolated_home, {"MACAPK_APK_PATH": str(apk37), "MACAPK_SDK_CEILING": "37"}, ["sdk"]
            )
            assert d_def["presented"] == 36 and d_def["build_sdk_int"] == 36, d_def
            assert d_37["presented"] == 37 and d_37["build_sdk_int"] == 37, d_37
            return {"default_ceiling_36": d_def, "raised_ceiling_37": d_37, "subprocesses": [r_def, r_37]}

        def c_sdk_005() -> dict[str, Any]:
            d, raw = run_probe_json(
                java_bin, cp, isolated_home, {"MACAPK_APK_PATH": str(apk36), "MACAPK_SDK_FROM_TARGET": "0"}, ["sdk"]
            )
            assert d["presented"] == 29 and d["build_sdk_int"] == 29 and d["explicit"] is False, d
            return {"observed": d, "subprocess": raw}

        def c_sdk_006() -> dict[str, Any]:
            d, raw = run_probe_json(
                java_bin,
                cp,
                isolated_home,
                {"MACAPK_APK_PATH": str(apk36), "MACAPK_SDK_INT": "31", "MACAPK_SDK_CEILING": "34"},
                ["sdk"],
            )
            assert d["presented"] == 31 and d["build_sdk_int"] == 31 and d["explicit"] is True, d
            assert d["build_release"] == "12", d
            return {"observed": d, "subprocess": raw}

        def c_sdk_007() -> dict[str, Any]:
            d, raw = run_probe_json(java_bin, cp, isolated_home, {"MACAPK_SDK_INT": "99"}, ["sdk"])
            assert d["presented"] == 37 and d["build_sdk_int"] == 37 and d["explicit"] is True, d
            return {"observed": d, "subprocess": raw}

        def c_sdk_008() -> dict[str, Any]:
            d, raw = run_probe_json(
                java_bin, cp, isolated_home, {"MACAPK_APK_PATH": str(apk36), "MACAPK_SDK_INT": "abc"}, ["sdk"]
            )
            assert d["presented"] == 29 and d["build_sdk_int"] == 29 and d["explicit"] is False, d
            return {"observed": d, "subprocess": raw}

        # --- Log level isolated subprocess checks ---
        def c_log_001() -> dict[str, Any]:
            d, raw = run_probe_json(java_bin, cp, isolated_home, {}, ["log"])
            assert d["nl"] == [True, True, True, True, True, True], d
            assert d["al"] == {"2": False, "3": False, "4": True, "6": True}, d
            return {"observed": d, "subprocess": raw}

        def c_log_002() -> dict[str, Any]:
            d, raw = run_probe_json(java_bin, cp, isolated_home, {"MACAPK_LOG_LEVEL": "4"}, ["log"])
            assert d["nl"] == [False, False, False, False, True, True], d
            assert d["al"] == {"2": False, "3": False, "4": True, "6": True}, d
            return {"observed": d, "subprocess": raw}

        def c_log_003() -> dict[str, Any]:
            d, raw = run_probe_json(java_bin, cp, isolated_home, {"MACAPK_LOG_LEVEL": "7"}, ["log"])
            assert d["nl"] == [False, False, False, False, False, False], d
            assert d["al"] == {"2": False, "3": False, "4": False, "6": False}, d
            return {"observed": d, "subprocess": raw}

        def c_log_004() -> dict[str, Any]:
            d, raw = run_probe_json(java_bin, cp, isolated_home, {"MACAPK_LOG_LEVEL": "2"}, ["log"])
            assert d["nl"] == [False, False, True, True, True, True], d
            assert d["al"] == {"2": True, "3": True, "4": True, "6": True}, d
            return {"observed": d, "subprocess": raw}

        def c_log_005() -> dict[str, Any]:
            d, raw = run_probe_json(java_bin, cp, isolated_home, {"MACAPK_LOG_LEVEL": "bogus"}, ["log"])
            assert d["nl"] == [True, True, True, True, True, True], d
            assert d["al"] == {"2": False, "3": False, "4": True, "6": True}, d
            return {"observed": d, "subprocess": raw}

        # --- Surface / Choreo / Cache / ServiceRegistry / ProcessSplit / Dex2Jvm ---
        def c_surf_001() -> dict[str, Any]:
            d_def, r_def = run_probe_json(java_bin, cp, isolated_home, {}, ["surface_order"])
            d_on, r_on = run_probe_json(
                java_bin, cp, isolated_home, {"MACAPK_AOSP_SURFACE_ORDER": "1"}, ["surface_order"]
            )
            d_off, r_off = run_probe_json(
                java_bin, cp, isolated_home, {"MACAPK_AOSP_SURFACE_ORDER": "0"}, ["surface_order"]
            )
            assert d_def["aosp_surface_order"] is False, d_def
            assert d_on["aosp_surface_order"] is True, d_on
            assert d_off["aosp_surface_order"] is False, d_off
            return {"default": d_def, "opt_in": d_on, "opt_out": d_off, "subprocesses": [r_def, r_on, r_off]}

        def c_surf_002() -> dict[str, Any]:
            d_def, r0 = run_probe_json(java_bin, cp, isolated_home, {}, ["present_fastpath"])
            d_fp0, r1 = run_probe_json(
                java_bin, cp, isolated_home, {"MACAPK_PRESENT_FASTPATH": "0"}, ["present_fastpath"]
            )
            d_la0, r2 = run_probe_json(
                java_bin, cp, isolated_home, {"MACAPK_PRESENT_LIVE_ARRAY": "0"}, ["present_fastpath"]
            )
            assert d_def["bitmap_live_argb"] is True, d_def
            assert d_fp0["bitmap_live_argb"] is False, d_fp0
            assert d_la0["bitmap_live_argb"] is False, d_la0
            return {"default": d_def, "fastpath_0": d_fp0, "live_array_0": d_la0, "subprocesses": [r0, r1, r2]}

        def c_sched_001() -> dict[str, Any]:
            d_def, r0 = run_probe_json(java_bin, cp, isolated_home, {}, ["choreo"])
            d_off, r1 = run_probe_json(
                java_bin,
                cp,
                isolated_home,
                {"MACAPK_CHOREO_VSYNC_THREADS": "0", "MACAPK_CHOREO_NANOTIME_BASE": "0"},
                ["choreo"],
            )
            assert d_def == {"vsync_threads": True, "nanotime_base": True}, d_def
            assert d_off == {"vsync_threads": False, "nanotime_base": False}, d_off
            return {"default": d_def, "opt_out": d_off, "subprocesses": [r0, r1]}

        def c_cache_001() -> dict[str, Any]:
            d_def, r0 = run_probe_json(java_bin, cp, isolated_home, {}, ["dexfile"])
            d_off, r1 = run_probe_json(
                java_bin, cp, isolated_home, {"MACAPK_DEXFILE_DEFINE_IN_LOADER": "0"}, ["dexfile"]
            )
            assert d_def["define_in_loader"] is True and d_off["define_in_loader"] is False, (d_def, d_off)
            return {"default": d_def, "opt_out": d_off, "subprocesses": [r0, r1]}

        def c_cache_002() -> dict[str, Any]:
            d_def, r0 = run_probe_json(java_bin, cp, isolated_home, {}, ["fw_xml_cache"])
            d_off, r1 = run_probe_json(
                java_bin, cp, isolated_home, {"MACAPK_FW_XML_CACHE": "0"}, ["fw_xml_cache"]
            )
            assert d_def["fw_xml_cache"] is True and d_off["fw_xml_cache"] is False, (d_def, d_off)
            return {"default": d_def, "opt_out": d_off, "subprocesses": [r0, r1]}

        def c_sys_001() -> dict[str, Any]:
            d_def, r0 = run_probe_json(java_bin, cp, isolated_home, {}, ["service_registry"])
            d_off, r1 = run_probe_json(
                java_bin,
                cp,
                isolated_home,
                {"MACAPK_SVC_BIND_ASYNC": "0", "MACAPK_SVC_STOP_ASYNC": "0", "MACAPK_SVC_REENTRY_DEFER": "0"},
                ["service_registry"],
            )
            assert d_def == {"bind_async": True, "stop_async": True, "reentry_defer": True}, d_def
            assert d_off == {"bind_async": False, "stop_async": False, "reentry_defer": False}, d_off
            return {"default": d_def, "opt_out": d_off, "subprocesses": [r0, r1]}

        def c_sys_004() -> dict[str, Any]:
            ps_dir = scratch / "ps_tmp"
            d_def, r0 = run_probe_json(
                java_bin, cp, isolated_home, {"MACAPK_LOG_LEVEL": "7"}, ["process_split", str(ps_dir)]
            )
            d_off, r1 = run_probe_json(
                java_bin,
                cp,
                isolated_home,
                {"MACAPK_LOG_LEVEL": "7", "MACAPK_PROCESS_SPLIT": "0"},
                ["process_split", str(ps_dir)],
            )
            assert d_def["process_name"] == "test.fg.process:ui", d_def
            assert d_def["system_client_enabled"] is True, d_def
            assert d_def["system_name"] == "org.macapk.processes", d_def
            assert d_off["process_name"] == "test.fg.process", d_off
            return {"default": d_def, "opt_out": d_off, "subprocesses": [r0, r1]}

        def c_dex_001() -> dict[str, Any]:
            d_def, r0 = run_probe_json(java_bin, cp, isolated_home, {}, ["dex_inner_public"])
            d_off, r1 = run_probe_json(
                java_bin, cp, isolated_home, {"DEX2JVM_INNER_CLASS_PUBLIC": "0"}, ["dex_inner_public"]
            )
            assert d_def["inner_class_public"] is True and d_off["inner_class_public"] is False, (d_def, d_off)
            return {"default": d_def, "opt_out": d_off, "subprocesses": [r0, r1]}

        def c_cli_001() -> dict[str, Any]:
            d, raw = run_native_cli_json(rt_bin, isolated_home, ["--peek-manifest", str(apk36)])
            assert d.get("package") == "org.macapk.fg.target36", d
            assert d.get("versionCode") == 1 and d.get("versionName") == "1.0", d
            assert d.get("label") == "FGTarget36", d
            return {"observed": d, "subprocess": raw}

        def c_cli_002() -> dict[str, Any]:
            d, raw = run_native_cli_json(rt_bin, isolated_home, ["--gles-scan", str(apk36)])
            assert d == {"es31": 0, "es1": 0}, d
            return {"observed": d, "subprocess": raw}

        dispatch = [
            ("FG-SDK-001_default_no_apk", c_sdk_001),
            ("FG-SDK-002_from_target_clamped", c_sdk_002),
            ("FG-SDK-003_ceiling_clamp_34", c_sdk_003),
            ("FG-SDK-004_ceiling_raise_37", c_sdk_004),
            ("FG-SDK-005_from_target_optout", c_sdk_005),
            ("FG-SDK-006_explicit_sdk_int_wins", c_sdk_006),
            ("FG-SDK-007_sdk_int_above_max_known", c_sdk_007),
            ("FG-SDK-008_sdk_int_invalid_fallback", c_sdk_008),
            ("FG-LOG-001_unset_defaults", c_log_001),
            ("FG-LOG-002_level_4_info", c_log_002),
            ("FG-LOG-003_level_7_silent", c_log_003),
            ("FG-LOG-004_level_2_verbose", c_log_004),
            ("FG-LOG-005_invalid_fallback", c_log_005),
            ("FG-SURF-001_aosp_surface_order", c_surf_001),
            ("FG-SURF-002_present_fastpath", c_surf_002),
            ("FG-SCHED-001_choreo_gates", c_sched_001),
            ("FG-CACHE-001_dexfile_define_in_loader", c_cache_001),
            ("FG-CACHE-002_fw_xml_cache", c_cache_002),
            ("FG-SYS-001_service_registry_gates", c_sys_001),
            ("FG-SYS-004_process_split", c_sys_004),
            ("FG-DEX-001_inner_class_public", c_dex_001),
            ("FG-CLI-001_peek_manifest", c_cli_001),
            ("FG-CLI-002_gles_scan", c_cli_002),
        ]
        for cid, fn in dispatch:
            run_case(cid, fn)

        for item in NOT_RUN_CASES:
            results.append(dict(item))

        counts = {
            "PASS": sum(1 for r in results if r["status"] == "PASS"),
            "FAIL": sum(1 for r in results if r["status"] == "FAIL"),
            "NOT_RUN": sum(1 for r in results if r["status"] == "NOT_RUN"),
            "TOTAL": len(results),
        }
        report = {
            "runtime_build_dir": str(rt_dir),
            "jdk_home": str(jdk_home),
            "apktool": apktool_bin,
            "counts": counts,
            "results": results,
        }

        if args.out_dir is not None:
            report_path = work_root / "feature_gates_test_report.json"
            report_path.write_text(json.dumps(report, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")

        if args.json:
            print(json.dumps(report, indent=2, ensure_ascii=False))
        else:
            for r in results:
                extra = f" -- {r.get('reason', r.get('error', ''))}" if r["status"] != "PASS" else ""
                print(f"[{r['status']}] {r['id']}{extra}")
            print(
                f"FeatureGatesTest: PASS={counts['PASS']} FAIL={counts['FAIL']} NOT_RUN={counts['NOT_RUN']}"
            )
            if args.out_dir is not None:
                print(f"Report: {work_root / 'feature_gates_test_report.json'}")

        return 0 if counts["FAIL"] == 0 else 1
    finally:
        if temp_ctx is not None:
            temp_ctx.cleanup()


if __name__ == "__main__":
    raise SystemExit(main())
