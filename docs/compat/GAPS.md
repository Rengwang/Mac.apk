# Mac.apk 兼容性缺口与证据优先级台账 (GAPLOG-001)

- **机器可读索引**：[gaps.json](gaps.json)
- **证据目录**：`work/upgrade/GAPLOG-001/`
- **前置来源**：[SCENARIOS.md](SCENARIOS.md) (`COMP-001`)、[SCAN_REPORT.md](SCAN_REPORT.md) (`SCAN-001`)、[FEATURE_GATES.md](FEATURE_GATES.md) (`GATE-001`)、[CAPABILITIES.md](CAPABILITIES.md) (`CAP-001`)、[PERFORMANCE.md](../../PERFORMANCE.md) (`OBS-001`)

## 1. 诊断策略结论：不新增额外运行时诊断钩子

按 `GAPLOG-001` 原则（“已有日志无法解释关键失败时，在相关边界补上默认关闭的有界诊断”），本轮先复跑 COMP-001 三套合成样本、无 broker 条件下的 `RemoteServiceProcessTest`、`FeatureGatesTest.py`、`scan_apk.py`、`MACAPK_CODEC_OBS=1` 与独立离线行为探针 `work/upgrade/GAPLOG-001/GapProbe.java`。

实测结果表明：**已有日志与最小复现脚本已经能够无歧义地解释当前所有已观测失败与契约偏差**，不需要在 `runtime/` 新增第 2 条诊断埋点。从而保持默认生产行为、例外/返回语义与热路径零改动（OFF/ON 零额外开销）。

## 2. 排序与去重规则

- **状态严格拆分**：`observed`（本轮有实跑命令、退出码与源码锚点直接证实）与 `predicted`（仅静态线索、缺失上游分母或因无 GUI / 无 broker 未运行的待复核条目）分列，不混淆。
- **去重**：按 `gap_id` 去重，共 **18** 项（当前 `fixed=14`、`observed=0`、`predicted=4`，`gaps.json` 已与本表同步），不按循环调用次数放大分数，也不凑数填充至 50 项。
- **优先级公式**：`Priority Score = Severity (critical=4, high=3, medium=2, low=1) × Independent Scenario Breadth × Contract Confidence (high=3, medium=2, low=1)`。

## 3. 已观测缺口（Observed Gaps）

| 排名 | Gap ID | 严重性 | 独立场景数 | 置信度 | 分数 | 能力 ID | 摘要与实证输出 | 候选测试 | 归属任务 |
|---:|---|---|---:|---|---:|---|---|---|---|
| 1 | `GAP-IPC-001` | `high` | 3 | `high` | 27 | `IPC-004` | **`PILOT-001` 已修复 `ServiceManager.getServiceOrThrow`**（抛出 `ServiceNotFoundException`，`CTR-IPC-XFAIL-001` 现为 `PASS`）；`SystemProperties.set()` 仍保留只读 no-op | `ContractSuiteRunner` | `PILOT-001 (DONE)` |
| 2 | `GAP-GATE-001` | `high` | 3 | `high` | 27 | `SURF-002` | **`GATE-002` 已修复**：4 个 `MACAPK_EXPERIMENTAL_*` 开关在 runtime 侧对齐为默认开启（`!"0"`）并保留显式 `=0` opt-out，直接 CLI、Broker 与 Launcher/Stub 行为一致 | `FeatureGatesTest` | `GATE-002 (DONE)` |
| 3 | `GAP-IPC-003` | `high` | 2 | `high` | 18 | `IPC-003` | **`IPC-001` 已修复（`0312791`）**：`ServiceRegistry.bind` 经 `SystemClient.bindBlockingIfRemote`（含 `IN_BROKER_BIND` 防回弹与 `LAST_REMOTE_BIND_HANDLED`）路由跨进程 `:worker`；无头 LaunchAgent 夹具 `debug/run_remote_service_broker_test.py` 接入 `run_tests.py --test RemoteServiceProcessTest --strict` 并通过（`rc=0`，`workerPid != clientPid`、`clientState=17`、`workerState=42`、`callerPid=clientPid`，退出后零残留 job/进程） | `RemoteServiceProcessTest` / `ProcessBrokerRoutingTest` | `IPC-001 (DONE)` |
| 4 | `GAP-MED-001` | `medium` | 2 | `high` | 12 | `MED-002` | **`MEDIA-001` 已修复**：`MediaExtractor` 校验非 `file://` 抛出 `IOException`，`getTrackFormat/selectTrack/unselectTrack` 校验越界抛出 `IllegalArgumentException`/`IllegalStateException`（`CTR-MEDIA-XFAIL-001` 现为 `PASS`） | `ContractSuiteRunner` | `MEDIA-001 (DONE)` |
| 5 | `GAP-MED-002` | `medium` | 2 | `high` | 12 | `MED-001` | **`MEDIA-001` 已全量修复（EOS + `df2d74c` 编码器闭环）**：`MediaCodec.createEncoderByType`/`createByCodecName`/`createInputSurface`/`signalEndOfInputStream` 与 `MediaCodecList` 接入 VideoToolbox H.264/HEVC 与 AudioToolbox AAC-LC 硬件/系统编码器，`run_tests.py` 下 `MediaCodecEncoderTest` 11 项检查全通过（`rc=0`） | `MediaCodecEncoderTest` | `MEDIA-001 (DONE)` |
| 6 | `GAP-GATE-002` | `medium` | 2 | `high` | 12 | `LIFE-002` | **`GATE-002` 已修复**：`NativeLog.java`、`Log.java`、`native_methods.cpp` 残留过时 level 5 注释已清理，启动入口日志级别对齐说明 | `FeatureGatesTest` | `GATE-002 (DONE)` |
| 7 | `GAP-IPC-002` | `medium` | 1 | `high` | 6 | `IPC-002` | **`IPC-001` 已修复**：`Binder` 跟踪事务分发深度与显式身份状态，`getCallingUidOrThrow()` 在非事务上下文按 AOSP 契约抛出 `IllegalStateException`，并在事务或显式身份下正常返回 UID（`CTR-IPC-XFAIL-002` 现为 `PASS`） | `ContractSuiteRunner` | `IPC-001 (DONE)` |
| 8 | `GAP-CONTRACT-BUNDLE-001` | `medium` | 1 | `high` | 6 | `IPC-001` | **`PILOT-001` 已修复**：API 33 `Bundle.getParcelableArrayList(k,Class)` / `getParcelableArray` / `getSerializable` / `getSparseParcelableArray` 已执行 `clazz.isInstance` 校验（`CTR-BUNDLE-XFAIL-001` 现为 `PASS`） | `ContractSuiteRunner` | `PILOT-001 (DONE)` |
| 9 | `GAP-CONTRACT-INTENT-001` | `medium` | 1 | `high` | 6 | `LIFE-001` | **`PILOT-001` 已修复**：`Intent.parseUri(..., URI_ANDROID_APP_SCHEME)` 已解析 `android-app://` 包名与内部 data URI（`CTR-INTENT-XFAIL-001` 现为 `PASS`） | `ContractSuiteRunner` | `PILOT-001 (DONE)` |
| 10 | `GAP-CONTRACT-PM-001` | `medium` | 1 | `high` | 6 | `RES-001` | **`PILOT-001` 已修复**：`PatternMatcher(PATTERN_ADVANCED_GLOB)` 已支持字符集/量词/语法校验并修正 `SIMPLE_GLOB` 转义 `\.`（`CTR-PM-XFAIL-001` 现为 `PASS`） | `ContractSuiteRunner` | `PILOT-001 (DONE)` |
| 11 | `GAP-GATE-003` | `low` | 1 | `high` | 3 | `LIFE-002` | **`GATE-002` 已修复**：`AppStubMain.swift` 在启动前同步清洗 9 个测试/自检环境变量，防止脏 shell 污染 | `FeatureGatesTest` | `GATE-002 (DONE)` |
| 12 | `GAP-GATE-004` | `low` | 1 | `high` | 3 | `LIFE-004` | **`GATE-002` 已修复**：`PresentedSdk.java` 中与 `MACAPK_SDK_FROM_TARGET` 现行 `!="0"` 默认开启矛盾的过时注释与日志说明已修正 | `FeatureGatesTest` | `GATE-002 (DONE)` |
| 13 | `GAP-GATE-005` | `low` | 1 | `high` | 3 | `SURF-002` | **`GATE-002` 已修复**：`bionic_shim.cpp` 与 `cocoa_bridge.mm` 的 `MACAPK_EXPERIMENTAL_ALPHA_PROBE` 统一为严格 `strcmp(..., "1") == 0` 判定 | `FeatureGatesTest` | `GATE-002 (DONE)` |
| 14 | `GAP-CONTRACT-AUDIO-001` | `low` | 1 | `high` | 3 | `MED-003` | **`PILOT-001` 已修复**：`AudioTrack.getMinBufferSize` 已校验非法采样率/声道/编码并返回 `ERROR_BAD_VALUE (-2)`（`CTR-AUDIO-XFAIL-001` 现为 `PASS`） | `ContractSuiteRunner` | `PILOT-001 (DONE)` |

## 4. 预测/待复核缺口（Predicted / Unverified Gaps）

| 排名 | Gap ID | 严重性 | 独立场景数 | 置信度 | 分数 | 能力 ID | 线索与待复核原因 | 候选测试 | 归属任务 |
|---:|---|---|---:|---|---:|---|---|---|---|
| 15 | `GAP-GFX-001` | `high` | 2 | `medium` | 12 | `SURF-002` | 7 项 `gpu_pixel` Metal/ANGLE/IOSurface 测试在离线已通过（`21c7a6e`），但交互窗口 `MACAPK_GL_WINDOW_READBACK_TRACE=1` / `MACAPK_PRESENT_TRACE=1` 与 Sidecar 副屏长时实测仍为 `NOT_RUN` | `gpu_pixel` + Sidecar 测试 | `GFX-001` / `VIDEO-PWR-001` |
| 16 | `GAP-IDX-001` | `medium` | 3 | `medium` | 12 | `RES-005` | 仓内缺少 `android-api.idx.gz` 与 `tools/api-index/gen.py`，`scan_apk.py` 仅能比对本地 `android-framework.jar`，上游 API 36 真实缺失方法数未知 | `ApiIndexDenominatorCheck` | `CONTRACT-001` |
| 17 | `GAP-NAT-001` | `medium` | 1 | `medium` | 4 | `IPC-003` | `comp-service-ipc-abi.apk!/lib/arm64-v8a/libcompprobe.so` 导出 `JNI_OnLoad` / `RegisterNatives` 构成 `SCAN-001` 静态盲区；`--extract-only` 仅安装 `.so` 而不执行动态绑定 | `RuntimeDynamicJniProbeTest` | `CONTRACT-001` |
| 18 | `GAP-RES-001` | `low` | 1 | `medium` | 2 | `RES-001` | `MacApkPackageManager` 以单 APK 视图+合成 stand-in 应答跨包查询；多密度/动态主题覆盖及通用跨包服务解析尚未与 AOSP 对照 | `PackageManagerContractTest` | `CONTRACT-001` |

## 5. 本轮实跑证据索引（`work/upgrade/GAPLOG-001/`）

- 合成样本构建：`python3 debug/fixtures/build_comp_fixtures.py --out work/upgrade/GAPLOG-001/fixtures --framework-apk thirdparty/framework-res/framework-res-api36.apk` (`rc=0`)
- CLI 与 `--extract-only` 复跑：`comp-ui-resources_cli.log`、`comp-surface-media_cli.log`、`comp-service-ipc-abi_cli.log`（均 `exit=0`）
- `runtime_fixture`：`comp-ui-resources.apk`（`DrawableIdentityAndTintTest` PASS、`UpstreamPackageFeaturesTest` PASS、`RemoteServiceProcessTest` SKIP）；`comp-service-ipc-abi.apk`（`UpstreamPackageFeaturesTest` PASS、`RemoteServiceProcessTest` SKIP；`DrawableIdentityAndTintTest` 因样本不含 UI drawable 失败 `rc=1`）
- 无 broker `:worker` 进程隔离复现：`work/upgrade/GAPLOG-001/remote_service_nobroker.log` (`rc=1`, `AssertionError: remote service lacks process identity/static isolation`)
- 音视频自检 + `MACAPK_CODEC_OBS=1`：`work/upgrade/GAPLOG-001/comp_surface_media_audio.log`、`comp_surface_media_video.log` (`rc=0`)
- 离线框架契约复现：`work/upgrade/GAPLOG-001/gap_probe.log` (`rc=0`)
- 扫描与门禁复检：`work/upgrade/GAPLOG-001/scan-report.json` (`rc=0`)、`work/upgrade/GAPLOG-001/validate_gates.json` (`rc=0`)、`work/upgrade/GAPLOG-001/feature_gates/` (`PASS=23 FAIL=0 NOT_RUN=4`, `rc=0`)
