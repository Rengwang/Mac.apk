# 上游 Fork 整合与 Workbuddy 研判评估（2026-11-14）

## 1. 审计来源与工作区边界

- 审计来源：`/Users/xrdemac/Developer/Mac.apk-forkmerge`（分支 `codex/merge-upstream-forks-20261114`，参考文档 `docs/FORK_MERGE_2026-11-14.md`），汇总 `kksimp/Mac.apk` 上游 181 个公开 fork 中约 21 个独立活跃分支的通用兼容改动。
- 合入目标：`/Users/xrdemac/.gemini/antigravity/worktrees/Mac.apk-compat/mac_apk_architecture_upgrade`（分支 `mac_apk_architecture_upgrade`，基线 `3289cca`）。
- 本次构建与验证全程使用 `MACAPK_NO_BUNDLE_SYNC=1`，未改动 `main`、primary checkout、`/Applications/Mac.apk.app` 或 `~/Library/Application Support/Mac.apk/runtime`。

## 2. Workbuddy 判断准确性评估

### 准确的部分

1. **已合入的 24 个通用提交（本仓库 `96d8f40..6af1616`）**：均无包名/类名特判、与 `3289cca` 无硬冲突，涵盖 WebRTC `SIOCGIFFLAGS`、`android.resource:` 流、Pattern K `ByteBuffer.allocateDirect().hasArray()`、相机方向/镜像/尺寸、音频引擎重启、Touch ID 对话框与开关、大资源流式读取、ELF 可执行布局兼容、ANR 线程栈诊断、returning-view ANGLE rebind、`ConnectionService` 自管理通话、Capacitor WebView opt-in 拦截、section-stripped ELF 导出修复、鼠标指针抓取、Unicode manifest 标签 / Flutter Skia 兼容、附加 `Application`/`PackageInfo` 身份与 `OsConstants` `PROT_*` 去重。
2. **正确拒绝的分支**：`israel2176/*` / `CrossDarkrix/*`（Win.apk UCRT 跨平台分支）、`kushu7/*`（含 2.9 万行及 `public/fonts` 体积膨胀）、`Meskjei/*`（含 10 MB 字体与产品分支改动）、纯 CI/文档/imgbot 分支、`chuenlok/fix/apk-label-encoding`（已在 `Utf16PoolToUtf8()` 中实现）、`wishbone305/fix/native-queue-host-click` 与 `schinckel/main`（已在基线中包含）。
3. **正确暂缓的竞争性/高风险分支**：`surfacetexture-external-textures*`、`screen-camera`、`defia/codex/avc-codec-compat` 与 `defia/codex/guest-code-page-writes`（会与本分支 `SURFACE-001` 的 `SurfaceTexture`/`MediaCodec`/`cocoa_bridge` 及 SEGV/SIGBUS handler 发生直接结构冲突，保留暂缓）。

### 不准确/遗漏的 4 项判断

1. **`fork/defia/codex/pinduoduo-runtime-guards`（误判为包含应用专有分支而拒绝）**：
   - 核查完整 diff 后确认 **0 处包名/类名特判**。其 4 项改动（`MacApkUnsafe.getInt(addr == 0)` 空地址防护、`__macapk_arm64_strdup(NULL)` 返回 `NULL` + `EINVAL`、旧版 V8 `int` 长度 `SetFlagsFromString` 识别、`AppStubMain.swift` `!secondaryProcess` 判断）全部为通用 ABI/空指针防御，已合入（`ed8ff5d..dc97efd`）。
2. **`fork/ThePhilDiamond/media-video-target-follows-codec`（误判依赖已滞后分支而暂缓）**：
   - 核查源码确认 `1.0.2379` 已具备 `Surface.macapkGetSurfaceTexture()` 与 `SurfaceTexture.macapkRegisterVideoTarget()`，该提交（PR #147）只是在 `MediaCodec.configure`/`setOutputSurface` 时解绑旧输出 Surface 的 `SurfaceTexture` 目标，已合入（`ba7fb67`），并在合入后补充保留 `macapkGetOwnerSurfaceView() == null` 约束。
3. **`fork/defia/codex/per-app-proxy` / `corebluetooth-gatt` / `keystore-user-verification` 与 `host-tls-isolation` / `android-loader-reflection` / `native-startup-contracts` 未区分基线**：
   - Workbuddy 将前者与后者混列为“待评估未来分支”，未发现前三个分支的 merge-base 较旧且更新版本已进入 `1.0.2379`；而后三个分支基于 `v1.0.2379`（`1500431`）之后包含真实独立修复（其中 `android-loader-reflection` 的 `ArtReflection` 与 `BaseDexClassLoader`/`DexPathList` 实时路径修复正好覆盖 B 站 9.19.0 的 `static final Field` 反射场景），故精准 cherry-pick 了这 3 个分支的独立非合并提交（外加 `4b4d91e` 携带的 `MemoryDexGroup` 多 DEX 实时加载逻辑），未采用会误回退 `guest_jit_pages.h` 的整分支 diff。
4. **`fork/Louis-He/amap_support`（`FORK-002`）**：
   - 当前 `1.0.2379` 的 `MacApkNativeLoad` + `AppClassLoader.macapkLoadNative` 已具备按需 ELF 转译能力，仅非 `lib*.so` 命名的魔数 `0x7f ELF` 识别尚未单独补齐，已无需按“从零实现”重开重卡。

## 3. 本轮合入与冲突处理说明

- **`host-tls-isolation`（`2584fff..32a2a37`）**：在 `GmsHttp.java` 与 `McsClient.java` 中合并 `HostCrypto.Scope` 宿主 TLS 隔离与 `1.0.2379` 既有的 `HostProxy` 捕获代理传输；补回 `debug/GmsProxyCompatibilitySmoke.java` 并在 `debug/check_host_https.sh` 接入执行。
- **`android-loader-reflection`（`76df735..8b0b238`）**：整合 `9522dcc` + `4b4d91e`（`MemoryDexGroup` 多 DEX 实时加载）+ `a9bdff9` + `1a6c466`，解决 `ClassFileRewriter.java`、`BaseDexClassLoader.java`、`DexPathList.java`、`AppClassLoader.java` 冲突，完整保留 `HOTAPI-001` 的 Pattern K（`DIRECT_BUFFER_HAS_ARRAY`）、`registerAsParallelCapable()` 与 `addNativePath(Collection<String>)`。
- **实时多 DEX 与 DexVMP empty-stub 共存修复（`DexConverter.java`、`DexPathList.java`）**：主线 `7b421d9` 为静态 DexVMP `nop`-only 方法默认生成 `emitEmptyBody`（静默返回 `0`/`null`），导致 `LiveMemoryDexSmoke` 对尚未解密的零字节内存 DEX 期望 `ClassNotFoundException` 时失败。本轮新增 `DexConverter.openLive(byte[]...)` 并在 `DexPathList.MemoryDexGroup` 调用，使得**仅**实时可变内存 DEX 会拒绝空/未准备方法体并保持动态重试语义，同时完全保留静态 APK、`LazyDexSource` 与 `InMemoryDexClassLoader` 预转路径的 DexVMP 兼容行为。
- **`native-startup-contracts`（`a292c4a..23e8775`）**：无冲突合入延迟 `JNI_OnLoad`、`GuestRwx` 复制保护、内联 `faccessat`/`openat`、JNI 信号恢复及 guest 依赖符号保持修复。
- **`MediaCodec.java` 清理**：`routeVideoTo` 统一校验 `macapkGetOwnerSurfaceView() == null` 并去除 `configure` / `setOutputSurface` 中的重复注册逻辑。
- **台账与门禁同步**：更新 `docs/compat/feature_gates.json` 行号偏移、`docs/compat/inventory.json`、`docs/compat/capabilities.json`、`docs/compat/CAPABILITIES.md` 与 `docs/compat/HOT_API_STUBS.md`。

## 4. 核验记录（最后一次代码改动后复跑，全部 `rc=0`）

- 离线构建与打包：`MACAPK_NO_BUNDLE_SYNC=1 runtime/scripts/build.sh && MACAPK_NO_BUNDLE_SYNC=1 scripts/build_launcher.sh`、`relink_bundle_deps.sh`、`codesign --verify --deep --strict build/Mac.apk.app`（`CONVERTER_ID=c0207dd09bc1489b`、`FRAMEWORK_ID=aaa97e2cbf60ecea`、`JAVA_ID=b00dc90be994ac7b`、`JARS_ID=b71e63b2a010e69d`、`NATIVE_ID=ae244b9e92231152`）。
- 核心测试套：`python3 scripts/run_tests.py --group offline --strict` → `42 PASS, 0 FAIL, 0 SKIP, 15 NOT_RUN`（`rc=0`）；`python3 scripts/run_contract_tests.py --strict-xfail` → `14 PASS, 0 XFAIL, 0 REGRESSION`（`rc=0`）。
- 生成器与像素/Fixture：`codegen_check` → `2 PASS`（`rc=0`）；`gpu_pixel` → `7 PASS`（`rc=0`）；`DrawableIdentityAndTintTest` + `UpstreamPackageFeaturesTest`（`comp-ui-resources.apk`）→ `2 PASS`（`rc=0`）。
- 门禁与台账：`validate_gates.py --check-source-inventory` → `PASS (gates=75, locations=148, mismatches=5, errors=0)`；`debug/FeatureGatesTest.py` → `23 PASS, 0 FAIL, 4 NOT_RUN`；`python3 -m unittest debug/CapRegistryTest.py -v` → `12 tests OK`。
- 合入专项隔离烟雾测试（17 项全部 `rc=0`）：`check_live_memory_dex.sh`、`check_in_memory_dex_path.sh`、`check_loader_mutation_regressions.sh`、`check_review_loader.sh`、`check_host_https.sh`（含 `GmsProxyCompatibilitySmoke`）、`check_android_loader.sh`（含 `NativeReflectionSmoke` on bundled JRE）、`check_activity_package_routing.sh`、`check_application_context.sh`、`check_unsafe_null_address.sh`、`check_native_helper_layout.sh`、`check_arm64_strdup.sh`、`check_wrapper_dlsym.sh`、`check_deferred_jni_onload.sh`、`check_inline_file_syscalls.sh`、`check_guest_rwx_copy.sh`、`manifest_unicode_test.py`、`check_jni_signal_recovery.sh`。
- 未运行项：`installed_bundle`、`RemoteServiceProcessTest`（系统 broker daemon 环境）与 Sidecar/GUI 实景视频回归（待用户授权测试真实安装包或进入后续专项阶段）。
