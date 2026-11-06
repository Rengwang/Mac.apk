# VIDEO-PWR-001：降低启动、空闲与视频播放功耗

- 状态：IN_PROGRESS
- 用户目标：视频播放（含弹幕）接近原生软件的低功耗与流畅度。
- 用户补充：刚打开就高功耗；关闭弹幕仍明显高于同机 MuMu 和 Bilibili Universal。
- 基础提交：2fc47a1；分支 codex/perf/video-power-001。
- 本任务优先于原顺序中的通用基础建设，相关测试与测量随目标实施。

## 验收范围

1. 区分冷启动、初始化后无操作、固定视频无弹幕、同视频有弹幕。
2. 对齐实际 runtime、APK、播放位置、分辨率、窗口大小与显示刷新率。
3. 测 CPU/线程热点、内存、GPU/唤醒与适用能量指标；CPU 不等于功耗。
4. 与同机原生/iPad 客户端及 MuMu 的可比场景对照，报告环境与不等价部分。
5. 优化需保留画质、视频速度、音画同步、弹幕、输入和前后台恢复；不能降帧伪造改善。
6. 重复前后测量超过噪声范围；相关通用回归通过，实际用户场景验收。

## 已获得的证据

- 独立 APFS 数据副本；重定向 shared-storage 与正式数据/原生库链接，启动前验证无指向副本外的文件链接。
- 首次 45 秒探测主要是启动初始化，不能充当稳定空闲基线。
- 直接 runtime CLI 缺少 stub 的 quiet-classload 与四项兼容默认值；后续采集显式对齐。
- 新增 debug/perf/profile_process.py：用累计 CPU 时间差计算区间占用，独立记录 RSS、sample 和异常。
- 原始日志/数据仅在被忽略的 work/upgrade/VIDEO-PWR-001；不提交账号数据或 APK。

## 当前限制

固定视频的 EGL10 Metal 实验路径已取得单进程 CPU 改善，但默认路径尚未启用，
启动后的空闲基线已稳定复测，但降低空闲重绘的 `MACAPK_FRAMESPEED_HEARTBEAT=1`
仍是显式实验开关，尚未改为通用默认。弹幕负载、Vulkan、GPU合成本身及实际能耗
仍未完成；弹幕开关需要独立、可观察的状态断言。窗口缩小后的播放缓冲仍需继续
调查。运行中采集的句柄与结果必须继续核对。

2026-11-03 补充：已用公开 IORegistry 与 `powermetrics` 对齐应用、WindowServer、
GPU 和能耗估计。同一 11 分钟视频的一对 OFF/ON 样本显示应用 CPU 均值
54.59%→31.83%、WindowServer 20.64%→17.01%，任务能耗估计下降约 44%，
系统组合功率估计下降约 31%；GPU 能量估计上升约 23%，RSS 从约 426 MiB 升到
580 MiB。因为只有一对且未强制播放位置归零，该项仍是探索性证据，不能据此切换默认。

已确认 `https://b23.tv/<BVID>` 可由 macOS 交给隔离测试外壳，经
`tv.danmaku.bili.ui.intent.IntentHandlerActivity` 直达对应视频。该方式可用于后续
固定视频复测；它不表示普通网页链接已默认交给正式 Mac.apk，也不表示生产行为改变。

固定短链 ABBA 复测已按 OFF/ON/ON/OFF 顺序完成四轮，每轮预热
60 秒、采样 30 秒并保留两个视频截图。相邻配对里 ON 的应用 CPU 降低约 29.9% 和
13.6%，但 WindowServer CPU 升高约 43.6% 和 61.1%，GPU 利用率升高约 54.1% 和
38.6%。因为后两轮整体负载明显漂移，且这组尚未采集能耗，不能确认整机节电；共享
EGL 继续默认关闭。

同内容 9.16.1 iPad 版也通过同一短链进入目标视频。其 30 秒参照为应用 CPU 21.23%、
WindowServer 17.88%、GPU 71.27%、RSS 426.0 MiB；窗口为 970×770，和
360×640 的 Mac.apk 探针不完全同条件。原生二进制直接链接 AVFoundation、CoreMedia、
VideoToolbox、Metal/UIKit；Mac.apk 另有 JVM、ARM 转译、IJK、ANGLE/MoltenVK、
SurfaceTexture 和 Canvas 合成，当前优化重点应继续放在移除这些兼容层的逐帧搬运。

跨应用心跳证据仍不充分：酷安浏览页的连续动效会阻止 30→60→120 扩展，但未失效
invalidate 的变化被 diff 安全网捕获；倒数日因缺少 `libcldp.dylib` 和初始化异常，
不能算有效兼容样本。

## 第一轮结果

详见 [诊断记录](../VIDEO_POWER_PROGRESS.md)。已完成采集，非有效播放基线；目标保持进行中。

## 随航与视频探测

副屏窗口已通过WindowServer边界和截图确认。视频详情触发硬件解码，但 EGL 初始化失败，备用路径报告缓冲尺寸不一致；详见调查记录。未证明视频真正呈现。

## 补充：测试外壳修正

补齐缺失 Vulkan ICD 后 EGL 初始化成功，实际视频帧推进；第二轮 EGL 失败是测试环境混杂，不能当作产品根因。真实播放单进程 CPU 初测平均约72%，详见调查记录。

## 读回热点已定位

播放线程反复走 MacApkGlWindow.queueFrame 的同步 GPU→CPU 读回；998×1480、前600帧约3.54GB，glReadPixels平均15.80ms。统计开关默认关闭。下一步设计通用可回退GPU路径并验证画面。

## IOSurface 原型

ANGLE Metal 的独立 IOSurface EGL 渲染/像素探针通过；Vulkan 相同探针结果全零，原因未明。生产GPU路径及性能收益尚未证明；详细条件见调查记录。

## EGL10 Metal 私有 display 验证

热点 EGL10 明确使用私有 ANGLE Metal display：相同 token 的 IOSurface 探针在完全关闭 GL 读回时像素正确。Vulkan 的 GL 像素正确但 IOSurface 和 Metal 纹理视图为零。接入生产前需验证异步同步和 TextureView 合成契约。

## 三缓冲 Metal 原型

私有 EGL10 Metal display 中 998×1480、60帧、四种 RGBA 值和三个位置的 EGL→IOSurface→Metal GPU blit 校验通过；回收前等待消费者。同步是保守 glFinish，仍待接入真实 TextureView 与完整能耗对比。

## 可复用共享帧池

ANGLE可用时运行时包含隔离的三槽 EGL/IOSurface 帧池，实测 GPU 颜色、FIFO、回压、双实例令牌隔离及销毁；当前无生产调用者。下一步 JNI/TextureView 和可回退合成，尚无节能结果。

## JNI 桥

增加内部共享帧池 Java/JNI 接口，完整构建和独立 JVM `-Xcheck:jni` EGL 像素/生命周期测试通过；GUI临时外壳因签名结构需修复，实际 APK 测试未运行。播放路径尚未接入。

## 临时应用启动验证

修复隔离测试外壳的签名及内部资源布局；严格校验通过，新运行时在 Sidecar 上启动 B 站并显示首页。JNI 帧池仍无播放器调用者，尚未取得性能改善。

## 共享帧兼容消费

已验证私有 Metal display 的 BGRA 行方向，并实现仅对已获取帧进行的 stride、透明度和翻转兼容拷贝；JVM/EGL像素测试及完整构建通过。CPU拷贝仍非最终零拷贝，尚未接入播放器。

## EGL10 Metal 共享帧实验路径

已把共享 IOSurface 接入 EGL10 `MacApkGlWindow` 的显式实验开关
`MACAPK_GL_WINDOW_SHARED_EXPERIMENT=1`；默认仍走原 GL readback。像素语义、
失败回退、窗口 resize 迁移和旧池回收均有测试。JNI 写入改用 critical array
避免第二份整帧回写；独立 JNI、完整 runtime、逐像素 headless 对照和真实视频
均已通过。相同 832x1480 视频下，最终 ON 单进程 CPU 均值/中位数
37.67%/32.63%，OFF 70.47%/64.61%。

该结果只证明 Metal EGL10 视频消费热点显著下降，尚不是 GPU、WindowServer、
待机或能耗总量结论；Vulkan 未接入。缩小窗口后 Canvas 为 720x1280，但播放器
SurfaceTexture 仍保留 832x1480 缓冲，未触发共享池 resize。触摸事件能到达
Android，暂停、弹幕及普通内容页签在自动点击下仍未观察到预期状态，需单独复验。

当前只提交本地测试分支，不安装、不合并、不替换正式 APK 或用户数据。

## 正式启动条件下的空闲 A/B

直接 runtime 测试显式补齐正式启动的 `MACAPK_LOG_LEVEL=4`、
`MACAPK_QUIET_CLASSLOAD=1` 和四个 Surface/Choreographer 默认值。同一测试外壳、
同一 APK/独立数据副本、360×640 逻辑窗口、Sidecar 60 Hz，先预热 120 秒，再对
B 站静止推荐首页连续采样 120 秒；三轮旧心跳与三轮
`MACAPK_FRAMESPEED_HEARTBEAT=1` 交替验证，均确认窗口在 Sidecar 且首页可见。

| 模式 | 三轮 CPU 均值 | 合并平均 | 三轮中位数 | 中位数平均 |
|---|---:|---:|---:|---:|
| 默认 30-vsync 心跳 | 14.718 / 14.811 / 15.378% | 14.969% | 11.892 / 11.811 / 11.896% | 11.866% |
| 自适应 30→60→120 心跳 | 11.839 / 11.524 / 11.642% | 11.668% | 7.891 / 8.947 / 6.939% | 7.926% |

单进程平均 CPU 降低约 22%，中位数降低约 33%；所有 720 个对照采样点均未超过
50%，100% 表示一个逻辑核心。该数据不包含 GPU、WindowServer、子进程或能耗。
自适应模式在静止页把每 300 个显示回调中的 Java 遍历从约 10 次降到约 2–3 次。

开启实验开关后的实际点击验证通过：直播页签切换、返回推荐、打开视频、开始播放、
暂停和关闭广告浮层都产生了预期可见状态。弹幕按钮未得到可重复、明确的开关状态
证据，继续标记 NOT_RUN。证据位于被忽略的
`work/upgrade/VIDEO-PWR-001/quiet-home-r{1-correct,2,3}/`、
`heartbeat-home-r{1,2,3}/` 和 `heartbeat-ui-verify-2.log`。

该优化仍为显式开关。翻转正式默认前需要更多应用类型覆盖、缺失 invalidate 的
回归样本和 GPU/能耗数据；不能用 B 站单场景通过替代跨应用验收。

## Surface IOSurface 最终交付路径

在稳定共享 EGL 路径之后，新增默认关闭的
`MACAPK_SURFACE_IOSURFACE_PRESENT=1`：把现有预乘 BGRA 像素写入按 SurfaceView
key 管理的四槽 IOSurface 环，直接作为 `CALayer.contents` 交付，绕开逐帧
`CGImage` 的 prepare、copy 和颜色转换。任一步失败仍走原路径；
`MACAPK_PRESENT_IOSURFACE_CS` 可分别选择 `none`、`device`、`srgb` 或窗口实际
显示器 profile。

同一 11 分钟视频的 180 秒长测稳定段中，原 `CGImage` 路径 CPU + GPU 功率约
4.1–5.0 W，新路径为 2.664–3.633 W；应用 energy impact 从 965–1045 降到
513–687，应用与 WindowServer CPU 均下降，GPU 功率小幅上升。r24 的 RSS 仍
明显高于原路径，部分差异尚未解释。

SurfaceView layer 移除时现已释放对应 IOSurface 环，防止多视频切换累积；r25c
20 秒短测通过，没有 busy fallback。`display` 色彩模式也改为读取窗口实际所在
显示器的 ICC profile，r26 已在 Sidecar 取得 3380 字节 profile；该模式短测的
WindowServer 和 GPU 开销高于同长度 `none`，所以低功耗候选使用 `none`。

用户已确认该设置下播放功耗较低。当前仍缺少动态长时间人工验收以及暂停/恢复、
seek、弹幕、resize、surfaceDestroyed、前后台恢复；在完整回归前保持显式实验，
不切默认、不安装、不合并。

## 共享 GL 功耗反弹复核

同视频、同副屏、同色彩模式和同自适应心跳下只切换共享 GL：组合功率
8.219 W（共享 GL）对 5.893 W（Surface IOSurface），共享 GL 高约 39.5%。
共享 GL 虽降低应用 CPU 与应用 energy impact，却把 WindowServer energy impact
提高约 71%、`avconferenced` energy impact 提高约 4.6 倍，GPU 功率提高约
132%。因此“应用 CPU 低”不能作为节能结论，后续优先消除 `copyArgb`、重复
Canvas 合成与 Sidecar 重复编码。返回箭头、tint 日志和自适应心跳均已排除为
本轮反弹的主因。

## 2026-11-04：静态页空闲呈现门控

新增默认关闭的 `MACAPK_IDLE_PRESENT_GATE=1`。纯 View/Canvas 页面在没有 traversal、
Surface 新帧或尺寸变化时跳过主机侧重复呈现，保留 500 ms 低频安全心跳；连续 GL、
原生 EGL、解耦呈现、首帧和 resize 仍走原路径。静态 B 站推荐首页三轮 OFF/ON
交替测试均使用同一测试外壳、APK、360×672 窗口和内建屏（本轮 Sidecar 已断开）：

| 三轮截尾均值 | OFF | ON | 变化 |
|---|---:|---:|---:|
| 应用 CPU | 15.99% | 10.22% | -36.1% |
| WindowServer CPU | 24.04% | 23.72% | -1.3% |
| GPU 设备利用率 | 20.64% | 20.31% | -1.6% |
| 应用 energy impact/s | 301.62 | 239.64 | -20.5% |
| CPU+GPU 组合功率 | 1.011 W | 0.947 W | -6.4% |
| GPU 功率 | 79.50 mW | 81.21 mW | +2.1% |

门控开启时每 300 个显示回调通常只执行 9–13 次，其中约 9–10 次来自安全心跳；
交互时会按 traversal 请求立即恢复工作。实际回归中首页滚动、切换页、打开普通视频、
返回首页和视频帧推进均正常，返回首页后跳过率恢复到约 97%。暂停按钮仍无法由当前
自动输入稳定断言，暂停/恢复继续标记 NOT_RUN。

证据：`work/upgrade/VIDEO-PWR-001/idle-gate-home-{off,on}-r{1,2,3}/`、
`idle-gate-home-summary.json` 和 `idle-gate-interaction-r1/`。该开关仍保持默认关闭，
提升默认前需要跨应用动画/输入回归和 Sidecar 复测。

## 2026-11-04：全屏旋转停顿转交 GFX-001

普通视频播放时进入全屏和旋转到横屏会触发 `resources.arsc` 全量 ingest、framework
资源解析和 `ResourceIndex` 同步重建。实测 framework 解析为 577–871 ms，资源索引
重建为 847–2001 ms，之后才执行 `onConfigurationChanged` 和 Surface resize；退出
全屏还出现 `display commit ... not applied after 30 frames`。该停顿位于 Mac.apk
配置切换链路，已转交 [GFX-001](GFX-001.md)。本轮没有把 B 站播放器重建单独归因，
也没有通过停止音频、忽略旋转或重新启用原生直挂来规避现象。

后续静态对照确认 Bilibili 9.16.1 的视频详情 Activity 已声明自行接收
`orientation`/`screenSize` 等变化，其 `onConfigurationChanged()` 覆写没有主动重建播放器。
Mac.apk 仍稳定出现旧尺寸 Surface 先提交、最终尺寸后提交和横屏半幅布局，因此旋转停顿与
布局异常继续归入 `GFX-001` 待修；父类/播放器组件响应和声音中断的精确耗时仍待时间线验证。
