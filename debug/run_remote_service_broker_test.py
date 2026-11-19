#!/usr/bin/env python3
"""Headless, leak-free multi-process broker test harness for RemoteServiceProcessTest.

Exercises GAP-IPC-003 end-to-end:
  1. Builds a temporary test.remote APK declaring RemoteServiceProcessTest$Worker
     under android:process=":worker" using local offline tools (javac + apktool).
  2. Registers a transient per-run LaunchAgent (org.macapk.system.test.<pid>.<rand>)
     serving com.macapk.loader.SystemServer over Mach bootstrap.
  3. Runs RemoteServiceProcessTest headlessly against that transient broker so
     SystemServer spawns macapk-runtime (:worker) with MACAPK_SERVICE_HOST=1.
  4. Verifies workerPid != clientPid, clientState == 17, workerState == 42,
     callerPid == clientPid, and zero GUI windows / leaked launchd jobs or PIDs.
"""

from __future__ import annotations

import argparse
import os
import pathlib
import plistlib
import re
import secrets
import shutil
import signal
import subprocess
import sys
import tempfile
import time
import zipfile


REPO_ROOT = pathlib.Path(__file__).resolve().parents[1]
DEFAULT_RUNTIME_BUILD_DIR = REPO_ROOT / "runtime" / "build"

SMALI_REMOTE_TEST = """\
.class public LRemoteServiceProcessTest;
.super Ljava/lang/Object;

.field static processSingleton:I

.method static constructor <clinit>()V
    .locals 1

    const/16 v0, 0x11
    sput v0, LRemoteServiceProcessTest;->processSingleton:I
    return-void
.end method

.method public constructor <init>()V
    .locals 0

    invoke-direct {p0}, Ljava/lang/Object;-><init>()V
    return-void
.end method
"""

SMALI_PROCESS_IDENTITY = """\
.class LRemoteServiceProcessTest$ProcessIdentity;
.super Ljava/lang/Object;

.method constructor <init>()V
    .locals 0

    invoke-direct {p0}, Ljava/lang/Object;-><init>()V
    return-void
.end method

.method static pid()I
    .locals 1

    invoke-static {}, Landroid/os/Process;->myPid()I
    move-result v0
    return v0
.end method
"""

SMALI_WORKER = """\
.class public LRemoteServiceProcessTest$Worker;
.super Landroid/app/Service;

.method public constructor <init>()V
    .locals 0

    invoke-direct {p0}, Landroid/app/Service;-><init>()V
    return-void
.end method

.method public onCreate()V
    .locals 1

    const/16 v0, 0x2a
    sput v0, LRemoteServiceProcessTest;->processSingleton:I
    return-void
.end method

.method public onBind(Landroid/content/Intent;)Landroid/os/IBinder;
    .locals 1

    new-instance v0, LRemoteServiceProcessTest$Worker$1;
    invoke-direct {v0, p0}, LRemoteServiceProcessTest$Worker$1;-><init>(LRemoteServiceProcessTest$Worker;)V
    return-object v0
.end method
"""

SMALI_WORKER_BINDER = """\
.class LRemoteServiceProcessTest$Worker$1;
.super Landroid/os/Binder;

.method constructor <init>(LRemoteServiceProcessTest$Worker;)V
    .locals 0

    invoke-direct {p0}, Landroid/os/Binder;-><init>()V
    return-void
.end method

.method protected onTransact(ILandroid/os/Parcel;Landroid/os/Parcel;I)Z
    .locals 1

    invoke-static {}, LRemoteServiceProcessTest$ProcessIdentity;->pid()I
    move-result v0
    invoke-virtual {p3, v0}, Landroid/os/Parcel;->writeInt(I)V

    sget v0, LRemoteServiceProcessTest;->processSingleton:I
    invoke-virtual {p3, v0}, Landroid/os/Parcel;->writeInt(I)V

    invoke-static {}, Landroid/os/Binder;->getCallingPid()I
    move-result v0
    invoke-virtual {p3, v0}, Landroid/os/Parcel;->writeInt(I)V

    const/4 v0, 0x1
    return v0
.end method
"""


def find_jdk_home(repo_root: pathlib.Path) -> pathlib.Path:
    selector = repo_root / "debug" / "jdk25_select.sh"
    proc = subprocess.run(["bash", str(selector)], capture_output=True, text=True)
    if proc.returncode != 0 or not proc.stdout.strip():
        raise RuntimeError(f"Failed to locate JDK via {selector}: {proc.stderr.strip()}")
    return pathlib.Path(proc.stdout.strip().splitlines()[-1])


def clean_env(isolated_home: pathlib.Path, overrides: dict[str, str] | None = None) -> dict[str, str]:
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


def resolve_apktool_framework_apk(runtime_build: pathlib.Path, scratch: pathlib.Path) -> pathlib.Path:
    cand = runtime_build / "bin" / "tools" / "framework-res.apk"
    if cand.is_file():
        return cand
    kegs = sorted(
        pathlib.Path("/opt/homebrew/Cellar/apktool").glob("*/libexec/apktool_*.jar"),
        reverse=True,
    )
    for jar in kegs:
        with zipfile.ZipFile(jar) as zf:
            if "prebuilt/android-framework.jar" in zf.namelist():
                out = scratch / "apktool-framework-res.apk"
                out.write_bytes(zf.read("prebuilt/android-framework.jar"))
                return out
    raise FileNotFoundError("Could not locate framework-res.apk or apktool prebuilt framework")


def build_worker_apk(
    apktool_bin: str,
    fw_res_apk: pathlib.Path,
    scratch: pathlib.Path,
) -> pathlib.Path:
    fw_dir = scratch / "apktool_fw"
    proj = scratch / "apk_proj"
    out_apk = scratch / "test-remote.apk"
    fw_dir.mkdir(parents=True, exist_ok=True)
    (proj / "res" / "values").mkdir(parents=True, exist_ok=True)
    (proj / "smali").mkdir(parents=True, exist_ok=True)

    if_proc = subprocess.run(
        [apktool_bin, "if", str(fw_res_apk), "-p", str(fw_dir)],
        capture_output=True,
        text=True,
    )
    if if_proc.returncode != 0:
        raise RuntimeError(f"apktool if failed: {if_proc.stderr or if_proc.stdout}")

    (proj / "AndroidManifest.xml").write_text(
        '<?xml version="1.0" encoding="utf-8"?>\n'
        '<manifest xmlns:android="http://schemas.android.com/apk/res/android"\n'
        '    package="test.remote" android:versionCode="1" android:versionName="1.0">\n'
        '    <uses-sdk android:minSdkVersion="24" android:targetSdkVersion="36" />\n'
        '    <application android:label="RemoteServiceProcessTest">\n'
        '        <service\n'
        '            android:name="RemoteServiceProcessTest$Worker"\n'
        '            android:exported="false"\n'
        '            android:process=":worker" />\n'
        '    </application>\n'
        '</manifest>\n',
        encoding="utf-8",
    )
    (proj / "res" / "values" / "strings.xml").write_text(
        '<?xml version="1.0" encoding="utf-8"?>\n<resources/>\n',
        encoding="utf-8",
    )
    (proj / "apktool.yml").write_text(
        "version: 3.0.3\n"
        "apkFileName: test-remote.apk\n"
        "sdkInfo:\n"
        "  minSdkVersion: 24\n"
        "  targetSdkVersion: 36\n"
        "versionInfo:\n"
        "  versionCode: 1\n"
        "  versionName: '1.0'\n"
        "resourcesInfo:\n"
        "  packageId: 127\n"
        "usesFramework:\n"
        "  ids:\n"
        "  - 1\n",
        encoding="utf-8",
    )

    smali_files = {
        "RemoteServiceProcessTest.smali": SMALI_REMOTE_TEST,
        "RemoteServiceProcessTest$ProcessIdentity.smali": SMALI_PROCESS_IDENTITY,
        "RemoteServiceProcessTest$Worker.smali": SMALI_WORKER,
        "RemoteServiceProcessTest$Worker$1.smali": SMALI_WORKER_BINDER,
    }
    for name, body in smali_files.items():
        (proj / "smali" / name).write_text(body, encoding="utf-8")

    b_proc = subprocess.run(
        [apktool_bin, "b", str(proj), "-p", str(fw_dir), "-o", str(out_apk)],
        capture_output=True,
        text=True,
    )
    if b_proc.returncode != 0 or not out_apk.is_file():
        raise RuntimeError(f"apktool b failed: {b_proc.stdout}\n{b_proc.stderr}")
    return out_apk


def pid_alive(pid: int) -> bool:
    if pid <= 1:
        return False
    try:
        os.kill(pid, 0)
        return True
    except ProcessLookupError:
        return False
    except PermissionError:
        return True


def find_scoped_pids(markers: list[str]) -> set[int]:
    """Find PIDs whose command line matches our per-test unique markers."""
    out: set[int] = set()
    proc = subprocess.run(["ps", "-axo", "pid=,command="], capture_output=True, text=True)
    if proc.returncode != 0:
        return out
    self_pid = os.getpid()
    for line in proc.stdout.splitlines():
        line = line.strip()
        if not line:
            continue
        parts = line.split(None, 1)
        if len(parts) < 2:
            continue
        try:
            pid = int(parts[0])
        except ValueError:
            continue
        if pid == self_pid:
            continue
        cmd = parts[1]
        if any(m in cmd for m in markers):
            out.add(pid)
    return out


def launchd_job_pid(job_target: str) -> int | None:
    proc = subprocess.run(["launchctl", "print", job_target], capture_output=True, text=True)
    if proc.returncode != 0:
        return None
    m = re.search(r"^\s*pid\s*=\s*(\d+)\b", proc.stdout, re.MULTILINE)
    return int(m.group(1)) if m else None


def terminate_pids(pids: set[int]) -> None:
    live = {p for p in pids if pid_alive(p)}
    if not live:
        return
    for p in live:
        try:
            os.kill(p, signal.SIGTERM)
        except ProcessLookupError:
            pass
    deadline = time.monotonic() + 3.0
    while time.monotonic() < deadline:
        live = {p for p in live if pid_alive(p)}
        if not live:
            return
        time.sleep(0.05)
    for p in live:
        try:
            os.kill(p, signal.SIGKILL)
        except ProcessLookupError:
            pass


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="Run RemoteServiceProcessTest headlessly against a transient launchd broker."
    )
    parser.add_argument(
        "--runtime-build-dir",
        type=pathlib.Path,
        default=DEFAULT_RUNTIME_BUILD_DIR,
        help=f"Built runtime directory (default: {DEFAULT_RUNTIME_BUILD_DIR})",
    )
    parser.add_argument(
        "--timeout",
        type=int,
        default=60,
        help="Client timeout in seconds (default: 60)",
    )
    args = parser.parse_args(argv)

    rt_build = args.runtime_build_dir.resolve()
    fw_jar = rt_build / "lib" / "android-framework.jar"
    loader_jar = rt_build / "lib" / "macapk-loader.jar"
    rt_bin = rt_build / "bin" / "macapk-runtime"
    binder_lib = rt_build / "bin" / "libmacapk-binder.dylib"
    test_src = REPO_ROOT / "debug" / "RemoteServiceProcessTest.java"

    missing = [str(p) for p in (fw_jar, loader_jar, rt_bin, binder_lib, test_src) if not p.is_file()]
    if missing:
        print(f"ERROR: missing required artifacts: {missing}", file=sys.stderr)
        return 1

    apktool_bin = shutil.which(os.environ.get("APKTOOL", "apktool"))
    if not apktool_bin:
        print("ERROR: apktool not found on PATH", file=sys.stderr)
        return 1

    jdk_home = find_jdk_home(REPO_ROOT)
    javac_bin = jdk_home / "bin" / "javac"
    java_bin = jdk_home / "bin" / "java"

    label = f"org.macapk.system.test.{os.getpid()}.{secrets.token_hex(4)}"
    domain = f"gui/{os.getuid()}"
    job_target = f"{domain}/{label}"
    bootstrapped = False
    tracked_pids: set[int] = set()
    exit_code = 1
    leaked = False

    with tempfile.TemporaryDirectory(prefix="macapk_remote_svc_test_") as td:
        tmp = pathlib.Path(td)
        isolated_home = tmp / "home"
        broker_root = tmp / "android_root"
        broker_logs = tmp / "broker_logs"
        client_classes = tmp / "client_classes"
        ready_file = tmp / "system-ready"
        broker_log = tmp / "broker.log"
        plist_path = tmp / f"{label}.plist"
        for d in (isolated_home, broker_root, broker_logs, client_classes):
            d.mkdir(parents=True, exist_ok=True)

        markers = [str(broker_root), label]
        try:
            c_proc = subprocess.run(
                [
                    str(javac_bin),
                    "-cp",
                    f"{fw_jar}:{loader_jar}",
                    "-d",
                    str(client_classes),
                    str(test_src),
                ],
                capture_output=True,
                text=True,
            )
            if c_proc.returncode != 0:
                print(f"FAIL: javac failed:\n{c_proc.stderr}", file=sys.stderr)
                exit_code = 1
                return exit_code

            fw_res_apk = resolve_apktool_framework_apk(rt_build, tmp)
            scratch_apk = build_worker_apk(apktool_bin, fw_res_apk, tmp)

            plist = {
                "Label": label,
                "Program": str(java_bin),
                "ProgramArguments": [
                    str(java_bin),
                    "-Djava.awt.headless=true",
                    f"-Duser.home={isolated_home}",
                    f"-Dmacapk.binder.lib={binder_lib}",
                    "-Xmx256m",
                    "-XX:+UseSerialGC",
                    "-XX:TieredStopAtLevel=1",
                    "--add-opens=java.base/java.io=ALL-UNNAMED",
                    "-cp",
                    f"{fw_jar}:{loader_jar}",
                    "com.macapk.loader.SystemServer",
                ],
                "MachServices": {label: True},
                "EnvironmentVariables": {
                    "HOME": str(isolated_home),
                    "PATH": os.environ.get("PATH", "/opt/homebrew/bin:/usr/bin:/bin:/usr/sbin:/sbin"),
                    "MACAPK_LOG_LEVEL": "7",
                    "MACAPK_SYSTEM_NAME": label,
                    "MACAPK_BROKER_APKS": str(scratch_apk),
                    "MACAPK_BROKER_RUNTIME": str(rt_bin),
                    "MACAPK_BROKER_ROOT": str(broker_root),
                    "MACAPK_BROKER_LOGDIR": str(broker_logs),
                    "MACAPK_BROKER_READY_FILE": str(ready_file),
                    "MACAPK_BROKER_BOOT_SYSTEM": "0",
                    "MACAPK_BROKER_IDLE_EXIT": "0",
                },
                "ProcessType": "Background",
                "StandardOutPath": str(broker_log),
                "StandardErrorPath": str(broker_log),
                "ThrottleInterval": 1,
            }
            with plist_path.open("wb") as f:
                plistlib.dump(plist, f)

            boot_proc = subprocess.run(
                ["launchctl", "bootstrap", domain, str(plist_path)],
                capture_output=True,
                text=True,
            )
            if boot_proc.returncode != 0:
                detail = (boot_proc.stderr or boot_proc.stdout).strip()
                print(f"SKIP: launchctl bootstrap unavailable ({detail})")
                exit_code = 2
                return exit_code
            bootstrapped = True

            client_env = clean_env(
                isolated_home,
                {
                    "MACAPK_LOG_LEVEL": "7",
                    "MACAPK_BROKER": "1",
                    "MACAPK_SYSTEM_NAME": label,
                },
            )
            run_proc = subprocess.run(
                [
                    str(java_bin),
                    "-Djava.awt.headless=true",
                    f"-Duser.home={isolated_home}",
                    f"-Dmacapk.binder.lib={binder_lib}",
                    "-cp",
                    f"{client_classes}:{fw_jar}:{loader_jar}",
                    "RemoteServiceProcessTest",
                ],
                cwd=str(REPO_ROOT),
                env=client_env,
                capture_output=True,
                text=True,
                timeout=args.timeout,
            )

            broker_pid = launchd_job_pid(job_target)
            if broker_pid:
                tracked_pids.add(broker_pid)
            tracked_pids.update(find_scoped_pids(markers))

            stdout = run_proc.stdout.strip()
            stderr = run_proc.stderr.strip()
            if stdout:
                print(stdout)
            if run_proc.returncode != 0:
                if stderr:
                    print(stderr, file=sys.stderr)
                if broker_log.is_file():
                    print("--- broker.log ---\n" + broker_log.read_text(errors="replace"), file=sys.stderr)
                for wf in sorted(broker_logs.glob("*.log")):
                    print(f"--- {wf.name} ---\n" + wf.read_text(errors="replace"), file=sys.stderr)
                print(f"FAIL: RemoteServiceProcessTest exited {run_proc.returncode}", file=sys.stderr)
                exit_code = 1
                return exit_code

            m = re.search(
                r"clientPid=(\d+)\s+workerPid=(\d+)\s+clientState=(\d+)\s+workerState=(\d+)\s+callerPid=(\d+)",
                stdout,
            )
            if not m or "PASS: worker process isolation and Binder caller identity" not in stdout:
                print(f"FAIL: unexpected RemoteServiceProcessTest output: {stdout!r}", file=sys.stderr)
                exit_code = 1
                return exit_code

            c_pid, w_pid, c_state, w_state, caller_pid = (int(x) for x in m.groups())
            tracked_pids.add(w_pid)
            if w_pid == c_pid or c_state != 17 or w_state != 42 or caller_pid != c_pid:
                print(f"FAIL: invariant violation in {m.group(0)}", file=sys.stderr)
                exit_code = 1
                return exit_code

            worker_log = broker_logs / "test.remote_worker.log"
            if not worker_log.is_file():
                print(f"FAIL: expected worker log missing at {worker_log}", file=sys.stderr)
                exit_code = 1
                return exit_code
            worker_text = worker_log.read_text(errors="replace")
            if "service host: no launcher activity" not in worker_text:
                print(f"FAIL: worker did not boot as headless service host:\n{worker_text}", file=sys.stderr)
                exit_code = 1
                return exit_code
            exit_code = 0
        finally:
            if bootstrapped:
                b_pid = launchd_job_pid(job_target)
                if b_pid:
                    tracked_pids.add(b_pid)
                tracked_pids.update(find_scoped_pids(markers))
                subprocess.run(["launchctl", "bootout", job_target], capture_output=True, text=True)
                time.sleep(0.2)
            tracked_pids.update(find_scoped_pids(markers))
            terminate_pids(tracked_pids)
            if bootstrapped:
                check_proc = subprocess.run(
                    ["launchctl", "print", job_target],
                    capture_output=True,
                    text=True,
                )
                if check_proc.returncode == 0:
                    leaked = True
                    print(f"ERROR: launchd job leaked after bootout: {job_target}", file=sys.stderr)
            surviving = find_scoped_pids(markers)
            if surviving:
                leaked = True
                print(f"ERROR: scoped test process(es) survived cleanup: {sorted(surviving)}", file=sys.stderr)
    return 1 if leaked else exit_code


if __name__ == "__main__":
    raise SystemExit(main())
