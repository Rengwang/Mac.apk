# PERF-001：建立可重复性能基线

- 状态：DONE（离线/无头重复基线已完成；交互式 GUI、Sidecar、WindowServer 与渲染/readback/功耗项已如实标记 `NOT_RUN` / 未采集并转入 `OBS-001`）
- 前置：BASE-002、COMP-001
- 证据目录：`work/upgrade/PERF-001/`

## 目标与范围

先以现有工具测量固定场景，明确“现在慢在哪里”和观测盲区。
允许新增测量脚本/场景说明/结果摘要；不顺手优化运行时或引入全套 trace 平台。

## 步骤

1. 按 PERFORMANCE.md 固定设备、源码、实际 runtime/JAR、数据、窗口、刷新率和开关。
2. 从兼容矩阵选择静止、滚动、固定视频及适用叠层场景。
3. 核实可用采集工具，先取得 CPU、RSS、冷/热启动等可可靠解释的指标。
4. 预热、固定采样窗口、至少三轮；保留原始数据、命令和波动，不挑最好的一次。
5. 对帧间隔、GPU、readback、wakeups 明确哪些可测、哪些尚缺采集点。
6. 输出已确认结果、瓶颈假设及 OBS-001 所需最小观测需求。
7. 产出 `docs/perf/BASELINE.md`；把启动分解为转换/类加载/首屏等实际可观测阶段，
   把占用分为堆、元数据/共享页、native/GPU、磁盘 APK/解包/JAR/共享 payload。
   同时记录子进程、WindowServer 和可用的 Sidecar 系统成本；无法测量的项保持未知。

## 验收

- [x] 固定场景可重复运行，数据对应明确的实际构建身份（见 [BASELINE.md](../perf/BASELINE.md) 第 1 节与 `work/upgrade/PERF-001/summary.json`）。
- [x] 至少一组重复测量显示可解释的结果与波动（8 个无头工作负载均完成 1 轮 warmup + 3 轮正式测量，保留全部逐轮数据与 min/median/max/spread）。
- [x] 未测指标写未测，回调帧率没有伪称显示帧率（交互 GUI / Sidecar / WindowServer / 真实帧间隔 / GPU 时间 / readback / 功耗均标为 `NOT_RUN` / 未采集并汇总入 `OBS-001` 需求）。
- [x] 不作性能改善声明，不设置无基线支撑的硬门槛。

## 回退与记录

- **执行记录（2026-11-14）**：
  - 新增顺序基准脚本 [measure_offline_baseline.py](../../debug/perf/measure_offline_baseline.py)，产出基线报告 [BASELINE.md](../perf/BASELINE.md) 与原始证据 `work/upgrade/PERF-001/summary.json`（及 `rounds/` 逐轮日志）。
  - 实测结论摘要（中位数 / 区间）：冷 `--extract-only` 0.66–0.73 s（221.5–250.3 MiB footprint）；无头音频启动+解码冷 0.370 s / 热 0.140 s（`Boot.run entered` 346 -> 125 ms，约 55 MiB footprint）；无头 H.264 视频启动+解码冷 3.720 s / 热 3.510 s（CPU 仅 0.89–1.03 s，412.5–416.2 MiB footprint）；`run_tests.py --group runtime_fixture` 1.520 s。
  - 交互 GUI / Sidecar / 真实呈现帧率 / GPU / readback / 功耗未采集，已结构化列为 [OBS-001](OBS-001.md) 最小观测补齐清单。
- **回退方式**：删除 `debug/perf/measure_offline_baseline.py`、`docs/perf/BASELINE.md` 并清理 `work/upgrade/PERF-001/`；不涉及生产代码与正式安装变更。
- **交接与下一项**：`PERF-001` 已完成；可推进 [OBS-001](OBS-001.md)（与当前并行的 `SCAN-001` / `GATE-001` 互补）。
