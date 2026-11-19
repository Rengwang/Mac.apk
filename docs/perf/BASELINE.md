# Mac.apk 当前性能与占用基线（PERF-001）

- **状态基点**：Git `03e7137bee5b22a8d339d56dfb6e66f3bed23227`（含 `BASE-002`、`TEST-001`、`SEC-001`、`COMP-001`、`CI-001`，`CAP-001` 随后合入 `c84a4db` 且未改动 `runtime/` 与 `launcher/`）。
- **测量脚本**：[measure_offline_baseline.py](../../debug/perf/measure_offline_baseline.py)（基于 `/usr/bin/time -l` 与 `[boot-clock]` / 运行时日志）；长驻进程采样工具见 [profile_process.py](../../debug/perf/profile_process.py)。
- **原始证据目录**：`work/upgrade/PERF-001/`（含 `summary.json` 及各轮原始 `rounds/<workload>/*.log`，被 `.gitignore` 忽略）。
- **严谨声明**：本基线仅包含**本地离线无窗口（headless）**实测；**未作任何性能优化声明，不设定无基线支撑的硬门槛**；所有无法在无窗口/无 root 权限下真实测量的项目（WindowServer、Sidecar 传输、真实显示帧间隔、GPU 时间戳、readback 字节/耗时、功耗/能量）均如实标为 `NOT_RUN` / 未采集，并转入 `OBS-001` 需求清单。

---

## 1. 固定实验环境

| 维度 | 实测固定值 |
|---|---|
| 主机型号 / CPU / 内存 | `Mac17,3`、`Apple M5`（10 核 CPU，8 核 GPU）、16 GiB (`17,179,869,184` B) |
| 操作系统 | `macOS 27.2.1` (`26B131`) |
| 供电与热状态 | `AC Power`（内置电池 80%，已接电源未警告），`pmset -g therm` 无热/性能/CPU 功耗降频警告 |
| 显示器与会话 | `Color LCD`（Built-in Liquid Retina Display，`1710x1112 pt` / `3420x2224 px` @2x，`60.0 Hz`，硬件面板 `2560x1664`）；未锁屏；**未连接 iPad Sidecar** |
| JDK 版本 | `OpenJDK 21.0.13`（`/opt/homebrew/Cellar/openjdk@21/21.0.13/...`） |
| 运行时与构建 ID | `runtime/build/bin/macapk-runtime`（`CONVERTER_ID=77da89513a226525`、`FRAMEWORK_ID=b902b95f478a5dce`、`JAVA_ID=24653ab436234457`、`JARS_ID=2d7994c010d8381e`、`NATIVE_ID=2140bedbbc35c18c`） |
| 样本 APK | 来自 [SCENARIOS.md](../compat/SCENARIOS.md) 的三类合成 APK：`comp-ui-resources.apk` (`3937 B`)、`comp-surface-media.apk` (`10737 B`)、`comp-service-ipc-abi.apk` (`7932 B`) |
| 开关口径 | 所有行为开关保持默认值；仅在需绕开交互式 Cocoa 窗口的 `Boot.run` 无头自检路径设置 `MACAPK_WINDOW_FIRST=0 MACAPK_AWT_MAIN_INIT=0`（与 `--extract-only` 保持零窗口一致） |
| 并发控制 | 每项测量顺序执行（1 轮 warmup + 3 轮正式采样），窗口期内无并发编译、bundle 同步或其他重负载任务 |

---

## 2. 静态交付物与进程/磁盘占用分解

### 2.1 运行时与共享 Payload 大小

| 组件路径 | 实测字节 | 约合（MiB） | 说明 |
|---|---:|---:|---|
| `runtime/build/bin/macapk-runtime` | 4,323,248 | 4.12 | 原生运行期宿主与 DEX->JAR 转换逻辑 |
| `runtime/build/bin/libmacapk-bionic-shim.dylib` | 2,741,288 | 2.61 | Bionic/POSIX/AMedia 等原生兼容垫片 |
| `runtime/build/bin/tools/framework-res.apk` | 22,856,298 | 21.80 | API 36 Android 框架资源包 |
| `runtime/build/lib/android-framework.jar` | 16,983,083 | 16.20 | Java Android 框架实现 |
| `runtime/build/lib/macapk-loader.jar` | 1,617,540 | 1.54 | 启动与资源/加载器 |
| `runtime/build/lib/bcprov-jdk18on-1.79.jar` | 8,575,537 | 8.18 | BouncyCastle 加密提供者 |
| `runtime/build/` 总目录 | 190,811,366 | 181.97 | 含 ANGLE / Skia / JRE 等构建输出 |
| `build/Mac.apk.app` 整包 | 175,286,840 | 167.17 | 未同步正式 `/Applications/Mac.apk.app` |

### 2.2 冷解包独立 `--android-root` 磁盘膨胀（`--extract-only`）

| 场景 | 源 APK | 解包总计 `android-root` | `.macapk/code`（JAR/icon/meta） | `data/app`（安装 APK+native） | `data/data`（缓存与运行目录） |
|---|---:|---:|---:|---:|---:|
| `comp-ui-resources` | 3,937 B | 9,264 B | 3,591 B | 3,991 B | 1,682 B |
| `comp-surface-media` | 10,737 B | 18,130 B | 5,229 B | 10,793 B | 2,108 B |
| `comp-service-ipc-abi` | 7,932 B | 24,426 B | 7,970 B | 12,694 B | 3,762 B |

### 2.3 进程内存分解说明（`/usr/bin/time -l` 口径）

- **`maximum resident set size`（`ru_maxrss`）**：由内核报告（macOS 上单位为字节，下表换算为 MiB = `bytes / 1048576`）。注意当被测命令派生子进程（如 `scripts/run_tests.py` 调用 `javac`/`java`）时，`ru_maxrss` 反映**进程树中单进程峰值上限**。
- **`peak memory footprint`（`ri_phys_footprint`）**：仅统计**直接子进程自身**的物理脏页+压缩内存峰值。因此对于单进程 `macapk-runtime`（含 in-process HotSpot），`peak_footprint_mib` 直接对应运行时自身（JVM 堆+元空间+native 映射私有页）内存，通常比 `max_rss_mib` 低约 ~28–36 MiB（即共享只读映射如 `libjvm.dylib`/framework 共享页不计入 footprint）；而对 Python 驱动 `run_tests.py` 的 `harness_runtime_fixture`，`footprint_mib = 15.0 MiB` 仅为 Python 父进程，`max_rss_mib = 266.6 MiB` 才是其启动的 `javac/java` 子进程峰值。
- **Java 堆 / 元空间 / GPU 显存细粒度拆分**：当前未启用 JFR / NMT (`-XX:NativeMemoryTracking`) 或 Metal 显存追踪，列为 `OBS-001` 后续补齐。

---

## 3. 重复测量结果汇总（每项 1 轮 Warmup + 3 轮正式采样）

> 下表展示**中位数（median）与全区间 `[min, max]`（spread = max - min）**；完整逐轮值见第 4 节或 `work/upgrade/PERF-001/summary.json`。

| 基准工作负载 | Wall Time `real_s`<br>median `[min, max]` | CPU Time `user+sys` (s)<br>median | Peak RSS (MiB)<br>median `[min, max]` | Peak Footprint (MiB)<br>median `[min, max]` | 上下文切换 `vol / invol`<br>median (次/进程) |
|---|---|---|---|---|---|
| `extract_cold_comp-ui-resources` | **0.660** `[0.59, 0.72]` (0.13) | 0.86 | **250.72** `[246.00, 252.94]` | **221.52** `[216.84, 223.77]` | 59 / 2,267 |
| `extract_cold_comp-surface-media` | **0.710** `[0.60, 0.72]` (0.12) | 0.96 | **279.47** `[279.20, 279.59]` | **250.33** `[250.03, 250.42]` | 67 / 2,783 |
| `extract_cold_comp-service-ipc-abi` | **0.730** `[0.61, 0.79]` (0.18) | 0.99 | **277.92** `[246.16, 278.64]` | **248.80** `[217.03, 249.49]` | 77 / 2,812 |
| `boot_audio_cold` (`AMEDIA`+`MediaSelfTest`) | **0.370** `[0.36, 0.38]` (0.02) | 0.34 | **82.95** `[82.77, 84.02]` | **55.05** `[54.92, 56.09]` | 53 / 1,800 |
| `boot_audio_warm` (`AMEDIA`+`MediaSelfTest`) | **0.140** `[0.14, 0.16]` (0.02) | 0.20 | **83.41** `[82.78, 83.77]` | **55.52** `[54.91, 55.92]` | 32 / 1,062 |
| `boot_video_cold` (`VideoImageSelfTest`) | **3.720** `[3.71, 3.73]` (0.02) | 1.03 | **452.20** `[449.86, 453.25]` | **416.25** `[413.89, 417.33]` | 58 / 5,113 |
| `boot_video_warm` (`VideoImageSelfTest`) | **3.510** `[3.49, 3.55]` (0.06) | 0.89 | **448.48** `[445.58, 455.78]` | **412.50** `[409.67, 419.83]` | 33 / 4,401 |
| `harness_runtime_fixture` (`run_tests.py`) | **1.520** `[1.52, 1.53]` (0.01) | 2.60 | **266.59** `[266.11, 266.69]` | 15.03 *(仅 Python)* | 48 / 9,847 |

---

## 4. 启动阶段拆解与逐轮明细

### 4.1 冷启动 vs 热启动 `[boot-clock]` 阶段耗时（`comp-surface-media`）

> 测量条件：`MACAPK_WINDOW_FIRST=0 MACAPK_AWT_MAIN_INIT=0` 无窗口模式；冷启动每轮销毁并重建 `--android-root`，热启动复用同一缓存目录。

| 里程碑（累计 ms，从 `execve` 起） | `boot_audio_cold`<br>R1 / R2 / R3 (median) | `boot_audio_warm`<br>R1 / R2 / R3 (median) | `boot_video_cold`<br>R1 / R2 / R3 (median) | `boot_video_warm`<br>R1 / R2 / R3 (median) |
|---|---:|---:|---:|---:|
| `main() entered` (dyld+stub) | 9 / 9 / 9 (**9**) | 9 / 9 / 9 (**9**) | 11 / 11 / 11 (**11**) | 11 / 10 / 12 (**11**) |
| `android root ready` (解包/GLES扫描/DEX) | 254 / 245 / 252 (**252**) | 37 / 38 / 37 (**37**) | 249 / 250 / 252 (**250**) | 42 / 40 / 43 (**42**) |
| `JVM create begins` | 272 / 262 / 270 (**270**) | 50 / 50 / 49 (**50**) | 267 / 268 / 270 (**268**) | 55 / 53 / 57 (**55**) |
| `JVM created` | 325 / 315 / 323 (**323**) | 102 / 104 / 102 (**102**) | 320 / 321 / 323 (**321**) | 109 / 106 / 109 (**109**) |
| `JNI natives registered` | 335 / 325 / 333 (**333**) | 111 / 114 / 111 (**111**) | 330 / 331 / 333 (**331**) | 119 / 117 / 119 (**119**) |
| `bionic shim loaded` | 346 / 336 / 344 (**344**) | 122 / 124 / 122 (**122**) | 337 / 337 / 339 (**337**) | 126 / 124 / 126 (**126**) |
| `Boot.run entered` | 349 / 339 / 346 (**346**) | 125 / 127 / 125 (**125**) | 340 / 340 / 342 (**340**) | 129 / 126 / 129 (**129**) |
| **冷→热净差值**（`Boot.run entered`） | **-221 ms**（主要来自 `android root ready`: 252 -> 37 ms） | — | **-211 ms**（`android root ready`: 250 -> 42 ms） | — |

### 4.2 安装解包阶段（`--extract-only`，`VectorDrawable` 图标触发安装期 JVM）

- **`dex_to_jar_ms`**：1–4 个类在预热后稳定为 **1–8 ms**（首次冷 warmup 为 `29 ms`）。
- **`arsc_parse_ms`**：0–1 ms。
- **`framework resolution view`**：因本工作区共享磁盘缓存已存在，3 轮冷 `--android-root` 解包均命中磁盘缓存（`replayed 1343 styles, 10869 values from disk cache in 16–17 ms`）；首次无磁盘缓存建缓存成本在 `COMP-001` 首轮日志实测为 `227 ms`。
- **安装期 JVM 成本**：因无光栅 mipmap 而触发 `Boot.extractIconOnly` 时，即使仅绘制 1 张 `VectorDrawable icon.png`，单次 `--extract-only` 仍消耗 **0.66–0.73 s** 墙钟时间与 **221–250 MiB** 物理 footprint（对比不运行 `Boot.extractIconOnly` 的 `boot_audio_warm` 仅 `55.5 MiB` footprint）。

### 4.3 已确认的观察现象与瓶颈假设（待进一步定位，不做性能定论）

1. **冷安装/冷启动主要消耗在 `android root ready`（~210 ms 差值）与安装期按需启动 JVM**：仅 1 个类的小 APK 在冷 `--android-root` 下仍需 ~210 ms 用于目录/APK 克隆、GLES 扫描缓存落盘与 DEX->JAR；而 `Boot.extractIconOnly` 启动 HotSpot + AWT/Java2D 导致轻量安装工具峰值 footprint 跳升至 ~221–250 MiB。
2. **`VideoImageSelfTest` 存在显著的等待时间与高内存占用**：解码 5 帧 64x64 H.264（3 帧输出）耗时 `3.51 s`（warm），其中 `user+sys` 仅 `0.89 s`，另有约 ~2.6 s 非 CPU 墙钟等待，且峰值 RSS 达到 `448.5 MiB`（`412.5 MiB` footprint，显著高于音频路径的 `83.4 MiB` / `55.5 MiB`）。是 VideoToolbox 初始化、轮询等待、还是 `ImageIO` / YUV buffer 分配所致，尚需精细剖分。

---

## 5. 观测盲区与 `OBS-001` 最小观测需求

| 指标类别 | 本轮状态 | 未覆盖原因 / 缺失采集点 | 转入 `OBS-001` 最小需求 |
|---|---|---|---|
| **交互式 GUI 空闲/滚动 CPU、RSS、唤醒** | `NOT_RUN` | 本轮无 Sidecar 且未启动交互 Cocoa 窗口；短生命周期离线自检无法替代 120s 窗口空闲稳态观测 | 在可安全展示窗口或 Sidecar 连接条件下用 `profile_process.py` 采样 60–120s 稳态与唤醒 |
| **WindowServer / Sidecar (`avconferenced`) 成本** | `NOT_RUN` | 未开启 GUI 窗口合成且未连接 iPad Sidecar | 使用 `profile_process.py --system-metrics` 与显式扩展屏布局校验采集 WindowServer CPU 与 GPU % |
| **实际显示帧间隔 `p50/p95/p99` 与丢帧率** | `未采集` | 无 `CAMetalLayer` / `CADisplayLink` / `Choreographer` 对齐的真实呈现时间戳导出（禁止用回调 FPS 冒充显示 FPS） | 在 runtime 呈现边界提供环境变量开关控制的轻量呈现时间戳环形缓冲/日志 |
| **Metal GPU 时间戳与显存** | `未采集` | 公共 `ioreg IOAccelerator` 仅提供整机利用率百分比，无每进程/每帧 GPU 耗时 | 增加 Metal command buffer GPU start/end 时间或 Xcode/Metal 计数器采样钩子 |
| **Readback 字节与同步耗时** | `未采集` | 运行时未暴露每帧 readback 字节计数与 waitUntilCompleted 阻塞时间打点 | 在关键 readback 路径增加开关控制的计数与耗时汇总 |
| **解码/渲染队列深度与等待** | `未采集` | 仅可观测到 `VideoImageSelfTest` 总墙钟 ~3.51s vs CPU 0.89s 的差值 | 增加 `MediaCodec` / Surface 队列深度与阻塞耗时计数器 |
| **真实功耗 / 能量消耗** | `未采集` | 无 root `powermetrics` 采样，不将 CPU/RSS 等价于电池续航 | 后续在需要能耗对比时引入 `powermetrics` / 电池统计 |
| **进程树内存与 JVM 细分（堆/元空间/NMT）** | `部分采集` | `/usr/bin/time -l` 仅给单进程 footprint 与跨子进程 max RSS | 必要时通过 `footprint -p` 与 JVM NMT 拆分堆内/堆外原生页 |

---

## 6. 复现命令

```bash
python3 debug/perf/measure_offline_baseline.py --out-dir work/upgrade/PERF-001 --rounds 3
```
