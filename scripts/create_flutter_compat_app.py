#!/usr/bin/env python3
"""Create a development app using Skia, without changing the original APK.

The app links to this checkout's built runtime; rebuilds take effect next launch.
"""
import argparse
import hashlib
import json
from pathlib import Path
import plistlib
import shlex
import shutil
import subprocess


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apk', required=True, type=Path)
    parser.add_argument('--output', required=True, type=Path)
    parser.add_argument('--data-root', required=True, type=Path,
                        help='Android data tree to preserve across launches')
    parser.add_argument('--runtime', type=Path, default=Path(__file__).resolve().parents[1]
                        / 'runtime/build/bin/macapk-runtime')
    args = parser.parse_args()
    runtime = args.runtime.expanduser().resolve(strict=True)
    apk = args.apk.expanduser().resolve(strict=True)
    output = args.output.expanduser().absolute()
    if output.suffix != '.app' or output.exists():
        parser.error('--output must name a new .app bundle')
    metadata = json.loads(subprocess.check_output(
        [str(runtime), '--peek-manifest', str(apk)], text=True))
    label = metadata['label'] or metadata['package']
    data_root = args.data_root.expanduser().absolute()
    data_root.mkdir(parents=True, exist_ok=True)
    contents = output / 'Contents'
    macos = contents / 'MacOS'
    resources = contents / 'Resources'
    macos.mkdir(parents=True)
    resources.mkdir()
    shutil.copy2(apk, resources / 'original.apk')
    # The CLI treats a path not ending in "android" as a parent directory.
    # Use the same Contents/android link as installed Mac.apk app bundles.
    (contents / 'android').symlink_to(data_root, target_is_directory=True)
    (macos / '.Runtime').symlink_to(runtime)
    identity = hashlib.sha256(metadata['package'].encode()).hexdigest()[:16]
    info = {
        'CFBundleIdentifier': f'org.macapk.fluttercompat.{identity}',
        'CFBundleName': output.stem,
        'CFBundleDisplayName': output.stem,
        'CFBundleExecutable': 'Launcher',
        'CFBundlePackageType': 'APPL',
        'CFBundleVersion': '1',
        'CFBundleShortVersionString': '1.0',
        'NSHighResolutionCapable': True,
        'LSMinimumSystemVersion': '26.4',
    }
    with (contents / 'Info.plist').open('wb') as file:
        plistlib.dump(info, file)
    logs = Path.home() / 'Library/Logs/macapk'
    logs.mkdir(parents=True, exist_ok=True)
    log = logs / f'fluttercompat-{identity}.log'
    launcher = macos / 'Launcher'
    launcher.write_text(
        '#!/bin/sh\n'
        'contents_dir="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"\n'
        f'export MACAPK_APP_LABEL={shlex.quote(label)}\n'
        'export MACAPK_ANGLE_BACKEND=metal\n'
        "export MACAPK_LAUNCH_BOOLEAN_EXTRAS='enable-impeller=false'\n"
        f'export MACAPK_APP_CACHE_DIR={shlex.quote(str(data_root / ".macapk/code"))}\n'
        'exec "$contents_dir/MacOS/.Runtime" --apk "$contents_dir/Resources/original.apk" '
        '--android-root "$contents_dir/android" --width 688 --height 917 '
        f'> {shlex.quote(str(log))} 2>&1\n')
    launcher.chmod(0o755)
    print(output)
    print(f'Runtime: {runtime}')
    print(f'Log: {log}')


if __name__ == '__main__':
    main()
