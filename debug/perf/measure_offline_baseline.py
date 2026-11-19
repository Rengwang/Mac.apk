#!/usr/bin/env python3
"""Sequential offline/headless performance baseline runner for PERF-001.

Runs strictly sequentially (no concurrent builds/tasks) against the COMP-001
synthetic fixtures, collecting kernel rusage (/usr/bin/time -l) + Mac.apk
[boot-clock]/unpack/resource timings across warmup + >=3 rounds per workload.

Does NOT launch Cocoa GUI windows (requires MACAPK_WINDOW_FIRST=0 and
MACAPK_AWT_MAIN_INIT=0 for Boot.run headless self-tests) and NEVER modifies
/Applications/Mac.apk.app or ~/Library/Application Support/Mac.apk/runtime.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import shutil
import statistics
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


REPO_ROOT = Path(__file__).resolve().parents[2]
DEFAULT_OUT_DIR = REPO_ROOT / "work" / "upgrade" / "PERF-001"
DEFAULT_FIXTURE_DIR = REPO_ROOT / "work" / "upgrade" / "COMP-001" / "fixtures"
DEFAULT_RUNTIME_BIN = REPO_ROOT / "runtime" / "build" / "bin" / "macapk-runtime"


def dir_size_bytes(path: Path) -> int:
    if not path.exists():
        return 0
    if path.is_file():
        return path.stat().st_size
    total = 0
    for item in path.rglob("*"):
        if item.is_file() and not item.is_symlink():
            total += item.stat().st_size
    return total


def parse_time_l(text: str) -> dict[str, Any]:
    out: dict[str, Any] = {}
    m_time = re.search(r"^\s*([0-9.]+)\s+real\s+([0-9.]+)\s+user\s+([0-9.]+)\s+sys\b", text, re.M)
    if m_time:
        out["real_s"] = float(m_time.group(1))
        out["user_s"] = float(m_time.group(2))
        out["sys_s"] = float(m_time.group(3))

    int_patterns = {
        "max_rss_bytes": r"^\s*(\d+)\s+maximum resident set size\b",
        "peak_footprint_bytes": r"^\s*(\d+)\s+peak memory footprint\b",
        "page_reclaims": r"^\s*(\d+)\s+page reclaims\b",
        "page_faults": r"^\s*(\d+)\s+page faults\b",
        "vol_ctx_switches": r"^\s*(\d+)\s+voluntary context switches\b",
        "invol_ctx_switches": r"^\s*(\d+)\s+involuntary context switches\b",
        "instructions_retired": r"^\s*(\d+)\s+instructions retired\b",
        "cycles_elapsed": r"^\s*(\d+)\s+cycles elapsed\b",
    }
    for key, pat in int_patterns.items():
        m = re.search(pat, text, re.M)
        if m:
            out[key] = int(m.group(1))
    return out


def parse_runtime_markers(text: str) -> dict[str, Any]:
    markers: dict[str, Any] = {}
    m_dex = re.search(r"\[macapk-dex\]\s+(\d+)\s+classes.*?in\s+(\d+)\s+ms\b", text)
    if m_dex:
        markers["dex_classes"] = int(m_dex.group(1))
        markers["dex_to_jar_ms"] = int(m_dex.group(2))

    m_arsc = re.search(r"arsc tables parsed for this APK set in\s+(\d+)ms", text)
    if m_arsc:
        markers["arsc_parse_ms"] = int(m_arsc.group(1))

    m_fw_cold = re.search(r"framework resolution view:.*\b(\d+)ms from framework-res\.apk", text)
    m_fw_warm = re.search(r"framework resolution view: replayed .*? in\s+(\d+)ms", text)
    if m_fw_cold:
        markers["fw_view_ms"] = int(m_fw_cold.group(1))
        markers["fw_view_mode"] = "cold"
    elif m_fw_warm:
        markers["fw_view_ms"] = int(m_fw_warm.group(1))
        markers["fw_view_mode"] = "disk_cache"

    boot_clock: dict[str, int] = {}
    for m in re.finditer(r"\[boot-clock\]\s+\+(\d+)\s+ms\s+(.+)", text):
        label = m.group(2).strip()
        val = int(m.group(1))
        if label.startswith("main() entered"):
            boot_clock["main_entered_ms"] = val
        elif label.startswith("android root ready"):
            boot_clock["android_root_ready_ms"] = val
        elif label.startswith("JVM create begins"):
            boot_clock["jvm_create_begin_ms"] = val
        elif label.startswith("JVM created"):
            boot_clock["jvm_created_ms"] = val
        elif label.startswith("JNI natives registered"):
            boot_clock["jni_registered_ms"] = val
        elif label.startswith("bionic shim loaded"):
            boot_clock["bionic_shim_ms"] = val
        elif label.startswith("Boot.run entered"):
            boot_clock["boot_run_entered_ms"] = val
    if boot_clock:
        markers["boot_clock"] = boot_clock
    return markers


def summarize_numeric(values: list[float | int]) -> dict[str, float]:
    return {
        "min": float(min(values)),
        "median": float(statistics.median(values)),
        "max": float(max(values)),
        "spread": float(max(values) - min(values)),
    }


def summarize_rounds(rounds: list[dict[str, Any]]) -> dict[str, Any]:
    keys = [
        "real_s",
        "user_s",
        "sys_s",
        "max_rss_mib",
        "peak_footprint_mib",
        "vol_ctx_switches",
        "invol_ctx_switches",
        "cycles_elapsed",
        "instructions_retired",
    ]
    summary: dict[str, Any] = {}
    for k in keys:
        vals = [r["rusage"][k] for r in rounds if k in r["rusage"]]
        if vals:
            summary[k] = summarize_numeric(vals)

    marker_keys = ["dex_to_jar_ms", "arsc_parse_ms", "fw_view_ms"]
    for mk in marker_keys:
        vals = [r["markers"][mk] for r in rounds if mk in r["markers"]]
        if vals:
            summary[mk] = summarize_numeric(vals)

    bc_keys = set()
    for r in rounds:
        bc_keys.update(r["markers"].get("boot_clock", {}).keys())
    if bc_keys:
        summary["boot_clock"] = {}
        for bk in sorted(bc_keys):
            vals = [
                r["markers"]["boot_clock"][bk]
                for r in rounds
                if bk in r["markers"].get("boot_clock", {})
            ]
            if vals:
                summary["boot_clock"][bk] = summarize_numeric(vals)
    return summary


def run_timed_command(
    cmd: list[str],
    log_path: Path,
    env_overrides: dict[str, str] | None = None,
) -> dict[str, Any]:
    env = os.environ.copy()
    if env_overrides:
        env.update(env_overrides)
    full_cmd = ["/usr/bin/time", "-l", *cmd]
    proc = subprocess.run(
        full_cmd,
        cwd=str(REPO_ROOT),
        env=env,
        text=True,
        capture_output=True,
    )
    combined = (
        f"$ {' '.join(cmd)}\n"
        f"exit={proc.returncode}\n"
        f"--- STDOUT ---\n{proc.stdout}\n"
        f"--- STDERR ---\n{proc.stderr}\n"
    )
    log_path.parent.mkdir(parents=True, exist_ok=True)
    log_path.write_text(combined, encoding="utf-8")
    if proc.returncode != 0:
        raise RuntimeError(f"Benchmark command failed (exit {proc.returncode}): {log_path}")

    rusage = parse_time_l(proc.stderr)
    if "max_rss_bytes" in rusage:
        rusage["max_rss_mib"] = round(rusage["max_rss_bytes"] / (1024 * 1024), 2)
    if "peak_footprint_bytes" in rusage:
        rusage["peak_footprint_mib"] = round(rusage["peak_footprint_bytes"] / (1024 * 1024), 2)

    markers = parse_runtime_markers(proc.stdout + "\n" + proc.stderr)
    return {"exit_code": proc.returncode, "rusage": rusage, "markers": markers}


def run_benchmark_series(
    name: str,
    rounds_count: int,
    out_dir: Path,
    build_step,
) -> dict[str, Any]:
    rounds_dir = out_dir / "rounds" / name
    if rounds_dir.exists():
        shutil.rmtree(rounds_dir)
    rounds_dir.mkdir(parents=True, exist_ok=True)

    warmup_res = build_step(phase="warmup", idx=0, rounds_dir=rounds_dir)
    measured: list[dict[str, Any]] = []
    for idx in range(1, rounds_count + 1):
        res = build_step(phase="round", idx=idx, rounds_dir=rounds_dir)
        res["round"] = idx
        measured.append(res)

    return {
        "name": name,
        "warmup": warmup_res,
        "rounds": measured,
        "summary": summarize_rounds(measured),
    }


def collect_environment(runtime_bin: Path) -> dict[str, Any]:
    def cmd_out(cmd: list[str]) -> str:
        return subprocess.run(cmd, cwd=str(REPO_ROOT), text=True, capture_output=True, check=False).stdout.strip()

    build_id_path = runtime_bin.parents[1] / "build-id.txt"
    build_ids = {}
    if build_id_path.is_file():
        for line in build_id_path.read_text().splitlines():
            if "=" in line:
                k, v = line.split("=", 1)
                build_ids[k.strip()] = v.strip()

    lib_dir = runtime_bin.parents[1] / "lib"
    payload_bytes = {
        "macapk_runtime": (runtime_bin).stat().st_size if runtime_bin.exists() else 0,
        "libmacapk_bionic_shim": (runtime_bin.parent / "libmacapk-bionic-shim.dylib").stat().st_size
        if (runtime_bin.parent / "libmacapk-bionic-shim.dylib").exists()
        else 0,
        "framework_res_apk": (runtime_bin.parent / "tools" / "framework-res.apk").stat().st_size
        if (runtime_bin.parent / "tools" / "framework-res.apk").exists()
        else 0,
        "android_framework_jar": (lib_dir / "android-framework.jar").stat().st_size
        if (lib_dir / "android-framework.jar").exists()
        else 0,
        "macapk_loader_jar": (lib_dir / "macapk-loader.jar").stat().st_size
        if (lib_dir / "macapk-loader.jar").exists()
        else 0,
        "bcprov_jar": (lib_dir / "bcprov-jdk18on-1.79.jar").stat().st_size
        if (lib_dir / "bcprov-jdk18on-1.79.jar").exists()
        else 0,
        "runtime_build_total": dir_size_bytes(runtime_bin.parents[1]),
        "app_bundle_total": dir_size_bytes(REPO_ROOT / "build" / "Mac.apk.app"),
    }

    return {
        "recorded_at_utc": datetime.now(timezone.utc).isoformat(timespec="seconds"),
        "git_head": cmd_out(["git", "rev-parse", "HEAD"]),
        "git_branch": cmd_out(["git", "rev-parse", "--abbrev-ref", "HEAD"]),
        "hw_model": cmd_out(["sysctl", "-n", "hw.model"]),
        "cpu_brand": cmd_out(["sysctl", "-n", "machdep.cpu.brand_string"]),
        "ncpu": cmd_out(["sysctl", "-n", "hw.ncpu"]),
        "mem_bytes": int(cmd_out(["sysctl", "-n", "hw.memsize"]) or 0),
        "macos_version": cmd_out(["sw_vers", "-productVersion"]),
        "macos_build": cmd_out(["sw_vers", "-buildVersion"]),
        "power_source": cmd_out(["pmset", "-g", "batt"]),
        "thermal": cmd_out(["pmset", "-g", "therm"]),
        "build_ids": build_ids,
        "payload_bytes": payload_bytes,
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--out-dir", type=Path, default=DEFAULT_OUT_DIR)
    parser.add_argument("--fixture-dir", type=Path, default=DEFAULT_FIXTURE_DIR)
    parser.add_argument("--runtime-bin", type=Path, default=DEFAULT_RUNTIME_BIN)
    parser.add_argument("--rounds", type=int, default=3)
    args = parser.parse_args(argv)

    if args.rounds < 3:
        parser.error("--rounds must be >= 3 per PERFORMANCE.md")

    out_dir = args.out_dir.resolve()
    fixture_dir = args.fixture_dir.resolve()
    runtime_bin = args.runtime_bin.resolve()
    out_dir.mkdir(parents=True, exist_ok=True)

    # Extract media assets cleanly into out_dir once before timing rounds
    m4a_path = out_dir / "probe_tone.m4a"
    h264_path = out_dir / "probe_clip.h264"
    surf_apk = fixture_dir / "comp-surface-media.apk"
    subprocess.run(
        ["unzip", "-p", str(surf_apk), "assets/probe_tone.m4a"],
        stdout=m4a_path.open("wb"),
        check=True,
    )
    subprocess.run(
        ["unzip", "-p", str(surf_apk), "assets/probe_clip.h264"],
        stdout=h264_path.open("wb"),
        check=True,
    )

    workloads: dict[str, Any] = {}

    # 1. Cold --extract-only across all three COMP-001 fixtures
    for fx_name in ["comp-ui-resources", "comp-surface-media", "comp-service-ipc-abi"]:
        apk_path = fixture_dir / f"{fx_name}.apk"

        def run_extract_step(phase: str, idx: int, rounds_dir: Path, apk=apk_path, label=fx_name):
            root = rounds_dir / f"{phase}_{idx}_root"
            if root.exists():
                shutil.rmtree(root)
            root.mkdir(parents=True, exist_ok=True)
            log_path = rounds_dir / f"{phase}_{idx}.log"
            res = run_timed_command(
                [str(runtime_bin), "--apk", str(apk), "--android-root", str(root), "--extract-only"],
                log_path,
            )
            res["disk_bytes"] = {
                "apk": apk.stat().st_size,
                "android_root_total": dir_size_bytes(root),
                "code_dir": dir_size_bytes(root / "android" / ".macapk" / "code"),
                "data_app_dir": dir_size_bytes(root / "android" / "data" / "app"),
                "data_data_dir": dir_size_bytes(root / "android" / "data" / "data"),
            }
            return res

        workloads[f"extract_cold_{fx_name}"] = run_benchmark_series(
            f"extract_cold_{fx_name}", args.rounds, out_dir, run_extract_step
        )

    # 2. Cold vs Warm headless Boot.run + audio decode (AMEDIA_SELFTEST + MEDIA_SELFTEST)
    audio_env = {
        "MACAPK_WINDOW_FIRST": "0",
        "MACAPK_AWT_MAIN_INIT": "0",
        "MACAPK_AMEDIA_SELFTEST": "1",
        "MACAPK_AMEDIA_SELFTEST_FILE": str(m4a_path),
        "MACAPK_MEDIA_SELFTEST": "1",
        "MACAPK_MEDIA_SELFTEST_FILE": str(m4a_path),
    }

    def run_audio_cold(phase: str, idx: int, rounds_dir: Path):
        root = rounds_dir / f"{phase}_{idx}_root"
        if root.exists():
            shutil.rmtree(root)
        root.mkdir(parents=True, exist_ok=True)
        return run_timed_command(
            [str(runtime_bin), "--apk", str(surf_apk), "--android-root", str(root)],
            rounds_dir / f"{phase}_{idx}.log",
            env_overrides=audio_env,
        )

    workloads["boot_audio_cold"] = run_benchmark_series(
        "boot_audio_cold", args.rounds, out_dir, run_audio_cold
    )

    warm_audio_root = out_dir / "rounds" / "boot_audio_warm" / "shared_root"
    if warm_audio_root.exists():
        shutil.rmtree(warm_audio_root)
    warm_audio_root.mkdir(parents=True, exist_ok=True)

    def run_audio_warm(phase: str, idx: int, rounds_dir: Path):
        return run_timed_command(
            [str(runtime_bin), "--apk", str(surf_apk), "--android-root", str(warm_audio_root)],
            rounds_dir / f"{phase}_{idx}.log",
            env_overrides=audio_env,
        )

    workloads["boot_audio_warm"] = run_benchmark_series(
        "boot_audio_warm", args.rounds, out_dir, run_audio_warm
    )

    # 3. Cold vs Warm headless Boot.run + video decode (VIDEO_IMAGE_SELFTEST)
    def video_env(out_png: Path) -> dict[str, str]:
        return {
            "MACAPK_WINDOW_FIRST": "0",
            "MACAPK_AWT_MAIN_INIT": "0",
            "MACAPK_VIDEO_IMAGE_SELFTEST": "1",
            "MACAPK_VIDEO_IMAGE_SELFTEST_FILE": str(h264_path),
            "MACAPK_VIDEO_IMAGE_SELFTEST_OUT": str(out_png),
        }

    def run_video_cold(phase: str, idx: int, rounds_dir: Path):
        root = rounds_dir / f"{phase}_{idx}_root"
        if root.exists():
            shutil.rmtree(root)
        root.mkdir(parents=True, exist_ok=True)
        return run_timed_command(
            [str(runtime_bin), "--apk", str(surf_apk), "--android-root", str(root)],
            rounds_dir / f"{phase}_{idx}.log",
            env_overrides=video_env(rounds_dir / f"{phase}_{idx}_goi.png"),
        )

    workloads["boot_video_cold"] = run_benchmark_series(
        "boot_video_cold", args.rounds, out_dir, run_video_cold
    )

    warm_video_root = out_dir / "rounds" / "boot_video_warm" / "shared_root"
    if warm_video_root.exists():
        shutil.rmtree(warm_video_root)
    warm_video_root.mkdir(parents=True, exist_ok=True)

    def run_video_warm(phase: str, idx: int, rounds_dir: Path):
        return run_timed_command(
            [str(runtime_bin), "--apk", str(surf_apk), "--android-root", str(warm_video_root)],
            rounds_dir / f"{phase}_{idx}.log",
            env_overrides=video_env(rounds_dir / f"{phase}_{idx}_goi.png"),
        )

    workloads["boot_video_warm"] = run_benchmark_series(
        "boot_video_warm", args.rounds, out_dir, run_video_warm
    )

    # 4. Fixture harness timing via scripts/run_tests.py --group runtime_fixture
    ui_apk = fixture_dir / "comp-ui-resources.apk"

    def run_fixture_harness(phase: str, idx: int, rounds_dir: Path):
        test_out = rounds_dir / f"{phase}_{idx}_report"
        res = run_timed_command(
            [
                sys.executable,
                str(REPO_ROOT / "scripts" / "run_tests.py"),
                "--group",
                "runtime_fixture",
                "--apk",
                str(ui_apk),
                "--out-dir",
                str(test_out),
            ],
            rounds_dir / f"{phase}_{idx}.log",
        )
        rep = json.loads((test_out / "report.json").read_text(encoding="utf-8"))
        res["harness_counts"] = rep.get("counts", {})
        return res

    workloads["harness_runtime_fixture"] = run_benchmark_series(
        "harness_runtime_fixture", args.rounds, out_dir, run_fixture_harness
    )

    report = {
        "environment": collect_environment(runtime_bin),
        "rounds_per_workload": args.rounds,
        "workloads": workloads,
    }
    summary_path = out_dir / "summary.json"
    summary_path.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(f"PERF-001 summary written -> {summary_path}")
    for w_name, w_data in workloads.items():
        s = w_data["summary"]
        r = s.get("real_s", {})
        fp = s.get("peak_footprint_mib", {})
        rss = s.get("max_rss_mib", {})
        print(
            f"  {w_name:28s} real_s median={r.get('median', 0):.3f} (min={r.get('min', 0):.3f}, max={r.get('max', 0):.3f}) | "
            f"rss_mib median={rss.get('median', 0):.1f} | footprint_mib median={fp.get('median', 0):.1f}"
        )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
