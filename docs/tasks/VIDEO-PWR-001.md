# VIDEO-PWR-001：降低启动、空闲与视频播放功耗

- 状态：IN_PROGRESS（`5a34b2a` 已合入 NEON 像素翻转与 `MediaCodec`/`AudioTrack` 去空轮询；`376256f` 已合入 [guest_jit_canary.h](../../runtime/translator/src/guest_jit_canary.h) XNU 跨核迁移 `TPIDR_EL0` 修复；2026-11-15 本轮已实现 `EGL10` keyed `SurfaceView` 直连 `CAMetalLayer` 并 promote 到 `/Applications/Mac.apk.app`，消除了 `tv.danmaku.render.core.c` 每帧同步 `glReadPixels`，但真机播放仍主观偏卡，按用户要求暂停开发并留存进度与缺口记录）
- 用户目标：视频播放（含弹幕）接近原生软件的低功耗与流畅度。
- 用户补充：刚打开就高功耗；关闭弹幕仍明显高于同机 MuMu 和 Bilibili Universal；当前已消除 `EGL10` `SurfaceView` 回读后视频播放主观体验仍偏卡（用户要求先停工并归档进度）。
- 当前分支与基点：`mac_apk_architecture_upgrade`（合入 `ed9698e` + `2d1da57` 并更新 [SurfaceTextureNativePixbufTest.java](../../debug/SurfaceTextureNativePixbufTest.java)，promote 至 `/Applications/Mac.apk.app` `261118.1447-24a8f2a+dirty`，`v1.0.2379`）

### 2026-11-18 Wave 2 阶段进展：GL-attached `SurfaceTexture` 零拷贝 IOSurface 绑定、`TextureView` 直通目标修复与正式版整合促进
- **改动内容**：
  - **GL-attached `SurfaceTexture` 零拷贝原生 `CVPixelBuffer`/`IOSurface` 路径（`MACAPK_SURFACETEXTURE_IOSURFACE_GL`，默认开启）**：
    - [SurfaceTexture.java](../../runtime/java/android/graphics/SurfaceTexture.java)：新增 `macapkWantsNativeVideoPixbuf()`、`macapkQueueNativeVideoPixbuf(long, int, int)`、`mVideoNativePixbuf`（`mLock` 保护）与 `mGlBoundIosurfaceState = long[3]`（`mGlBoundLock` 保护，`[dpy, eglSurface, retainedPixbuf]`）；未 latch 被覆盖时在锁外通过 [NativeCocoa.java](../../runtime/java/com/macapk/loader/NativeCocoa.java) `releaseVideoPixelBuffer` 释放旧句柄；`updateTexImage()`、`releaseTexImage()`、`detachFromGLContext()`、`release()` 及无绑定库裸 JVM（`UnsatisfiedLinkError` 保护）均清理句柄与绑定的 `EGLSurface`。
    - [cocoa_bridge.mm](../../runtime/src/cocoa_bridge.mm) 与 [native_methods.cpp](../../runtime/src/native_methods.cpp)：`MacapkOfferDecodedFrameToTextureView` 在目标需要原生 pixbuf 时跳过 `CVPixelBufferLockBaseAddress` 与 `jintArray` 拷贝，改为 `CFRetain` 并投递句柄；新增 `CocoaReleaseVideoPixelBuffer`、`CocoaReleaseGlBoundIosurfaceState` 与 `CocoaLatchVideoPixelBufferToGl`（按当前 `eglGetCurrentDisplay()`/`eglGetCurrentContext()` 同时兼容 `EGL14` 默认 display 与 `MacApkEGL10` `0x4A453130` 私有 display，保存/恢复 `GL_TEXTURE_BINDING_2D`，优先使用 `EGL_IOSURFACE_ANGLE` + `EGL_TEXTURE_INTERNAL_FORMAT_ANGLE = 0x80E1` + `eglBindTexImage` 零拷贝绑定；若无 `IOSurface` 或绑定失败则回退到 C++ [video_pixels.h](../../runtime/src/video_pixels.h) `ArgbToRgba` NEON 转换 + `glTexImage2D` 并立即 `CFRelease`）。
    - **实测方向与像素验证**：在 ANGLE Metal（`EGL_DEFAULT_DISPLAY` 与 `0x4A453130`）通过 FBO 着色器采样 `GL_TEXTURE_2D` + `glReadPixels` 实测确认：`0x80E1`（`GL_BGRA_EXT`）产生精确 RGBA，且 `eglBindTexImage` 与 `glTexImage2D`（top-down RGBA）具有相同的垂直采样序（`v=0` 对应视频首行），配合 `VIDEO_TRANSFORM_MATRIX` 输出正立四象限图像。
  - **`TextureView` 直通活跃目标跟踪与 `setSurfaceTexture()` 回调修复**：
    - [SurfaceTexture.java](../../runtime/java/android/graphics/SurfaceTexture.java)：新增 `sDirectVideoLayerTarget`，在 `macapkTryDirectVideoLayer(SurfaceTexture)` 成功时记录并由 `macapkClearDirectVideoState()`/`release()`/`attachToGLContext()` 清理，`macapkActiveVideoRectPacked()` 优先读取 `sDirectVideoLayerTarget` 再回退 `sActiveVideoTarget`，修复多卡片显式目标在 `sActiveVideoTarget == null` 时返回 `0L` 的问题。
    - [TextureView.java](../../runtime/java/android/view/TextureView.java)：`setSurfaceTexture()` 在调用 `publishAvailableIfReady()` 前先将 `mAvailable = false`，使 `mSurfaceWidth`/`mSurfaceHeight` 正确初始化并触发 `onSurfaceTextureAvailable`。
- **Wave 2 离线验证**：
  - [TextureViewDirectVideoTest.java](../../debug/TextureViewDirectVideoTest.java) 新增显式多卡片目标 `macapkActiveVideoRectPacked()` 与 `setSurfaceTexture` 尺寸/回调断言；新增 [SurfaceTextureNativePixbufTest.java](../../debug/SurfaceTextureNativePixbufTest.java) 验证裸 JVM、`CFRetain`/`CFRelease` 覆盖与生命周期释放、ANGLE Metal 双 display 零拷贝着色器 FBO `glReadPixels` 像素与方向、无 `IOSurface` C++ NEON 回退与无 EGL 上下文释放路径。
  - 构建：`MACAPK_NO_BUNDLE_SYNC=1 ./runtime/scripts/build.sh` -> `rc=0`（`CONVERTER_ID=c0207dd09bc1489b`、`FRAMEWORK_ID=268c97bb300b5556`、`JAVA_ID=1ec69a1e06fc5311`、`JARS_ID=6cb8c9d643d80ca0`、`NATIVE_ID=5cce30aa26e85345`）。
  - 回归套件（已将 `SurfaceTextureNativePixbufTest` 接入 `gpu_pixel`）：
    - `python3 scripts/run_tests.py --group bare_jvm,jvm_launch_sensitive,native_unit,gpu_pixel --strict` -> `rc=0`（`PASS=54, FLAKY_PASS=0, FAIL=0, SKIP=0, NOT_RUN=11`）。
    - `python3 scripts/run_tests.py --group bare_jvm,jvm_launch_sensitive,native_unit,gpu_pixel,codegen_check --strict` -> `rc=2`（`PASS=55, FLAKY_PASS=0, FAIL=0, SKIP=1, NOT_RUN=9`；`gles_sig_roundtrip` `PASS`，仅 `gen_framework_attrs_check` 因缺少 `--attrs-xml` 为 `SKIP`）；`python3 scripts/run_tests.py --group bare_jvm,jvm_launch_sensitive,native_unit,gpu_pixel --test gles_sig_roundtrip --strict` -> `rc=0`（`PASS=1, FAIL=0, SKIP=0, NOT_RUN=64`）。
    - `python3 scripts/run_tests.py --test RemoteServiceProcessTest --strict` -> `rc=0`（`PASS=1, FAIL=0, SKIP=0, NOT_RUN=64`）；`python3 debug/FeatureGatesTest.py --json` -> `rc=0`（`PASS=23, FAIL=0, NOT_RUN=4`）。
  - 正式版促进与安装态验收：`MACAPK_FORCE_INSTALL=1 ./scripts/build_launcher.sh --promote` -> `rc=0`（替换 `/Applications/Mac.apk.app` 为 `261118.1011-c2c3ce5+dirty` `v1.0.2379` 并同步 `/Users/xrdemac/Library/Application Support/Mac.apk/runtime`）；`python3 scripts/run_tests.py --test installed_staleness_check --test installed_entitlements_check --allow-installed --strict` -> `rc=0`（`PASS=2, FAIL=0, SKIP=0, NOT_RUN=63`，未弹出 GUI 窗口）。
  - 独立原位验证：`java -Djava.library.path=runtime/build/bin ... SurfaceTextureNativePixbufTest` -> `rc=0`（覆盖 `EGL_DEFAULT_DISPLAY` 与 `MacApkEGL10-0x4A453130`）；`runtime/build/bin/macapk-runtime --gms accounts` -> `rc=0`（实机 `RegisterAllNativeMethods` 绑定通过）。
  - **交互/随航未测项**：`GFX-001` 全屏旋转切换、`DANMAKU-001` 弹幕开/关 A/B、及 `VIDEO-PWR-001` 实机 Sidecar 帧节奏/功耗对比均为 **`NOT_RUN (Sidecar disconnected)`**。

### 2026-11-15 阶段进展：keyed `SurfaceView` `EGL10` 零回读直连与正式版实测（暂停点）
- **本轮改动范围（worktree 未提交，已构建并 promote 至 `/Applications/Mac.apk.app`）**：
  - `EGL10` keyed `SurfaceView` 直通（已离线+实机验证）：[angle_glue.cpp](../../runtime/src/angle_glue.cpp) 抽取共享 `CreateWindowOnMetalLayer` 并新增 `CreateKeyedSurfaceViewWindow`（`nativeCreateKeyedSurfaceViewWindow`，软注册 `kKeyedMethods`）；[MacApkEGL10.java](../../runtime/java/javax/microedition/khronos/egl/MacApkEGL10.java) 新增 `KEYED_SURFACEVIEW_WINDOW`（`!"0".equals(System.getenv("MACAPK_EGL10_KEYED_SURFACEVIEW"))`）与 `tryCreateKeyedSurfaceViewWindow` / `noteDirectSurfaceFirstFrame`，使 `SurfaceView`/`SurfaceHolder`/`Surface(ownerSurfaceView)` 通过 `CocoaEnsureNativeEglLayerForSurfaceKey` 直接渲染到 keyed `CAMetalLayer` 并通知 View 树挖孔，不可用时回退至 `macapkEnsureEglStream()` -> `MacApkGlWindow`。
  - 其余关联工作区改动（跨进程/直通辅助）：[Surface.java](../../runtime/java/android/view/Surface.java)、[SurfaceView.java](../../runtime/java/android/view/SurfaceView.java)、[TextureView.java](../../runtime/java/android/view/TextureView.java)、[SurfaceTexture.java](../../runtime/java/android/graphics/SurfaceTexture.java)、[cocoa_bridge.mm](../../runtime/src/cocoa_bridge.mm)、[video_toolbox.mm](../../runtime/src/video_toolbox.mm)、[binder_mach.cpp](../../runtime/src/binder_mach.cpp) 及测试 [Egl10KeyedSurfaceViewTest.java](../../debug/Egl10KeyedSurfaceViewTest.java)、[SurfaceParcelTransportTest.java](../../debug/SurfaceParcelTransportTest.java)、[TextureViewDirectVideoTest.java](../../debug/TextureViewDirectVideoTest.java)。
- **本轮构建与验证证据**：
  - 离线测试：`python3 scripts/run_tests.py --group bare_jvm,jvm_launch_sensitive,native_unit --strict` -> `Exit code: 0`，**`PASS=45, FLAKY_PASS=0, FAIL=0, SKIP=0, NOT_RUN=18`**（`CONVERTER_ID=c0207dd09bc1489b`、`FRAMEWORK_ID=945cc701b81ca642`、`JAVA_ID=2527e6b619325f8d`、`JARS_ID=3c839b531c62a126`、`NATIVE_ID=5cce30aa26e85345`）。
  - 门控脚本：`python3 debug/FeatureGatesTest.py --json` -> `rc=0`，**`PASS=23, FAIL=0, NOT_RUN=4`**（`FG-INST-001..004` 为 `NOT_RUN`）。
  - 正式版促进：`MACAPK_FORCE_INSTALL=1 ./scripts/build_launcher.sh --promote` -> `Exit code: 0`，替换 `/Applications/Mac.apk.app`（`261115.0457-376256f+dirty`，`v1.0.2379`）并同步 `/Users/xrdemac/Library/Application Support/Mac.apk/runtime`。
  - 正式版 B 站实测（`scratch/measure_bili_perf.py`，`rc=0`）：日志确认 `tv.danmaku.render.core.c` 已走 keyed `SurfaceView` 直连 `CAMetalLayer`（`keyed SurfaceView window (key=1|2|4): EGLSurface on native-EGL layer ... zero-readback (javax EGL10)`、`direct keyed SurfaceView layer (key 1|2|4) frame #1 (1562x2084)`），`slow eglSwapBuffers=0`（对比 `04:48` 基线每帧 `33-42 ms` 同步 `glReadPixels` 已消除）、`readback frames=0`、`[dequeue-key] key=2 acquires=120 nil=0 last_ms=0.031 avg_ms=0.060 max_ms=3.320`、`key=3 acquires=600 nil=0 last_ms=0.440 avg_ms=0.902 max_ms=31.858`、`[I] fps` 9 组采样 `54.9–60.0`、`[frame-trace] guest-swap n=600 dt_us` 主体约 `16.6 ms`、`[vtb-stat]` 达 `retained=307 released=300 live=7 polled=303 rendered=0`。
- **已知残余卡顿线索与未完成项（下一步起点）**：
  1. `Surface(SurfaceTexture)` 非 `SurfaceView` 流仍在日志出现 `EGL14: eglCreateWindowSurface(Surface(SurfaceTexture) 1562x2084) -> pbuffer-backed window 0x1` 与 `[native-stream] SurfaceTexture window=... egl=0x1`/`0x3`，偶发 `[frame-trace] guest-swap` `33116`/`33419 us` 掉帧，启动聚焦期存在 `[main-stall]` 62 次。
  2. 首页未调用 `macapkRegisterVideoTarget()` 成为活跃直通目标的 `TextureView` 视频预览（`texture offer #197..202 source=1080x1920 gen=118..121`、`video sink state -> NONE`、`video gpu present count=0`）仍走 CPU latch/Canvas 路径。
  3. `[vtb-stat] rendered=0` 表明 ijkplayer 走 VTB -> `SurfaceTexture.updateTexImage()` -> `ijk_ext_render`（`EGL10`），且日志记录 `[frame-pacing] per-thread Java Choreographer vsync with no libunity loaded: FramePacer NOT engaged`；`updateTexImage()` 与宿主 `60 Hz` `CVDisplayLink`/`MTKView` 显示提交之间的实际显示帧节奏及真机详情页 steady-state 录屏对比尚未排查。
  4. 原待测项 [GFX-001](GFX-001.md) 竖/横全屏按钮切换及旋转 30 秒连续性、[DANMAKU-001](DANMAKU-001.md) 弹幕关/开独立功耗 A/B 与系统总功率/GPU 采样仍未关闭。

### 2026-11-15 Sidecar 副屏闪退修复与播放复验
- **根因与修复**：`libmsaoaidsec.so` 等匿名 RWX（`macapk::jitpages`）访存保护陷阱中，XNU 在 `TPIDR_EL0[23:0]` 编码每核/簇 ID（`arm64_native` 置位 bit 48），线程在函数 prologue `mrs TPIDR_EL0` 与 epilogue `ldr [Xn,#0x28]` 间跨核迁移时原 `base == tpidr` 校验失效导致 `SIGSEGV`。现于 [guest_jit_canary.h](../../runtime/translator/src/guest_jit_canary.h) 放宽为比对不变量高位 `~0xffffffULL` 与 `fault == base + 0x28`，并在 [guest_jit_canary_test.cpp](../../debug/guest_jit_canary_test.cpp) 补充跨核/跨簇/高位不匹配回归（`python3 scripts/run_tests.py --test guest_jit_canary_test --strict` PASS）。
- **Sidecar（`Sidecar Display (AirPlay)`）B 站 9.19.0 隔离实测（`MACAPK_PROCESS_SPLIT=0`）**：连续两轮启动均直接在副屏打开并进入 `MainActivityV2`（无 `SIGSEGV`、无新 `hs_err`、`final_rc=0`）；首页内嵌播放及点击视频卡片进入 `UnitedBizDetailsActivity` 均正常渲染播放（`eval-home-fixed.png` / `eval-video-fixed.png`，`[vtb-stat]` 达 `retained=1030 released=1020 live=10 polled=1022 rendered=997`，`[nowplaying] state=1 pos=1.0/679.4 rate=1.00 art=yes`，滚动弹幕与字幕正常；15 秒详情播放 `ps` 采样均值 `108.9% CPU` / `RSS 1719.4 MiB`）。
- **未完成**：[GFX-001](GFX-001.md) 竖/横全屏按钮切换及旋转 30 秒连续性、[DANMAKU-001](DANMAKU-001.md) 弹幕关/开独立功耗 A/B 与系统总功率/GPU 采样仍待后续交互测试。
- 后续执行顺序见 [升级任务表](../UPGRADE_PLAN.md)。

## 验收范围

1. 区分冷启动、初始化后无操作、固定视频无弹幕、同视频有弹幕。
2. 对齐实际 runtime、APK、播放位置、分辨率、窗口大小与显示刷新率。
3. 测 CPU/线程热点、内存、GPU/唤醒与适用能量指标；CPU 不等于功耗。
4. 与同机原生/iPad 客户端及 MuMu 的可比场景对照，报告环境与不等价部分。
5. 优化需保留画质、视频速度、音画同步、弹幕、输入和前后台恢复；不能降帧伪造改善。
6. 重复前后测量超过噪声范围；相关通用回归通过，实际用户场景验收。

## 2026-11-03 至 11-04 的采样条件和结果

- 独立 APFS 数据副本；重定向 shared-storage 与正式数据/原生库链接，启动前验证无指向副本外的文件链接。
- 首次 45 秒探测主要是启动初始化，不能充当稳定空闲基线。
- 直接 runtime CLI 缺少 stub 的 quiet-classload 与四项兼容默认值；后续采集显式对齐。
- 新增 debug/perf/profile_process.py：用累计 CPU 时间差计算区间占用，独立记录 RSS、sample 和异常。
- 原始日志/数据仅在被忽略的 work/upgrade/VIDEO-PWR-001；不提交账号数据或 APK。

### 探索性结果与限制

固定视频的 EGL10 Metal 实验路径已取得单进程 CPU 改善，但默认路径尚未启用，
启动后的空闲基线已稳定复测，但降低空闲重绘的 `MACAPK_FRAMESPEED_HEARTBEAT=1`
仍是显式实验开关，尚未改为通用默认。弹幕负载、Vulkan、GPU合成本身及实际能耗
仍未完成；弹幕开关需要独立、可观察的状态断言。窗口缩小后的播放缓冲仍需继续
调查。运行中采集的句柄与结果必须继续核对。

2026-11-03：已用公开 IORegistry 与 `powermetrics` 对齐应用、WindowServer、
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

### 早期探针与读回热点

- 首轮 Sidecar 探针因临时测试外壳缺失 Vulkan ICD 而初始化 EGL 失败。补齐后观察到视频帧推进。
- `MacApkGlWindow.queueFrame` 的 998×1480 探针前 600 帧 GPU→CPU 读回约 3.54 GB，
  `glReadPixels` 平均 15.80 ms。
- 私有 ANGLE Metal display 的 EGL→IOSurface→Metal 探针通过 60 帧像素对照，使用
  `glFinish` 同步。相同 Vulkan 探针的 GL 像素正确，IOSurface 与 Metal 纹理视图为零。
- 三槽共享帧池通过 FIFO、回压、实例隔离、销毁及独立 JVM JNI 像素验证。早期生产
  路径仍有一次兼容拷贝，后续 EGL10 接入见下节。

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

## Surface IOSurface 直交实验

在稳定共享 EGL 路径之后，新增默认关闭的
`MACAPK_SURFACE_IOSURFACE_PRESENT=1`：把现有预乘 BGRA 像素写入按 SurfaceView
key 管理的四槽 IOSurface 环，直接作为 `CALayer.contents` 交付，绕开逐帧
`CGImage` 的 prepare、copy 和颜色转换。任一步失败仍走原路径；
`MACAPK_PRESENT_IOSURFACE_CS` 可分别选择 `none`、`device`、`srgb` 或窗口实际
显示器 profile。

同一 11 分钟视频的 180 秒长测使用 360×672 Sidecar 窗口。稳定段的原 `CGImage` 路径 CPU + GPU 功率约
4.1–5.0 W，新路径为 2.664–3.633 W；应用 energy impact 从 965–1045 降到
513–687，应用与 WindowServer CPU 均下降，GPU 功率小幅上升。r24 的 RSS 仍
明显高于原路径，中位数约 774 MiB，均值约 876 MiB。四槽环约 14 MiB，内存增长仍需复核。

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


## 历史原文

28 轮实验的逐轮命令、采样区间和过程记录可用
`git show dfe3fe2:docs/VIDEO_POWER_PROGRESS.md` 查阅。
原始测量文件的路径按当时工作树记录，适用当前版本的结果由后续任务重新采集。


## 2026-11-14 当前构建实跑边界

使用 [B 站官方下载中心](https://app.bilibili.com/) 安卓 64 位 9.19.0 APK
（`tv.danmaku.bili`，SHA-256 `c3ea50c7773267f252e441b5c1aebf7574492223b2bb1568d7a7093d1f8eb59c`）及隔离测试 root。
本地 `e10148d` 源码构建成功进入访客协议弹窗。内建 Retina，1710×1112 pt、2×、60 Hz，Sidecar 未连接。
弹窗要求接受使用协议和访客隐私政策。未代用户接受；本次测试进程按预定时限结束（SIGTERM）。
播放、弹幕、窗口内播放器全屏、音频连续性及同场景功耗仍为 NOT_RUN。
本地证据 `work/upgrade/VIDEO-PWR-001/{manifest.log,initial.log,bili-window.png}`。

## 2026-11-14 像素回读 NEON 加速与媒体后台去轮询

- **像素回读与 IOSurface 拷贝（`FlipBgraPremulToArgb` / `FlipRgbaPremulToArgb`）**：
  - 在 [video_pixels.h](../../runtime/src/video_pixels.h) 新增共享 ARM64 NEON 加速函数 `FlipBgraPremulToArgb`（不透明 16 像素块直通拷贝、全透明块清零、仅半透明像素回退反预乘）与 `FlipRgbaPremulToArgb`（不透明 16 像素块 SIMD 交换 R/B 通道），并接入 [shared_gl_frames.mm](../../runtime/src/shared_gl_frames.mm) 的 `copyArgb` 与 [native_methods.cpp](../../runtime/src/native_methods.cpp) 的 `Cocoa_convertGlPbufferToArgb`（消除标量逐像素循环与 GCLocker critical pin 持有时间）。
  - 扩充 [VideoPixelsTest.cpp](../../debug/VideoPixelsTest.cpp) 对奇偶宽度、stride、全不透明/全透明/混合 alpha 逐像素比对及边界哨兵测试（PASS），`gpu_pixel` 全部 7 项（含 `SharedGlFramesJniTest`）PASS。
  - `clang++ -std=c++17 -O2` 1080p×120 帧微基准对比：`FlipBgraPremulToArgb` 由 `0.812 ms/帧` 降至 `0.140 ms/帧`（约 5.8×），`FlipRgbaPremulToArgb` 由 `0.671 ms/帧` 降至 `0.138 ms/帧`（约 4.9×）。
- **媒体后台空闲/暂停去轮询**：
  - [MediaCodec.java](../../runtime/java/android/media/MediaCodec.java)：`asyncDispatchLoop()` 与 `waitForBufferLocked()` 改为仅当存在在途外部 VideoToolbox 帧（`hasExternalVideoFeed() && !mOutFree.isEmpty() && nativeVidQueueDepth(mVidDec) > 0`）或定时渲染队列积压时才做短超时轮询；音频解码器、VP8/VP9、空闲/暂停视频及输出池满时分别走事件唤醒（`mAsyncLock.wait()`）与 20 ms 防御上限（消除 100 Hz / 500 Hz 无效唤醒，`MediaCodecDequeueTest` PASS）。
  - [AudioTrack.java](../../runtime/java/android/media/AudioTrack.java)：`macapk-audiotrack-pos` 位置监听线程引入 `mPosLock` 条件等待，在未播放（暂停/停止）、未配置监听或未设置 marker/period 时零唤醒休眠（消除 50 Hz 空转），并于 `play()` / `pause()` / `stop()` / `flush()` / `release()` 与监听配置入口即时唤醒。
- **未覆盖边界**：真实 B 站 9.19.0 在同意隐私弹窗后的动态播放、弹幕、Sidecar 副屏长时 `powermetrics` 采样仍需人工交互（`NOT_RUN`），本卡维持 `IN_PROGRESS`。

## 2026-11-18 Sidecar 非抢焦点/非抢鼠标无头自动化通道

- **实现范围**：
  - [AppStubMain.swift](../../launcher/AppStubMain.swift)：按 `MACAPK_AUTOMATION=1` 且 `MACAPK_AUTOMATION_DIR` 非空门控自动化模式；非自动化路径保留原 9 变量 `unsetenv`；自动化路径在 `RegistryStore` `row.env` 之后强制设置 `MACAPK_NO_ACTIVATE=1`、`MACAPK_PAUSE_WHEN_HIDDEN=0`，保留 `MACAPK_HOST_DISPLAY`/`MACAPK_AUTOMATION*`/trace 变量，为主进程重定向 `MACAPK_AUTOMATION_LOG` 至 stdout/stderr 并禁用弹窗与单实例焦点激活，以 `execv` 同 PID 移交运行时。
  - [cocoa_bridge.mm](../../runtime/src/cocoa_bridge.mm)：新增 `MacApkAutomationMode()`、`MacapkExplicitLaunchScreen()` 与 `MacapkAnchorScreenForPlaceholder()`。主线程按 `MACAPK_HOST_DISPLAY` 选屏前确保 `NSApp` 已初始化以获得 Sidecar 菜单栏后的 `visibleFrame`（`905pt` 高度）；两个 `MacApkHarnessNoActivate()` 展示点在自动化模式使用 `orderFrontRegardless` 置前供 `screencapture -l` 抓图而不激活或抢键盘焦点；`reshape_placeholder_to_globals_main()` 与 `recenter_placeholder_next_cycle()` 在横竖屏切换时锚定并钳位在目标副屏 `visibleFrame` 内，不跳回 `[NSScreen mainScreen]`。
  - [AutomationServer.java](../../runtime/java/com/macapk/loader/AutomationServer.java)、[Boot.java](../../runtime/java/com/macapk/loader/Boot.java) 与 [TouchDispatcher.java](../../runtime/java/com/macapk/loader/TouchDispatcher.java)：仅主进程在 `MACAPK_AUTOMATION=1` 且 `MACAPK_AUTOMATION_DIR` 非空时启动守护线程（关闭零开销）；提供纯 Java RFC 8259 JSON 编解码、`ready.json`/`cmd.json`/`state.json` 原子读写与 `state`、`ui_tree`、`tap`、`swipe`、`key`、`set_orientation`、`open_url`、`quit` 命令；`TouchDispatcher` 新增 `macapkInjectHostSwipe`、`macapkAwaitLoopIdle` 与 `macapkSurfaceWidthPx`/`macapkSurfaceHeightPx`。
  - [sidecar_runner.swift](../../debug/sidecar_runner.swift) 与 [AutomationServerTest.java](../../debug/AutomationServerTest.java)：`discoverScreens()` 调用 `NSApplication.shared` 发现 `Sidecar Display (AirPlay)`，提供 `screens`/`launch`/`wait-ready`/`cmd`/`resize`/`screenshot`/`quit`，禁用 `CGEvent` 鼠标与 `activates = true`。
- **本轮离线与构建验证（7df7ce0 基线 worktree）**：
  - `MACAPK_NO_BUNDLE_SYNC=1 ./runtime/scripts/build.sh` -> `rc=0`（`CONVERTER_ID=c0207dd09bc1489b`、`FRAMEWORK_ID=da0e20c79cd8f037`、`JAVA_ID=42ea880ddd88b2fd`、`JARS_ID=431b0826a5a74dad`、`NATIVE_ID=5cce30aa26e85345`）。
  - `AppStubMain.swift` 编译检查（`swiftc -O -parse-as-library -target arm64-apple-macos26.0 -framework AppKit`）-> `rc=0`。
  - `swiftc -O -target arm64-apple-macos26.0 debug/sidecar_runner.swift -o runtime/build/bin/sidecar_runner` + `./runtime/build/bin/sidecar_runner screens` -> `rc=0`（识别 `Sidecar Display (AirPlay)` `displayID=4`，`isSidecar=true`，`quartzVisibleFrame` `x=-1339, y=30, width=1339, height=905`）。
  - `AutomationServerTest`（JDK 21 裸 JVM，JSON 编解码、门控关闭、归一化坐标映射、`ui_tree` 结构与叠加层 bounds/centerNorm、原子状态与 `quit` 先回写再退出）-> `rc=0`。
  - `python3 scripts/run_tests.py --group bare_jvm,jvm_launch_sensitive,native_unit --strict` -> `rc=0`（`PASS=46, FLAKY_PASS=0, FAIL=0, SKIP=0, NOT_RUN=19`）。
- **未执行项**：真实 `/Applications/Mac.apk.app` 交互态 B 站 Sidecar GUI 自动化驱动与帧节奏/功耗实测保留 `NOT_RUN`（待实机 Sidecar 自动化验证）。
- **整合与正式版促进检查点（`mac_apk_architecture_upgrade` @ `b1e41ac` + 整合提交）**：
  - `git merge --no-ff 11379438cf66968a3e5c2930b3f849c8362eb8e1`（`b1e41ac`）；在 [run_tests.py](../../scripts/run_tests.py) 将 `AutomationServerTest` 注册到 `bare_jvm`（`classpath_mode="both"`）。
  - 构建：`MACAPK_NO_BUNDLE_SYNC=1 ./runtime/scripts/build.sh` -> `rc=0`（`CONVERTER_ID=c0207dd09bc1489b`、`FRAMEWORK_ID=da0e20c79cd8f037`、`JAVA_ID=42ea880ddd88b2fd`、`JARS_ID=431b0826a5a74dad`、`NATIVE_ID=5cce30aa26e85345`）；`. ./scripts/sdk_fallback.sh && swiftc -O -target arm64-apple-macos26.0 debug/sidecar_runner.swift -o runtime/build/bin/sidecar_runner` -> `rc=0`；`./runtime/build/bin/sidecar_runner screens` -> `rc=0`（确认 `Sidecar Display (AirPlay)` `displayID=4`，`isSidecar: true`，`quartzVisibleFrame` `{-1339, 30, 1339, 905}`）。
  - 台账同步：`python3 scripts/cap_registry.py generate --update-capabilities --output-md docs/compat/CAPABILITIES.md` -> `rc=0`（[inventory.json](../compat/inventory.json) `sha256=fab0bc051c7be870`、`7011` 类型、`1091` JNI、`2325` bionic；[capabilities.json](../compat/capabilities.json) `sha256=482c7e4f46bb948a`、`28` entries）；`python3 -m unittest debug/CapRegistryTest.py -v` -> `rc=0`（`Ran 12 tests, OK`）。
  - 离线测试：`python3 scripts/run_tests.py --group bare_jvm,jvm_launch_sensitive,native_unit --strict` -> `rc=0`（**`PASS=47, FLAKY_PASS=0, FAIL=0, SKIP=0, NOT_RUN=19`**，含 `AutomationServerTest` `PASS`）。
  - 正式版促进：`MACAPK_FORCE_INSTALL=1 ./scripts/build_launcher.sh --promote` -> `rc=0`（替换 `/Applications/Mac.apk.app` 为 `261118.1114-b1e41ac+dirty` `v1.0.2379`，同步共享运行时 `/Users/xrdemac/Library/Application Support/Mac.apk/runtime` 与 4 个已安装应用存根含 `/Applications/tv.danmaku.bili.app` `81af0f09c46fdfd5a2a576a1847693d6`）；`python3 scripts/run_tests.py --test installed_staleness_check --test installed_entitlements_check --allow-installed --strict` -> `rc=0`（**`PASS=2, FAIL=0, SKIP=0, NOT_RUN=64`**）；`pgrep -fl "Mac.apk|Bili|bilibili"` 无运行窗口进程。

## 2026-11-18 VideoToolbox B-frame 管线与无头次级进程冷启动优化检查点

- **VideoToolbox B-frame 饥饿与冷探测缓存**：
  - [video_toolbox.mm](../../runtime/src/video_toolbox.mm) 与 [NativeMedia.java](../../runtime/java/com/macapk/loader/NativeMedia.java)：将 `macapk_vtb_decode` 的 `kMaxInFlight`/`kMaxQ` 提高至 `16`/`20`、`OutputCallback` `kMaxOutQ` 提高至 `36`（`cvInFlight.notify_all()`）；默认关闭 `kVTDecodeFrame_EnableTemporalProcessing`（`MACAPK_VTB_TEMPORAL_PROCESSING=1` 开启），交由 Java `VideoReorder` 排序；新增 `macapk_vtb_wait_poll` / `nativeVidWaitPoll(dec, timeoutUs)`（在 `cvInFlight` 上界定等待 `min(timeoutUs, 2000)` us）；为 `nativeVideoCapabilities` 增加按 `kern.osversion` + `mime` 键控的原子写入磁盘缓存（`~/Library/Caches/Mac.apk/vtcaps_v1.cache`，回退 `/tmp/macapk_vtcaps_v1_<uid>.cache`，`MACAPK_VTCAPS_DISK_CACHE=0` 关闭）。
  - [MediaCodec.java](../../runtime/java/android/media/MediaCodec.java)：视频输出槽扩至 `VIDEO_OUT_BUFS = 24`、`VideoReorder` 上限扩至 `20`，`dequeueInputBuffer` 放宽为 `reorderCap = Math.max(12, mReorder.depth + 4)` / `nativeDepth >= 16` / `timedCount >= 12`，`dequeueOutputBuffer` 与 `asyncDispatchLoop` 在释放 `mAsyncLock` 后通过 `nativeVidWaitPoll` 等待（保留 `UnsatisfiedLinkError` 回退至 `nativeVidPoll` 并由 `mVidWaiters` / `mFlushSeq` 保护 `releaseDec` 生命周期）。
- **无头服务进程、`.eh_frame` 跳注册与媒体服务预热**：
  - [main.cpp](../../runtime/src/main.cpp) 与 [Boot.java](../../runtime/java/com/macapk/loader/Boot.java)：支持 `--service-only` / `--process-name` 及 `MACAPK_SERVICE_HOST` / `MACAPK_SECONDARY_PROCESS` / `:suffix` 进程识别（`MACAPK_HEADLESS_SERVICE=0` 或 `MACAPK_FORCE_SECONDARY_WINDOW=1` 退回），跳过前置 `CocoaInit()` 占位窗、AWT 预热、T1 启动窗上色与图标栅格化。
  - [arm64_native.cpp](../../runtime/translator/src/arm64_native.cpp)：`fde_count > 1024` 且动态符号/重定位无未定义 C++ unwind 符号（`_Unwind_RaiseException`、`_Unwind_Resume`、`__cxa_throw`、`__gxx_personality_v0`、`__cxa_begin_catch`）时跳过逐个 `__register_frame` 循环（`MACAPK_SKIP_NOEXCEPT_EH_FRAME=0` 关闭）。
  - [SystemClient.java](../../runtime/java/com/macapk/loader/SystemClient.java) 与 [Boot.java](../../runtime/java/com/macapk/loader/Boot.java)：首个 Activity `onResume` 后约 `400 ms`（`MACAPK_PREWARM_MEDIA_SERVICES=0` 关闭）按通用媒体关键词（`media`/`player`/`ijk`/`playback`/`audio`/`exo`）异步预热至多 1 个 manifest 声明的远程 `:suffix` `<service>` 进程。
- **worktree 验证**：`MACAPK_NO_BUNDLE_SYNC=1 ./runtime/scripts/build.sh` -> `rc=0`；`python3 scripts/run_tests.py --test MediaCodecEncoderTest --test BrokerServiceStartupTest --strict` -> `rc=0`（`PASS=2, FAIL=0`），`MediaCodecDequeueTest` `PASS`。

## 2026-11-18 整合构建、promote 与 Sidecar 25s 实测验证（`ed9698e` + `2d1da57`）

- **整合与离线/安装态检查**：
  - `MACAPK_NO_BUNDLE_SYNC=1 ./runtime/scripts/build.sh` -> `rc=0`（`CONVERTER_ID=c0207dd09bc1489b`、`FRAMEWORK_ID=9c0e118287aa5ad3`、`JAVA_ID=162d1166a77db70b`、`JARS_ID=ab9d8834830ed86c`、`NATIVE_ID=d9446ee83be26eec`）。
  - `python3 scripts/cap_registry.py generate --update-capabilities --output-md docs/compat/CAPABILITIES.md` -> `rc=0`（`inventory sha256=e75a1be5367e7147`、`7011` 类型、`1092` JNI、`2325` bionic；`capabilities sha256=e475af56922e20df`、`28` entries）；`python3 -m unittest debug/CapRegistryTest.py -v` -> `rc=0`（`12 tests OK`）。
  - `python3 scripts/run_tests.py --test SurfaceTextureNativePixbufTest --test MediaCodecEncoderTest --test BrokerServiceStartupTest --test TextureViewDirectVideoTest --test Egl10KeyedSurfaceViewTest --test SurfaceParcelTransportTest --test GuestReturnAliasTest --test AutomationServerTest --strict` -> `rc=0`（**`PASS=8, FAIL=0, SKIP=0, NOT_RUN=58`**；[SurfaceTextureNativePixbufTest.java](../../debug/SurfaceTextureNativePixbufTest.java) 已适配 4 槽 FIFO 环并补充溢出淘汰与 ANGLE Metal 实测）。
  - `MACAPK_FORCE_INSTALL=1 ./scripts/build_launcher.sh --promote` -> `rc=0`（`/Applications/Mac.apk.app` `261118.1447-24a8f2a+dirty`，同步共享运行时与 4 个应用存根）；`python3 scripts/run_tests.py --test installed_staleness_check --test installed_entitlements_check --allow-installed --strict` -> `rc=0`（**`PASS=2, FAIL=0, SKIP=0, NOT_RUN=64`**）。
- **Sidecar（`Sidecar Display (AirPlay)` `displayID=4` `x=-1339,y=30,1339x905 @2x`）25s 非抢焦点实测（`verify_sidecar_perf.py`）**：
  - 窗口与焦点：`onSidecar=true`（`wid=8090`），`frontmost` 全程未切至 `tv.danmaku.bili`；`perf_t3.png` / `perf_t20.png` 视觉确认 HEVC 852x480 视频、字幕与横向弹幕正常渲染；`sidecar_runner quit tv.danmaku.bili` 后 0 个残留进程。
  - 启动与服务进程表现：
    - 主进程预热日志确认触发 `prewarmed media service host ... (tv.danmaku.bili:ijkservice)`，且跳过 noexcept `.eh_frame` FDE 注册生效（如 `libkntr.so: skipped 60990`、`libMiniEngine.so: skipped 85144`、`libignet.so: skipped 27586`、`libijkffmpeg.so: skipped 11117`）。
    - **未达预期项 1（`:ijkservice` 仍冷启耗时与弹窗占位）**：`:ijkservice` 在 `boot-clock +11289 ms` 才进入 Cocoa runloop，且日志仍打印 `[macapk] window: placeholder shown`（`MacapkWindowFirst` 仅跳过 T0，进入 `CocoaRunLoop` 后仍创建占位窗）；从点击首页视频卡片（`t_tap`）到打开 `UnitedBizDetailsActivity` 为 `146.8 ms`，但到 `[video-toolbox] open: mime=video/hevc 852x480 hw=YES` 为 `21,433.8 ms`，到 `native-pixbuf offer #1` 为 `21,653.5 ms`。
  - 20.264s 连续播放实测与对比（对比 Wave-3 前置基线 `bili_wave3.log`）：
    - UI `Choreographer` `fps` 均值 `59.96`（`slow_eglSwapBuffers_in_20s=0`、`gl_readback_lines_in_20s=0`、`keyed SurfaceView` `EGL10` `direct CAMetalLayer=2`），**但 `EGL14` 仍创建 `pbuffer-backed window 0x1`/`0x3`（2 次）**。
    - **`native-pixbuf offer` 20.264s 交付帧数**：`379` 行 / 序号 `#15..#399`（跨度 `385` 帧）= **`18.95 fps`**（`18.70 fps` by lines，低于 28 fps 门限；同期 `:ijkservice` `[vtb-stat]` `retained` `79->622` `+543`、`released` `60->600` `+540`、`polled` `65->606` `+541` (`26.7/s`)、`live=22`）。
    - `ps` 20s 占用（5 进程）：B 站累计 CPU `131.27%`（`pcpu_mean=121.48%`），RSS 均值 `1532.7 MB` / 峰值 `1652.0 MB`；`WindowServer` `14.41%`（`73.7 MB`）。
  - 产物：`scratch/sidecar_wave3/bili_after_perf.log`、`after_perf_report.json`、`perf_t3.png`、`perf_t20.png` 及 `~/Library/Logs/macapk/app_tv.danmaku.bili_9_19_0-tv.danmaku.bili_ijkservice.log`。
- **RE-PLAN 结论**：卡点已精确定位，本卡继续保持 `IN_PROGRESS`：① `:ijkservice` 进入 `CocoaRunLoop` 仍创建占位窗且进程级初始化耗时 `11.3s`；② 视频解码 `polled +541`（`26.7/s`）而 `SurfaceTexture` 仅收到 `385` 帧（`18.95 fps`）且 `EGL14` 仍走 `Surface(SurfaceTexture)` `pbuffer-backed` 路径。

## 2026-11-18 Slice B：`Looper` 日志与唤醒、音频主时钟平滑/防爆音及次级服务快启（`6c1822f` 基线）

- **诊断基线（`/Applications/tv.danmaku.bili.app` @ `6c1822f` Sidecar 非抢焦点实测）**：
  - 主进程 `ijk_java_player` 仅消耗 `87.02 ms` CPU，`dispatch #1800..#24600` 为短时消息突发，但 `Looper.java` 默认每 200 次无条件打印 `Log.i` 造成日志刷屏；`AudioTrack` 与 `MediaCodec` 均在 `:ijkservice` 进程运行，旧版 `SystemServer.launch` 未透传测试端 `MACAPK_*` 环境变量。
  - `:ijkservice` 启动期间并发运行了 `ApkNeedsGles31Cached` / `ApkNeedsGles1Cached`（并曾触发 MoltenVK 与 ES1 扫描），且 4 个次级服务并发冷启竞争。
- **实现改动**：
  - [Looper.java](../../runtime/java/android/os/Looper.java) 与 [MessageQueue.java](../../runtime/java/android/os/MessageQueue.java)：`dispatch #` 超过前 5 条与主循环 60/600/3000 统计日志改由 `MACAPK_LOOPER_TRACE=1` 门控；非屏障延迟消息插入已有更早队头之后时跳过多余 `NativeLooper.wake` 管道写唤醒。
  - [AudioTrack.java](../../runtime/java/android/media/AudioTrack.java) 与 [audio_backend.mm](../../runtime/src/audio_backend.mm)：`getMinBufferSize` 最小缓冲下限提至 80 ms（`Math.max((sampleRateInHz * 2) / 25, 2048)`）；`render_stream` 增加 `last_sample`/`fade_in_needed` 32 帧平滑淡出/淡入消除欠载爆音，并引入单写者 seqlock 与 `AudioGetCurrentHostTime()` 量子内插值使 `getPlaybackHeadPosition()`/`getTimestamp()` 单调平滑推进（`MACAPK_AUDIO_CLOCK_INTERP=0` 可关闭），新增 `macapk_audio_total_underruns()`。
  - [main.cpp](../../runtime/src/main.cpp) 与 [SystemClient.java](../../runtime/java/com/macapk/loader/SystemClient.java)：`ApkNeedsGles1Cached` 新增 `android_root/.macapk/gles1_scan_v1.cache` 持久化；`MacapkHeadlessService` 在无显式覆盖时直接走 Metal 并跳过 GLES31/GLES1 扫描；主进程 `SystemClient.attach()` 原子写入 `.macapk_child_env.cfg` 由次级无头进程读取继承 `MACAPK_*` 标志；`scheduleMediaServicePrewarm` 错峰避让 `onCreate` 并发服务拉起并异步预热 `vtcaps_v1.cache`。
- **worktree 验证**：`MACAPK_NO_BUNDLE_SYNC=1 ./runtime/scripts/build.sh` -> `rc=0`；`AudioTrackClockMonotonicityTest`、`MediaCodecEncoderTest`、`BrokerServiceStartupTest`、`MediaCodecDequeueTest`、`ProcessBrokerRoutingTest`（`--strict`）全通过；实机 CoreAudio 微基准 `test_audio_native` 120×1ms 轮询 `114` 次平滑推进、`maxStep=77` 帧（`1.6 ms`）、`pause`/`flush` 校验 `PASS`。

## 2026-11-18 暂停交接检查点（Slice A + Binder FIFO + 选窗/退出链路 + 测试稳定性与残余瓶颈）

因用户要求暂停排障并整理交接，本卡保持 **`IN_PROGRESS`**，以下严格区分已清理合入的代码/已运行验收、暂停前 trace 阶段观测值、以及待接手项：

- **代码与测试改动清单（相对 `HEAD b6cbdda`，已清理临时诊断打印并复原 `AudioTrack.java`）**：
  - **跨进程 Surface/VTB 时间戳与顺序契约（Slice A）**：[Surface.java](../../runtime/java/android/view/Surface.java)、[SurfaceTexture.java](../../runtime/java/android/graphics/SurfaceTexture.java)、[MediaCodec.java](../../runtime/java/android/media/MediaCodec.java)、[video_toolbox.mm](../../runtime/src/video_toolbox.mm)、[NativeMedia.java](../../runtime/java/com/macapk/loader/NativeMedia.java)、[egl14_bridges.inc](../../runtime/src/egl14_bridges.inc)、[MacApkEGL10.java](../../runtime/java/javax/microedition/khronos/egl/MacApkEGL10.java)、[MacApkGlWindow.java](../../runtime/java/javax/microedition/khronos/egl/MacApkGlWindow.java)。
    - `Surface` 新增进程唯一 `mSurfaceId`、`mGeneration` 与单调 `mPresentSeq`，远端帧投递携带 generation/序号；生产侧与消费侧新增零开销原子统计及 `latchIntervalMs` 环形直方图（`p50`/`p95`/`p99`/`max`/`>50ms`/`>80ms`）。
    - `MediaCodec` 将拆分的 `mSurface`/`mHasSurface` 收敛为不可变 `OutputTarget(surface, hasSurface, gen)` + `volatile mOutputTarget` 原子更新，并在 `stop`/`reset`/`release`/`flush` 通过 `bumpOutputGen()` 隔离滞后渲染；`vidDecode()` 移出 `mAsyncLock` 至 `mEncSubmitLock`（受 `mVidWaiters` / `mFlushSeq` 保护），`releaseOutputBuffer()` 先释放 `mAsyncLock` 再执行呈现与 `nativeVidReleaseFrame`。
    - `egl14_bridges.inc` 为 `SurfaceView` native-EGL 层登记 `egl14_direct_add(d, s, -2)` 并上报 `eglDirectSwaps`。
  - **Oneway Binder 接收保序**：[binder_mach.cpp](../../runtime/src/binder_mach.cpp) 引入 `g_rcv_mu` 与 `MacApkBinderTransport.java` 配合，仅对 `oneway` 事务持锁跨越 `dispatch`（保证远端帧投递 FIFO），同步事务与 `MACH_NOTIFY_DEAD_NAME` 在派发前释放。
  - **无头启动、退出与 Sidecar 测试辅助**：
    - [AutomationServer.java](../../runtime/java/com/macapk/loader/AutomationServer.java)：新增 `video_stats`（支持 `reset` 与 `producer`/`consumer`/`latchIntervalMs` 快照）；`defaultExitProcess()` 改用 `NativeCocoa.exitProcess(0, null)`（避免 `System.exit(0)` 与 `ApplicationShutdownHooks`/`__cxa_finalize` 死锁风险）并移除退出前 UI thread `finishAffinity()` 等待，兜底 `Runtime.getRuntime().halt(0)`。
    - [SystemServer.java](../../runtime/java/com/macapk/loader/SystemServer.java)：增强按 `callerPkg` 归属追踪的包级退出清理与 `terminateProcessList()`（先 SIGTERM、超时后 SIGKILL 并 `waitFor` 收口）。
    - [cocoa_bridge.mm](../../runtime/src/cocoa_bridge.mm)：无头次级服务跳过占位窗显示路径。
    - [Boot.java](../../runtime/java/com/macapk/loader/Boot.java) 与 [SystemClient.java](../../runtime/java/com/macapk/loader/SystemClient.java)：抽出 `startResourceIndexTask()`/`awaitResourceIndexTask()`/`applyResourceIndexAndTheme()`，让无头子进程 `ResourceIndex` 后台构建与初始化并行；`scheduleMediaServicePrewarm` 提前启动 `vtcaps_v1.cache` 预热并将首拍 `:ijkservice` 延迟缩短为 `150 ms`/错峰窗口 `600 ms`。
    - [sidecar_runner.swift](../../debug/sidecar_runner.swift)：重构 CG/AX 窗口候选结构（`CGWindowCandidate`/`AXWindowCandidate`）改善 Sidecar 截图选窗，`terminateMatchingApps()` 分两阶段先清理次级运行时子进程（`killPidGroup` / `appBundleMatches`）再终止主进程。
    - 新增 [CrossProcessTimestampContractTest.java](../../debug/CrossProcessTimestampContractTest.java)（并修正 `PHASE_POST_RELEASE_DROP` `arg=1` 直接等待 `ipcDroppedInvalid >= 1L`，消除与 `ipcRecv` 的自增竞态），更新 [BrokerServiceStartupTest.java](../../debug/BrokerServiceStartupTest.java) 与 [run_tests.py](../../scripts/run_tests.py)。
- **清理后当前代码构建、台账与促进证据**：
  - `MACAPK_NO_BUNDLE_SYNC=1 ./runtime/scripts/build.sh` -> `rc=0`（`CONVERTER_ID=c0207dd09bc1489b`、`FRAMEWORK_ID=2ddf9b63646d86ff`、`JAVA_ID=54b1eca73eb8fc43`、`JARS_ID=d6f301f481d7af7e`、`NATIVE_ID=d9446ee83be26eec`）。
  - `swiftc -O -target arm64-apple-macos26.0 debug/sidecar_runner.swift -o runtime/build/bin/sidecar_runner` -> `rc=0`。
  - `python3 scripts/cap_registry.py generate --update-capabilities --output-md docs/compat/CAPABILITIES.md` -> `rc=0`（[inventory.json](../compat/inventory.json) `sha256=1511181e323a8d75`、`7018` 类型、`1094` JNI、`2325` bionic；[capabilities.json](../compat/capabilities.json) `sha256=b8654b7e97d85704`、`28` entries）；`python3 -m unittest debug/CapRegistryTest.py -v` -> `rc=0`（`Ran 12 tests, OK`）。
  - `MACAPK_FORCE_INSTALL=1 ./scripts/build_launcher.sh --promote` -> `rc=0`（替换 `/Applications/Mac.apk.app` 为 `261118.2159-b6cbdda+dirty` `v1.0.2379`，同步共享运行时 `/Users/xrdemac/Library/Application Support/Mac.apk/runtime`，4 个应用存根 `0 updated, 4 already current`）。
- **清理后当前代码验证矩阵（精确范围、通过/失败/未跑项）**：
  - **PASS (`2/2`)**：`python3 scripts/run_tests.py --test installed_staleness_check --test installed_entitlements_check --allow-installed --strict` -> `rc=0`（`PASS=2, FAIL=0, SKIP=0, NOT_RUN=66`）。注：`--promote` 会把 `build/Mac.apk.app` 移入 `/Applications/Mac.apk.app`，故同日 `worktree_*` 检查呈 `SKIP=3`（`missing local app bundle`）。
  - **PASS (`9/9`)**：`python3 scripts/run_tests.py --test CrossProcessTimestampContractTest --test AutomationServerTest --test SurfaceTextureNativePixbufTest --test SurfaceParcelTransportTest --test TextureViewDirectVideoTest --test Egl10KeyedSurfaceViewTest --test BrokerServiceStartupTest --test AudioTrackClockMonotonicityTest --test MediaCodecEncoderTest --strict` -> `rc=0`（`PASS=9, FLAKY_PASS=0, FAIL=0, SKIP=0, NOT_RUN=59`；其余 59 项未在本命令运行）。
  - **FAIL (`1/1`)**：`python3 scripts/run_tests.py --test RemoteServiceProcessTest --strict` -> `rc=1`（`2508.2ms`），且单独执行 `python3 debug/run_remote_service_broker_test.py --runtime-build-dir runtime/build` -> `rc=1`（`clientPid=22621 workerPid=22621 clientState=42 workerState=42 callerPid=22621`，`broker.log` 未见 `TX_BIND` 落回同进程）。历史上一轮独立执行曾通过，当前基线复现为稳定或高概率失败，**不可视为通过**，下一轮接手需优先排查。
  - **NOT_RUN**：清理临时诊断打印与重构 `MediaCodec.OutputTarget` 后的完整 20s Sidecar B 站验收（0 破音/源帧率/首帧/退出残留进程）、全量 `bare_jvm`+`jvm_launch_sensitive`+`native_unit`（全组上次于 Wave-3 `b1e41ac` 运行，之后仅跑上述 9 项子集）、[GFX-001](GFX-001.md) 全屏旋转往返、[DANMAKU-001](DANMAKU-001.md) 弹幕 A/B，以及 `/Users/xrdemac/.gemini/antigravity/brain/2dee5841-dccb-4c6e-b869-87a7de9d61f4/scratch/` 下的外部离线/Sidecar 暂存日志已保留在会话 scratch 目录（未入仓库）。
- **暂停前 Sidecar 诊断结论（来自 `261118.2144-b6cbdda+dirty` 清理日志前的 8.115s trace 实测，供后续接手定位）**：
  - 8.115s Sidecar 实测（`displayID=4`, `wid=12825`, 开启 `MACAPK_VIDEO_PRESENT_TRACE`/`MACAPK_AUDIO_CLOCK_TRACE`/`MACAPK_MEDIA_PROBE`）：`onSidecar=true`、`frontmost` 未抢占、`placeholder_shown_count=0`、`mvk_info_count=0`、`vulkan_line_count=0`、`vtprobe` 命中缓存（`loaded 7 cached codec probes`）、`:ijkservice` 进 Cocoa runloop 缩至 `+7694 ms`（较 Wave-3 `11,289 ms` 降 ~3.6s）、`lingering_after_quit=[]`；音频 2×5s 窗口 `464`/`468` 次渲染，**`total_underruns=0`**，`total_nonsilent_renders=920`；视频 `dequeuedOut=238`、`releaseTrue=158`、`releaseFalse=81 (33.89%)`、`ipcSent=158`、`ipcRecv=158`、`ipcSeqOutOfOrder=0`、`ipcDroppedInvalid=0`、`ringAccepted=158`、`ringEvicted=0`、`updateTexLatched=158`、`uniqueTimestampLatched=158`、`latchedFps=19.47`、`eglDirectSwaps=137`、`eglPbufferSwaps=0`、`latchIntervalMs` `p50=2.52ms` / `p95=116.55ms` / `p99=133.78ms` / `max=138.98ms` / `gt80ms=75`。
  - **107 ms 视频突发节律定位进展**：已排除 `MediaCodec.mAsyncLock` 锁竞争（移出 `vidDecode` 并延后锁外释放/呈现后仍为 ~107 ms 突发）；`AudioTrack.write(WRITE_NON_BLOCKING)` 每 ~20.5 ms 平滑写入且 `total_underruns=0`；`AudioTrack.getTimestamp()` `staleMs = System.nanoTime() - ts.nanoTime` 呈 `-12.5..-22.0 ms`（因 `audio_backend.mm` 以 `AudioConvertHostTimeToNanos(mHostTime) + q_dur_ns` 计算未来呈现时戳），且即使 `getTimestamp()` 间隔 1000 ms 时视频仍保持 ~107 ms 突发。线程采样显示 `:ijkservice` 内 `ijk_ff_vout`（`video_refresh_thread`）约 99% 样本停在 `libsystem_c.dylib` `nanosleep`，每 ~95–110 ms 醒来连放 2–5 帧（伴随 33.9%–57.9% `releaseFalse` 丢帧及 `ijk_ff_videodec` 乱序 `releaseOutputBuffer(false)`），同时 VTB `live=18..23`、`enqueue_thread` 长期阻塞在 `dequeueInputBuffer`（`nativeDepth >= 16`）。
- **下一会话接手建议**：
  1. 优先排查并修复 `RemoteServiceProcessTest`（核查 `debug/run_remote_service_broker_test.py` 启动就绪等待或 `SystemServer`/`SystemClient` 远程绑定路径）。
  2. 继续定位 `ijk_ff_vout` 为何在 `nanosleep` 集中睡眠 ~107 ms（检查 `runtime/translator/src/bionic_shim.cpp` 中 `nanosleep`/`clock_gettime`/guest `av_usleep` 传参或 `ff_ffplay` `audclk`/`frame_timer` 行为）。
  3. 跑完当前清理版代码的 20s Sidecar 完整验收（零破音、源帧率、首帧速度、退出零残留进程），再推进 `GFX-001` 与 `DANMAKU-001`。
