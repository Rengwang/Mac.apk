# ARCH-001：锁定代码修复冲刺队列与 PILOT-001 范围

- 状态：DONE
- 前置：CONTRACT-001、GAPLOG-001
- 证据依据：[GAPS.md](../compat/GAPS.md)、[CONTRACT_TESTS.md](../compat/CONTRACT_TESTS.md)、[ADR-0003](../adr/0003-aosp-module-evaluation.md)、[FEATURE_GATES.md](../compat/FEATURE_GATES.md)、[PERFORMANCE.md](../../PERFORMANCE.md)

## 决策结论（免新建冗余 ADR）

综合 `GAPLOG-001`（18 项去重缺口）、`CONTRACT-001`（7 项已钉住 `XFAIL`）、`AOSP-EVAL-001`（[ADR-0003](../adr/0003-aosp-module-evaluation.md)）、`OBS-001` 与 `GATE-001` 的实证数据，按“高确定性、高复现率、最小爆炸半径优先”锁定代码修复冲刺顺序：

1. **[PILOT-001](PILOT-001.md)（首项立即执行，Batch 1 Framework API 契约修复）**：一次性修复 `runtime/java/` 中 5 项已有确定性离线失败断言的公共/框架 API 契约缺口（`ServiceManager.getServiceOrThrow`、`Bundle` API 33 类型校验、`PatternMatcher` `PATTERN_ADVANCED_GLOB` + `\.` 转义、`Intent.parseUri` `android-app://`、`AudioTrack.getMinBufferSize` 参数校验），并在 `contract_baseline.json` 中将对应 5 项 `XFAIL` 翻转为 `PASS`。
2. **[MEDIA-001](MEDIA-001.md)（Batch 2 媒体状态机与边界修复）**：修复 `OBS-001` 定位到的 `VideoImageSelfTest.java:236-280` 漏发 `BUFFER_FLAG_END_OF_STREAM` 导致 VideoToolbox 滞留末尾帧并空转 400×5ms（`out_try_again=405`、耗时 ~2.0s）问题，以及 `MediaExtractor` 非 `file://` URI 报错与 `getTrackFormat`/`selectTrack` 越界校验（翻转 `CTR-MEDIA-XFAIL-001`）。
3. **[GATE-002](GATE-002.md)（Batch 3 启动入口开关与环境一致性修复）**：对齐启动器、App Stub、直接 CLI 与 broker spawn 的默认开关/日志等级、清理 harness 环境变量、统一 `MACAPK_EXPERIMENTAL_ALPHA_PROBE` 的 `"1"` 判定并清理 `PresentedSdk.java` 过时注释。
4. **[IPC-001](IPC-001.md)（Batch 4 Binder 调用身份与远端契约）**：修复 `Binder.getCallingUidOrThrow()` 非事务上下文抛出 `IllegalStateException`（翻转 `CTR-IPC-XFAIL-002`）并核验跨进程 `:worker` 绑定路径。
5. **[SURFACE-001](SURFACE-001.md) → [SCHED-001](SCHED-001.md) → [GPU-001](GPU-001.md) / [CACHE-001](CACHE-001.md)**：依次收敛 producer 代际隔离、帧调度唤醒与局部拷贝/启动开销。

## 验收

- [x] 选择由实际证据支持，明确先后顺序与边界。
- [x] 每项均可独立构建、独立验证、独立 `git revert` 回退。
- [x] `PILOT-001` 与后续各卡的目标文件、构建命令和回归门槛已直接具体化。
- [x] 冻结前置盘点/文档仪式，直接进入代码实现。

## 回退与记录

仅更新规划与卡片范围，不改动生产代码；下一步直接执行 [PILOT-001](PILOT-001.md)。
