# 性能测量规范

状态：PERF-001 已建立离线/无头重复基线（见 [docs/perf/BASELINE.md](docs/perf/BASELINE.md)），OBS-001 已完成现有观测开关盘点并在 `MediaCodec` 补齐默认关闭、有界上界的队列/等待/`getOutputImage` 观测链路（`MACAPK_CODEC_OBS=1`）。
研究报告中 readback、调度、合成等属于调查方向；尚未证明谁是用户工作负载的主瓶颈。

## 固定实验条件

每个场景记录：源码 SHA、构建 ID、实际启动的 runtime/JAR 路径与内容标识、
Mac 型号、macOS、JDK、显示刷新率、窗口像素尺寸、供电/低电量状态、APK 版本与 SHA-256、
功能开关、测试数据快照、预热步骤、采样时长和后台负载。

冷启动与热启动分开。视频尽量使用固定的本地测试片段，避免网络波动影响判断。
同场景初始建议预热 30 秒、采样 120 秒、重复 3 轮；按场景调整并在比较前固定。
前后版本使用相同条件；必要时交替 A/B，记录热状态及重复运行波动。
不要在同一测量窗口中编译、同步共享 runtime 或同时压测其他应用。

## 起步场景

1. 静止窗口/空闲：观察 CPU 与唤醒，确认没有持续无效工作。
2. 固定列表滚动：观察帧间隔、响应与资源增长。
3. 固定视频无叠层与有透明叠层：观察显示、解码、合成及稳定性。
4. 一个不同渲染栈的工作负载：避免仅改善主验收应用。

可用 APK/fixture 在 COMP-001 确认；不存在的实验 APK 先登记待建设，不假装现成。

## 指标口径

| 指标 | 采集点 / 开关（源码锚点） | 单位、时钟源与线程 | 关联标识与缓冲上界 | 当前状态与限制 |
|---|---|---|---|---|
| CPU、RSS、footprint、上下文切换 | `/usr/bin/time -l`（`debug/perf/measure_offline_baseline.py`）；守护采样 `debug/perf/profile_process.py`（`ps`/`sample`） | 秒 / bytes（MiB）/ 次数；进程生命周期聚合或 `INTERVAL_SEC` 轮询；覆盖主进程及子进程需单独聚合 | PID / workload 名 / `round_i` | 已用于 PERF-001 & OBS-001 离线无头测量；`vol_ctx_switches` 不可直接等同内核唤醒 |
| 启动耗时（进程内阶段） | `MACAPK_BOOTSPEED_CLOCK`（默认开启，`=0` 关闭；`runtime/src/main.cpp:253`、`Boot.java:130`）与 `MACAPK_LOAD_TIMING=1`（`arm64_native.cpp:5396`） | `ms`；单调时钟（native launch clock / `CLOCK_UPTIME_RAW`）；主线程 / 加载线程 | `[boot-clock]` / `[load-timing]` 阶段名 | 无界单次启动日志（每阶段一行）；不含冷/热磁盘缓存外的 App 首帧呈现 |
| 帧间隔（提交/present 代理指标） | `MACAPK_FRAME_TRACE=1`（`cocoa_bridge.mm:6563-6625`）、`MACAPK_FRAME_STATS=1`（`cocoa_bridge.mm:14757`）、`MACAPK_VIDEO_PRESENT_TRACE=1`（`MediaCodec.java:1400-1451`） | `us`（`CACurrentMediaTime` -> `mach_absolute_time`）或 `ns`（`System.nanoTime()`）；present/main 线程或 `MediaCodec:VideoPresent` | `tag` / `mTraceN`；`FRAME_TRACE` 固定环形 `dt_us[600]`（约 10s @ 60Hz），`VIDEO_PRESENT_TRACE` 固定数组 `120` | **代理指标**（提交到 `CALayer`/present 回调，非 `CVDisplayLink`/Panel 扫描实际显示时间戳）；无界或需带窗口运行，离线无头未触发 |
| 丢帧/超时与渲染节点复用 | `MACAPK_VIDEO_PRESENT_TRACE=1`（`mTraceDrops` / `mTraceAppDrops`）、`MACAPK_RENDERNODE_STATS=1`（`RenderNode.java:149-193`） | 次数与 `ns`；`System.nanoTime()`；释放线程 / UI 渲染线程 | 对象级计数器 + O(1) `AtomicLong` | 仅统计解码释放层与 RenderNode；WindowServer 合成丢帧未采集 |
| GPU 时间与功耗 | 宿主工具 `powermetrics` / Metal counters（`profile_process.py` 的 `ioreg` 仅提供 AGX 状态占位） | `ms` / `mW` / `idle_wakeups`；需硬件时间戳 | 进程/系统级 | **未采集**：`powermetrics` 需 root 权限，离线无头且无 GUI 合成负载下未运行 |
| readback（GL / Pixel / YUV） | 现有 `MACAPK_GL_WINDOW_READBACK_TRACE=1`（`MacApkGlWindow.java:106-108,271-313,420-430`）；新增 `MACAPK_CODEC_OBS=1`（`MediaCodec.java` `img_calls`/`img_bytes`/`img_ns`） | bytes 与 `ns`（`System.nanoTime()`）；GL producer swap 线程 / `Image.getPlanes()` 调用线程；同步包含在 readback 耗时内 | `codec#id` / 每 300 swap 窗口汇总；`CODEC_OBS` 固定 `MAX_OBS_EVENTS=64` 环形 + `dropped` | `CODEC_OBS` 已实跑验证（见下）；`GL_WINDOW_READBACK_TRACE` 仅覆盖有 GL Surface 的 GUI 场景，离线未触发 |
| Canvas present 阶段拆解 | `MACAPK_PRESENT_TRACE=1`（`cocoa_bridge.mm:8480-8527`） | `ms`（`CACurrentMediaTime()`）；Java present 线程 / main 线程 | 每 300 次 present 输出均值汇总 | 默认关闭；无窗口未触发 |
| MediaCodec 队列、等待与 poll 边界 | 新增 `MACAPK_CODEC_OBS=1`（`MediaCodec.java:293,1186-1399,1828-1960`），补充既有 `MACAPK_MEDIA_PROBE=1`（`MediaStats.java`） | 次数、bytes 与 `ns`（`System.nanoTime()`）；记录调用线程切换次数 `thr_switches` | `codec#id` + `mime` + `surface=0\|1`；事件环形上限 `cap=64`，超限写 `dropped` 计数并在 `stop`/`reset`/`release` 输出一行 `[codec-obs]` | 已实现、单测验证并在实跑中量化 `VideoImageSelfTest` 等耗时间隙；仅统计累计耗时/最大值与近 64 事件窗口，不自动输出历史高分位直方图 |

无法直接观测实际显示时，明确使用的是代理指标；不把 Choreographer 回调 FPS 称为显示 FPS。
不要把显示刷新率一律写成 60 Hz，也不要把视频源帧率当作 UI 帧率。
多轮分位数分别报告或从原始样本统一重算，不直接平均 p95/p99 冒充整体分位数。

## 观测开关验证与基线补充结论 (OBS-001)

1. **`MACAPK_CODEC_OBS=1` 设计与边界**：默认关闭（`CODEC_OBS == false` 时 `mObs == null`，不分配缓冲、不加锁、无 stderr 输出）；开启时每个 `MediaCodec` 实例分配固定 `MAX_OBS_EVENTS=64` 基本类型环形槽，溢出后递增 `dropped`（`total_ev = retained + dropped`），在 `stop()`/`reset()`/`release()` 生命周期通过 `System.err` 输出单行 `[codec-obs]` 汇总（裸 JVM 与 `macapk-runtime` 一致）。
2. **闭环定位 `PERF-001` 遗留疑问（`boot_video_warm` `3.51s` wall vs `0.89s` CPU）**：开启 `MACAPK_CODEC_OBS=1` 实测 `comp-surface-media.apk` + `probe_clip.h264`（`VideoImageSelfTest`）显示 `in_q=5 eos_q=0 out_deq=3 out_try_again=405 out_wait_ns≈2.67–3.46 ms img_calls=3 img_bytes=67584 img_ns≈1.13–1.26 ms`——证明 3 帧 BGRA→YUV_420_888 readback/转换总耗时仅 `~1.1–1.3 ms` 而不是秒级瓶颈；其余 `~2.0s` 墙钟时间消耗在测试 harness `VideoImageSelfTest.java` 未送入 EOS 导致 2 帧滞留解码器时空转 `emptyPolls < 400` × `Thread.sleep(5)`。
3. **OFF vs ON 开销实测（同构建 `JAVA_ID=4008da24b44d9ca9`，`1 warmup + 3 measured rounds`，`comp-surface-media.apk`）**：
   - `boot_audio_warm`：OFF `real_s` 中位数 `0.170s`（spread `0.010s`，RSS `90.73 MiB`） vs ON `0.170s`（spread `0.020s`，RSS `97.92 MiB`），差值 `+0.000s`（`+0.00%`）；每轮稳定产出 1 行 `[codec-obs]`（`retained=53 dropped=0 total_ev=53 in_q=13 in_bytes=2369 out_deq=13 out_bytes=24576`）。
   - `boot_video_warm`：OFF `real_s` 中位数 `3.100s`（spread `0.110s`，RSS `462.20 MiB`） vs ON `3.110s`（spread `0.020s`，RSS `472.59 MiB`），差值 `+0.010s`（`+0.32%`，完全落入 OFF 波动区间）；每轮稳定产出 1 行 `[codec-obs]`（`retained=64 dropped=361 total_ev=425`）。
4. **交接给 `ARCH-001` 的剩余观测限制**：带真实窗口与透明叠层的连续滚动/播放负载下，GL readback（`MACAPK_GL_WINDOW_READBACK_TRACE=1`）、Metal/CALayer 呈现（`MACAPK_PRESENT_TRACE=1` + `MACAPK_FRAME_TRACE=1`）以及系统级功耗/GPU 时间（`powermetrics`）仍需后续有窗口/GPU 权限环境补采。

## 原始结果与完成条件

原始数据、日志和测试数据放在被忽略的 `work/upgrade/PERF-001/`、`work/upgrade/OBS-001/` 等目录；
提交经过脱敏的场景定义、命令、工具版本、结果摘要与原始证据校验信息。
默认不新增运行时插桩；外部测量无法回答的问题再交 OBS-001（已完成并收敛为单条默认关闭的有界开关）。

有效优化必须同时满足：可复现的前后结果、改善超过所观察到的测量波动、
正确性与兼容性检查通过、内存/空闲负载等相关指标没有不可接受的退化。
具体门槛在建立基线后按场景制定，不能事先凭空承诺“提升 30%”。
CPU/RSS 改善不自动等于功耗改善；没有能量测量时不声称电池续航提高。
