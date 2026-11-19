#!/usr/bin/env python3
"""Offline Android-contract suite runner & baseline checker (CONTRACT-001)."""
from __future__ import annotations

import argparse
import json
import os
import pathlib
import subprocess
import sys
import tempfile

REPO = pathlib.Path(__file__).resolve().parents[1]
DEFAULT_BASELINE = REPO / "docs" / "compat" / "contract_baseline.json"
DEFAULT_SOURCE = REPO / "debug" / "ContractSuiteTest.java"


def find_jdk() -> pathlib.Path:
    env_home = os.environ.get("JAVA_HOME")
    if env_home and (pathlib.Path(env_home) / "bin" / "javac").is_file():
        return pathlib.Path(env_home)
    for cand in [
        pathlib.Path("/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home"),
        pathlib.Path("/opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home"),
    ]:
        if (cand / "bin" / "javac").is_file():
            return cand
    raise FileNotFoundError("JDK 21 not found")


def evaluate(
    baseline_path: pathlib.Path,
    source_path: pathlib.Path,
    fw_jar: pathlib.Path,
    strict_xfail: bool = False,
    java_args: list[str] | None = None,
    out_dir: pathlib.Path | None = None,
) -> tuple[int, dict]:
    if not baseline_path.is_file():
        return 2, {"error": f"missing baseline file: {baseline_path}"}
    if not source_path.is_file():
        return 2, {"error": f"missing test source: {source_path}"}
    if not fw_jar.is_file():
        return 2, {"error": f"missing android-framework.jar: {fw_jar}"}

    baseline = json.loads(baseline_path.read_text(encoding="utf-8"))
    cases_meta = {c["id"]: c for c in baseline["cases"]}
    jdk = find_jdk()

    with tempfile.TemporaryDirectory(prefix="macapk_contract_") as td:
        cls_dir = pathlib.Path(td) / "classes"
        cls_dir.mkdir(parents=True, exist_ok=True)
        c_proc = subprocess.run(
            [str(jdk / "bin" / "javac"), "-cp", str(fw_jar), "-d", str(cls_dir), str(source_path)],
            cwd=str(REPO),
            text=True,
            capture_output=True,
        )
        if c_proc.returncode != 0:
            return 3, {"error": "javac failed", "stderr": c_proc.stderr}

        env = os.environ.copy()
        env["MACAPK_LOG_LEVEL"] = "7"
        cmd = [
            str(jdk / "bin" / "java"),
            "-Djava.awt.headless=true",
            "-cp",
            f"{cls_dir}:{fw_jar}",
            "ContractSuiteTest",
            *(java_args or []),
        ]
        r_proc = subprocess.run(cmd, cwd=str(REPO), env=env, text=True, capture_output=True)
        if r_proc.returncode != 0:
            return 4, {"error": f"java exited {r_proc.returncode}", "stderr": r_proc.stderr}

    observed: dict[str, dict] = {}
    for line in r_proc.stdout.splitlines():
        if not line.startswith("CONTRACT_CASE "):
            continue
        payload = line[len("CONTRACT_CASE "):]
        try:
            obj = json.loads(payload)
        except json.JSONDecodeError as e:
            return 5, {"error": f"malformed CONTRACT_CASE JSON: {e}", "line": line}
        if "id" not in obj or "status" not in obj:
            return 5, {"error": f"incomplete CONTRACT_CASE object: {obj}"}
        observed[obj["id"]] = obj

    if set(observed.keys()) != set(cases_meta.keys()):
        missing = sorted(set(cases_meta.keys()) - set(observed.keys()))
        extra = sorted(set(observed.keys()) - set(cases_meta.keys()))
        return 5, {"error": f"case set mismatch missing={missing} extra={extra}"}

    counts = {"PASS": 0, "XFAIL": 0, "XPASS": 0, "REGRESSION": 0}
    results = []
    exit_code = 0
    for cid, meta in cases_meta.items():
        obs = observed[cid]
        expected = meta["expected_status"]
        actual = obs["status"]
        detail = obs.get("detail", "")
        if expected == "PASS":
            if actual == "PASS":
                verdict = "PASS"
                counts["PASS"] += 1
            else:
                verdict = "REGRESSION"
                counts["REGRESSION"] += 1
                exit_code = 1
        elif expected == "XFAIL":
            if actual == "FAIL":
                substr = meta.get("expected_failure_substr", "")
                if substr and substr not in detail:
                    verdict = "REGRESSION"
                    counts["REGRESSION"] += 1
                    exit_code = 1
                elif strict_xfail:
                    verdict = "XFAIL_STRICT_FAIL"
                    counts["XFAIL"] += 1
                    exit_code = 1
                else:
                    verdict = "XFAIL"
                    counts["XFAIL"] += 1
            else:
                verdict = "XPASS"
                counts["XPASS"] += 1
        else:
            return 5, {"error": f"unknown expected_status={expected} for {cid}"}
        results.append({
            "id": cid,
            "capability_id": meta["capability_id"],
            "gap_id": meta["gap_id"],
            "expected": expected,
            "actual": actual,
            "verdict": verdict,
            "detail": detail,
        })

    report = {
        "android_compared": baseline["android_compared"],
        "aosp_anchor": baseline["aosp_anchor"],
        "counts": counts,
        "strict_xfail": strict_xfail,
        "results": results,
    }
    if out_dir is not None:
        out_dir.mkdir(parents=True, exist_ok=True)
        (out_dir / "contract_report.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    return exit_code, report


def run_self_test(fw_jar: pathlib.Path) -> int:
    base = json.loads(DEFAULT_BASELINE.read_text(encoding="utf-8"))
    exp_pass = sum(1 for c in base["cases"] if c["expected_status"] == "PASS")
    exp_xfail = sum(1 for c in base["cases"] if c["expected_status"] == "XFAIL")

    # 1. Normal verify must pass rc=0
    rc0, rep0 = evaluate(DEFAULT_BASELINE, DEFAULT_SOURCE, fw_jar, strict_xfail=False)
    if rc0 != 0 or rep0["counts"]["PASS"] != exp_pass or rep0["counts"]["XFAIL"] != exp_xfail:
        print(f"SELF-TEST FAIL: normal run rc={rc0} rep={rep0}", file=sys.stderr)
        return 1

    # 2. --strict-xfail must return non-zero when any XFAIL remains
    rc_strict, _ = evaluate(DEFAULT_BASELINE, DEFAULT_SOURCE, fw_jar, strict_xfail=True)
    if exp_xfail > 0 and rc_strict == 0:
        print("SELF-TEST FAIL: strict_xfail exited 0 despite XFAILs", file=sys.stderr)
        return 1

    # 3. Injected deterministic regression on PASS case must return non-zero
    rc_inj, rep_inj = evaluate(DEFAULT_BASELINE, DEFAULT_SOURCE, fw_jar, java_args=["--inject-regression"])
    if rc_inj == 0 or rep_inj["counts"]["REGRESSION"] < 1:
        print(f"SELF-TEST FAIL: injected regression exited {rc_inj}", file=sys.stderr)
        return 1

    # 4. Missing baseline/fixture file must return non-zero
    rc_miss, _ = evaluate(REPO / "docs" / "compat" / "NONEXISTENT_baseline.json", DEFAULT_SOURCE, fw_jar)
    if rc_miss == 0:
        print("SELF-TEST FAIL: missing baseline exited 0", file=sys.stderr)
        return 1

    # 5. Corrupted output format must return non-zero
    rc_bad, _ = evaluate(DEFAULT_BASELINE, DEFAULT_SOURCE, fw_jar, java_args=["--corrupt-output"])
    if rc_bad == 0:
        print("SELF-TEST FAIL: corrupt output exited 0", file=sys.stderr)
        return 1

    print(
        f"PASS: contract runner self-test "
        f"(baseline={exp_pass} PASS + {exp_xfail} XFAIL, strict_xfail!=0, inject!=0, missing!=0, corrupt!=0)"
    )
    return 0


def main() -> int:
    ap = argparse.ArgumentParser(description="Mac.apk offline Android contract runner (CONTRACT-001)")
    ap.add_argument("--baseline", type=pathlib.Path, default=DEFAULT_BASELINE)
    ap.add_argument("--source", type=pathlib.Path, default=DEFAULT_SOURCE)
    ap.add_argument("--fw-jar", type=pathlib.Path, default=REPO / "runtime" / "build" / "lib" / "android-framework.jar")
    ap.add_argument("--strict-xfail", action="store_true", help="Exit non-zero if any pinned XFAIL fails")
    ap.add_argument("--out-dir", type=pathlib.Path, default=None)
    ap.add_argument("--self-test", action="store_true", help="Verify negative fault-injection checks")
    args = ap.parse_args()

    if args.self_test:
        return run_self_test(args.fw_jar)

    rc, rep = evaluate(args.baseline, args.source, args.fw_jar, strict_xfail=args.strict_xfail, out_dir=args.out_dir)
    if "error" in rep:
        print(f"ERROR: {rep['error']}", file=sys.stderr)
        return rc
    for row in rep["results"]:
        print(f"[{row['verdict']:10s}] {row['id']:22s} ({row['capability_id']}) {row['detail']}")
    print(f"Summary: {rep['counts']} | android_compared={rep['android_compared']} | exit={rc}")
    return rc


if __name__ == "__main__":
    raise SystemExit(main())
