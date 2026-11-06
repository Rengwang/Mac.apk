#!/usr/bin/env python3
"""Bounded macOS process CPU/RSS probe; CPU is process time, not energy.

Launch a command after --, wait for warmup, sample, and stop only that child.
With --system-metrics, also sample WindowServer CPU and Apple GPU utilization.
Raw app logs and samples may contain personal data: use an ignored output dir.
"""
import argparse
import json
import plistlib
import subprocess
import time
from datetime import datetime, timezone
from pathlib import Path


def cpu_seconds(value):
    days, clock = value.split('-', 1) if '-' in value else ('0', value)
    parts = [float(p) for p in clock.split(':')]
    total = 0.0
    for part in parts:
        total = total * 60 + part
    return int(days) * 86400 + total


def read_process(pid):
    result = subprocess.run(['ps', '-p', str(pid), '-o', 'time=,rss='],
                            capture_output=True, text=True, check=True)
    cpu, rss = result.stdout.split()
    return cpu_seconds(cpu), int(rss)


def find_pid(name):
    """Return one exact process name, or None when it is not available."""
    result = subprocess.run(['pgrep', '-x', name], capture_output=True, text=True)
    pids = [int(line) for line in result.stdout.splitlines() if line.isdigit()]
    return pids[0] if len(pids) == 1 else None


def read_apple_gpu():
    """Read public IORegistry utilization counters from the first accelerator."""
    try:
        raw = subprocess.run(
            ['ioreg', '-r', '-d', '1', '-c', 'IOAccelerator', '-a'],
            capture_output=True, check=True,
        ).stdout
        devices = plistlib.loads(raw)
        for device in devices if isinstance(devices, list) else [devices]:
            stats = device.get('PerformanceStatistics') if isinstance(device, dict) else None
            if isinstance(stats, dict):
                return {
                    'device_percent': stats.get('Device Utilization %'),
                    'renderer_percent': stats.get('Renderer Utilization %'),
                    'tiler_percent': stats.get('Tiler Utilization %'),
                }
    except (OSError, plistlib.InvalidFileException, subprocess.SubprocessError):
        pass
    return None


def utc_timestamp():
    return datetime.now(timezone.utc).isoformat(timespec='milliseconds')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--warmup', type=int, default=120)
    parser.add_argument('--duration', type=int, default=60)
    parser.add_argument('--system-metrics', action='store_true',
                        help='also record WindowServer CPU and Apple GPU utilization')
    parser.add_argument('command', nargs=argparse.REMAINDER)
    args = parser.parse_args()
    command = args.command[1:] if args.command[:1] == ['--'] else args.command
    if not command or args.warmup < 0 or args.duration < 1:
        parser.error('require a command, nonnegative warmup, positive duration')
    args.output.mkdir(parents=True, exist_ok=True)
    # Refuse accidental replacement of an earlier measurement.
    with (args.output / 'app.log').open('x') as log:
        started_at = utc_timestamp()
        child = subprocess.Popen(command, stdout=log, stderr=subprocess.STDOUT)
        print(f'PID={child.pid}', flush=True)
        rows = []
        error = None
        windowserver_pid = find_pid('WindowServer') if args.system_metrics else None
        try:
            deadline = time.monotonic() + args.warmup
            while time.monotonic() < deadline:
                if child.poll() is not None:
                    raise RuntimeError(f'child exited during warmup: {child.returncode}')
                time.sleep(min(1, max(0, deadline - time.monotonic())))
            previous_cpu, _ = read_process(child.pid)
            previous_windowserver_cpu = None
            if windowserver_pid is not None:
                try:
                    previous_windowserver_cpu, _ = read_process(windowserver_pid)
                except (OSError, subprocess.SubprocessError, ValueError):
                    windowserver_pid = None
            previous_time = time.monotonic()
            sample_started_at = utc_timestamp()
            for _ in range(args.duration):
                time.sleep(1)
                cpu, rss = read_process(child.pid)
                now = time.monotonic()
                sample_ended_at = utc_timestamp()
                row = {'elapsed_s': now - previous_time,
                       'cpu_percent': 100 * (cpu - previous_cpu) / (now - previous_time),
                       'rss_kib': rss,
                       'sample_started_at': sample_started_at,
                       'sample_ended_at': sample_ended_at}
                if windowserver_pid is not None:
                    windowserver_cpu, _ = read_process(windowserver_pid)
                    row['windowserver_cpu_percent'] = (
                        100 * (windowserver_cpu - previous_windowserver_cpu)
                        / (now - previous_time)
                    )
                    previous_windowserver_cpu = windowserver_cpu
                if args.system_metrics:
                    row['apple_gpu'] = read_apple_gpu()
                rows.append(row)
                previous_cpu, previous_time = cpu, now
                sample_started_at = sample_ended_at
            subprocess.run(['sample', str(child.pid), '3', '-file',
                            str(args.output / 'stack.sample.txt')], check=True,
                           stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        except Exception as exc:
            error = str(exc)
        finally:
            if child.poll() is None:
                child.terminate()
                try:
                    child.wait(timeout=15)
                except subprocess.TimeoutExpired:
                    child.kill()
                    child.wait()
            (args.output / 'result.json').write_text(json.dumps({
                'command': command, 'started_at': started_at,
                'warmup_s': args.warmup,
                'requested_samples': args.duration, 'samples': rows,
                'error': error,
                'scope': ('target process, WindowServer and system GPU utilization; '
                          'excludes children and energy' if args.system_metrics else
                          'one process only; excludes children, WindowServer, GPU and energy'),
            }, indent=2))
        if error:
            raise SystemExit(error)
        print(f'COMPLETE samples={len(rows)}', flush=True)


if __name__ == '__main__':
    main()
