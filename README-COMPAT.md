> **历史开发记录**：此文件保留早期 DSH 工作，其中含脱壳/重打包应用的试验结论，不能代表当前原始 APK 兼容性。最新状态请见 [进度快照](docs/compat/STATUS-2026-10-28.md)。

# Mac.apk compatibility patches (DSH, 2026-10-26)

Upstream: https://github.com/kksimp/Mac.apk (AGPL-3.0)
Base: v1.0.2303, commit 25bc68b

## Patches

1. `Activity.macapkAttach`: fallback to base-context Resources and pin them in a
   `ContextThemeWrapper` for AppCompat.
2. `Application`: keep the first non-null Resources/Assets as
   `macapkPrimaryResources` / `macapkPrimaryAssets`.
3. `ContextWrapper.getResources/getAssets`: fall back to the Application's
   primary resources instead of returning null.
4. `ActivityThread.sCurrentActivityThread`: AOSP-compatible static field for
   Tinker/AppUtils reflection.
5. `DexConverter` / `Translator`: empty DexVMP method bodies become safe default
   returns (`RETURN/ARETURN/IRETURN/...`; constructors call `super.<init>()`)
   instead of dropping the whole class.
6. `Boot`: hide the host system proxy by default. Set
   `MACAPK_KEEP_SYSTEM_PROXY=1` to keep it.
7. `MacApkPackageManager`: per-app signature override from
   `~/Library/Application Support/Mac.apk/signature-overrides/<pkg>.bin`
   (DER X.509), applied in `readApkSignatures()` (the SELF path) and in
   `registryPackageInfo()` (other packages).

## Build

```bash
runtime/scripts/build.sh
```

Requirements: macOS 26.4+, Xcode CLT, Homebrew openjdk@21, cmake, capstone,
apktool. `thirdparty/framework-res/framework-res-api36.apk` can be copied from an
installed Mac.apk (`Contents/Resources/tools/framework-res.apk`).

## Install the runtime jars (without replacing the app bundle)

```bash
R="$HOME/Library/Application Support/Mac.apk/runtime/lib"
cp runtime/build/lib/android-framework.jar runtime/build/lib/macapk-loader.jar "$R/"
```

Back up the old jars first. Restart any running app.

## Signature override for re-signed APKs

```bash
./macapk-signature-override.sh original.apk [packageName]
```

This extracts `META-INF/*.RSA|DSA|EC` from the ORIGINAL APK to
`~/Library/Application Support/Mac.apk/signature-overrides/<pkg>.bin`.
It fixes native/Java checks that call
`PackageManager.getPackageInfo(pkg, GET_SIGNATURES)` and hash
`signatures[0]`.

## Validation (2026-10-26)

- Clean unpacked 公考雷达 v2 (no app-side patches, no libsign .so patch):
  proxy toast 0, page-lost 0, `hashcode error` 0, reaches MainActivity.
- 公考雷达 v24 also reaches MainActivity with the patched runtime.

## CoolApk 16.6.5

The 98 MB `classes.dex` contains a 97.9 MB encrypted payload. Re-dumping with
all filters removed produced 16 DEXes (~102 MB), but all are Pangle/ByteDance
plugin DEXes, with zero `Lcom/coolapk/` classes. frida-dexdump attach/spawn and
BlackDex 3.2 are both blocked by the packer. The remaining route is an
unhardened/older CoolApk build, or an ART-level dump on a permissive ROM.


## c001apk 1.3.5 (third-party CoolApk client) — verified working

Installed `~/Downloads/c001apk-1.3.5-arm64.apk` (`app.awavenue.c001apk`) with the
patched runtime. Flutter/Impeller renders through the native EGL window surface;
tabs, dynamic feed and detail navigation all work. Screenshot:
`~/Downloads/c001apk_Mac运行.png`; report: `~/Downloads/c001apk_Mac运行报告.md`.

This is a third-party client, not the official CoolApk. The official 16.6.5
(NetEase Yidun) and the "东明 16.6.4" mod (native `MainActivity.onCreate`) remain
unusable on Mac.apk.

## Official CoolApk 16.6.5 — running (2026-10-26)

Repacked the official 16.6.5 APK with 10 reconstructed DEXes (9 large anonymous
regions + one 1.9 MB region; DEX base is 4 bytes before the page boundary),
restored `com.coolapk.market.CoolMarketApplication`, removed libnesec, replaced
the native `CoolMarketApplication.onCreate()` with a Java call to its private
`ޏ()` init, removed Pangle/ad native libs (the SIGILL source), and signed with
the test key + original-certificate override.

Result: `/Applications/酷安.app` shows the real main feed, tabs, images and
comments and stays alive. Report: `~/Downloads/酷安16.6.5_官方脱壳运行报告.md`.

### Official CoolApk detail page fixed

`FeedDetailActivityV8.onCreate` only needs the intent's `extra_feed_id` (and the
reply/notice/api/history extras) copied into `ޑ/ޒ/ޓ/ޔ/ޕ`, then call the
existing `ྈ()` loader. It fetches the feed and mounts `FeedDetailFragmentV8`.
Together with the real `isTaskRoot` task tracking this makes the official detail
page render. Screenshot: `~/Downloads/酷安16.6.5_详情页_Mac运行.png`.

### Bilibili 9.16.1 status

Installs (33 dex / 216 MB) but hangs at startup in Bilibili's Gripper hook +
blcrash/Bugly native crash reporter (`InitMiuiFix hook failed`, then
`[blcrash] bl_crash_native_log before new jstring msg`, main parked).
Fixed along the way: `OsConstants.PROT_* / MAP_*`, `NativeOs.mmap`/`Os.mmap`,
and relinking the runtime to the bundled JRE. Report:
`~/Downloads/哔哩哔哩_Mac启动诊断.md`.
