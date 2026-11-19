# Mac.apk 兼容性样本矩阵（COMP-001）

- **状态基点**：Git `e90ef146008d8d0335793d684b26be4588850bf4`（1.0.2379 基线构建 + `TEST-001` + `SEC-001`），仅覆盖本工作区真实可复现的离线合成样本（synthetic fixtures）。
- **机器可读索引**：[scenarios.json](scenarios.json)
- **合成构建入口**：[build_comp_fixtures.py](../../debug/fixtures/build_comp_fixtures.py)（构建说明见 [debug/fixtures/README.md](../../debug/fixtures/README.md)）
- **产物与隐私红线**：所有构建生成的 `.apk`、提取目录和运行日志仅输出至被 `.gitignore` 忽略的 `work/upgrade/COMP-001/`，不进入 Git；不使用用户本地真实 APK、账号缓存或敏感截图；不将上游 README/历史文档的宣传数或未创建建议（如 `SurfaceStackLab`）计为本分支已验证通过项。

## 环境与验证边界

- **显示器与会话状态**：仅连接内置视网膜屏 `Built-in Retina Display`（`1710x1112 pt @2x`，可用区 `1710x1074 pt`），会话未锁屏（`kCGSSessionOnConsoleKey: 1`），**未连接 iPad Sidecar 副屏**。
- **本轮执行范围**：在独立 `--android-root`（`work/upgrade/COMP-001/roots/<scenario>`）下运行无窗口 CLI 探针、`--extract-only` 安装解包/向量图标栅格化、`MACAPK_WINDOW_FIRST=0 MACAPK_AWT_MAIN_INIT=0` 下的音频/视频自检以及 `scripts/run_tests.py` 的 `runtime_fixture` / `bare_jvm` 回归检查；**未启动交互式 Cocoa GUI 窗口与完整 `macapk-system` broker 进程树**。
- **官方本地离线工具链**：`apktool 3.0.3` + 仓内校验过的 `thirdparty/framework-res/framework-res-api36.apk`（SHA-256 `aa6582552e614539ca85c0debd76a6283d80e26456895c829e11e755521d7cd4`）、`Apple clang 21.0.0`、`ffmpeg 7.1.5`。

## 样本构建复现命令

```bash
python3 debug/fixtures/build_comp_fixtures.py --out work/upgrade/COMP-001/fixtures
```

## 场景总表

> 结果严格区分：`PASS`（已运行且全部断言通过）、`PARTIAL`（核心安装/解包/离线探针通过，但交互 GUI 或跨进程 broker 等高阶运行路径标为 `NOT_RUN`/`SKIP`）、`FAIL`（运行失败）、`SKIP`/`NOT_RUN`（受运行模式或前置环境限制未运行）。

| 场景 ID | 类型 | 包名 / Target / Presented SDK | 产物大小 / SHA-256 | 离线探针与单元测试 | 交互 GUI / 完整 Broker | 场景总评 |
|---|---|---|---|---|---|---|
| `comp-ui-resources` | 常规 UI / 资源 / 特性清单 | `org.macapk.comp.uires`<br>`targetSdk=36` -> `presented=36` | `3937 B`<br>`f5506201ed15ecb3...` | `--peek-manifest`、`--gles-scan`、`--peek-window-policy`、`--extract-only`、`DrawableIdentityAndTintTest`、`UpstreamPackageFeaturesTest`、`ResourceIndexCacheKeyTest`、`UpstreamResourceAliasTest` 全 `PASS` | `NOT_RUN`（无 Sidecar / 无交互 GUI 启动） | `PARTIAL` |
| `comp-surface-media` | 多 Surface (`SurfaceView` + `TextureView`) / Canvas / 媒体 | `org.macapk.comp.surfmedia`<br>`targetSdk=36` -> `presented=36` | `10737 B`<br>`27a86ae8a8bc4602...` | CLI 探针、`--extract-only`、`UpstreamPackageFeaturesTest`、`AMEDIA_SELFTEST`、`MediaSelfTest`、`VideoImageSelfTest`、`MediaCodecDequeueTest`、`SurfaceTextureRoutingTest`、`SurfaceVideoTargetIsolationTest` 全 `PASS` | `NOT_RUN`（无 Sidecar / 实时合成显示帧未运行） | `PARTIAL` |
| `comp-service-ipc-abi` | 独立进程 Service (`:worker`) / Binder+Parcel / ARM64 JNI `.so` | `org.macapk.comp.ipcabi`<br>`targetSdk=36` -> `presented=36` | `7932 B`<br>`f99997d30187f299...` | CLI 探针、`--extract-only`（4 classes DEX->JAR、`services=1`、安装 `lib/arm64/libcompprobe.so` 且 `objdump -T` 导出 3 个 `.text` 动态符号）、`UpstreamPackageFeaturesTest`、`ParcelBinderTransportTest`、`ParcelWireTest` 全 `PASS` | `RemoteServiceProcessTest` `SKIP`；实时 JNI `dlopen` 与 `:worker` 进程绑定 `NOT_RUN` | `PARTIAL` |

---

## 逐场景详情与可复现步骤

### 1. `comp-ui-resources`（常规 UI / 资源 / 清单特性）

- **来源与许可**：[debug/fixtures/ui_resources](../../debug/fixtures/ui_resources)（仓内原创最小合成样本）。
- **独立数据目录**：`work/upgrade/COMP-001/roots/comp-ui-resources`
- **关键覆盖点**：`targetSdkVersion=36`、`minSdkVersion=24`、两个 `<uses-feature>` 声明（`touchscreen` 与 `leanback`）、`VectorDrawable` 启动图标（`@drawable/ic_launcher_probe`）、2 个额外文件型 drawable（`probe_badge.xml`、`probe_bg.xml`）、主题样式引用、以及 `MainActivity` 通过 `Resources.getIdentifier` 加载 `activity_main` 布局。
- **启动与验证命令**：
  ```bash
  APK=work/upgrade/COMP-001/fixtures/comp-ui-resources.apk
  ROOT=work/upgrade/COMP-001/roots/comp-ui-resources
  runtime/build/bin/macapk-runtime --peek-manifest "$APK"
  runtime/build/bin/macapk-runtime --gles-scan "$APK"
  runtime/build/bin/macapk-runtime --peek-window-policy "$APK"
  runtime/build/bin/macapk-runtime --apk "$APK" --android-root "$ROOT" --extract-only
  python3 scripts/run_tests.py --group runtime_fixture --apk "$APK" --out-dir work/upgrade/COMP-001/run_tests_fixture
  python3 scripts/run_tests.py --test ResourceIndexCacheKeyTest --test UpstreamResourceAliasTest --out-dir work/upgrade/COMP-001/run_tests_barejvm
  ```
- **清理命令**：`rm -rf work/upgrade/COMP-001/roots/comp-ui-resources`
- **实测结果**：
  - `--peek-manifest`（exit 0）：`{"package":"org.macapk.comp.uires","versionCode":100,"versionName":"1.0.0","label":"Comp UI Resources Probe","launcherOrientation":1,"iconRef":"0x7f020000"}`。
  - `--gles-scan`（exit 0）：`{"es31":0,"es1":0}`。
  - `--peek-window-policy`（exit 0）：`class=C1-free box=1710x1042pt (3420x2084px) universal=1 resizeable=1 targetSdk=36`。
  - `--extract-only`（exit 0）：`targetSdk=36 -> presenting 36`；ARSC 导入 10 行（2 strings、3 colors、1 style、4 file rows）；`Boot.extractIconOnly` 渲染 `VectorDrawable` 到 `icon.png`。
  - `scripts/run_tests.py --group runtime_fixture`（exit 0）：`DrawableIdentityAndTintTest` **PASS**（987.6ms）、`UpstreamPackageFeaturesTest` **PASS**（332.5ms）、`RemoteServiceProcessTest` **SKIP**（需完整 broker harness）。
  - `ResourceIndexCacheKeyTest`（291.8ms）与 `UpstreamResourceAliasTest`（280.5ms）：**PASS**（exit 0）。
  - 交互式 Cocoa 窗口与 Activity 视图布局实机显示：**NOT_RUN**（无 Sidecar / 本阶段不启动 GUI）。

---

### 2. `comp-surface-media`（多 Surface / Canvas / 合成音视频）

- **来源与许可**：[debug/fixtures/surface_media](../../debug/fixtures/surface_media)（仓内原创合成样本；内嵌 `ffmpeg` 生成的 `assets/probe_tone.m4a` 3223 B / SHA-256 `6ddca8d0...` 与 `assets/probe_clip.h264` 3463 B / SHA-256 `1062b941...`）。
- **独立数据目录**：`work/upgrade/COMP-001/roots/comp-surface-media`
- **关键覆盖点**：`MainActivity` 同时创建 `SurfaceView`（实现 `SurfaceHolder.Callback` 并在 `surfaceCreated` 中调用 `lockCanvas` / `Canvas.drawColor` / `drawCircle` / `unlockCanvasAndPost`）和 `TextureView`（实现 `SurfaceTextureListener`）；声明 `<uses-feature android:glEsVersion="0x00030000" android:required="false"/>` 与 `android.hardware.audio.output`；打包真实 AAC 音频与 Baseline H.264 Annex-B 视频流用于无窗口解码验证。
- **启动与验证命令**：
  ```bash
  APK=work/upgrade/COMP-001/fixtures/comp-surface-media.apk
  ROOT=work/upgrade/COMP-001/roots/comp-surface-media
  runtime/build/bin/macapk-runtime --peek-manifest "$APK"
  runtime/build/bin/macapk-runtime --gles-scan "$APK"
  runtime/build/bin/macapk-runtime --peek-window-policy "$APK"
  runtime/build/bin/macapk-runtime --apk "$APK" --android-root "$ROOT" --extract-only
  unzip -p "$APK" assets/probe_tone.m4a > work/upgrade/COMP-001/probe_tone.m4a
  unzip -p "$APK" assets/probe_clip.h264 > work/upgrade/COMP-001/probe_clip.h264
  MACAPK_WINDOW_FIRST=0 MACAPK_AWT_MAIN_INIT=0 \
    MACAPK_AMEDIA_SELFTEST=1 MACAPK_AMEDIA_SELFTEST_FILE=work/upgrade/COMP-001/probe_tone.m4a \
    MACAPK_MEDIA_SELFTEST=1 MACAPK_MEDIA_SELFTEST_FILE=work/upgrade/COMP-001/probe_tone.m4a \
    runtime/build/bin/macapk-runtime --apk "$APK" --android-root "$ROOT"
  MACAPK_WINDOW_FIRST=0 MACAPK_AWT_MAIN_INIT=0 \
    MACAPK_VIDEO_IMAGE_SELFTEST=1 MACAPK_VIDEO_IMAGE_SELFTEST_FILE=work/upgrade/COMP-001/probe_clip.h264 \
    MACAPK_VIDEO_IMAGE_SELFTEST_OUT=work/upgrade/COMP-001/goi_mine.png \
    runtime/build/bin/macapk-runtime --apk "$APK" --android-root "$ROOT"
  python3 scripts/run_tests.py --test UpstreamPackageFeaturesTest --apk "$APK" --out-dir work/upgrade/COMP-001/run_tests_surfmedia_feat
  python3 scripts/run_tests.py --test MediaCodecDequeueTest --test SurfaceTextureRoutingTest --test SurfaceVideoTargetIsolationTest --out-dir work/upgrade/COMP-001/run_tests_barejvm
  ```
- **清理命令**：`rm -rf work/upgrade/COMP-001/roots/comp-surface-media work/upgrade/COMP-001/probe_tone.m4a work/upgrade/COMP-001/probe_clip.h264 work/upgrade/COMP-001/goi_mine.png`
- **实测结果**：
  - `--peek-manifest`（exit 0）：`launcherOrientation=0`（landscape）。
  - `--gles-scan`（exit 0）：`{"es31":0,"es1":0}`。
  - `--peek-window-policy`（exit 0）：`class=C1-free box=1710x1042pt orientation=land targetSdk=36`。
  - `--extract-only`（exit 0）：`targetSdk=36 -> presenting 36`；渲染 `VectorDrawable` 到 `icon.png`。
  - `AMEDIA_SELFTEST` + `MediaSelfTest`（exit 0，无窗口运行）：原生 NDK 路径 `12288 frames rms=2776.2 PASS`；Java `MediaExtractor -> MediaCodec -> AudioToolbox` 路径 `12288 frames rms=2776 -> PASS`。
  - `VideoImageSelfTest`（exit 0，无窗口运行）：枚举 20 个编解码器（含 `OMX.macapk.dec.video/avc[video/avc,hw]`），经 VideoToolbox 解码 3 帧 64x64 YUV420，`motionDelta=8.74068... -> PASS`（写出 `goi_mine.png`；无外部 ffmpeg ref PNG 时跳过 refDelta 对比）。
  - `UpstreamPackageFeaturesTest`（322.8ms）及 `MediaCodecDequeueTest`、`SurfaceTextureRoutingTest`、`SurfaceVideoTargetIsolationTest`：**PASS**（exit 0）。
  - 多 Surface 同屏 Metal 图层合成与实时显示帧：**NOT_RUN**（无 Sidecar / 无交互 GUI 启动）。

---

### 3. `comp-service-ipc-abi`（隔离进程 Service / Binder+Parcel / ARM64 ABI）

- **来源与许可**：[debug/fixtures/service_ipc_abi](../../debug/fixtures/service_ipc_abi) + [debug/fixtures/native/compprobe.c](../../debug/fixtures/native/compprobe.c)（仓内原创合成样本与独立 C 原生探测库）。
- **独立数据目录**：`work/upgrade/COMP-001/roots/comp-service-ipc-abi`
- **关键覆盖点**：声明 `<service android:name=".WorkerService" android:exported="false" android:process=":worker"/>`；自定义 `WorkerBinder extends android.os.Binder` 覆写 `onTransact` 处理 `Parcel` 读写；`MainActivity` 通过 `ServiceConnection` 与 `IBinder.transact` 调用；静态初始化调用 `System.loadLibrary("compprobe")` 加载打包在 `lib/arm64-v8a/libcompprobe.so` 的独立编译 ARM64 `ET_DYN` ELF64 动态库（4704 B，SHA-256 `12e110f6...`，导出 `.text` 动态符号 `compprobe_add`、`JNI_OnLoad`、`Java_org_macapk_comp_ipcabi_MainActivity_nativeProbe`）。
- **启动与验证命令**：
  ```bash
  APK=work/upgrade/COMP-001/fixtures/comp-service-ipc-abi.apk
  ROOT=work/upgrade/COMP-001/roots/comp-service-ipc-abi
  runtime/build/bin/macapk-runtime --peek-manifest "$APK"
  runtime/build/bin/macapk-runtime --gles-scan "$APK"
  runtime/build/bin/macapk-runtime --peek-window-policy "$APK"
  runtime/build/bin/macapk-runtime --apk "$APK" --android-root "$ROOT" --extract-only
  objdump -T "$(find "$ROOT" -name libcompprobe.so | head -n 1)"
  python3 scripts/run_tests.py --test UpstreamPackageFeaturesTest --apk "$APK" --out-dir work/upgrade/COMP-001/run_tests_ipcabi_feat
  python3 scripts/run_tests.py --test ParcelBinderTransportTest --test ParcelWireTest --out-dir work/upgrade/COMP-001/run_tests_barejvm
  ```
- **清理命令**：`rm -rf work/upgrade/COMP-001/roots/comp-service-ipc-abi`
- **实测结果**：
  - `--peek-manifest`、`--gles-scan`、`--peek-window-policy`：**PASS**（exit 0）。
  - `--extract-only`（exit 0）：4 classes DEX->JAR 在 6ms 内完成；Manifest 解析记录 `services=1`；将 `lib/arm64-v8a/libcompprobe.so` 安装为 `lib/arm64/libcompprobe.so`（`0755`）；注：install-time 原生翻译仅在旧式 `_apkt/lib`（ARM32）存在时运行，ARM64 `.so` 采用启动期按需 `Image::load` 加载（日志写 `native libs to translate: no` 符合源码预期）。
  - `objdump -p -T -h libcompprobe.so`：**PASS**（`elf64-littleaarch64`、`PT_LOAD` RX/RW + `PT_DYNAMIC` + SysV `.hash` + 3 个 `.text` 全局函数符号）。
  - `UpstreamPackageFeaturesTest`（314.6ms）、`ParcelBinderTransportTest`（262.1ms）、`ParcelWireTest`（253.9ms）：**PASS**（exit 0）。
  - `RemoteServiceProcessTest`（`run_tests.py --group runtime_fixture`）：**SKIP**（需完整 `macapk-runtime + macapk-system` broker 夹具）。
  - 实时运行期 Guest ELF 加载（`arm64_native::Image::load`）与 `:worker` 进程实机 `bindService`：**NOT_RUN**（需交互启动 / 完整 broker 环境）。

---

## 明确未覆盖范围（Not Covered / Not Run）

| 栈 / 场景类别 | 当前状态 | 原因与后续衔接 |
|---|---|---|
| Jetpack Compose | 未覆盖（`NOT_COVERED`） | 合成样本文本不含 Compose runtime/Recomposer；待后续补充独立样本或在真实评估记录中单独建卡 |
| Flutter（`libflutter.so` + Impeller/Skia） | 未覆盖（`NOT_COVERED`） | 离线合成脚本不含 Flutter 引擎二进制 |
| Unity（`libunity.so` / IL2CPP） | 未覆盖（`NOT_COVERED`） | 离线合成脚本不含 Unity 引擎二进制 |
| Android WebView / Chromium | 未覆盖（`NOT_COVERED`） | 尚无 WebView 场景样本与离线验证页面 |
| ARM32（`armeabi-v7a`）翻译产物 | 未覆盖（`NOT_COVERED`） | `comp-service-ipc-abi` 仅构建了 `arm64-v8a` `ET_DYN`；ARM32 需单独构建 `armv7a` ELF 样本 |
| 用户真实第三方 APK | 未覆盖（`NOT_COVERED`） | 遵循保密与版权边界，不将真实 APK、账号缓存或截图纳入基线与 Git |
| 交互式 GUI、Sidecar 副屏渲染与真帧率测量 | 未运行（`NOT_RUN`） | 当前无 Sidecar 扩展屏连接且非 GUI 验收卡；渲染与呈现帧率由 [PERF-001](../tasks/PERF-001.md) 在约定显示条件下补充 |
| 尚未实现的构想工具（如 `SurfaceStackLab`） | 未纳入通过项 | 仅记录当前仓库实际存在的测试与样本，不把尚未创建的工具标为通过 |
