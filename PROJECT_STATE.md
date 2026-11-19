# 当前工程状态

更新日期：2026-11-18（Asia/Taipei）。本文件区分“已检查”“历史记录”和“本轮实测”。

## 当前基线与总览（`mac_apk_architecture_upgrade` @ `b6cbdda` + 暂停交接检查点：Slice A + Binder FIFO + 选窗/退出链路收口）

- **当前 worktree 与构建**：`/Users/xrdemac/.gemini/antigravity/worktrees/Mac.apk-compat/mac_apk_architecture_upgrade`，分支 `mac_apk_architecture_upgrade`（基于 `b6cbdda` Slice B，整合 Slice A 跨进程 `Surface`/VTB 时间戳契约、[binder_mach.cpp](runtime/src/binder_mach.cpp) oneway FIFO 保序、[MediaCodec.java](runtime/java/android/media/MediaCodec.java) `OutputTarget` 原子更新与锁外呈现/解码、[AutomationServer.java](runtime/java/com/macapk/loader/AutomationServer.java) `video_stats` 与 `NativeCocoa.exitProcess` 退出口、[SystemServer.java](runtime/java/com/macapk/loader/SystemServer.java) 包级 teardown 保护、[cocoa_bridge.mm](runtime/src/cocoa_bridge.mm) 无头免占位窗、[Boot.java](runtime/java/com/macapk/loader/Boot.java)/[SystemClient.java](runtime/java/com/macapk/loader/SystemClient.java) 无头预热并行、[egl14_bridges.inc](runtime/src/egl14_bridges.inc) `directKey` 登记与 [sidecar_runner.swift](debug/sidecar_runner.swift) 选窗/两阶段清零退出，并已清理临时诊断日志、复原 `AudioTrack.java`）；已通过 `MACAPK_FORCE_INSTALL=1 ./scripts/build_launcher.sh --promote` 原子替换 `/Applications/Mac.apk.app`（`261118.2159-b6cbdda+dirty`，`v1.0.2379`）、同步 `/Users/xrdemac/Library/Application Support/Mac.apk/runtime` 并确认 4 个已安装应用存根（含 `/Applications/tv.danmaku.bili.app`）为最新（`CONVERTER_ID=c0207dd09bc1489b`、`FRAMEWORK_ID=2ddf9b63646d86ff`、`JAVA_ID=54b1eca73eb8fc43`、`JARS_ID=d6f301f481d7af7e`、`NATIVE_ID=d9446ee83be26eec`），重编 `runtime/build/bin/sidecar_runner`（`rc=0`）。
- **本轮构建与离线/安装态验收证据（清理后当前代码实测）**：
  - `MACAPK_NO_BUNDLE_SYNC=1 ./runtime/scripts/build.sh`（`rc=0`）与 `swiftc -O -target arm64-apple-macos26.0 debug/sidecar_runner.swift -o runtime/build/bin/sidecar_runner`（`rc=0`）。
  - `python3 scripts/cap_registry.py generate --update-capabilities --output-md docs/compat/CAPABILITIES.md`（`rc=0`）：[inventory.json](docs/compat/inventory.json) `sha256=1511181e323a8d75`（`7018` 类型、`1094` JNI、`2325` bionic），[capabilities.json](docs/compat/capabilities.json) `sha256=b8654b7e97d85704`（`28` entries）；`python3 -m unittest debug/CapRegistryTest.py -v`（`rc=0`，`12 tests OK`）；[gaps.json](docs/compat/gaps.json) / [GAPS.md](docs/compat/GAPS.md) `total=18, observed=0, predicted=4, fixed=14`。
  - `python3 scripts/run_tests.py --test CrossProcessTimestampContractTest --test AutomationServerTest --test SurfaceTextureNativePixbufTest --test SurfaceParcelTransportTest --test TextureViewDirectVideoTest --test Egl10KeyedSurfaceViewTest --test BrokerServiceStartupTest --test AudioTrackClockMonotonicityTest --test MediaCodecEncoderTest --strict`（`rc=0`）：**`PASS=9, FLAKY_PASS=0, FAIL=0, SKIP=0, NOT_RUN=59`**（其中 [CrossProcessTimestampContractTest.java](debug/CrossProcessTimestampContractTest.java) 已修复 `PHASE_POST_RELEASE_DROP` `arg=1` 轮询 `ipcDroppedInvalid` 竞态；其余 59 项本命令未跑）。
  - `python3 scripts/run_tests.py --test installed_staleness_check --test installed_entitlements_check --allow-installed --strict`（`rc=0`）：**`PASS=2, FAIL=0, SKIP=0, NOT_RUN=66`**（注：因 `--promote` 已把 `build/Mac.apk.app` 移至 `/Applications/Mac.apk.app`，同日加跑 `worktree_*` 3 项呈 `SKIP=3` `missing local app bundle`）。
  - **已发现 FAIL 项**：`python3 scripts/run_tests.py --test RemoteServiceProcessTest --strict`（`rc=1`, `2508.2ms`）及 `python3 debug/run_remote_service_broker_test.py --runtime-build-dir runtime/build`（`rc=1`, `clientPid=22621 workerPid=22621 clientState=42 workerState=42 callerPid=22621`，`broker.log` 未见 `TX_BIND` 落回同进程），交接下一轮优先排查。
- **本轮暂停前 Sidecar 诊断进展与残余缺口（[VIDEO-PWR-001](docs/tasks/VIDEO-PWR-001.md) 保持 `IN_PROGRESS`）**：
  - 清理临时诊断打印与重构 `OutputTarget` **之后**的完整 20s Sidecar 实测、`GFX-001` 全屏旋转往返与 `DANMAKU-001` 弹幕 A/B 当前为 **`NOT_RUN`**。
  - 清理打印**前**的一轮 8.115s Sidecar trace 实测（`261118.2144-b6cbdda+dirty`, `Sidecar Display (AirPlay)` `displayID=4`, `wid=12825`）显示：`:ijkservice` 进 Cocoa runloop 缩至 `+7694 ms`（较 Wave-3 `11,289 ms` 降 ~3.6s）、`placeholder_shown_count=0`、`eglDirectSwaps=137`、`eglPbufferSwaps=0`、退出 0 残留、`total_underruns=0`（2×5s 共 932 次音频渲染，920 次非静音），但视频 `latchIntervalMs` 仍呈 ~107 ms 突发（`latchedFps=19.47`、`releaseFalse=33.89%`、`p95=116.55ms`、`max=138.98ms`）。
  - 已排除 `MediaCodec.mAsyncLock` 锁竞争并定位关键线索：`:ijkservice` 内 `ijk_ff_vout` 线程约 99% 采样停在 `libsystem_c.dylib` `nanosleep` 每 ~95–110 ms 醒来连放多帧（同时 VTB `live=18..23`、`enqueue_thread` 阻塞于 `dequeueInputBuffer`），待下一轮沿 `bionic_shim` `nanosleep`/`clock_gettime`/guest `av_usleep` 与 `ff_ffplay` 音频时钟继续深挖。
- 历史 Wave-1（`1c2b170`）、Wave-3（`ed9698e` + `2d1da57`）实测与更早台账见 [VIDEO-PWR-001](docs/tasks/VIDEO-PWR-001.md) 与 `git show 735f100:PROJECT_STATE.md`。

## 已完成阶段与任务（DONE）

- **P0/P1/P3 基建与评估（BASE-002 至 ARCH-001 / AOSP-EVAL-001）、PILOT-001、MEDIA-001、GATE-002 与 IPC-001 已完成（DONE）**：
  - `PILOT-001`：已修复 `ServiceManager.getServiceOrThrow`、`Bundle` API 33 类型校验、`PatternMatcher` `PATTERN_ADVANCED_GLOB` + `\.` 转义、`Intent.parseUri` `android-app://` 与 `AudioTrack.getMinBufferSize` 参数校验。
  - `MEDIA-001`：已修复 `VideoImageSelfTest` EOS 补发（消除 400x5ms 轮询等待，`eos_q=1, out_deq=5, out_try_again=3`）、`MediaCodec.asyncDispatchLoop` 条件唤醒优化、`MediaExtractor` 边界与非 `file://` URI 校验（`CTR-MEDIA-XFAIL-001` 翻转为 `PASS`）。
  - `GATE-002`：已对齐 4 个 `MACAPK_EXPERIMENTAL_*` 表面/调度开关为 runtime 默认开启（`!= "0"`）并保留 `=0` opt-out；`AppStubMain.swift` 同步清洗 9 个测试专用环境变量；清理 `NativeLog.java`、`Log.java`、`native_methods.cpp` 过时 level 5 注释并对齐说明；修正 `PresentedSdk.java` 中与现行实现矛盾的回退旧注释与日志；统一 `MACAPK_EXPERIMENTAL_ALPHA_PROBE` 为严格 `strcmp(..., "1") == 0` 判定。
  - `IPC-001`：已修复 `Binder.getCallingUidOrThrow` 事务与显式身份契约（非事务上下文抛 `IllegalStateException`，`transact()` / `onTransact()` 或 `clearCallingIdentity()` 内正常返回 UID）；`MacApkBinderTransport` 跨进程派发同步维护事务深度；**至此 `contract_baseline.json` 全部 14 项 Android 契约测试 100% PASS**（`14 PASS, 0 XFAIL, 0 REGRESSION`）。
- **[SURFACE-001](docs/tasks/SURFACE-001.md) 已完成**：EGL 回读提交核对已有连接身份，拒绝旧连接的迟到帧；Surface 释放其私有 EGL 流，排队缓冲回收一次。离线 42 PASS，GPU/JNI 2 PASS，无窗口视频自检 5 帧 PASS。
- **[SCHED-001](docs/tasks/SCHED-001.md) 已完成**：主线程 Choreographer 按 token 取消并按 phase / deadline 派发，离线 42 PASS。
- **[RES-001](docs/tasks/RES-001.md) 已完成**：实现未压缩原始资源的 APK 文件描述符读取，支持资源别名及有界输入流。离线 42 PASS。
- **[SURFACE-002](docs/tasks/SURFACE-002.md) 已完成**：旧 Cocoa IOSurface 回调携带原 ring 到事务结束，回收时隔离同 key 重建后的 slot。
- **[SURFACE-003](docs/tasks/SURFACE-003.md) 已完成**：Cocoa 在宿主或 drawable 代际变化时淘汰已排队的旧呈现，几何与 contents 同一事务提交。
- **[GPU-001](docs/tasks/GPU-001.md) 已完成**：透明像素上载前转换改为 ARM64 NEON，微基准 0.9521 → 0.3080 ms/帧，像素结果通过。
- **[CACHE-001](docs/tasks/CACHE-001.md) 已完成**：解包元数据复用一个资源表读取，热启动 Android root 阶段 38 → 33.5 ms，图标和元数据一致。
- **[GC-001](docs/tasks/GC-001.md) 评估已完成**：短负载 G1 暂停/RSS 较低，保留 Serial 默认。
- **上游 fork 通用修复整合已完成（FORK-MERGE-2026-11-14，详见 [整合评估](docs/FORK_MERGE_2026-11-14.md)）**：
  - 审计 `/Users/xrdemac/Developer/Mac.apk-forkmerge`（Workbuddy），合入全部 24 项无特判通用提交（`96d8f40..6af1616`），并补入 4 个上游分支（含实时多 DEX `MemoryDexGroup`、`HostProxy`/`HostCrypto` 共存、loader/反射与 native 启动契约）。
- **B 站 9.19.0 启动反射缺口与生态高频 Stub 首批修复已完成（HOTAPI-001）**：
  - 修复 `debug/gles_gen/`、`debug/gen_framework_attrs.py` 代码生成器断口，修复 B 站 9.19.0 启动日志缺失接口（`AssetManager`、`StorageManager`、`LoadedApk.mClassLoader` / `AppBaseContext.mPackageInfo`、`DexPathList.addNativePath`），新增 `scripts/hot_api_stub_scanner.py`（复用 Robolectric `shadows-framework-4.16.1.jar`）并修复 `ActivityManager.getRunningTasks/getRecentTasks`、`Resources.getFloat`、`Drawable`/`BitmapDrawable`、`GLSurfaceView`、`ViewGroup` 首批高频 Stub（详情见 [HOT_API_STUBS.md](docs/compat/HOT_API_STUBS.md)）。
- **高频 API Stub 第二批补齐与 ELF 魔数加载已完成（HOTAPI-002，`cd74136`）**：
  - [AppClassLoader.java](runtime/java/com/macapk/loader/AppClassLoader.java)：抽取 `hasElfMagic(File)`，使非 `lib*.so` 命名的解包 ELF 二进制在 `macapkLoadNative(String)` 中走 `lib_native/elf_<pathHash>_<safeName>.dylib` 翻译加载而非直接 `dlopen`。
  - 补齐 10 个核心类高频 getter/setter 状态回传与默认值保留：`Activity`、`Window`、`ActivityManager`、`PackageInstaller`、`AbsListView`、`View`、`ViewGroup`、`HardwareRenderer`、`AudioManager`、`MediaPlayer`；新增 [HotApiBatch2Test.java](debug/HotApiBatch2Test.java) 并接入 `scripts/run_tests.py`（[HOT_API_STUBS.md](docs/compat/HOT_API_STUBS.md) broken pairs 由 46 降至 21）。
- **`GFX-001` 配置/旋转框架资源表复用优化（PENDING，`326979c`）**：
  - [ResourceIndex.java](runtime/java/com/macapk/loader/ResourceIndex.java)：引入不可变 `MacapkFwParsed` 经 `SoftReference` 复用，并始终填充 `sFwThresholdCache`；隔离冷缓存 `ResBench`：`land1` `160→18 ms`（-88.7%），`night1` `121→12 ms`（-90.1%）；新增 [ResourceIndexRotationTest.java](debug/ResourceIndexRotationTest.java)。
- **`VIDEO-PWR-001` 像素回读 NEON 加速与媒体后台去轮询（IN_PROGRESS，`5a34b2a`）**：
  - [video_pixels.h](runtime/src/video_pixels.h)、[shared_gl_frames.mm](runtime/src/shared_gl_frames.mm)、[native_methods.cpp](runtime/src/native_methods.cpp)：实现 ARM64 NEON `FlipBgraPremulToArgb` 与 `FlipRgbaPremulToArgb`（1080p×120 微基准：`0.812→0.140 ms/帧` 与 `0.671→0.138 ms/帧`），扩充 [VideoPixelsTest.cpp](debug/VideoPixelsTest.cpp)。
  - [MediaCodec.java](runtime/java/android/media/MediaCodec.java)、[AudioTrack.java](runtime/java/android/media/AudioTrack.java)：消除音频、VP8/VP9、空闲/暂停视频与未播放 `AudioTrack` 下的 100 Hz / 500 Hz / 50 Hz 后台空轮询唤醒。
- **高频 API Stub 第三批补齐（[HOTAPI-003](docs/tasks/HOTAPI-003.md) 已完成 `DONE`）**：
  - [Window.java](runtime/java/android/view/Window.java)、[Activity.java](runtime/java/android/app/Activity.java)、[Dialog.java](runtime/java/android/app/Dialog.java)：存储 Window 转场/重叠/淡入时长、`NavigationBarDividerColor` 与 `Elevation`，并在 `ActivityWindow` / `DialogWindow` 对齐 Android 15 edge-to-edge 强制语义；`DialogWindow.getElevation()` 补充 `@Override`。
  - [AccessibilityNodeInfo.java](runtime/java/android/view/accessibility/AccessibilityNodeInfo.java)：修复 `obtain(View[, int])` / `setSource` 与 `getLabeledByList()` / `removeLabeledBy` 缺陷，补充 `equals`/`hashCode` 与节点关系 ID 记录（无连接状态保持 `null` per AOSP）。
  - [View.java](runtime/java/android/view/View.java)：`setBackgroundTintMode/getBackgroundTintMode` 独立存储 `macapkBackgroundTintMode`（与 `mBackgroundTintBlendMode` 解耦）；[hot_api_stub_scanner.py](scripts/hot_api_stub_scanner.py) 单列 3 对经核实的 AOSP inert 契约，可修复 broken pairs 降至 `0`；新增 [HotApiBatch3Test.java](debug/HotApiBatch3Test.java)。
- [ORACLE-001](docs/tasks/ORACLE-001.md) 已完成四族 Robolectric SDK 36 差分（`4/4 MATCH`）；真机/模拟器为 `NOT_RUN`。
- [CI-002](docs/tasks/CI-002.md) 保持 `BLOCKED`（待远端 PR 权限，远端 `NOT_RUN`）。

## 当前下一步（交接起手顺序）

1. **排查 `RemoteServiceProcessTest` 失败**：检查 [run_remote_service_broker_test.py](debug/run_remote_service_broker_test.py) LaunchAgent 就绪等待与 `SystemServer`/`SystemClient` 远程绑定路径，确保多进程隔离测试稳定 `PASS`。
2. **彻底收口 `VIDEO-PWR-001` 并将 20s Sidecar 验收跑通**：沿已定位线索（`:ijkservice` `ijk_ff_vout` 在 `nanosleep` 睡 ~107 ms、33.9%–57.9% `releaseFalse`、`bionic_shim` 与 `ff_ffplay` 音频时钟交互）继续定位并优化首帧/帧率；在清理版 `/Applications/Mac.apk.app` 跑满 20s 窗口对齐实测确认 0 破音、帧率达源帧率与 0 残留进程，再推进 [GFX-001](docs/tasks/GFX-001.md) 与 [DANMAKU-001](docs/tasks/DANMAKU-001.md)。
3. **`GAP-IPC-003` 与 `GAP-MED-002` 已闭环（`DONE`）**：[gaps.json](docs/compat/gaps.json) 现为 `observed=0, fixed=14, predicted=4`，剩余 `CI-002` 保持 `BLOCKED`（待远端 PR 权限）。

## 升级前清理与入口

- [CLEANUP-001](docs/tasks/CLEANUP-001.md) 汇总早期清理；[快速入口](docs/UPGRADE_START_HERE.md) 与 [连续执行协议](docs/AUTONOMOUS_EXECUTION.md) 已收口为 `1+1` 文档同步（[PROJECT_STATE.md](PROJECT_STATE.md) + [UPGRADE_PLAN.md](docs/UPGRADE_PLAN.md)/任务卡），机械门禁见 [check_doc_sync.py](scripts/check_doc_sync.py)。

## 本机测试交付约定

用户于 2026-11-05 明确要求，并于 2026-11-15 再次重申：只要构建出新的完整 `Mac.apk.app`，**不要保留测试版**，直接通过 `scripts/build_launcher.sh --promote`（或 `scripts/rebuild_and_run.sh --promote`）原子替换本机 `/Applications/Mac.apk.app` 正式版并同步共享运行时（`~/Library/Application Support/Mac.apk/runtime`）及已安装应用存根（同时移除 `build/Mac.apk.app` 与重复 LaunchServices 注册；替换前先核验离线测试与签名并保留可回退备份；仅在日常开发中间步骤用 `MACAPK_NO_BUNDLE_SYNC=1` 跳过安装）。

## 历史记录

- 当前 1.0.2379 构建基线见 [BASE-002 验收报告](docs/BASELINE_BUILD_CURRENT.md)；早期基线见 [BASE-001 验收报告](docs/BASELINE_BUILD_2026-11-03.md)。
- 11 月 6 日的主线整合、安装版本与回退位置见 [主线整合记录](docs/tasks/MAIN-INTEGRATION-20261106.md)。
- 早期功耗与全屏问题的实验数据见 [VIDEO-PWR-001](docs/tasks/VIDEO-PWR-001.md)。
- 历次历史状态原文可用 `git show 21c7a6e:PROJECT_STATE.md`（含 `IPC-001` 交接细节）与 `git show dfe3fe2:PROJECT_STATE.md` 查阅。
