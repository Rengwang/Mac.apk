# Synthetic Compatibility Fixtures (`COMP-001`)

This directory contains original minimal synthetic Android fixture sources built offline for compatibility matrix baseline verification (`COMP-001`).

- **License / Provenance**: Original synthetic test code authored in this repository; zero third-party APKs, user data, or signing keys.
- **Builder**: `python3 debug/fixtures/build_comp_fixtures.py --out work/upgrade/COMP-001/fixtures`
- **Local Offline Tools Required**: `apktool`, `clang` (targeting `aarch64-linux-android29`), `ffmpeg`. No Android SDK (`d8`/`apksigner`) or network access required.
- **Git Hygiene**: Never commit built `.apk`, `.so`, `.o`, or runtime extraction directories (`work/upgrade/` is gitignored).

## Fixture Set

1. `ui_resources/` -> `comp-ui-resources.apk` (`org.macapk.comp.uires`, targetSdk 36)
   - Vector launcher icon (`@drawable/ic_launcher_probe`), >=2 distinct file drawable resources, `<uses-feature>` entries, strings/colors/styles, `activity_main.xml`, `MainActivity.smali`.
2. `surface_media/` -> `comp-surface-media.apk` (`org.macapk.comp.surfmedia`, targetSdk 36)
   - `SurfaceView` + `TextureView` + `Canvas` callback smali, `<uses-feature android:glEsVersion="0x00030000"/>`, plus generated synthetic `assets/probe_tone.m4a` and `assets/probe_clip.h264`.
3. `service_ipc_abi/` + `native/compprobe.c` -> `comp-service-ipc-abi.apk` (`org.macapk.comp.ipcabi`, targetSdk 36)
   - Isolated `<service android:process=":worker"/>`, `Binder`/`Parcel` transaction smali, `System.loadLibrary("compprobe")` + minimal freestanding ARM64 `ET_DYN` `lib/arm64-v8a/libcompprobe.so`.
