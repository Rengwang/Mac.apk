# Mac.apk 升级任务表

更新：2026-11-18。路线见 [ROADMAP.md](ROADMAP.md)，执行规则见
[AUTONOMOUS_EXECUTION.md](AUTONOMOUS_EXECUTION.md)。本表是任务顺序与状态的唯一总表；证据在各卡。

**当前已全部 `DONE`**：P0/P1/P3 基建与评估、`PILOT-001`、`MEDIA-001`（含 `df2d74c` 闭环 **`GAP-MED-002`** VideoToolbox/AudioToolbox 硬件编码器）、`GATE-002`、`IPC-001`（14/14 契约全绿，且 `0312791` 闭环 **`GAP-IPC-003`** 跨进程 `:worker` `ServiceRegistry.bind` 与无头 LaunchAgent 回归）、`SURFACE-001..003`、`SCHED-001`、`RES-001`、`GPU-001`、`CACHE-001`、`GC-001`、`ORACLE-001`（Robolectric SDK 36）、`FORK-MERGE`（`598da00`）、`HOTAPI-001`、`HOTAPI-002`（`cd74136`）与 **`HOTAPI-003`**（清零可修复 broken pairs，保持 3 对 AOSP inert 契约）。`VIDEO-PWR-001` 处于**暂停交接状态（`IN_PROGRESS`）**：在 `b6cbdda`（Slice B）基础上已合入 Slice A（跨进程 Surface/VideoToolbox 时间戳与顺序契约、`MediaCodec.OutputTarget`/解锁呈现与解码、zero-overhead `video_stats`）、`binder_mach.cpp` oneway FIFO 保序、`AutomationServer` `NativeCocoa.exitProcess` 退出口、`Boot`/`SystemClient` 无头预热并行与 `sidecar_runner` 选窗/两阶段清零退出，清理临时诊断打印并 promote 至 `/Applications/Mac.apk.app`（`261118.2159-b6cbdda+dirty`，`v1.0.2379`；`CONVERTER_ID=c0207dd09bc1489b FRAMEWORK_ID=2ddf9b63646d86ff JAVA_ID=54b1eca73eb8fc43 JARS_ID=d6f301f481d7af7e NATIVE_ID=d9446ee83be26eec`）；台账 `CapRegistryTest` `12 tests OK`、`installed_staleness_check`+`installed_entitlements_check` `2 PASS`、9 项核心离线/GPU 回归 `9 PASS`（含 [CrossProcessTimestampContractTest.java](../debug/CrossProcessTimestampContractTest.java)），但 **`RemoteServiceProcessTest` `FAIL`**（`clientPid == workerPid`，待排查），且清理后完整 20s Sidecar 验收 `NOT_RUN`。
**Sidecar 进展与残余瓶颈**：清理调试打印前的一轮 8.115s trace 实测（`261118.2144-b6cbdda+dirty`，`displayID=4`）确认无头跳过占位窗（`placeholder_shown_count=0`）、零破音（`total_underruns=0`）、`eglPbufferSwaps=0` / `eglDirectSwaps=137`、`:ijkservice` 进 Cocoa runloop 缩至 `+7694 ms`、退出后 0 残留；但视频 `latchIntervalMs` 仍呈 ~107 ms 突发（`latchedFps=19.47`、`releaseFalse=33.89%`），线程采样定位 `:ijkservice` 内 `ijk_ff_vout` 约 99% 样本停在 `libsystem_c.dylib` `nanosleep`（每 ~95–110 ms 醒来连放多帧），故 `VIDEO-PWR-001` 保持 `IN_PROGRESS`、`GFX-001` 与 `DANMAKU-001` 保持 `PENDING`。
**当前剩余可推进工作（交接起手顺序）**：① 排查并修复 `RemoteServiceProcessTest` 失败（`clientPid == workerPid`）；② 定位 `ijk_ff_vout` 在 `nanosleep` 集中睡眠 ~107 ms 的根因并跑通当前清理版 20s Sidecar 完整验收，随后复测 `GFX-001` 与 `DANMAKU-001`；③ 需远端 PR 权限的 `CI-002`。
不再新增独立盘点/扫描/自检/ADR 前置卡，每张修复卡直接执行“目标缺口 → `runtime/`/`launcher/` 最小通用修复 → `MACAPK_NO_BUNDLE_SYNC=1` 离线构建 → 针对性测试 + `run_tests.py --group offline` 冒烟 → 本地提交并进入下一张”。

## 主线：按依赖连续推进

| 顺序 | 任务与产出 | 前置 | 状态 |
|---|---|---|---|
| 历史 | [FND-001](tasks/FND-001.md) 工程框架 | 无 | DONE |
| 历史 | [BASE-001](tasks/BASE-001.md) 2026-11-03 构建基线 | FND-001 | DONE |
| 历史 | [ROADMAP-001](tasks/ROADMAP-001.md) 核查与执行路线 | 无 | DONE |
| 历史 | [CLEANUP-001](tasks/CLEANUP-001.md) 升级前清理 | 无 | DONE |
| 1 | [BASE-002](tasks/BASE-002.md) 当前 HEAD 构建与身份基线 | BASE-001 | DONE |
| 2 | [TEST-001](tasks/TEST-001.md) 统一分类测试入口 | BASE-002 | DONE |
| 3 | [SEC-001](tasks/SEC-001.md) 现有访问边界与隔离可行性 | BASE-002 | DONE |
| 4 | [COMP-001](tasks/COMP-001.md) 固定 fixture/场景矩阵 | BASE-002、TEST-001、SEC-001 | DONE |
| 5 | [CAP-001](tasks/CAP-001.md) 扫描所需的版本化能力台账 | TEST-001 | DONE |
| 6 | [SCAN-001](tasks/SCAN-001.md) 扫代表性 APK，找疑似 API/ABI 缺口 | CAP-001、COMP-001 | DONE |
| 7 | [GAPLOG-001](tasks/GAPLOG-001.md) 实跑确认、去重并排序 | CAP-001、SCAN-001、TEST-001、SEC-001 | DONE |
| 8 | [CONTRACT-001](tasks/CONTRACT-001.md) 用 Android 行为依据写首批回归测试 | CAP-001、TEST-001、COMP-001、GAPLOG-001 | DONE |
| 9 | [ARCH-001](tasks/ARCH-001.md) 锁定代码修复冲刺队列与 PILOT-001 范围 | CONTRACT-001、GAPLOG-001 | DONE |
| 10 | [PILOT-001](tasks/PILOT-001.md) 首批 5 项 Framework API 契约修复 | ARCH-001、TEST-001 | DONE |
| 11 | [CI-001](tasks/CI-001.md) 本地回归门禁与 workflow 配置 | TEST-001 | DONE |
| 12 | [GATE-001](tasks/GATE-001.md) 开关台账与默认值一致性 | CAP-001、TEST-001 | DONE |
| 13 | [PERF-001](tasks/PERF-001.md) 当前性能/占用基线 | BASE-002、COMP-001 | DONE |
| 14 | [OBS-001](tasks/OBS-001.md) 最小观测缺口 | PERF-001 | DONE |
| 15 | [AOSP-EVAL-001](tasks/AOSP-EVAL-001.md) 单模块源码复用评估 | CAP-001、CONTRACT-001 | DONE |

顺序是默认优先级，不替代依赖图。某项有真实外部阻碍，可先做不依赖它的较后任务。
一次只实施一张卡；完成后更新本表与卡片、保存检查点并继续，不要求用户逐卡分派。
资源/测试框架不齐时先解决最小可复现链，不全量升级依赖。
首项普通兼容性修复用 TEST-001 的本地入口复测。若所选修复确实依赖性能基线、
开关治理或 AOSP 源码移植评估，先把相应任务列入实现卡的前置并完成。

## 独立验证阶段

| 任务 | 前置与触发 | 状态 |
|---|---|---|
| [CI-002](tasks/CI-002.md) 远端 runner 实跑 | CI-001；已有远端执行授权和合适 runner | BLOCKED |
| [ORACLE-001](tasks/ORACLE-001.md) Android 行为差分 | CONTRACT-001；匹配 API 的可用参考环境 | DONE（Robolectric SDK 36） |

2026-11-14 将旧 CI-001 的本地配置与远端执行拆开，避免无推送授权使本地工程永久停滞。
CI-001 本地门禁已完成；CI-002 的实际远端 job 仍为 NOT_RUN。
ORACLE-001 已对照 Robolectric SDK 36 的四个 framework 契约；设备/完整系统行为仍需对应证据。

## 代码修复冲刺与后续候选（Code-Fix Sprint）

按 `PILOT-001 → MEDIA-001 → GATE-002 → IPC-001 → SURFACE-001 → SCHED-001 → RES-001` 顺序推进，不要求每卡单独写 ADR：

| 任务 | 技术前置与范围 | 状态 |
|---|---|---|
| [MEDIA-001](tasks/MEDIA-001.md) `VideoImageSelfTest`/codec EOS 补发与 `MediaExtractor` 边界校验 | ARCH-001、OBS-001 | DONE |
| [GATE-002](tasks/GATE-002.md) 启动入口开关统一、环境变量清理与注释修正 | GATE-001 | DONE |
| [IPC-001](tasks/IPC-001.md) `Binder.getCallingUidOrThrow` 与远端 Binder 契约修复 | ARCH-001、CI-001 | DONE |
| [SURFACE-001](tasks/SURFACE-001.md) producer identity/generation 与迟到帧隔离 | ARCH-001、CI-001 | DONE |
| [SCHED-001](tasks/SCHED-001.md) deadline、取消与帧顺序 | ARCH-001、CI-001 | DONE |
| [GPU-001](tasks/GPU-001.md) 局部 GPU/拷贝优化 | ARCH-001、PERF-001、OBS-001 | DONE |
| [CACHE-001](tasks/CACHE-001.md) 缓存/共享/启动优化 | ARCH-001、PERF-001 | DONE |
| [SURFACE-002](tasks/SURFACE-002.md) 有界队列与 fence/owner | SURFACE-001 | DONE |
| [SURFACE-003](tasks/SURFACE-003.md) 几何与合成事务 | SURFACE-002 | DONE |
| [RES-001](tasks/RES-001.md) 资源/主题单项契约 | ARCH-001、CI-001 | DONE |
| [GC-001](tasks/GC-001.md) GC 稳定性与默认值评估 | ARCH-001、PERF-001、OBS-001 | DONE |
| HOTAPI-001（[HOT_API_STUBS.md](compat/HOT_API_STUBS.md)）B 站 9.19.0 启动反射缺口 + Robolectric 高频 API Stub 筛选与首批修复（含遗留 codegen/门禁修复） | RES-001、ORACLE-001 | DONE |
| HOTAPI-002（[HOT_API_STUBS.md](compat/HOT_API_STUBS.md)）第二批高频核心类 Stub 补齐（`View` / `ViewGroup` / `Window` / `Activity` / `ActivityManager` / `PackageInstaller` / `AbsListView` / `HardwareRenderer` / `AudioManager` / `MediaPlayer`）与非 `lib*.so` ELF 魔数加载 | HOTAPI-001 | DONE |
| HOTAPI-003（[任务卡](tasks/HOTAPI-003.md) / [清单](compat/HOT_API_STUBS.md)）清零剩余 broken setter/getter（`Window` transition/elevation/navBarDivider、`AccessibilityNodeInfo` 多标签/源节点 ID、`View.setBackgroundTintMode`；保留 3 对 AOSP inert 契约） | HOTAPI-002 | DONE |

后续卡若还涉及未建立的 Surface/IPC 契约，开工前将相应前置补到卡和本表，不隐藏依赖。
候选依据 APK 扫描、运行复现和回归证据排序。Surface、IPC 和资源任务各按实际依赖推进。

## 既有专项任务

| 任务 | 本轮处理 |
|---|---|
| [GFX-001](tasks/GFX-001.md) | 保持 PENDING（`egl14_bridges.inc` 已补充 native-EGL `SurfaceView` `directKey` 登记与 swap 计数；暂停前 8.115s trace 显示 `eglDirectSwaps=137`、`eglPbufferSwaps=0`，当前 `261118.2159-b6cbdda+dirty` 下全屏旋转往返仍 `NOT_RUN`） |
| [DANMAKU-001](tasks/DANMAKU-001.md) | 保留 PENDING（历史 Sidecar 25s 播放确认弹幕/字幕渲染，当前构建弹幕开/关功耗 A/B 仍 `NOT_RUN`） |
| [VIDEO-PWR-001](tasks/VIDEO-PWR-001.md) | 保持 IN_PROGRESS（暂停交接：已合入 Slice A+B、`binder_mach.cpp` oneway FIFO、`OutputTarget` 解锁呈现、`AutomationServer` `video_stats`/安全退出与 `sidecar_runner` 修复并 promote 至 `261118.2159-b6cbdda+dirty`；9 项核心离线/GPU 测试 `9 PASS`、`installed` `2 PASS`，`RemoteServiceProcessTest` `FAIL`；暂停前 8.115s trace 已达成 0 破音/无占位窗/`+7694 ms`/0 残留，剩余 `ijk_ff_vout` ~107 ms `nanosleep` 突发卡点；清理后 20s Sidecar 完整验收 `NOT_RUN`） |
| [UPSTREAM-2377](tasks/UPSTREAM-2377.md)、[MAIN-INTEGRATION-20261106](tasks/MAIN-INTEGRATION-20261106.md) | 历史整合证据，不等于当前 HEAD 全部验收通过 |

## 状态与统一交付

- TODO：待执行，前置未全满足；READY：可开工；IN_PROGRESS：正在执行。
- BLOCKED：有明确外部条件，记录解除方式；DEFERRED：尚未激活的独立阶段/候选。
- DONE：本卡适用必需验收全部有证据。历史 DONE 保留日期/版本，不外推到新 HEAD。
- PASS / FAIL / SKIP / NOT_RUN 是检查结果；SKIP/NOT_RUN 不能满足必需条件。

每卡交付结果、文件、命令/退出码/证据、未完成项、风险、回退、下一卡。
使用 [任务模板](tasks/TEMPLATE.md)、[测试规范](../TESTING.md)、[性能规范](../PERFORMANCE.md)。
