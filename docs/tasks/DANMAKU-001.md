# DANMAKU-001：恢复视频播放页的弹幕显示与开关状态

- 状态：PENDING（层级与取包进程链已有修复和局部移动弹幕证据，完整生命周期未验收）
- 前置：VIDEO-PWR-001、UPSTREAM-2377
- 基础提交：`f663380f05d1f3e4af97f27426a08fa92e5d6f65`
- 证据目录：`work/upgrade/DANMAKU-001/`（不得提交私人播放记录或账号数据）

## 问题与目标

用户在安装版视频播放页反馈弹幕再次不显示。需要分清四件事：B 站弹幕开关状态是否
变化、弹幕 SurfaceView/透明层是否创建并提交帧、VideoToolbox 与独立 Surface 是否正常、
配置切换或窗口 resize 后图层是否被隐藏或错误合成。期望开启弹幕时文字持续横向移动；
关闭后立即停止显示，重新开启可以恢复；播放、暂停、seek、全屏、返回和窗口拖动后状态
仍正确。

用户补充确认了全屏验收口径：这里指视频窗口右下角按钮触发的“竖屏全屏”和“横屏全屏”，
不是整个应用进入 macOS 系统全屏。切换时目前会同时观察到画面突然停顿和声音短暂停顿；
因此弹幕连续性验收还必须覆盖竖屏全屏→横屏全屏→竖屏全屏，以及退出播放器全屏，分别
记录首次和预热后的画面、声音及弹幕是否连续。

## 已知历史

2026-10-29 的 `4706bf2` 曾修复同一类问题，不能通过恢复某个旧文件或应用专用补丁来
回退当前上游集成。当时确认并保留的通用契约包括：

- `SurfaceView` 的每个隐藏/可见 Surface 使用稳定 key，不再共享“最后一个 owner”；
- 视频和弹幕各自拥有独立 `ANativeWindow`/Metal layer，几何、buffer 尺寸、view alpha、
  z-order、detached/destroy 生命周期分开管理；
- 透明弹幕层必须保留混合 alpha，不能被后端强制设为 opaque；
- `Choreographer` ready callback 必须唤醒 native looper，避免 60 Hz 弹幕帧已经产生但
  宿主仍阻塞；
- `MediaCodec` 只绑定明确登记的视频 `SurfaceTexture` 目标，普通第二个消费者不能抢占
  解码帧。

历史安装态验收曾确认视频播放、弹幕随时间移动、播放/弹幕按钮状态变化和竖屏全屏返回
正常；后续 VIDEO-PWR-001 记录多次把弹幕开关标为 `NOT_RUN`，所以这次回归需要重新做
可重复的视觉断言，不能只凭线程日志判断。

## 范围

允许：`SurfaceView`、`Surface`、`SurfaceTexture`、透明 Metal layer、Cocoa 图层合成、
`Choreographer`/looper、`ImageView` level state，以及必要的 Java/native 回归测试。

不包含：修改 B 站 APK、按包名或类名特判、降低视频帧率、删除弹幕透明层，或把已验证
有功耗回退问题的渲染路径直接设为全局默认。

## 执行步骤

1. 从同一已初始化数据快照打开固定视频，记录安装版 build ID 和窗口尺寸。
2. 分别采集弹幕开、关、再开三段的日志与短录屏，确认按钮状态、Surface key、帧提交和
   可见像素四者是否一致。
3. 若图层存在但无像素，沿 Canvas/EGL/SurfaceTexture 生产者到 Metal consumer 定位；
   若图层不存在，检查 Activity 切换、z-order、alpha 或 frame callback 生命周期。
4. 实施跨应用修复并增加针对失败环节的最小回归；当前 `VIDEO-PWR-001` 和 `GFX-001`
   改动必须保留，不能用历史版本整体覆盖。
5. 完成播放/暂停、seek、开关弹幕、窗口 resize、全屏切换、返回和重新进入回归。

## 验收条件

- [ ] 弹幕开启时，在至少两个视频中观察到文字持续横向移动；关闭后不再出现，再开启恢复。
- [ ] 视频播放、声音、进度、弹幕按钮状态和弹幕可见性相互一致。
- [ ] 使用播放器右下角“竖屏全屏 / 横屏全屏”按钮做双向切换和退出；过渡期间画面与声音
  均无可感知停顿，弹幕不停滞、丢失或残留，且首次与预热后的结果分别记录。
- [ ] 窗口 resize、全屏切换和返回后弹幕仍可见，且没有视频层或弹幕层残留。
- [ ] 适用 Java/native 测试列出 PASS / FAIL / SKIP / NOT_RUN、命令、退出码和证据。
- [ ] 修复不包含包名、Activity 类名或资源 ID 特判；回退开关和风险已记录。

## 执行记录

### 2026-11-06 主线整合时的最新边界

前一聊天后续曾在两段视频中观察到移动弹幕，窗口反复缩放后仍可见移动文字。
这补充了下文较早的“未见移动弹幕”记录，但没有完成关闭→再开启、重启、seek、
播放器竖屏/横屏全屏往返及每次切换后至少 30 秒的音画连续性矩阵，不能关闭任务。
缩放的 Surface key 差异与白带证据见 GFX-001 的 2026-11-06 记录。

本轮只做主线整理与离线验证，未重新启动 GUI。manifest 路由及两种 opt-out、
32 cold binds / 16 receivers / 4+64 queue、迟到 completion / 取消 / generation /
broker 死亡、z-order、视频目标隔离、LaunchAgent、透明 layer 和返回别名门控均通过。
命令、退出码与证据见 [主线整合记录](MAIN-INTEGRATION-20261106.md)。
两项实验仍默认关闭，正式安装、共享运行时和前一轮诊断环境本轮均未修改。

2026-11-05 接续已在 GFX-001 播放器回归中观察到：两段不同视频均未见移动弹幕，
按钮为勾选状态。`tv.danmaku.render.core.c` 的独立 Surface 有首帧和有效几何；后续只读
反编译确认该类实现的是 `IVideoRenderLayer`，属于视频输出，不能因命名含 danmaku 就
把它当作弹幕生产者。这不是弹幕像素、帧连续性或 alpha 已通过的证据。原始记录在 GFX-001 的
`work/upgrade/GFX-001/resume-20261105/`，尚未完成弹幕关/再开与像素探针。

只读检查发现一个需独立验证的契约差异：当前 `setZOrderMediaOverlay()` 在已 on-top
时忽略调用，而 Android 14/15 的 AOSP 方法无条件更新 `mRequestedSubLayer`。
当前安装日志恰有另一个 Surface 先 `setZOrderOnTop(true)` 再 `setZOrderMediaOverlay(true)`。
这证明存在需要补测的调用顺序差异，**尚不能断言它就是本次弹幕消失的唯一原因**。
后续先补默认 → on-top → media-overlay → on-top → media 的顺序回归，明确 requested
与 applied 状态、attach 前后及动态更新语义，再做通用修正与实际合成 A/B。

官方源码依据：
[Android 15 SurfaceView.java](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android-15.0.0_r1/core/java/android/view/SurfaceView.java)
的 `setZOrderMediaOverlay()`/`setZOrderOnTop()`；
[Android 14 对照](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android-14.0.0_r1/core/java/android/view/SurfaceView.java)。
历史依据仍为 `docs/MACAPK_UPGRADE_HANDBOOK.md`、提交 `4706bf2` 和既有 Surface/alpha/
Choreographer 测试，不恢复整份旧文件，不以 native frame 日志代替移动文字视觉验收。

### 2026-11-05 层级请求修正与安装态复核

实施范围是本任务卡；GFX-001 的已有未提交变更保持原样。

- 新增 `debug/SurfaceZOrderRequestTest.java`：先 on-top，再 media-overlay 的回归在
  修复前以 `order=2, expected=1` 失败（退出码 1），修复后通过（退出码 0）。也验证
  overlay/on-top 的反向覆盖、false 重置、两个 owner 的 key 隔离和 key 稳定性。
  裸 JVM 用 Unsafe 跳过 JNI 构造，显式从 MEDIA 请求开始；**不覆盖构造初值、attach、
  applied transaction 或实际像素**。运行须设 `MACAPK_LOG_LEVEL=6`。
- `SurfaceView.setZOrderMediaOverlay()` 去掉“已经 on-top 就忽略”的条件，按 AOSP
  14/15 更新请求顺序。当前 runtime 仍以一个 volatile 字段供 draw/native pull 使用；
  没有声称已经实现 AOSP 分离 requested/applied、target-SDK 门控的全部动态事务语义。
  两个 setter 在调用线程写入该 owner 的顺序，宿主主线程通过现有 keyed pull 读取；
  不直接操作 CALayer，不新建/替换 Surface key，不改变 producer、generation、销毁或取消。
- 为已有 `MACAPK_GL_WINDOW_READBACK_TRACE=1` 增加 opt-in 像素计数/哈希：在 producer
  发布可回收数组**之前**检查前三帧和每 300 帧，默认关闭，不增加默认每帧扫描；扫描样本
  不作为性能测量。诊断确认视频 key 1 的整帧不透明是正常视频输出，不能证明弹幕正常。
- 安装前 APFS 备份 `work/upgrade/DANMAKU-001/Mac.apk.before.app`；先安装诊断版
  `261105.1131-f663380+dirty` 保存 `baseline-probe.log`，再安装层级修正版
  `261105.1137-f663380+dirty`，保存 `zorder-installed.log`。当前共享运行时
  `JARS_ID=eba4fe6bf152d3fe`、`NATIVE_ID=02a9465d7b6ae3a6`；没有改 GC 或默认图形后端。
- 两次启动前读取 NSScreen：Sidecar 名称 `Sidecar Display (AirPlay)`，frame
  `(-1149,310,1149,802)`、scale 2；两次 autoresize 都确认内容 `1149x740`，frame
  `1149x772 at -1149,310`，铺满该副屏可见区域。没有移动其他窗口。
- **PASS（窄项）**：透明 Surface key 2 的实际 native pull 由 `top=1 order=2 z=0.60`
  变为 `top=0 order=1 z=0.35`，视频仍播放，控件可操作，播放器竖屏全屏 → 横屏全屏 →
  竖屏全屏 → 详情 → 热门列表保持同一副屏窗口尺寸；没有系统全屏代替操作。
- **FAIL（弹幕）**：同一“闲鱼”视频以及第二段“网恋 VS 奔现”视频的开、关、再开测试，
  按钮图标有变化，多次画面观察仍未见移动弹幕。native key 2 只有很少提交记录；探针
  前三次中心像素为 RGBA 0,0,0,0，提交均正常退出。日志在 1–3 次及每 60 次采样，未达
  60 次，所以不能把最后打印 `enter=3` 当作精确总帧数，也不能用中心像素推断全帧为空。
  回调线程有持续 enter/return 记录，多个采样间隔约 16.7 ms，未见提交调用卡在内部的
  证据。下一步需查请求数据/渲染状态、完整 alpha 覆盖和应用消息进入 native 的链路，
  不盲目提升 z-order 或把唤醒实验设为默认。
- **FAIL（声音连续性）**：普通 Serial GC 默认启动，进入竖屏全屏后最近 5 秒音频窗
  `pulled=400834/441230, underruns=3`；横屏返回竖屏后最近窗
  `427768/441226, underruns=1`。这是 interleaved float samples 和 PLAYING 欠载次数，
  不等同于精确听感停顿时长。此轮开了重诊断，不作为性能 A/B 或新回归的因果证据。
- 退出全屏后另有 `DanmakuLog-error: init chronos but some reason ...`；仅作为定位线索，
  尚未归因。已安装 APK 的只读反编译与日志留在忽略的证据目录，不提交 APK/DEX/应用源码。

### 2026-11-05 预热后全屏往返与延迟欠载

- 模式事件和音频窗口已再次对齐。预热后进入竖屏全屏的首个 5 秒窗为
  `pulled=395008/480256, underruns=4`，下一窗为 `406912/480256, underruns=2`。
- 播放中竖屏全屏→横屏全屏记录到真实 `VERTICAL_FULLSCREEN → LANDSCAPE_FULLSCREEN`；
  紧邻两窗均为 `480256/480256, 0`，但第三窗随后为 `458432/480256, underruns=1`。
- 横屏全屏→竖屏全屏记录到 `LANDSCAPE_FULLSCREEN → VERTICAL_FULLSCREEN`；切换后
  一窗为 `423616/480256, underruns=1`。退出到详情后的一个窗口又记录
  `408256/480256, underruns=1`。
- 结论：切换完成并不等于音画连续。任务必须继续包含每次进入、双向切换和退出后的
  至少 30 秒观察，并同时判断视频时间轴、声音欠载、弹幕文字和弹幕开关状态。
  本轮仍未看到持续横向移动的弹幕，弹幕项的最终验收仍为 FAIL/NOT_RUN。
- 证据：`work/upgrade/DANMAKU-001/audio-matrix-serial-20261105.log`；过滤文件
  SHA-256 为 `09b7631d315522e1f328fb9cfcbc87ef979d0d0e0b0eff74e3988ee82965188a`。

| 检查 | 结果 | 命令/证据 |
|---|---|---|
| Java 构建，两次 | PASS，0 | `debug/build_java_only.sh`，`java-probe-build.log` / `java-zorder-build.log` |
| 层级顺序前后 | FAIL 1 → PASS 0 | `SurfaceZOrderRequestTest`，`zorder-before.log` / `zorder-after.log` |
| Surface 目标与视频目标隔离 | PASS，0 | `SurfaceTextureRoutingTest` / `SurfaceVideoTargetIsolationTest` |
| attached Display 生命周期 | PASS，0 | `ViewAttachedDisplayTest` |
| 透明 Metal layer 及 copy | PASS，0 | clang++ C++17 + Foundation/QuartzCore，`SurfaceMetalLayerAlphaTest` |
| 构建并安装，两次 | PASS，0 | `MACAPK_NO_BUNDLE_SYNC=1 scripts/build_launcher.sh --promote`，两份 promote log |
| 安装签名、共享 identity、diff | PASS，0 | `codesign --verify --deep --strict`、Resources/build-id.txt `cmp`、`git diff --check` |
| 两视频移动弹幕 | FAIL | 开/关/再开仍无移动文字；GUI 观察，未保存短录屏 |
| pause/seek/resize 与其他应用 | NOT_RUN | 本轮不据此扩大兼容性结论 |

测试用例的 javac/java 使用 `/opt/homebrew/opt/openjdk@21/bin`，classpath 为
`work/upgrade/DANMAKU-001/tests:runtime/build/lib/android-framework.jar`。所有测试进程已退出。

## 回退

修复保留在独立提交中；若影响其他应用，回退该提交并保留弹幕层诊断开关与证据。

## 2026-11-05 接续：历史修复核对与取包服务阻塞

本轮没有修改产品源码、提交或重新安装；原有 10 个未提交文件保持边界。正式包仍为
`261105.1436-f663380+dirty`（不是建议中写的 1137），安装态 identity 为
`JARS_ID=380c1544e5cc1c71`、`NATIVE_ID=d03752474eb1d2df`。本轮 GUI 前复核
Sidecar `Sidecar Display (AirPlay)`，frame `(-1149,310,1149,802)`、scale 2；
测试窗口 CG frame 在该屏内，后段为 `(-875,84,600,664)`（CG 顶左原点），未要求主屏测试。

### 历史事实与外部建议取舍

- `4706bf2` 确实有独立 ANativeWindow/Metal layer、透明 alpha、明确视频 target 与
  looper 唤醒修复；手册 5.4、7.2 明确记载用户验收和隔离数据复验看到弹幕横向移动。
  当前 HEAD 包含该提交，核心实现/四个生产兼容开关仍在；不能声称旧修复全部丢失。
- WorkBuddy 关于不直接默认切换 G1、音频旧缓冲路径现成可 A/B、不要扩缓存和继续追
  数据链的建议有源码依据。音频缓冲 A/B 本轮 NOT_RUN；不得挪作弹幕修复结论。
- “高速滑动已经基本解决”只能适用于原记录的隔离、预热列表样本，不能覆盖带视频
  的推荐页或所有应用。“init chronos ... container”在 `fu1.e.d()` 的 else 分支仅表示
  `this.i != null`，并不是 addView 抛异常；真正初始化异常有不同的 catch 日志。
- 上轮 `full-alpha-installed.log` 的三次 opt-in 采样已检查每帧 3,401,040 像素，均
  `nonzero_alpha=0/nonzero_rgb=0`。这只证明那三次整帧为空，仍不等于整个播放期只提交三帧。

### 新的可复现失败链

使用忽略目录内临时 Java Instrumentation agent，只加方法入口/返回观测，不改 APK、
不注入应用状态，也不把有探针的运行作为性能样本。第二段探针在
`resume-1545/launch-agent-r2.log` 中确认：

1. `ViewMoss.executeViewProgress` 约 103 ms 返回非 null，`hasChronos()=true`；
   数据转换返回非 null `ViewProgressDetail`，所以此样本不是接口没返回。
2. `ChronosPackageManager.obtain(...)` 已收到 HTTPS zip URL 和校验参数，随后线程
   停在 `S10.o.execute → CountDownLatch.await`。线程栈证据为
   `resume-1545/package-thread-dump.txt`；取包没有返回，不能声称包已下载或运行。
3. 同次日志反复出现 `ServiceRegistry.onBind KDownloadService` 的 ClassCastException：
   客户端实现 `kntr.base.downloader.n` 被 cast 为 `KDownloaderImpl`。只读解析真实 APK
   manifest 确认服务的 `android:process` 为 `tv.danmaku.bili:download`；当前关闭 broker
   时却由主进程的 in-process ServiceRegistry 创建。该错误上轮 baseline 日志也已存在。
4. `j0` sender 只在内容包 ready 后发送缓冲消息；探针所见 Java 层“sendMessageAsync”
   不能单独证明消息已经进 native。所采运行没有观察到非 null `runPackage`。

由此把“内容包下载等待 + 服务进程错位”列为当前弹幕缺失的直接调查链。旧验收时是否
命中过已下载的包、或远程配置后来改用了独立下载器，尚未比较，不能作为已证实的历史原因。

### 多进程对照与第二个阻碍

从正式数据做 APFS clone 到 `resume-1545/broker-snapshot/android`，使用现有安装 runtime、
独立临时 Mach 服务 `org.macapk.danmaku-probe-20261105`，仅测试进程启用 broker/split，
未改全局 Google 服务开关。脚本与 plist 都留在忽略证据目录。

- 首次 broker 继承宿主 `MACAPK_LOG_LEVEL=4`，helper 的 `setenv(...,7,0)` 未覆盖它，
  裸 JVM 在 Manifest 日志处调用未绑定 `NativeLog.print`，导致注册零个包。仅对测试
  broker 显式设置 level 7 后，成功登记 84 个服务；产品 helper 本轮未修改。
- 随后实际启动 `tv.danmaku.bili:download` 子进程（47954），已进入正确的进程初始化，
  但主 UI 无法继续操作。三个 jcmd dump 对齐确认：broker 16 个处理线程在
  `SystemServer.ensureRunning/startLock` 上等待服务 attach；子进程停在同步
  `SystemClient.attach → nativeTransact`；主 UI 停在 `forwardBroadcast → nativeTransact`。
  这是启动等待耗尽 broker 处理线程的证据，不能把“打开 broker”作为完成修复。
- 证据：`resume-1545/{broker-thread.txt,worker-thread.txt,broker-main-thread.txt,broker.log}`，
  以及 `broker-main-r3.log`、`worker-logs/tv.danmaku.bili_download.log`。有探针、使用小窗口，
  不构成与先前 Sidecar 铺满样本的性能 A/B。
- 所有本轮测试 app/worker 进程已结束，临时 launchctl job 已 bootout；未动全局 broker。

### 下一步（仍属 DANMAKU-001 的必要兼容链）

先解决 broker 生命周期请求的启动等待占满处理线程，再验证正确进程中下载、回调、
包加载与移动文字。优先考虑把 bind/start 的慢启动等待移出 Binder 接收线程，采用
有界队列和异步完成协议，保留 caller identity、取消、死亡与超时；不能简单增加线程数、
伪造完成回调或手工复制资源包来冒充修复。还需把本应用内部 android:process 路由与
“启用 Google GMS”解耦，且不得自动启动无关应用。先补最小无 APK 回归，再改通用实现。

本轮结论：历史修复真实存在；当前有效包未就绪及进程路由失败有新证据；弹幕视觉验收
仍 FAIL/NOT_RUN，音画、60 FPS 和功耗目标均未完成。`git diff --check` PASS（0）。


### 2026-11-05 17:51 暂停交接（用户明确要求）

本段覆盖上面“尚未改产品”的早期阶段状态；任务保持 PENDING，用户暂停后不再测试。

- 已改 `SystemServer/SystemClient`：BIND 可带 completion Binder，receiver 立即确认，
  慢启动交给4线程/64队列，显式拒绝溢出；旧客户端同步reply保留。客户端用每次绑定
  record 身份作 generation，取消/重复完成/排队后取消不会复活旧连接。
- 已改 `SystemBrokerMain.c`，强制裸JVM日志等级7，防止继承等级4调用未绑定NativeLog JNI。
- 正式默认路由修复：`Boot.chooseProcessName` 在provider前配置manifest；多个process
  自动启用 `org.macapk.processes`，与Google开关独立。新增同helper的 `--app-processes`
  模式及LaunchAgent，禁用Google自动boot、过滤系统包；Dock stub及launcher负责按需
  注册。显式 BROKER=0/PROCESS_SPLIT=0 保留旧模式，BROKER=1 保留原开发路由语义。
- 新回归 `debug/{BrokerServiceStartupTest,RemoteBindCompletionTest,ProcessBrokerRoutingTest}.java`。
  `ProcessBrokerRoutingTest` 修复前 exit1（manifest process collapsed），修复后正常
  及两个optout变体各exit0；另外两个回归均exit0。Java-only/Swift/helper构建、
  diffcheck、安装签名、正式与共享build-id cmp PASS。Parcel相关测试在async阶段PASS。
- 安装 `261105.1746-f663380+dirty`，JARS `d0a27e5715db04b8`，native仍
  `d03752474eb1d2df`。证据 `resume-1545/{java-process-build,process-launcher-build,
  process-promote,production-main-thread}.log/txt`（具体后缀以文件为准）。
- CUA普通启动正式 `/Applications/哔哩哔哩.app`，不注入agent/BROKER测试参数。
  PID78093，根 `/Applications/哔哩哔哩.app/Contents/android`；日志
  `~/Library/Logs/macapk/app_tv.danmaku.bili_9_16_1.log` 记录 attached org.macapk.processes。
  launchctl显示helper argv确为 `macapk-system --app-processes`，PID78184；下载worker
  78245和pushworker78353存在。Google开关未更改。
- 视觉：正式版先在《网恋 VS 奔现》（伢伢gagako，97.1万/562弹幕）看到多行弹幕；
  后《到底多绝望，才会走出这样的步伐…》（人类早期纪录片，262.5万/605弹幕）也看到
  移动文字。第二段点击下方弹幕图标后文字消失，随后重开操作因CUA返回
  `The user changed...`未实际执行。期间窗口/视频有外部变化，不能把所有转换归因本Agent。
  **正式启动已恢复移动弹幕，但完整开关与生命周期矩阵尚未完成。** CUA部分已结束总结。
- 隔离测试此前PID59160已成功取包、runPackage并显示《有人天生就喜欢这种感觉》
  （162万/1.5万弹幕），关/开验证通过；运行40m46s后 nativeFireTask SIGSEGV/SIGBUS。
  `resume-1545/hs_err_pid59160.log`、broker-main-r4.log 保留。native回调生命周期只是
  调查方向，未证实原因。临时测试job/进程已清理，新产品process broker保留。
- 已知代码后续审查点：async ack之后broker死亡、completion未到的pending连接清理
  尚无完整覆盖；旧协议无callback仍同步。不得声称全部生命周期已解决。
- 当前全局launchd已有渲染和省电实验变量（此前即存在），本轮未更改；正式普通启动
  只证明无特别BROKER开关也有弹幕，不证明清洁默认性能。G1仍非默认、帧率/音画/功耗
  大目标未完成。无需扩缓存，音频buffer A/B仍未做。
- 回退备份：`resume-1545/Mac.apk.before-processes.app` 与
  `shared-runtime-before-processes`，前版1648；更早before-async备份也保留。
  新endpoint回退还需由同owner正确unregister `org.macapk.processes`，勿动Google job。
- 下位Agent先核对当前PID/显示器/用户是否仍在操作，优先继续正式版热门视频off/on、
  重启、退出/resize/竖横全屏回归，再查上述native崩溃；不要重新停在隔离环境才恢复。
  全部代码未提交，保留本轮之前的GFX/Canvas/SurfaceView/bionic变更。

### 2026-11-05 18:20–18:33 接续：多进程 LaunchAgent 幂等注册与干净环境

新增 `launcher/ProcessBrokerLaunchAgent.swift` 和对应 Swift 回归。注册顺序为优先
`SMAppService`，不可用时写
`~/Library/Application Support/Mac.apk/launchd/org.macapk.processes.plist`，再以
`launchctl bootstrap` 加载。fallback 成功条件同时检查 marker 指纹和
`launchctl print gui/<uid>/org.macapk.processes` 的真实 job；`SMAppService` 只返回
`.enabled` 不再作为成功证据，因为 launchd 可能暂时没有对应 job。

真实集成验证时临时移走包内 process agent plist 以稳定进入 fallback 分支，完验后
原地恢复并重新签名。结果：

- 第一次调用输出 `loaded through launchctl`，job path 为 App Support plist；Program
  为本地 `build/Mac.apk.app/Contents/Helpers/macapk-system`，argv 为
  `--app-processes`，第二次调用输出 `already loaded` 且未重启。
- 测试前用户 launchd domain 残留 18 个 `MACAPK_*` 环境变量。逐项 `launchctl
  unsetenv` 后重新 bootstrap，最终 job 不再包含 `MACAPK_AUDIO_PROBE`、
  `MACAPK_PROBE_LOG`、`MACAPK_LOG_LEVEL` 或渲染实验变量。
- `ProcessBrokerLaunchAgentTest` PASS；`MACAPK_NO_BUNDLE_SYNC=1 scripts/build_launcher.sh`
  PASS；`codesign --verify --deep --strict build/Mac.apk.app` PASS；`git diff --check` PASS。
- 同批裸 JVM 回归 PASS：manifest 多进程（含 Google 关闭）、remote bind 取消/
  generation/null binding、32 cold binds/16 receivers、4 worker + 64 队列过载显式拒绝、
  z-order 请求顺序。

证据：`work/upgrade/DANMAKU-001/launchagent-real-1832/`、
`work/upgrade/DANMAKU-001/regression-1833/`。这只是注册和路由通过，不能替代 B 站弹幕
视觉矩阵；DANMAKU-001 保持 PENDING。

### 2026-11-05 19:01 接续：faultdump 递归崩溃修复

两份外部意见中，“控制变量、保留真实 fault、不要用 G1 掩盖”与“Java 悬垂对象不是
已证实根因”的判断成立；把 G1、缓存扩容或按 APK/业务类打分支当作本次修复不成立。
实际复查发现，18:57 失败样本的 `hs_err` 首先被诊断代码改写：

- 原始信号是 `SIGBUS/BUS_ADRALN`；`x22` 在该次 fault 中是压缩 oop
  `0x05780155`，并不是稳定的 C++ `this`。旧 faultdump 无条件读取
  `[x22, x22+0x38)`，制造了 `0x05780155` 处的第二个 SIGSEGV。
- fp-chain 也把 `[fp, fp+16)` 直接当可读栈帧；单靠数值范围和 16 字节对齐不能证明
  该页已映射。guest handler 没有推进 PC 时，JVM 连续返回 `macapk_segv_handler+0x2cc4`
  并最终 abort，掩盖最初故障。
- `runtime/translator/src/bionic_shim.cpp` 现用现有 `macapk_diag_safe_read`
  （`mach_vm_read_overwrite`）一次读取对象段或单个 fp frame。读取失败只输出
  `unreadable` 并停止该链，不再吞掉原信号，也不伪造值。

验证：

- `MACAPK_NO_BUNDLE_SYNC=1 runtime/scripts/build.sh`：PASS，
  `NATIVE_ID=a6a3faa0b3a32f3d`；未同步或替换正式包/共享 runtime。
- 同一进程使用 `MACAPK_FAULT_DUMP=1` 清洁环境直接冷启动超过 90 秒；日志捕获多次
  `SIGBUS/BUS_ADRALN`，包括 `Java: Fresco#Decode-*`、`PrepareHolderThread` 和 `main`，
  guest handler 均恢复正常执行，没有新增 `hs_err`；之后主动终止。证据：
  `work/upgrade/DANMAKU-001/faultdump-safe-20261105/stdout.log`。
- 裸 JVM `SurfaceZOrderRequestTest`、`ProcessBrokerRoutingTest`、
  `BrokerServiceStartupTest` 均 exit0；`git diff --check` PASS。证据：
  `work/upgrade/DANMAKU-001/regression-after-faultfix-20261105/`。

这一结果关闭的是“诊断器把可恢复 SIGBUS 改成第二次 SIGSEGV 并 abort”这条确定问题，
不等于已证明原始 BUS_ADRALN 均无副作用。真实视频导航、Chronos/`nativeFireTask`
长时运行、弹幕生命周期和音频 A/B 仍需继续；DANMAKU-001 保持 PENDING。

### 2026-11-05 19:14 自动连播黑视频的证据边界

自动切集从 `Auto-Play Next(57/81)` 到 `Auto-Play Next(0/81)` 时，视觉上视频区为黑，
但新旧项没有对应的 SurfaceView 销毁/重建或 `video layer (re)attached` 日志。新视频
继续报告 `video sink: non-SurfaceView Surface (SurfaceTexture-backed)`；
`[video-toolbox] open: mime=video/hevc 1280x720 hw=YES` 成功，随后 `vtb-stat` 的
`polled`、`rendered` 持续增长，约 60 FPS。至少在这个样本中，黑色区域不是 HEVC 解码
失败，也不是 VP9/AV1 的能力探测错误。

下一步必须在新进程同时启用两个现有、默认关闭的探针：

```text
MACAPK_VIDEO_FRAME_TRACE=1
MACAPK_VIDEO_SINK_TRACE=1
```

先播放同一热门视频确认移动弹幕，再等待自动切集，至少记录：旧/新 SurfaceTexture 的
identity、`macapkRegisterVideoTarget`/unregister 顺序、frame queue 与 latch 的
`st/tex/n`、TextureView 是否仍 attached/visible、实际 draw 是否消费新代际。只有区分
“新目标未注册”“已 queue 未 latch”“已 latch 未 draw”和“已 draw 但合成被遮黑”后，
才能决定修改点。现有证据只覆盖一次自动切集，不能外推为所有视频或所有 SurfaceTexture
路径；任务保持 PENDING。

外部建议复核：音频弹性缓冲方向成立，且 `MACAPK_AUDIO_AOSP_FRAMECOUNT=0` 的现有
实现已经是 `max(app buffer, ~120 ms) × 2` ring；无需新写一套。应做同素材 A/B，
保持 `MACAPK_AUDIO_PROBE=1`，每 5 秒比较 `pulled/total` 与 `underruns`，连续观察
至少 30 秒。把 G1 固化为默认、继续扩缓存、使用 APK/业务类特殊分支，或把尚未证实的
Java 悬垂对象模型作为根因，均不应在本轮实施。

### 2026-11-05 19:xx 外部建议二次复核、注册恢复与视频合成微基准

二次核对 antigravity/GPT 交接意见后，采纳以下部分：先以真实 launchd job 和多进程
attach 作为弹幕前提；SIGBUS/BUS_ADRALN 应沿 ARM 转译/native 路径查，不把 Java
悬垂对象当已证根因；音频只做现有 AOSP frameCount 与弹性路径 A/B；G1 仍是受控实验。
继续拒绝“默认启用 G1”“扩大 BitmapShader 缓存”“按 B 站包名/类名加分支”和把
自动连播黑屏直接归因于 HEVC 解码失败。

锁屏状态下做了不需要 GUI 的注册恢复验证。开始时
`launchctl print gui/501/org.macapk.processes` 为 `Could not find service`，而
`~/Library/Application Support/Mac.apk/launchd/processes.state` 仍指向旧构建，确认了
“缓存标记存在但实际 job 消失”的坏状态。直接调用正式
`/Applications/Mac.apk.app/Contents/MacOS/MacApk --ensure-process-broker` 后，fallback
重新生成 App Support plist、加载 `/Applications/Mac.apk.app/Contents/Helpers/macapk-system`
并更新为当前 identity。随后连续 3 次先 `bootout` 同一精确 job、再走注册入口，每次都
exit 0 且 print 成功；没有出现原担心的清理后无法重注册。该结果说明现有 launchd-job
存在性是成功判据、旧 marker 不会冒充成功；仍是单机三次幂等测试，不替代应用内三冷启动
与弹幕视觉断言。

对 `TextureView` 的 1280×720 源帧做了独立 headless 微基准（当前正式 framework jar，
OpenJDK 21，JFR/trace 均未开启）：`Bitmap.setPixels` 到 930×523 开销的 p50 约
0.20 ms、p95 0.21 ms；缩放 `Canvas.drawBitmap` p50 约 0.52 ms、p95 0.62 ms；两段组合
p50 约 0.61 ms、p99 0.77 ms。这里不包含 `CVPixelBuffer -> int[]` JNI 拷贝、整棵 View
遍历、GC、整窗像素提取和 CoreAnimation 提交，因此不能据此声称视频卡顿已经解释或修复；
它排除了“单次缩放本身必然几十毫秒”的简单假设，下一步仍需整帧时间线与线程 CPU 对齐。

已扩展 `work/upgrade/DANMAKU-001/auto-next-20261105/run-surface-probe.sh`：视频诊断
同时记录 frame/sink/present/draw-cost，并用保留输出记录构建、APK、agent 和音频模式；
`MACAPK_TRACE_VIDEO=0` 的音频 A/B 仍不加载 instrumentation agent、不打开视频 trace。
脚本 `bash -n` 与 `git diff --check` PASS。GUI 矩阵尚未开始，原因是 macOS 在两次
自动解锁尝试后仍报告锁屏；没有在锁屏状态启动 B 站或移动任何用户窗口。

### 2026-11-05 19:40 探针 headless 自检与返回插桩修复

在继续 GUI 前用裸 JVM 实际加载 instrumentation agent，首次自检发现
`SurfaceTexture.macapkLatchBitmap()` 的返回插桩会触发 `VerifyError: Bad type on
operand stack`：旧 agent 按 `(method, receiver, value)` 调用 helper，但字节码实际压栈
顺序是 `(value, method, receiver)`。真实 B 站加载目标类时也会遇到相同错误，因此此前
只做 `bash -n` 不能证明探针可运行。

现将 `returned` helper 和调用描述符统一为 `(Object value, String method, Object
receiver)`；保留原返回值后，reference/int/long/float/double 的 return wrapper 仍按
原字节码语义调用。新增 `AgentSurfaceSmoke.java` 与 `verify-surface-agent.sh`，覆盖
decoded-frame queue、latch、TextureView attach/detach、unregister、release 和返回
日志。`run-surface-probe.sh` 在加载视频 trace 前强制运行该自检，失败即停止，不再
启动半损坏的 GUI 测试。

- PASS：agent class 与 JAR 重建后的四个 class 文件 SHA-256 分别一致。
- PASS：修复后 agent 重建和 smoke，输出 `surface agent smoke PASS`；日志包含 queue、
  latch 及 `surface-probe-return ... macapkLatchBitmap`。
- PASS：`SurfaceTextureRoutingTest`、`SurfaceVideoTargetIsolationTest` 和当前
  `debug/build_java_only.sh` 均 exit 0。
- NOT_RUN：真实视频 trace、自动连播、弹幕生命周期和音频 A/B。系统仍报告
  `CGSSessionScreenIsLocked=Yes`，GUI 保持未启动。

证据：`work/upgrade/DANMAKU-001/static-after-unlock-20261105/`；GUI 探针仍位于
`work/upgrade/DANMAKU-001/auto-next-20261105/`。

### 2026-11-05 19:51 锁屏防误跑、显示器选择与最新崩溃归属

`run-surface-probe.sh` 原先固定使用 `MACAPK_HOST_DISPLAY='Built-in Retina Display'`。这在
Sidecar 重连后会违反本机测试约束，因此新增 `choose-host-display.swift`：读取当前
`NSScreen` 的名称、display ID、内建属性、主屏属性与 frame，优先选择名称明确包含
Sidecar/iPad 的非内建屏，其次在存在唯一非内建屏时选择它，否则选择主屏。脚本把完整
显示器清单和选择结果写入每次测试目录，并可通过显式 `MACAPK_HOST_DISPLAY` 覆盖。

脚本也会先读取 `CGSSessionScreenIsLocked`，锁屏时以 exit 3 拒绝启动 GUI。当前实测只
有 `Built-in Retina Display`（id 1、1710x1112pt、2x，主屏）且系统仍锁屏；锁屏拒绝
分支 PASS，因此没有启动 B 站、移动窗口或产生新的 GUI 视频证据。

对最新 `hs_err_pid23255.log` 重新做了归属检查。它是 18:57 在
`PrepareHolderThread 0` 上发生的 `SIGSEGV/SEGV_ACCERR`，当前显示在
`macapk_segv_handler`；邻近状态出现 `ResourceIndex.macapkFwStyleAttr` 和
`Theme.Material.Light`，不是 17:56 的 Chronos `SIGBUS/BUS_ADRALN`。同一应用日志在
19:03 记录的 runtime native identity 是 `d03752474eb1d2df`，而当前正式安装的是
`a6a3faa0b3a32f3d`（`JARS_ID=d0a27e5715db04b8`）。因此 18:57 日志可证明旧运行时的
信号处理/样式索引路径存在风险，却不能直接冒充当前安装版的复现；当前版本仍需在解锁
后的真实冷启动中观察。

外部建议中“broker 注册失败必须显式处理并反复验收”“SIGBUS 沿 ARM 转译/native 路径
追”“音频做现有模式 A/B”“G1 只作受控实验”继续采纳。当前没有复现
`launchd bootstrap` 拒绝注册：精确 job 的 3 次 bootout/重注册均成功，19:03 日志也有
`attached tv.danmaku.bili to org.macapk.processes`；旧 `processes.state` 与真实 job 的
不一致仍必须专门验收。默认 G1、扩缓存、B 站包名/类名分支仍不采纳。

### 2026-11-05 19:54 Headless Broker 回归复跑

锁屏仍为 `CGSSessionScreenIsLocked=true`，没有启动 GUI。显示器重新枚举只有
`Built-in Retina Display`（id 1、1710x1112pt、2x、主屏、内建），与用户断开副屏的
现场一致；选择器按规则回退内屏，没有移动任何窗口。

在清洁临时 HOME 下复跑 `MACAPK_LOG_LEVEL=7` 的裸 JVM 回归：

- `ProcessBrokerRoutingTest` PASS：manifest 多进程在没有 Google 开关时选择
  `org.macapk.processes`；单进程应用保持 Broker 关闭；显式 opt-out 保持旧进程模型。
- `RemoteBindCompletionTest` PASS：取消、同 connection 的新旧 generation、重复完成、
  排队后取消、binder identity 与 null binding 均符合预期。
- `BrokerServiceStartupTest` PASS：32 个并发冷绑定、16 个 receiver、后到 REGISTER、
  旧同步协议和 Binder identity 均保持；4 个 worker 占满、64 项队列占满后，第 65 项
  显式 `RejectedExecutionException`。
- `ProcessBrokerLaunchAgentTest` PASS；`cmake --build runtime/translator/build --target
  macapk-bionic-shim -j4` PASS；`git diff --check` PASS。
- 真实 launchd `org.macapk.processes` job 存在、`state=not running`，Program 和 argv
  均指向正式 `Mac.apk.app` 的 `macapk-system --app-processes`。

这些结果仍只关闭 headless 路由/生命周期问题，不替代三次真实冷启动、每次
`attached tv.danmaku.bili to org.macapk.processes`、移动弹幕视觉断言和音画矩阵。

### 2026-11-05 20:10 4 GiB 别名诊断的信号安全修正

新增的 `[sigbus-4g-alias]` 路径原先直接调用 `__macapk_arm64_guest_image_for_pc`，
而该函数遍历 `loaded_images()` 的 `std::vector`；源码注释明确说明它不能用于信号
上下文。现改为只读 `macapk_diag_note_guest_image` 维护的固定数组镜像，通过 release/
acquire 发布边界读取镜像名、base 和 offset，不推进 PC、不修正故障地址，也不吞掉
原始 SIGBUS。

- PASS：`cmake --build runtime/translator/build --target macapk-bionic-shim -j8`，退出 0。
- PASS：开发 shim 已复制到 `runtime/build/bin/libmacapk-bionic-shim.dylib`，其 SHA-256
  为 `5f17d2d7cb7977aad6a73cce70e482f7bfc90acd16ca194fddd3ad1770e4335d`。
- 未替换正式安装或共享 runtime；正式 SHA-256 仍为
  `967ba666b690b75ba8ba27e9507bbbc09851f81482ed022010a3b12674fdda46`。
- NOT_RUN：系统仍报告 `CGSSessionScreenIsLocked=true`，没有启动真实视频或操作任何
  已有窗口；三次冷启动、移动弹幕、别名诊断实跑和音频 A/B 均待解锁后执行。

外部建议中“沿 ARM64 转译/native 返回链追查”“先保证每次多进程 attach”和“音频用现有
两种模式做 A/B”继续采纳；默认 G1、扩缓存、按 B 站包名/类名分支及用 Java 生命周期
猜测覆盖现有地址证据不采纳。

### 2026-11-05 20:25 Broker 死亡后的未完成绑定

异步 BIND 已把慢启动移出 Binder receiver，但此前遗漏了“broker 已确认排队、目标
进程尚未 attach 时 broker 崩溃/退出”的客户端语义：记录会永久等待一个不会再来的
completion，之后同一应用可能回到进程内回退并错误处理 `android:process` 服务。

本轮在 `SystemClient` 增加 broker Binder 的实际 `linkToDeath` 监听和每代绑定记录：

- `RESOLVE` 返回的 `ComponentName` 随 generation 保存；broker 死亡时，只清除仍未
  completion 的当前代记录，向各连接交付一次 `onBindingDied(name)`。已连接服务继续
  使用自己的死讯监听，旧/重复 completion 不能复活该记录。
- 死亡 broker 的 `sSystem`、lookup 缓存与 death record 一并失效；运行中的应用用
  `org.macapk.processes` 稳定服务名做三次短退避重注册，使后续 sibling-process bind
  不退回已折叠的进程模型。
- `bindRemote/startRemote` 遇 broker 死亡竞争时显式返回失败，不再由空 Binder
  引用抛到 UI 线程。launchd job 继续作为真实存活判据，没有改 Google 开关或自动启动
  无关应用。
- `RemoteBindCompletionTest` 新增 broker 死亡、component identity、缓存失效与迟到的
  completion 不复活测试。该测试是裸 JVM 语义测试，没有用 fake callback 冒充真实
  Mach restart；真实三次冷启动仍须解锁后验证。

验证与证据：

| 检查 | 结果 | 证据 |
|---|---|---|
| Java-only 构建 | PASS，exit 0 | `work/upgrade/DANMAKU-001/broker-death-java-build-r2.log` |
| Broker 死亡代际回归 | PASS，exit 0 | `work/upgrade/DANMAKU-001/broker-regression-2023/remote-bind.log` |
| 32 cold bind / 4+64 queue | PASS，exit 0 | `broker-regression-2023/broker-startup.log` |
| manifest 路由 / Google 解耦 | PASS，exit 0 | `broker-regression-2023/routing.log` |
| media-overlay 请求顺序 | PASS，exit 0 | `broker-regression-2023/zorder.log` |

运行后核心图形 JAR 的开发 identity 为 `JARS_ID=46f0929375c0080f`；没有替换正式
应用或共享 runtime。系统仍为 `CGSSessionScreenIsLocked=1`，所以本轮没有启动 B 站、
没有产生移动弹幕/音画/全屏视觉证据，DANMAKU-001 继续保持 PENDING。

### 2026-11-05 20:32 4 GiB 返回别名窄恢复实验

两份 `hs_err` 与同进程应用日志进一步对齐：两次故障的首个地址都是对应
`libchronos.so` guest 映射内一个真实指令地址加 `0x100000000`，且故障现场 `PC == LR`。
第二个 `+0x202508` 是 `ldr x30, [sp], #0x10`，第一个 `+0x1d176c` 是
`adrp x3` → `add x3` → `bl` 序列；两者都会在任何间接控制流前重写 `x30`。应用日志也
表明最初的 guest delivery 是 `sig=11/SEGV_ACCERR`，随后经 guest signal chain 重复返回
并由 HotSpot 生成最终 `SIGBUS/BUS_ADRALN` 报告。因此不能只引用最终信号号判断根因。

新增默认关闭的 `MACAPK_RECOVER_4G_RETURN_ALIAS=1` 实验。恢复条件同时要求：

- `PC == si_addr == LR == image_base + offset + 4 GiB`；
- 修正后的地址属于固定数组镜像表中的一个已加载 guest image；
- 修正点是指定的 `ldr x30,[sp],#imm`，或同目标寄存器的 `adrp+add+bl` 序列。

其他 fault 一律不修，也不处理只有单一位点像图片地址的情况。恢复日志保留 image、
base、offset、原/修正地址、命中的指令形态和机器码。

- PASS：`GuestReturnAliasTest` 接受两例真实形态，并拒绝错误目标寄存器、错误基址
  寄存器、ADD 目标不一致和无 BL 的近似序列。
- PASS：`cmake --build runtime/translator/build --target macapk-bionic-shim -j8`。
- PASS：`git diff --check`。
- 开发 shim SHA-256：`96bde05f0dafafa6b1fc4370e6a5e89c48c45a39c73aca9558b4c47d6e8e4673`。
  未替换正式应用或共享 runtime；共享 runtime shim 仍为
  `967ba666b690b75ba8ba27e9507bbbc09851f81482ed022010a3b12674fdda46`。
- NOT_RUN：系统持续报告 `IOConsoleLocked=Yes`、`CGSSessionScreenIsLocked=Yes`，CUA
  也拒绝 native app 操作；用户回复“已解锁”后实际状态未改变，因此没有启动 GUI。
  两次真实故障是否恢复、恢复后是否保持音画/弹幕稳定，必须由解锁后的三冷启动证明。

证据在 `work/upgrade/DANMAKU-001/recovery-build-2035/`。

### 2026-11-05 20:37 锁屏期间离线回归

系统仍报告 `IOConsoleLocked=Yes`、`CGSSessionScreenIsLocked=Yes`，显示器为
`Display Asleep: Yes`；没有启动 GUI。重新执行 Java-only 全量构建并复跑当前 broker、
图层和 native 返回别名测试：

| 检查 | 结果 | 证据 |
|---|---|---|
| Java-only 构建与交叉检查 | PASS | `work/upgrade/DANMAKU-001/java-build-offline-2037.log` |
| 32 cold bind / 16 receiver / 4+64 queue | PASS | `regression-offline-2037/broker-startup.log` |
| manifest 路由、Google 解耦、两种显式 opt-out | PASS | `regression-offline-2037/routing*.log` |
| 取消、generation、迟到 completion、broker 死亡 | PASS | `regression-offline-2037/remote-bind.log` |
| Surface z-order 请求顺序 | PASS | `regression-offline-2037/zorder.log` |
| LaunchAgent plist/fingerprint | PASS | `launchagent-offline-2037/test.log` |
| 4 GiB 返回别名形态接受/拒绝 | PASS | `alias-offline-2037/test.log` |
| native shim 增量构建 | PASS | `cmake --build ... --target macapk-bionic-shim -j8` |

这些结果只证明离线控制流和构建边界，不证明真实 broker attach、移动弹幕、音频或全屏
切换。GUI 验收仍在锁屏解除后执行。

### 2026-11-05 20:38 4 GiB 恢复完整门控单测

把恢复判定从只做指令形态分类扩展为可单测的完整地址门：同时要求
`PC == LR == si_addr`、地址带精确 `+4 GiB` 别名，并命中两种允许重写 `x30` 的指令形态。
信号处理仍另外要求修正地址属于固定 guest-image 镜像表。新增反例覆盖 LR 不一致、
si_addr 不一致、小于 4 GiB、错误目标寄存器、错误基址寄存器和空指令窗口。

- PASS：`alias-gate-2038/GuestReturnAliasTest`，启用 `-Wall -Wextra -Werror`。
- PASS：`cmake --build runtime/translator/build --target macapk-bionic-shim -j8`。
- 当前开发 shim 已同步到 `runtime/build/bin/`，SHA-256 为
  `ef87fcc23ed390660c4649e58899d1bc8695a7ae64dfa61ce6d99687a4a6d06f`。
- 共享 runtime 和 `/Applications/Mac.apk.app` 仍未替换。
