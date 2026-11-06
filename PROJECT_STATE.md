# 当前工程状态

更新日期：2026-11-06（Asia/Taipei）。本文件区分“已检查”“历史记录”和“本轮实测”。

## 当前工作

- **2026-11-06 GitHub 发布完成**：升级源码检查点 `7a9b6d9` 已发布至用户仓库的 `main`
  与 `codex/fix/gfx-rotation-20261105`；两项实验实现及测试已包含，仍默认关闭。
  经用户明确授权，聊天记录已移出两个分支的可达历史；重新完整裸克隆确认历史条目为 0，
  未获取到记录 blob。GitHub Support 已创建旧 SHA 缓存清除工单，尚待处理，未显示工单号。
  实现、测试与功能待验收状态保持下文结论；完整核验与工单状态见主线整合任务卡末尾。

- **2026-11-06 正式本机更新完成**：用户要求本地 Mac.apk 使用全部最新改动后，已从干净
  源码 `476cd05` 执行完整 runtime 构建并重新打包，安装版为 `261106.0819-476cd05`。
  `/Applications/Mac.apk.app`、共享 runtime、4 个已安装应用启动器与 process broker
  注册均已核验；`installed=true`、`stale=false`，`NATIVE_ID=2140bedbbc35c18c`。
  先前单独编译未将 native/shim 完整暂存进包的问题已修正，编译→暂存→包→共享目录的
  UUID/JAR/build-id 链通过。备份、命令与验收边界见主线整合任务卡末尾；本轮未启动 GUI，
  不据此关闭既有音画/全屏任务，实验开关保持默认状态。

- **2026-11-06 主线整合**：按用户要求，把 `codex/fix/gfx-rotation-20261105` 的
  帧诊断提交及保留的 broker、Surface、窗口和诊断改动整理入主线；验证与回退入口见
  [MAIN-INTEGRATION-20261106](docs/tasks/MAIN-INTEGRATION-20261106.md)。本轮 Java、
  native runtime、shim、launcher 构建及离线回归通过；没有启动 GUI 或改动正式安装。
- 最新现场来自前一聊天：两个视频曾见移动弹幕，后续多次缩放未见持续右侧白带；
  这是历史局部证据。旧 key1 与新 key4/key5 的 rect 差异、弹幕完整开关/重启、
  播放器竖横全屏和切换后至少 30 秒音画连续性仍未关闭，GFX-001 / DANMAKU-001
  均保持 PENDING。HUD 提交帧率不能代替实际显示帧率或声音验收。
- 裁切 BitmapShader 缓存和 4 GiB 返回地址恢复均保持默认关闭；不改默认 GC、
  缓存上限或图形后端。以下按日期保留的安装、暂停、锁屏与进程记录均为历史现场，
  不代表本轮的运行状态。

- **2026-11-05 约 17:51 用户要求暂停并交其他 Agent。** 本轮已安装
  `261105.1746-f663380+dirty`（JARS_ID `d0a27e5715db04b8`），签名及正式/共享
  runtime build-id 对照通过。新增通用服务启动异步完成协议，4 worker/64 队列避免
  Binder receiver 启动等待死锁；manifest 多进程默认使用独立 `org.macapk.processes`
  按需 broker，不依赖 Google GMS 开关。无 APK 特例。路由 fail-before/pass-after，
  显式 opt-out、32 binds/16 receivers、队列拒绝、取消/generation/null-bind 回归通过。
  **正式普通启动 PID 78093** 已 attached 到新 endpoint；broker PID 78184，下载子进程
  PID 78245，另一 worker PID 78353。两个热门视频（97.1万播放《网恋 VS 奔现》、
  262.5万播放《到底多绝望，才会走出这样的步伐…》）均截图见移动弹幕；第二个关闭后
  消失，但重新开启被 CUA “user changed”保护拒绝，故 off/on 完整矩阵未通过。
  所有 GUI 本轮在内屏；默认启动未额外设置 BROKER。仍继承宿主原有渲染实验/省电
  环境变量，不能声称清洁默认性能或60FPS。**勿宣告 DANMAKU-001 完成**：二次开启、
  重启、resize、竖横全屏、音画持续验收未完成；先前隔离探针 PID 59160 在
  `PlatformRunner.nativeFireTask` 崩溃仍待调查。新普通运行未重现该崩溃。
  回退备份 `work/upgrade/DANMAKU-001/resume-1545/Mac.apk.before-processes.app`
  及 `shared-runtime-before-processes`。本轮未提交/推送，旧改动均保留。
  详细接手说明在任务卡末尾。该条记录的是 17:51 现场；17:56 主 UI 已因 SIGBUS
  退出，当前没有可用于验收的 B 站窗口。
- 2026-11-05 18:20–18:33 接续：新增用户 LaunchAgent 回退，当 ad-hoc 包无法由
  `SMAppService` 注册 `org.macapk.processes` 时写入 App Support plist 并以
  `launchctl bootstrap` 加载；注册前校验真实 job，而不是只看 `SMAppService` 的
  `.enabled` 状态。真实 fallback 连续两次运行分别为“loaded”和“already loaded”，
  第二次未重启 job，证实 path/helper/argv 与冷启动幂等性。清除了用户 launchd
  domain 中残留的 18 个 `MACAPK_*` 诊断/渲染变量，新 fallback job 不再继承
  `MACAPK_AUDIO_PROBE`、`MACAPK_PROBE_LOG`、`MACAPK_LOG_LEVEL` 等旧环境。隔离
  launchctl、最终 Swift 单测、32 cold binds/16 receivers/4+64 队列、remote-bind
  generation、z-order 和 bundle 签名均 PASS。崩溃仍是 34 秒 SIGBUS/BUS_ADRALN，
  两次 PC/LR 可精确减去 4 GiB 后落入 `libchronos.so+0x1d176c` / 旧样本
  `+0x202508`；下一步用干净环境加 `MACAPK_FAULT_DUMP=1` 复现并采集 fp-chain，
  不能把地址静默归零。弹幕真实开关矩阵和第二条热门视频仍待 Mac 解锁后复测。
- 2026-11-05 19:01–19:03 接续：已修正 `MACAPK_FAULT_DUMP` 自身的递归崩溃点。
  原诊断无条件解引用 `x22` 并把 `[fp, fp+16)` 当有效栈帧；遇到压缩 oop
  `0x05780155` 时先制造第二个 SIGSEGV，随后 guest handler 不推进 PC，最终把真实
  SIGBUS 掩盖成 `macapk_segv_handler+0x2cc4`。现在两处都经
  `mach_vm_read_overwrite` 的 fault-free helper 读取，不可读时只打印地址并停止该链。
  本地 runtime 增量构建 PASS，build `NATIVE_ID=a6a3faa0b3a32f3d`；清洁环境直接冷启动
  运行超过 90 秒，期间多次 `SIGBUS/BUS_ADRALN` faultdump 均被 guest handler 恢复，
  没有新 `hs_err`，最终由测试者主动终止。18:57 的首个失败样本当前以“诊断器递归造成
  致命退出”解释；原始 BUS_ADRALN 是否还有独立致命性，仍需后续真实视频/长时复现确认。
  未替换正式包或共享 runtime。同批 manifest routing、32 cold bind/4+64 queue 和
  z-order 裸 JVM 回归均 exit0；弹幕完整生命周期和音频 A/B 仍待 Mac 解锁。
- 2026-11-05 后续接手：核对 `4706bf2` 与历史移动弹幕验收，核心 Surface/alpha/looper
  修复仍在。临时方法探针定位新链路：ViewProgress 返回有效 Chronos 参数，但内容包
  obtain 卡在下载 latch；声明于 `:download` 的服务在主进程创建，反复发生客户端代理
  cast 服务端实现异常。隔离副本启用现有 broker 后成功启动下载子进程，又实测到 broker
  处理线程耗尽、子进程 attach 与主 UI 广播均等待同步 IPC。未改产品源码或全局 GMS；
  测试进程和临时 launchctl job 已清理。下一步修通用异步服务启动/进程路由，再验收
  下载包→native→移动弹幕；详见 DANMAKU-001 新增接续段及 `resume-1545/` 证据。
- 2026-11-05 接续：用户要求通用优化界面至稳定 60 帧并修复弹幕。当前先验收 GFX-001：
  Sidecar 窗口化铺满后的推荐/热门/视频详情页滚动，以及**播放器右下角按钮**进入/退出
  竖屏全屏、横屏全屏和两者往返。用户确认切换时**声音也短暂停顿**，须做音画连续性
  验收；macOS 系统全屏和 `View > Rotate` 不能替代该项。当前实施 DANMAKU-001，GFX-001
  保留未完成状态和现有变更。
- 本轮已验收 Sidecar 窗口化铺满 `1149x740` 内容区，首页/视频/返回保持尺寸；热门页
  预热滚动末两组各 300 帧 total p90 4.4/4.3 ms（Guest/CPU 代理指标，非实际显示 FPS）。
  推荐页大封面仍约 25 ms 软件采样。撤回了上轮退化的 packed rounded blit 实验；
  新裁切缓存原型因边界舍入反例默认关闭。
- DANMAKU-001 已修正 media-overlay 请求被先前 on-top 吞掉的通用 API 差异；失败回归
  转通过，安装态 native 层级 2 → 1 已验证。最新安装版 `261105.1137-f663380+dirty`，
  JARS_ID `eba4fe6bf152d3fe`，已同步共享运行时并通过签名/identity 核验。两段视频的
  弹幕开/关/再开仍未见移动文字，**尚未修复弹幕**。已确认命名含 danmaku 的 key 1
  是视频输出；透明 key 2 提交稀少，但多个 native Choreographer 采样正常进入/返回。
  播放器竖/横屏往返、返回详情保持副屏铺满，声音仍有 PLAYING 欠载。详细证据和限制见
  `docs/tasks/DANMAKU-001.md`；下一步查弹幕数据/渲染状态、alpha 覆盖和 native 消息链。
- 播放器竖屏全屏已复现 677.6 ms 慢帧和 3 次音频欠载；进一步记录到 Serial Full GC
  最长 453.674 ms。同 JVM/1 GiB 堆的 G1 独立样本最长 GC 暂停 20.215 ms、0 Full GC，
  正确保持播放的横屏切换连续 8 个音频聚合均无欠载，但首次竖屏仍有 439.4 ms 慢帧。
  G1 未改为默认，完整 ABBA/多应用/内存/功耗尚待验证；详见 GFX-001 本轮记录。
- 2026-11-05 Sidecar 预热后矩阵再次确认：进入竖屏全屏首窗 4 次、下一窗 2 次音频欠载；
  竖屏→横屏切换后先连续两窗为零，第三窗仍有 1 次欠载；横屏→竖屏紧随窗口 1 次，
  退出详情后再 1 次。画面与声音必须持续观察至少 30 秒，不能以切换后首窗通过作为结论。
  过滤证据见 `work/upgrade/DANMAKU-001/audio-matrix-serial-20261105.log`，任务保持 PENDING。
- 阶段：VIDEO-PWR-001 性能实验分支；BASE-001 构建基线已完成。
- 当前任务：在独立数据副本中降低 B 站启动、空闲和视频播放开销。
- 待办：`DANMAKU-001`。用户报告安装版视频页弹幕再次不显示；先从固定视频做弹幕开/关/再开、
  窗口 resize、全屏和返回回归，再定位独立 Surface、透明层、Choreographer 或状态按钮中的
  失效环节。历史修复依据见 `docs/tasks/DANMAKU-001.md`。
- 当前状态：EGL10 Metal 共享帧、Surface IOSurface 交付与自适应空闲心跳均有本机收益，但仍是显式实验路径，正式安装未改变。
- 2026-11-05：已修复 BitmapShader 每像素数组分配并复用 PaintContext raster；855,837 次像素对照通过，六缩略图受控绘制分配约 20/101 MB 每帧降至 36 KB。实际横屏全屏仍有 60–80 ms 软件 draw 帧，GFX-001 保持 PENDING；详见任务卡新增段。用户现要求选择性集成 kksimp/Mac.apk 上游更新，先保留当前改动并在独立集成分支评估。
- 最新证据：`docs/VIDEO_POWER_PROGRESS.md` 第二十二至二十七轮；原始日志在 `work/upgrade/VIDEO-PWR-001/`。
- 静态页空闲呈现门控已完成三轮内建屏 A/B：应用 CPU 15.99%→10.22%，应用 energy impact -20.5%，CPU+GPU 组合功率 1.011 W→0.947 W；WindowServer 基本持平，GPU 功率小幅上升。开关仍默认关闭，待跨应用与 Sidecar 复测。
- 门控交互回归已通过首页滚动、切页、普通视频打开、视频帧推进和返回；暂停控件仍无法可靠自动断言。
- 全屏竖横屏切换已复现到 Mac.apk 配置链路：基线 `ResourceIndex` 同步重建耗时 847–2001 ms，framework 资源解析耗时 577–871 ms，且退出全屏出现 display commit 等待 30 帧。资源缓存把首次全屏 847→224 ms、首次横屏 962→414 ms；第二次和第三次切换完整索引已命中，重建为 0 ms。新增全屏容器尺寸查询后，竖屏全屏可提前预热准确的 `1710x962dp land`，耗时 23 ms；首次实际旋转的严格配置键仍未命中但同步构建仅 22 ms，后续往返均为 0 ms。横屏仍有旧 `1208x680` 交换链先于最终 `3420x1440` Surface 的两阶段提交，并稳定复现横屏视频只在左侧窄区域、退出全屏后布局仍偏移；立即布局提交实验反而多出中间交换链，已撤回。最新内建屏 transaction 原型首次横屏仍为 373 ms、后续 0 ms，交换链直接使用最终尺寸，但等待分支未触发，且实现没有重新读取最终 holder geometry，源码已撤回；声音连续性和 Sidecar `3420` 场景仍待验收。`docs/tasks/GFX-001.md` 继续保持待修。
- B 站 9.16.1 源码对照进一步确认旋转问题应留在 Mac.apk 待修清单：视频详情 Activity 的清单自行接收方向/尺寸变化，`onConfigurationChanged()` 覆写只调用父类并记录配置，没有主动重建播放器；而 Mac.apk 仍出现旧尺寸 Surface 先提交、最终尺寸后提交和横屏半幅布局。父类/播放器组件响应与声音中断的精确耗时仍待时间线验证。
- 本轮执行期间 Sidecar 已断开，内建屏结果已明确标注；重新连接后仍需补随航对照。
- 本轮已验证完整 runtime 构建、JNI/像素/lifecycle、Sidecar 空闲基线和实际输入/播放；同一 11 分钟视频的一对 OFF/ON 样本中，应用和 WindowServer CPU、任务能耗估计及系统组合功率均下降，同时 GPU 能耗估计和内存占用上升。
- `debug/perf/profile_process.py` 现可同时记录应用、WindowServer 与 GPU 利用率，并为每段样本写入 UTC 起止时间，用于和 `powermetrics` 对齐。
- 固定 b23 短链已完成 OFF/ON/ON/OFF 四轮，四轮均进入同一视频并留在 Sidecar；相邻配对中 ON 降低应用 CPU，但 WindowServer、GPU 和 RSS 上升，且轮次漂移明显，所以不能据单对能耗结论切换默认。
- 同内容 9.16.1 iPad 版的 30 秒参照为应用 CPU 21.23%、WindowServer 17.88%、GPU 71.27%、RSS 426.0 MiB（970×770 窗口）；它直接链接 AVFoundation/CoreMedia/VideoToolbox/Metal/UIKit，当前差距主要来自 JVM、ARM 转译、IJK 和兼容合成层。
- Surface IOSurface 交付路径的 180 秒稳定段组合功率约 2.664–3.633 W，较原 `CGImage` 路径约 4.1–5.0 W 降低；应用 energy impact 下降约 40%–50%，主线程逐帧 `CGImage` 转换热点消失。
- SurfaceView 移除时已释放对应 IOSurface 环；`display` 色彩模式已改用窗口实际所在显示器，Sidecar profile 读取通过。低功耗候选继续使用 `MACAPK_PRESENT_IOSURFACE_CS=none`。
- 同条件复核确认共享 GL 的 CPU 局部收益会带来系统合成反弹：共享 GL 组合功率 8.219 W，原 Surface IOSurface 路径 5.893 W，前者高约 39.5%；WindowServer 和 Sidecar `avconferenced` 是主要增量。共享 GL 实验继续关闭。
- 弹幕开关、ABBA 同批能耗、Vulkan、默认路径切换与最低 macOS 兼容性仍未完成。
- 总任务入口：`docs/UPGRADE_PLAN.md`。
- 新手入口：`docs/UPGRADE_START_HERE.md`。

## 2026-11-01 只读检查确认的事实

| 项目 | 结果 |
|---|---|
| 用户原始仓库 | `/Users/xrdemac/Documents/Mac.apk-compat` |
| 本轮基础提交 | `ea1ace750a5a40528bc52610114a8c0a32eb0f40` |
| 基础提交标题 | Restore the exec bit on check-requirements.sh |
| 原始工作目录 | 检查时 `main` 干净；本轮不在该目录实施变更 |
| `origin` | `https://github.com/kksimp/Mac.apk.git`，上游 |
| `fork` | `https://github.com/Rengwang/Mac.apk.git`，兼容分支仓库 |
| 跟踪状态 | 本地 `main` 相对已缓存 `origin/main` 显示 ahead 55；未 fetch，不代表远端实时状态 |
| 当前任务分支 | `codex/perf/video-power-001` |
| 当前开发工作区 | `/Users/xrdemac/.codex/worktrees/macapk-upgrade-foundation/Mac.apk-compat` |
| 已有 CI | 基础提交只跟踪 `.github/workflows/cla.yml`，不是 runtime 回归 CI |
| 已有测试 | `debug/` 有 Java、Objective-C++、C++、Python 检查，尚待统一分类和运行入口 |
| 已有总手册 | `docs/MACAPK_UPGRADE_HANDBOOK.md` |
| APK 安全边界 | `SECURITY.md` 说明 APK 以当前 macOS 用户权限运行，没有手机式 sandbox |

## 证据与限制

- 已检查研究报告、仓库说明、升级手册、构建脚本、测试样例与 Git 元数据。
- 原有手册保存了 2026-10-29 的构建、测试和用户验收记录；这些是历史证据。
- BASE-001 已在本机重新完成 runtime/launcher 构建、bundle 依赖审计和 entitlements 检查。
- EGL10 Metal 共享帧实验在相同真实视频中把单进程 CPU 均值从 70.47% 降到 37.67%；默认渲染路径尚未切换。
- 自适应空闲心跳在三轮 B 站静止首页中把单进程 CPU 合并均值从 14.969% 降到 11.668%，中位数从 11.866% 降到 7.926%；该开关仍默认关闭。
- 11 分钟视频同视频探索对显示应用 CPU 均值 54.59%→31.83%，WindowServer 20.64%→17.01%，GPU 利用率 36.43%→36.77%；系统采样给出任务能耗、CPU 功率和组合功率下降，但 GPU 能量与内存占用上升。单对数据不能替代最终验收。
- 测试窗口均位于 Sidecar 副屏；直播页签、推荐页、视频打开、播放和暂停已人工验证，弹幕开关仍 NOT_RUN。
- Frame Timeline、统一 FrameScheduler、SurfaceRegistry、LayerCompositor、APK Doctor 等是待评估方向，不能作为现成测试依赖。
- 本工作区从 Git 提交创建；被忽略的本机依赖、framework-res 和构建缓存不会随 Git 自动复制。BASE-001 必须检查。

## 当前交付边界

只在 `codex/perf/video-power-001` 本地测试分支提交源码、测试和文档。没有推送、合并、发布、安装、替换正式应用/共享 runtime 或写入用户 APK 数据。

## 后续更新方式

每个任务完成后仅更新：当前任务/下一任务、最新验证摘要、证据位置和未解决阻碍。
详细日志保存在被 Git 忽略的 `work/upgrade/<任务号>/`；经脱敏的结论写入任务卡。

### 上游 1.0.2377 集成

已在任务分支集成上游 `8b45e48` 并保留本地兼容与性能工作；B 站匿名可执行代码中的 TLS 栈保护值读取崩溃已修复，两次真实运行越过原故障点。启动和 HEVC 解码器开启有日志证据，滑动、切换、全屏、实际画面与性能对比仍待验证。详见 `docs/tasks/UPSTREAM-2377.md`。

用户于 2026-11-05 明确要求：本项目以后每次生成供其测试的新版本后，直接替换 `/Applications/Mac.apk.app` 正式版并同步共享运行时及所需应用启动器，使已安装应用使用新构建；替换前保留可回退备份并核验构建。该要求覆盖本项目通用的“除非明确要求不替换正式安装”约定。2026-11-05 已安装上游集成版本 1.0.2377；此前正式版和共享运行时备份在集成工作树的 `work/upgrade/UPSTREAM-20261105/installed-backup/`。

2026-11-05 外部建议复核：正式版两项省电开关默认关闭的提醒属实；用正式版运行时及隔离数据显式同时启用，两路径均在日志中触发并运行至手动停止，尚未覆盖足以默认启用的交互和多应用场景。GFX-001 仍未完成；`ANativeActivityCallbacks.onContentRectChanged` 不是普通播放器的通用 NativeWindow 回调，不能据此强制 `glViewport`。详见任务卡。

2026-11-05 用户反馈滑动仍卡。GFX-001 新一轮已确认大图 BitmapShader 仍是绘制热点，双线性采样重复坐标计算优化通过 855,837 次像素回归，六图微基准约改善 6%–10%；真实动态页面仍有超过 100 ms 的 draw/layout 峰值，尚不能声称滑动或旋转修复。详见任务卡。

2026-11-05 后续：BitmapShader 按轴对齐输出行复用纵向索引的通用优化通过 1,060,637 次像素对照；受控六图基准较上轮再快约 9%–10%。B 站真实页面滚动和酷安首次条款页可绘制，但酷安内容页及跨应用性能尚未验证；布局和旋转仍待修。

2026-11-05 用户确认 Dock 图标路径正确，但触控板双指滑动在热门页仍严重卡顿，窗口越大越明显。截图确认的热门页合成手势测试中，大/小窗口绘制帧 p90 分别约 22/16 ms；动态内容、空闲帧与诊断开销限制了可比性。GFX-001 保持 PENDING。

2026-11-05 后续：已实现通用的 `Canvas + BitmapShader` 形状烘焙缓存，按 Bitmap generation、路径几何、缩放和过滤模式失效，32 MiB LRU，接入 Bitmap recycle 与框架低内存回调。80 项门控、129.7 万像素对照和内存上限测试通过；真实 1190×2084“热门”页连续 precise-scroll 无崩溃，缓存命中约 99.3%。该证据不覆盖横屏全屏、旋转和音画连续性，GFX-001 仍为 PENDING。

2026-11-05 FPS 与窗口诊断：新增按应用保存的 `View -> Show App FPS`，合并 Canvas、Surface、EGL 同 tick 提交，静止无提交时显示 `APP idle`；4 场景测试通过。调试自动尺寸窗口按当前显示器 `visibleFrame` 等比夹紧，Sidecar 实测整个 `1090x772` frame 位于副屏内。隔离推荐页视频后的高速 `-8pt/frame` 热门页样本达到约 58.2–60 FPS，draw-only p90 `15.2 ms`；先前 `draw≈28 ms` 样本混有视频、加载和整页帧，不能归因于缓存容量。32 MiB 缓存仅使用约 7.25 MiB，本轮不扩大；全屏热门列表、物理触控板视频叠加、旋转和多应用仍待验收，GFX-001 保持 PENDING。

2026-11-05 自动连播复核：在“57/81 → 0/81”的自动切集前后，新视频仍报告 `video sink: non-SurfaceView Surface (SurfaceTexture-backed)`，VideoToolbox 持续以约 60 帧输出且 `vtb-stat` 的 `polled/rendered` 继续增长，没有解码失败；与此同时界面视频区为黑。该样本没有对应的 SurfaceView 销毁/重建或视频层重新绑定日志，说明问题更可能位于同一 TextureView/SurfaceTexture 目标的切集、latch 或 Canvas 合成链，不能归因于 HEVC 解码器或 `vp09/av01` 能力探测失败。下一步用 `MACAPK_VIDEO_FRAME_TRACE=1` 和 `MACAPK_VIDEO_SINK_TRACE=1` 记录目标注册、queue、latch、draw 与代际后再修改实现。证据在 `work/upgrade/DANMAKU-001/gui-20261105-1/`。

两份外部建议中可执行的是音频缓冲 A/B；源码确认 `MACAPK_AUDIO_AOSP_FRAMECOUNT=0` 现有路径已提供约 120 ms 下限与 2x headroom，不需要另写缓冲。不得据此预先改变默认值，须使用同素材、PLAYING 状态、每 5 秒 `pulled/total` 与 `underruns` 对齐并观察至少 30 秒。将 G1 设为默认、扩大缓存、按 B 站/APK 类名分支，以及把未证实的 Java 悬垂对象作为根因，均不采纳。

2026-11-05 二次复核新增两项确定性证据：正式包在旧 `processes.state` 存在但 launchd job 消失时，`--ensure-process-broker` 能恢复当前 helper；随后 3 次强制 `bootout` + 重注册均 exit 0 且 job 可见。TextureView 的 1280×720→930×523 headless 基准中，`setPixels` p50 0.20 ms、缩放 blit p50 0.52 ms、组合 p50 0.61 ms；该数值不含 CVPixelBuffer/JNI 拷贝、整树遍历、GC、整窗像素提取与 CoreAnimation，不能单独解释用户感知卡顿。自动连播与音频仍待 GUI 整帧 trace；当前 macOS 在两次自动解锁尝试后仍锁屏，不启动 GUI 测试。

继续准备 GUI 视频 trace 时，headless agent 自检发现 `TraceSurfaceAgent` 的返回值插桩压栈顺序错误，会让 `SurfaceTexture.macapkLatchBitmap()` 在类验证阶段报 `VerifyError`；已修正 helper 描述符，并把编译、加载和 queue/latch/lifecycle smoke 接入 `run-surface-probe.sh` 的前置检查。当前 Java build、Surface 目标隔离和探针 smoke 均通过；真实视频矩阵仍未运行，因为系统仍报告锁屏。

2026-11-05 19:51 又收紧了 GUI 前置条件：探针现在读取当前 `NSScreen` 并按实际名称、display ID 和位置选择 Sidecar，锁屏时直接 exit 3。当前只有内建 Retina（1710x1112pt、2x）且仍锁屏，未启动图形应用。最新 18:57 崩溃的 native identity 为 `d03752474eb1d2df`，不能直接归到当前安装版 `a6a3faa0b3a32f3d`；它仍提示旧运行时的信号处理/样式索引有风险，需要当前版本冷启动复测。进程 broker 的 3 次 launchd job 重注册和最近日志中的多进程 attach 已通过，旧 marker 与真实 job 脱节的问题仍待应用内三冷启动验证。音画、弹幕生命周期、旋转和音频 A/B 继续等待真实解锁。

2026-11-05 19:54 在仍锁屏、无 GUI 的状态下复跑 headless Broker 回归：三份 Java 测试均 exit 0；manifest 多进程与 Google 解耦、单进程不误启 Broker、32 冷绑/16 receiver/64 队列显式过载拒绝、旧同步协议及延迟 completion generation 均通过；Swift LaunchAgent 测试和 native bionic shim 增量构建也通过。显示器只枚举到内建主屏，测试窗口未启动。真实 launchd job 仍指向正式 `macapk-system --app-processes`，state=not running。下一道门禁仍是解锁后的三次连续冷启动与视觉弹幕/音画矩阵，不能用 headless 结果关闭任务。

2026-11-05 20:25 补齐异步 BIND 的 broker 死亡语义：未完成连接在新 broker 死亡时收到一次带 component 的 `onBindingDied`，旧 completion 不能复活记录；lookup 与 death cache 失效后，运行中的应用会按 `org.macapk.processes` 稳定服务名做三次短退避重注册，之后 sibling-process bind 不会退回折叠进程。`bindRemote/startRemote` 的 broker 死亡竞争也显式失败。新增该分支的裸 JVM 回归后，RemoteBindCompletion、32 冷绑/16 receiver/64 queue、manifest/Google 解耦和 z-order 四组均 exit 0，Java-only 构建 PASS，证据在 `work/upgrade/DANMAKU-001/broker-regression-2023/`。系统仍锁屏，内建屏无 Sidecar；本轮未启动 GUI，三次真实冷启动、移动弹幕、音画和竖横全屏往返仍待补。

2026-11-05 20:32 对两次 Chronos/native 崩溃增加默认关闭的 4 GiB 返回别名窄恢复实验。两例都满足 guest PC、si_addr、LR 同为一个有效图片指令地址加 `0x100000000`，且修正点的下一条控制流前会重写 x30。`MACAPK_RECOVER_4G_RETURN_ALIAS=1` 只接受 `ldr x30,[sp],#imm` 和同目标寄存器 `adrp+add+bl` 两种形态；其他 fault 保持原样。形态接受/拒绝测试、native shim 构建和 diffcheck PASS，开发 shim SHA-256 为 `96bde05f0dafafa6b1fc4370e6a5e89c48c45a39c73aca9558b4c47d6e8e4673`；未替换正式应用或共享 runtime。系统仍为 `IOConsoleLocked=Yes`、`CGSSessionScreenIsLocked=Yes`，CUA 明确拒绝操作，因此窄恢复尚未真实命中，三次冷启动、移动弹幕和音画矩阵未运行。

2026-11-05 20:37 锁屏期间重新执行 Java-only 全量构建；当前 broker routing、32 cold bind/4+64 queue、远端绑定 generation/取消/broker 死亡、Surface z-order、LaunchAgent plist 和 4 GiB 返回别名形态测试均 PASS，native shim 增量构建 PASS。证据在 `work/upgrade/DANMAKU-001/{java-build-offline-2037.log,regression-offline-2037,launchagent-offline-2037,alias-offline-2037}`。这只关闭离线控制流风险；显示器仍休眠且系统仍锁屏，真实多进程 attach、移动弹幕、音画和全屏矩阵仍未运行。

2026-11-05 20:38 4 GiB 返回别名恢复增加完整地址门单测：`PC == LR == si_addr`、精确 4 GiB 位移、允许指令形态和 guest-image 归属全部通过才恢复；反例覆盖 LR/si_addr 不一致、小于 4 GiB、错误寄存器与空窗口。`-Wall -Wextra -Werror` 单测和 native shim 构建 PASS，开发 shim 已同步到 runtime/build/bin，SHA-256 `ef87fcc23ed390660c4649e58899d1bc8695a7ae64dfa61ce6d99687a4a6d06f`；共享 runtime 与正式应用仍未替换。
