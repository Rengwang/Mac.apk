# 视频功耗调查：第一轮记录（2026-11-03）

目标尚未完成，尚未修改生产渲染、解码或帧率。

已建立独立数据副本，重定向副本外文件链接；原始数据和 APK 未提交。
首轮45秒仅用于启动探测。第二轮对齐 stub 的日志/兼容默认值，预热120秒、采样60秒。
使用 debug/perf/profile_process.py，从进程累计CPU时间的差值计算每秒占用，不是 ps 的生命周期均值。

第二轮诊断：CPU均值 26.86%，中位数 14.86%，
范围 0.99–99.94%；最后RSS 393.0 MiB。
CPU 100% 表示一个逻辑核心。这些数值不等于瓦数，也不包含子进程、WindowServer或GPU。

限制：画面内容/可见性未得到视觉确认；采集中执行过jcmd；没有固定视频、原生客户端或MuMu对照。
因此不能称为空闲/播放基线，更不能声称改善。最后3秒原生栈以等待为主，不能代表整段采样。

显示器：本机Sidecar扩展屏已连接，AppKit坐标(-1087,310,1087,802)、scale2；本机主屏(0,0,1710,1112)。
这些仅是当时观测，不可用于硬编码。窗口工具无法按二进制路径/进程名选择直接启动的runtime，
尚未确认测试窗位于副屏；之后测量先解决可识别的测试app包装/窗口控制，并复查画面与位置。

下一步：在iPad副屏确认可视场景，采集稳定无操作/固定视频无弹幕/有弹幕的数据和线程热点，
与同机原生客户端和MuMu作可比对照。仅在定位热点后实施通用优化及对应回归。

原始证据：work/upgrade/VIDEO-PWR-001/production-idle-1/，包含result.json、conditions.json、
app.log、java-threads.txt、stack.sample.txt；均被Git忽略。
两个本轮采集进程均已退出，不留后台压测。


## 第二轮：随航窗口与真实视频路径

2026-11-03：测试程序通过独立应用外壳启动，并用 `MACAPK_HOST_DISPLAY` 按当时显示名称选择 Sidecar。
WindowServer 报告测试窗口边界为 x=-824、y=30、宽499、高772（屏幕配置需每次重新查询）；
截图确认B站首页及视频详情页显示，测试进程已停止。外壳使用独立数据副本和本工作区 runtime。

进入视频详情后窗口截图的视频区域为黑色；窗口截图可能无法捕捉独立合成层，故不能单凭截图认定用户看到黑屏。
日志实际记录 HEVC VideoToolbox decoder `hw=YES`，同时 IJK EGL 初始化失败，回退到 YV12 native-window 路径：
请求 852×480，而返回缓冲 998×1480，播放器报告尺寸不符。
源码 `ANativeWindow_lock` 使用窗口尺寸而非请求的缓冲尺寸，且 `unlockAndPost` 当前注释说明未合成像素。
这解释了日志中的尺寸契约缺口，但还不能证明其导致了功耗过高或最终显示问题。

点击视频后20秒的单进程CPU抽样均值约18.3%（19个区间）；未确认视频帧实际呈现或播放状态，
不能作为播放功耗基线，也未包括GPU、窗口服务或子进程。下一步应以最小通用测试验证缓冲区几何和发布路径，
同时调查 EGL 初始化失败与画面呈现；确认实际播放后再做性能对比。


## 第三轮：补齐测试外壳 Vulkan 配置后复验

第二轮的独立测试外壳遗漏 `MoltenVK_icd.json`，日志明确提示 Vulkan 初始化将失败，因此第二轮 EGL 失败和 YV12 备用路径不能归因于正常安装环境。源码中缓冲几何的缺口仍存在，但它与本次功耗的关系未证明。

补齐配置后，2026-11-03 重新在 Sidecar 测试：窗口边界 x=-824、y=30、499×772；B站视频连续截图中行人位置发生变化，证实画面推进。日志显示 HEVC VideoToolbox 硬件解码启用、EGL 初始化成功。视频内容不同于第二轮，不能构成严格前后性能对比。

连续30个时间点计算出的29个区间内，runtime 单进程CPU均值72.42%、中位数67.46%、范围57.50%–101.47%（100%为一个逻辑核）；截图可证实有画面推进，但未量化帧时间、音画同步或弹幕。该值不包括GPU、WindowServer、其他进程，也不是瓦数。诊断启用了 EGL trace；无 MuMu 或 iPad 客户端对照、无重复轮次，因此不是最终结论。

原始数据：`work/upgrade/VIDEO-PWR-001/sidecar-video-valid.json`、`sidecar-video-valid.sample.txt`、`sidecar-probe-5.log`。下一步在同一视频与窗口条件下重复测量，并采集 Java/native 活跃线程、GPU/能耗指标，比较各客户端后再针对证据明确的热点修改。


## 第四轮：定位 GPU 读回热点

随航屏中播放 B站视频，30 次 Java 线程快照中，`ijk_ext_render` 线程 12 次处于 `GLES20.glReadPixels`，调用栈明确来自 `MacApkGlWindow.queueFrame` → `MacApkEGL10.eglSwapBuffers`；相隔约12.9秒的线程累计CPU差约50.4%单核。原生采样也捕获 ANGLE Metal `readPixels` → Metal texture `getBytes`。

添加默认关闭的 `MACAPK_GL_WINDOW_READBACK_TRACE=1`，本地 Java-only 编译通过。另一段实际播放测试，统计显示读回尺寸 998×1480 RGBA（5,908,160字节/帧）；前600帧总计 3,544,896,000 字节（约3.54 GB），`glReadPixels` 总耗时 9.482秒（平均15.80毫秒/帧），整个 queueFrame 累计 9.969秒。计时在调用线程上，包含驱动等待，不等于纯CPU时间或功率；额外统计本身的开销尚未测量。

该读回为当前基于 Canvas 的 SurfaceTexture 消费路径提供像素，直接删除会破坏画面和正确性。下一步需设计真正的 GPU 共享/合成路径或经验证的异步方式，保留 SurfaceTexture 的帧、透明度、变换、生命周期和跨线程语义；同时测无弹幕、弹幕、空闲及其他应用，不假设任何单个开关可直接修复。

原始证据 `work/upgrade/VIDEO-PWR-001/video-java-stacks.txt`、`sidecar-video-valid.sample.txt`、`sidecar-readback-trace.log`；其内容被 Git 忽略。统计开关默认关闭；尚无功耗降低或流畅度改善的前后证据。


## 第五轮：ANGLE 与 IOSurface 可行性原型

新增 `debug/perf/egl_iosurface_probe.mm`：使用当前仓库自带 ANGLE 与 64×64 BGRA IOSurface，在 EGL client-buffer pbuffer 上绘制红色，完成 glFinish、eglSwapBuffers、解绑后锁定 IOSurface 校验像素。该探针没有调用 glReadPixels，也不启动应用或创建图形窗口。CPU 锁定仅用于验证结果，不能当成 GPU 合成性能测量。

Apple M5、macOS 27.2 的当前工作区构建：ANGLE Metal 返回预期 BGRA=(0,0,255,255)，PASS；ANGLE Vulkan/MoltenVK 后端在相同步骤读到 (0,0,0,0)，FAIL。验证过配置支持该扩展且 EGL surface/context 创建成功；结果仅说明此探针和组合失败，不证明 Vulkan 无法支持 IOSurface 或设备不支持该能力。B站运行日志曾自动选择 Vulkan 后端，不能依赖 Metal 的结果直接替换生产路径。

参考 ANGLE 扩展定义：https://chromium.googlesource.com/angle/angle/+/main/extensions/EGL_ANGLE_iosurface_client_buffer.txt 。该扩展允许 IOSurface 创建 EGL surface；生产方案仍需验证多帧同步、Metal 消费、TextureView 层级、裁剪/透明和 SurfaceTexture 帧语义。下一步先定位 Vulkan 像素未同步原因，同时构建可复现的跨后端像素及生命周期测试；不可因为原型通过就宣称节能或绕过现有画面验证。

复现：`clang++ -std=c++17 -Ithirdparty/angle-prebuilt/include debug/perf/egl_iosurface_probe.mm -Lruntime/build/bin -lEGL -lGLESv2 -framework Foundation -framework IOSurface -Wl,-rpath,$PWD/runtime/build/bin -o work/upgrade/VIDEO-PWR-001/egl_iosurface_probe`；运行该可执行文件默认测 Metal；设置 `VK_ICD_FILENAMES` 指向 `runtime/build/bin/MoltenVK_icd.json` 并传 `vulkan` 测 Vulkan。两种后端必须分别报告结果。


## 第六轮：确定 EGL10 后端与共享纹理结果

实测视频调用栈是 `MacApkEGL10.eglSwapBuffers`，该实现通过独立 native-display token `0x4A453130` 明确选择 ANGLE Metal；应用其他 native EGL 路径的 Vulkan 自动选择不代表这个 EGL10 pbuffer 也使用 Vulkan。上一节因此过于保守，不能把应用级 Vulkan 选择直接等同于这个热点的后端。

扩展原型：同一 EGL pbuffer 中先用 `glReadPixels` 确认绘制红色，再为相同 IOSurface 创建 Metal 纹理并检查像素。Metal 默认和 EGL10 相同的私有 display 均显示红色；Vulkan 中 OpenGL 读回红色，但 IOSurface 的 CPU 与 Metal 纹理视图均为零。禁止 GL 读回的 `no-readback` 模式再次验证：私有 Metal display 仍显示正确红色，Vulkan 仍为零。Vulkan 失败原因尚未确认，不能做全局假设。

下一步面向 EGL10 Metal 设计可关闭的共享画面原型：每个生产者拥有可追踪生命周期的 IOSurface、EGL pbuffer 与 bounded 帧队列；由 Metal 合成到正确 TextureView 位置，并覆盖 alpha、坐标变换、裁剪、透明遮挡、resize、销毁和帧更新通知。仍需保证多线程生产/消费同步；探针中的 `glFinish` 与同步像素校验只证明可见性，不能代表最终低功耗或无延迟。对于 EGL14、GL 消费者、复杂遮挡和未满足条件的场景继续旧路径，直到独立验证支持。比较需使用同一视频、同一副屏和同等画质，并测读回字节、帧时间、CPU/GPU和能耗。

复现编译同上，但增加 `-framework Metal`。运行 `egl_iosurface_probe metal-private no-readback` 应 PASS；`VK_ICD_FILENAMES=.../MoltenVK_icd.json MVK_CONFIG_LOG_LEVEL=0 egl_iosurface_probe vulkan no-readback` 当前返回失败。默认模式还用 `glReadPixels` 验证GL图像；该读回仅用于诊断。


## 第七轮：多帧共享与 Metal GPU 消费原型

新增 `debug/perf/egl_iosurface_multiframe.mm`：EGL10相同私有 Metal display 中建立3个 IOSurface pbuffer，60帧轮换 RGBA/半透明颜色；每帧生产者 glFinish 后提交 Metal GPU blit，slot 被下一次写入前等待对应消费者 command buffer 完成，再校验左上、中央、右下像素。单进程验证 `64×64` 和实测窗口 `998×1480` 均 PASS，全程未调用 glReadPixels。Vulkan 同一个原型仍在首帧返回全零并 FAIL。

一次 998×1480 纯色、60帧测试中 glFinish 平均0.502ms、包含提交与回收的整个测试循环平均0.620ms/帧。此数据来自纯色清屏、固定尺寸和 CPU 等待的头less探针，并不模拟视频解码、复杂 shader、实际 TextureView 合成、音频或显示，也不是与先前15.8ms的一一对比。图像校验使用GPU blit到共享缓冲后读取三个像素，生产方案不会因此自动具备零同步或零拷贝。确认原型路径有继续集成价值，功耗目标未完成。

尚需实现应用内每SurfaceTexture独立资源、在非 GL Canvas 的窗口层正确排序/裁剪/透明度和同步，以及 detach/resize、非可见情况下的消费者所有权。不能直接把一个全局layer覆盖到界面上；复杂场景应回退旧路径。待实际视频对照时必须验证弹幕、文本、滚动、前后台、旋转和跨应用回归，且测能耗而非仅CPU。

复现：`clang++ -std=c++17 -Ithirdparty/angle-prebuilt/include debug/perf/egl_iosurface_multiframe.mm -Lruntime/build/bin -lEGL -lGLESv2 -framework Foundation -framework IOSurface -framework Metal -Wl,-rpath,$PWD/runtime/build/bin -o work/upgrade/VIDEO-PWR-001/egl_iosurface_multiframe`，再运行 `work/upgrade/VIDEO-PWR-001/egl_iosurface_multiframe metal-private full`。Vulkan 测试须设置 ICD 路径并传入 `vulkan`，当前预期实验失败；禁止将实验失败隐藏为通过。


## 第八轮：共享帧资源管理器的运行时基础

新增 `runtime/src/shared_gl_frames.{h,mm}`，在 ANGLE 可用时编译进 runtime；目前没有任何生产调用者，不会改变播放。每个实例拥有3个 IOSurface 与 EGL pbuffer，提供生产申请、发布/取消、按序消费、释放和销毁；使用互斥锁保护状态并用进程内全局递增令牌避免跨实例/复用时误释放。队列满时明确返回失败而非忙等或隐式丢帧；有未完成帧时禁止销毁。调用方必须负责线程寿命、EGL解除绑定、生产完成同步和 GPU 消费完成后释放。

`debug/perf/shared_gl_frames_test.mm` 在真实私有 EGL10 Metal display 和 998×1480 IOSurface 上验证三帧、GPU blit颜色、FIFO、队列满、忙时禁止销毁、重复释放、复用旧令牌、两个实例之间令牌隔离以及最终销毁；使用 `-Wall -Wextra -Werror` 编译并 PASS。完整 runtime 的 `MACAPK_NO_BUNDLE_SYNC=1 runtime/scripts/build.sh` 构建 PASS，日志 `work/upgrade/VIDEO-PWR-001/shared-frame-build-final.log`；没有安装或替换共享 runtime。

该组件尚未处理 Java/JNI 绑定、TextureView 合成层、resize迁移及 Vulkan 路由，也没有接入视频，因此尚无应用性能提升；原有播放路径保持默认。真实多帧/重叠 UI/截图、音画同步、能源测量与跨应用回归仍需进行。`NATIVE_ID` 是 translator/bionic 缓存标识，不是完整 runtime 可执行文件的身份，诊断时应同时记录二进制 SHA-256。


## 第九轮：Java/JNI 帧池接口

新增 `SharedGlFrameBridge` 与 JNI 实现；运行时只在 ANGLE 可用时注册这7个方法。池 ID 为受锁保护的进程内标识，过期/不存在的 ID 不作为指针解引用；数组为空或长度不足时不改变帧池。借用 IOSurface 的有效期止于匹配的 release，生产端和消费端分别负责 GL 完成、GPU 消费完成；当前接口仍没有视频播放调用者。

实测完整 `MACAPK_NO_BUNDLE_SYNC=1 runtime/scripts/build.sh` 通过，二进制包含7个 JNI 导出。`SharedGlFramesJniTest.java` 使用 `-Xcheck:jni`、真实 JVM、同一份 JNI 实现、ANGLE Metal、EGL context 与 IOSurface 检查 3 帧像素、FIFO、容量、忙时销毁、空/短数组、无效及重复释放、跨池/过期令牌和关闭后句柄，全部 PASS。测试动态库在隔离工作目录编译，非正式安装。

尝试将新二进制加入临时 GUI 测试外壳时，外壳签名检查因内部包含未签名的配置文件而失败；未启动新构建的实际 APK，因此启动/注册端到端仍 NOT_RUN。此失败仅涉及自建临时测试外壳，不证明 Mac.apk 正式应用无法启动。不要绕过代码签名保护或改写已安装应用；先修复临时外壳的打包与签名结构，再做真实播放器验收。

生产渲染、功耗与流畅度未改变。下一步仍需连接 EGL10 当前 pbuffer、正确的 TextureView 层级合成、帧完成与生命周期处理，并在真实视频上做前后对照。


## 第十轮：修复临时外壳签名并验证真实应用启动

将测试外壳的配置、资源及依赖移入 bundle，内部链接指向 bundle 内部；使用项目现有测试 entitlements 对隔离外壳重新 ad-hoc 签名，并通过 `codesign --verify --deep --strict`。未更改正式安装或系统安全设置。

同机检测到 Sidecar 扩展屏并指定其当前名称；测试窗口边界为 x=-824、y=30、499×772，在 Sidecar 范围内。使用新运行时和独立数据副本启动 B 站，截图确认首页显示；`RegisterAllNativeMethods` 中的 Bind 在类查找或注册失败时会中止，而应用已到首页且日志未见 SharedGlFrameBridge 注册失败，说明新增注册没有阻止启动。该检查不等于 JNI 方法从实际播放器调用的端到端功能验证，接口仍无生产调用者。

原始启动日志 `work/upgrade/VIDEO-PWR-001/shared-jni-startup-2.log`；测试进程已停止。功耗/画面表现尚未改变，实际视频路径和帧同步、层级合成仍待接入。


## 第十一轮：共享帧兼容消费与像素契约

`debug/perf/egl_iosurface_orientation.mm` 用上下、左右不同颜色及半透明图案逐像素对照：私有 EGL10 Metal display 共有4096/4096像素与 GL readback 的行方向一致，BGRA/RGBA 通道需交换；不是上下翻转的4096/4096。Vulkan 对照未匹配，不扩大到 Vulkan。

共享帧池增加 `copyArgb` 兼容消费方法，仅允许对已 take 取得且尚未 release 的令牌使用；按真实 IOSurface 行跨度读取，将 EGL 内存的底向上 BGRA 转为现有 Canvas 所需顶向下、非预乘 ARGB。它为旧 Canvas 消费者提供回退，仍会CPU复制整个帧，绝不等于最终GPU零拷贝路径；目前没有生产调用者。

独立 JVM `-Xcheck:jni` + ANGLE Metal 真正绘制后验证上/下、红/绿/蓝、半透明、透明像素、非法状态、短/空数组、旧令牌和跨池隔离均 PASS；完整 runtime 构建 PASS（日志 `work/upgrade/VIDEO-PWR-001/shared-copy-build.log`）。不涉及安装或正式应用数据。原版 SurfaceTexture EGL 的生产路径尚未改写，实际性能改善未验证。

之前外壳签名问题已修复，更新的 `copyArgb` 构建尚未在B站实跑。本轮后下一步是搭建以共享EGL surface为生产者、现有Canvas为消费者的可关闭集成测试，验证像素/生命周期并测量真实播放；随后继续直接GPU层合成，不能把CPU复制中间路径当作终点。


## 第十二轮：EGL10 Metal 共享帧实跑与 CPU 热点下降

`MacApkGlWindow` 增加默认关闭的 `MACAPK_GL_WINDOW_SHARED_EXPERIMENT=1` 路径。
只在 EGL10 Metal 分支中把同步 `glReadPixels` 的 EGL pbuffer 替换为可跟踪的
IOSurface EGL surface，再用兼容转换写入现有 Canvas 消费队列；任一步失败仍
回退原 GL readback。resize 会创建新一代共享池，并在旧 surface 解绑后取消旧
DRAWING 帧、销毁旧池；Vulkan 和生产默认路径不变。

第一版 JNI 使用 `GetIntArrayElements`，真机 CPU 反而升至均值130.97%、中位数
125.12%（OFF 均值71.17%、中位数65.38%），已作为负结果保留。根因是数组 API
额外复制整帧后还执行回写。改用 `GetPrimitiveArrayCritical` 并完整写回后，
最终真实视频在相同 832x1480 缓冲、17:20 视频和 Sidecar 窗口中测得：

- OFF 复测：均值70.47%、中位数64.61%；
- ON 最终：均值37.67%、中位数32.63%；
- ON RSS 约701.6 MiB；
- 3000帧共享路径复制14.776GB逻辑像素，累计5.941秒，约1.98ms/帧；
- 未见 `shared copy failed` 或 `readback frames` 回退。

上述百分比只表示单进程、单位为一个逻辑核的 CPU 占用，不包含 GPU、WindowServer、
Child process或能耗；不能据此声称瓦数或续航改善。A/JNI-array中间版本真实视频
日志仍保留为反例。

最终 JNI 测试通过，`-Xcheck:jni` 覆盖池边界、像素、FIFO、生命周期和
DRAWING-only同步复制。完整 runtime 构建通过
`work/upgrade/VIDEO-PWR-001/shared-critical-writeback-build.log`。新 headless
对照以完整数组逐像素相等为门槛：64x64 为旧路径0.274ms、共享路径0.193ms；
998x1480 为旧路径12.941ms、共享路径4.930ms。数值是纯色合成探针，不等于视频
功耗。

最终原生采样没有 `glReadPixels`，可见 `GL_Finish`、`copyArgb`、Metal、ANGLE 和
VideoToolbox 活动。窗口缩小时显示配置切到720x1280，Canvas也切到该尺寸，但播放
SurfaceTexture仍为832x1480，因此共享池未 resize；视觉未裁切，预留继续缩小视频
分配的研究项。自动点击控制栏能显示，暂停、弹幕及普通页签没有出现预期状态；
事件已到Android，暂不声称输入回归通过。

原始日志/采样：`work/upgrade/VIDEO-PWR-001/shared-critical-writeback-on-1.log`、
`shared-critical-off-2.log`、`shared-video-final-on.sample.txt`、
`shared_gl_readback_compare-final*.log`；均为忽略文件。改动只留在本地测试分支，
未安装、合并或替换正式应用与数据。


## 第十三轮：正式启动条件下的空闲基线与自适应心跳 A/B

本轮先修正直接 CLI 测试与正式启动之间的环境差异：设置
`MACAPK_LOG_LEVEL=4`、`MACAPK_QUIET_CLASSLOAD=1`，并启用启动 stub 的四项通用默认值
`MACAPK_EXPERIMENTAL_NATIVE_SURFACE_LAYERS=1`、
`MACAPK_EXPERIMENTAL_NATIVE_SURFACETEXTURE=1`、
`MACAPK_EXPERIMENTAL_PRESERVE_SURFACE_ALPHA=1`、
`MACAPK_EXPERIMENTAL_CHOREO_PIPE_WAKE=1`。测试仍使用独立 APFS 数据副本、当前已构建
runtime 和同尺寸 Sidecar 窗口；每轮预热 120 秒、采样 120 秒。

默认心跳的三轮平均 CPU 为 14.718%、14.811%、15.378%，合并平均 14.969%；
每轮中位数为 11.892%、11.811%、11.896%。中位数合并平均 11.866%，说明空闲
基线约为 12%，另有约 30% 的周期性尖峰。120 个区间内没有超过 50% 的单点。
线程快照中 Android 主线程累计约 5.55%，C2/C1 JIT 编译线程约 3.36%/1.19%；
原生采样同时显示 MetalKit 按 60 Hz 驱动，而 Java 遍历已经跳过约 290/300 帧。

启用已有但未默认的 `MACAPK_FRAMESPEED_HEARTBEAT=1` 后，三轮平均 CPU 为
11.839%、11.524%、11.642%，合并平均 11.668%；中位数为 7.891%、8.947%、6.939%，
合并平均 7.926%。平均降低约 22%，中位数降低约 33%。静止推荐页的 M6 记录表明
Java 遍历从每 300 个显示回调约 10 次降到约 2–3 次；触摸、布局或真实画面变化会
把周期恢复为 30-vsync。所有 720 个对照采样点均未超过 50%。

开启实验开关后在同一窗口实际点击：直播页签切换、返回推荐、打开视频卡片、开始
播放、暂停和关闭广告浮层均出现预期状态；视频时间从 00:15 推进到 00:52，暂停
后按钮变为播放。弹幕按钮没有获得可重复的视觉状态断言，不能记作通过。

限制：数据是 B 站一个静止页面、一个窗口和一台机器的单进程 CPU；不包含 GPU、
WindowServer、子进程或瓦数，尚未覆盖其他应用、前后台、滚动、长时动画和能耗。
该开关继续默认关闭，只有更多应用类型和 `[heartbeat-diff]` 证据通过后才能考虑
提升为启动默认值。原始结果位于被忽略的
`work/upgrade/VIDEO-PWR-001/quiet-home-r{1-correct,2,3}/`、
`heartbeat-home-r{1,2,3}/`、`java-threads-spike.txt` 和 `native-spike.sample.txt`。


## 第十四轮：跨应用心跳与未触发 invalidate 的更新

B 站之外的酷安 16.6.6 兼容版可进入未同意隐私协议时的只读首页。同一浏览页
OFF/ON 各 20 个应用样本中，单进程 CPU 均值 60.385%→59.110%，中位数
56.086%→51.820%；首页持续播放丰富动画，所以心跳没有扩展，仍保持 30-vsync。
两轮都出现未失效 invalidate 的差异，意味着 `[heartbeat-diff]` 安全网确实捕获了
页面更新；这类连续动画场景不能把自适应心跳当作主要节能手段。

倒数日可启动外壳，但缺少 `libcldp.dylib`，并因
`lateinit property localRealmDataSource has not been initialized` 初始化失败。
它只在空白/失败页提供了调度器探针，不算功能兼容样本，也不能扩大产品结论。
原始结果位于 `canvas-daysmatter-{off,on}-r1/` 与
`canvas-coolapk-browse-{off,on}-r1/`；隐私协议页面和错误点击运行不混入有效对照。


## 第十五轮：GPU、WindowServer 与时间对齐

`debug/perf/profile_process.py --system-metrics` 现在同时读取目标进程、
`WindowServer` 和公开 IORegistry 的 Apple GPU 利用率。每个一秒样本记录 UTC
起止时间，结果另记录进程启动时间，供 5 秒粒度的 `powermetrics` 数据对齐。
GPU 利用率不是能量指标，WindowServer 也可能接收应用减少的工作，因此不能只看
应用 CPU 判定省电。

一次 30 秒探索性对照中，B 站视频 OFF 为应用 CPU 55.26%、WindowServer
17.75%、GPU 设备利用率 38.03%；ON 为 17.55%、23.50%、36.80%。这说明 ON
把一部分 CPU 转移给 WindowServer，单次 CPU 变化不能作为总功耗结论。

随后用一次管理员确认启动 120 个、5 秒间隔的 `powermetrics` 批次，采集
`tasks,cpu_power,gpu_power`。powermetrics 输出是 NUL 分隔的多份 XML plist，
解析时必须保留全部文档而不能只读第一份。四个早期时段中，两个首页运行发生明显
阶段漂移，不能使用；两个视频运行分别为 30 秒和 35 秒的不同视频，且 ON 已到结束
页。它们只证明工具链联调成功，不能支持能耗 A/B。原始文件和摘要位于
`powermetrics-session-20261103-1324.plist` 与
`powermetrics-summary-20261103-1324.json`。


## 第十六轮：同一长视频的一对 OFF/ON 探索

通过隔离测试外壳直接打开 `https://b23.tv/BV19b4A6PECX`，日志显示
`Mac.apk.URL` 收到链接并路由给 `tv.danmaku.bili.ui.intent.IntentHandlerActivity`；
窗口截图确认进入 11 分钟的原神角色预告，视频画面在连续截图中推进。两组均使用
`Sidecar Display (AirPlay)`、360×640 逻辑窗口、相同 APK/数据副本和正式启动兼容
环境，预热 90 秒、采样 30 秒，并关闭自适应心跳。

| 30 秒应用采样 | OFF | ON | ON 相对变化 |
|---|---:|---:|---:|
| 应用 CPU 均值 / 中位数 | 54.59% / 48.70% | 31.83% / 27.06% | -41.7% / -44.4% |
| WindowServer CPU 均值 | 20.64% | 17.01% | -17.6% |
| GPU 设备利用率均值 | 36.43% | 36.77% | +0.9% |
| RSS | 425.7 MiB | 579.8 MiB | +154.1 MiB |

同一时段内 6 个 powermetrics 样本的均值为：应用任务能耗估计
2787.81→1558.77（-44.1%），WindowServer 任务能耗估计 681.41→595.69
（-12.6%），CPU 功率 7651.18→5071.87（-33.7%），系统组合功率
8095.34→5616.13（-30.6%）；GPU 能量估计同时上升 22.5%。这些是同一台机器上
用于比较优化的本地估计，不是跨设备瓦数，也不代表电池续航承诺。

剩余限制：只完成一对，未做 ABBA 轮换；两组视频内容相同但播放位置没有强制归零，
弹幕状态未通过独立断言，GPU 和内存成本仍需优化。因此共享 EGL 继续显式开关，
不提升为默认。原始证据位于 `fixed-bv19b4a6pecx-main-{off,on}-r1/` 与
`powermetrics-fixed-video-pair-r1{.plist,-summary.json}`。


## 第十七轮：原生视频层架构建议的证据门槛

“视频使用独立 Metal/IOSurface 层、弹幕使用上层透明层”与当前减少 Java Canvas
复制热点的方向一致，已列为下一阶段架构候选。当前证据尚不能证明
`CALayer.contents = IOSurface` 会自动实现端到端零拷贝，更不能推导 CPU 低于 5%。
窗口裁剪、变换、透明度、遮挡、色彩、生命周期、前后台恢复和实际合成成本都必须
逐项验证。

当前共享帧池已有三槽和 FIFO 回压；WindowServer 反弹的假设需要提交速率、复用冲突、
帧时间与脏区数据支持，不能直接归因于 VSync。弹幕是否已经独立、AudioTrack 是否
产生异常唤醒，也必须先取得 View 层级、Wakeups/s 和音频缓冲证据。这些项目不混入
当前默认路径，也不能用推测替代测量。


## 第十八轮：固定短链 ABBA 复测与原生参照

为保证每轮都从同一视频开始，测试器先启动隔离外壳，等 10 秒后再把
`https://b23.tv/BV19b4A6PECX?t=0` 交给应用；不执行点击、自动按键或 dump-tree。
四轮按 OFF/ON/ON/OFF 交替，每轮预热 60 秒、采样 30 秒，并在第 45、55 秒各截
一次 Sidecar 窗口。四张截图都显示《原神》角色预告“米提亚：井井有条”，窗口
始终位于 Sidecar 的 `x=-755, y=80, 360x672`，短链直达路径成立。

| 轮次 | 模式 | 应用 CPU 均值 | WindowServer CPU 均值 | GPU 设备利用率 | RSS 均值 |
|---|---|---:|---:|---:|---:|
| 1 | OFF | 53.29% | 14.98% | 39.63% | 779.2 MiB |
| 1 | ON | 37.38% | 21.52% | 61.07% | 819.0 MiB |
| 2 | ON | 126.52% | 21.31% | 52.03% | 865.7 MiB |
| 2 | OFF | 146.42% | 13.23% | 37.53% | 781.2 MiB |

相邻配对中，ON 的应用 CPU 分别低 29.9% 和 13.6%，但 WindowServer 分别高 43.6%
和 61.1%，GPU 设备利用率分别高 54.1% 和 38.6%，RSS 高 5.1% 和 10.8%。后两轮
的整体负载比前两轮明显更高，因此绝对 CPU 受阶段或系统噪声影响，不能把第一对
的稳定状态直接外推。仍未采集这一组 ABBA 对应的管理员能耗数据；CPU 下降不等于
整机省电，GPU/WindowServer 增加可能吃掉或反转收益。共享 EGL 继续默认关闭。

同一台机器、同为 9.16.1 版本的 iPad 版 `bili-universal.app` 也接受该 b23 短链，
直接进入同一视频。将测试窗口移到 Sidecar 后，在 970×770 窗口中预热并连续采样
30 秒：CPU 均值 21.23%、中位数 20.98%，WindowServer 17.88%、GPU 设备利用率
71.27%、RSS 426.0 MiB。它的 CPU 明显更低，而 GPU 利用率反而高于当前 Mac.apk
探针；因此“CPU 低”来自原生执行和直接合成路径，不能简单解释成少用 GPU。

`otool -L` 显示 iPad 版直接使用 AVFoundation、CoreMedia、CoreVideo、
VideoToolbox、Metal、MetalKit、QuartzCore 与 UIKit。Mac.apk 视频轨迹则同时出现
IJK/VideoToolbox 解码、EGL10/EGL14、ANGLE/MoltenVK、SurfaceTexture 队列、
Java Canvas 与逐帧 ARGB 转换/合成。两边的视频解码都可命中硬件，剩余差距主要
来自 Android/JVM/转译和兼容合成管线。原生样本的窗口尺寸、布局和清晰度控制方式
与 360×640 Mac.apk 探针不同，且尚未采集对齐能耗，所以它目前是架构参照，不是
最终的同条件验收。

原始证据位于 `fixed-bv19b4a6pecx-abba-*-r1/`、
`fixed-video-abba-r1-summary.json`、`native-bili-shortlink-r1/`；均为忽略文件。


## 第十九轮：Surface IOSurface 直交与能耗长测

保留稳定的共享 EGL 路径与 Java SurfaceTexture 兼容语义，只在最终 CALayer
交付处新增显式实验 `MACAPK_SURFACE_IOSURFACE_PRESENT=1`：同一批预乘 BGRA
像素写入按 SurfaceView key 管理的四槽 IOSurface 环，再直接设为
`CALayer.contents`，绕开每帧 `CGImage` 的创建、准备与颜色匹配。原生生产层
直挂实验仍关闭；失败或无空闲槽时继续走原 `CGImage` 路径。

r20 原 `CGImage` 路径与 r24 新路径均在同一短链、同一视频、360x672 Sidecar
窗口中进行 180 秒测试。稳定阶段证据如下：

| 区间 | r20 CPU + GPU 功率 | r24 CPU + GPU 功率 | r24 应用 CPU | r24 WindowServer CPU |
|---|---:|---:|---:|---:|
| 30–60 秒 | 约 4.1–5.0 W | 3.431 W | 328 ms/s | 147 ms/s |
| 60–90 秒 | 约 4.1–5.0 W | 3.399 W | 280 ms/s | 148 ms/s |
| 90–120 秒 | 约 4.1–5.0 W | 3.633 W | 292 ms/s | 177 ms/s |
| 120–150 秒 | 约 4.1–5.0 W | 2.664 W | 311 ms/s | 120 ms/s |
| 150–180 秒 | 约 4.1–5.0 W | 3.023 W | 301 ms/s | 135 ms/s |

r24 的应用 energy impact 为 513–687，较 r20 的 965–1045 下降约 40%–50%；
主线程 `prepare_image`、`copy_image`、`CGContextDrawImage` 和
`vImageConvert_AnyToAny` 热点消失，GPU 功率小幅上升。r24 RSS 中位数约
774 MiB、均值约 876 MiB；四槽环本身按 720x1280 计算只占约 14 MiB，不足以
解释全部增长，因此大部分内存差异仍需在暂停、返回和长时间运行中分开验证。

本轮给 SurfaceView layer 被移除的路径接入 IOSurface 环释放，避免多视频切换
累积约 14 MiB/视频的缓冲；保留轻量 key 占位，使已排队的收尾帧不会访问已释放
状态。清理后的 r25c 20 秒短测通过：应用 CPU 中位数 38.44%、WindowServer
11.84%，视频连续截图不同，未见 busy fallback。首次短测曾因临时测试外壳重签
时漏掉运行时权限而在 dyld 启动阶段退出；补齐 JIT、x18 和关闭库校验权限后通
过，不是播放或缓冲代码崩溃。

`display` 色彩模式现在读取运行时窗口实际所在屏幕，并监听窗口跨屏移动；不再
固定取主屏。r26 日志确认收到 Sidecar 的 3380 字节 ICC profile。该模式 20 秒
短测的应用 CPU 中位数为 33.89%、WindowServer 20.37%、GPU 49%，且观察到一次
busy fallback；因此功耗候选继续使用目前已验证的
`MACAPK_PRESENT_IOSURFACE_CS=none`，`display` 仅保留给同帧色彩验证。

用户在该设置下确认播放功耗明显降低。当前仍需人工确认动态画面长期流畅、无闪
烁且颜色可接受，并完成暂停/恢复、seek、弹幕开关、resize、surfaceDestroyed
和前后台恢复。该路径仍是显式实验开关，不安装、不合并、不替换正式应用。


## 第二十轮：视频返回图标颜色与共享帧并发回归

用户截图显示视频页左上角返回箭头为黑色，旁边主页图标为白色。反编译确认
Bilibili 9.16.1 的返回按钮构造普通 `AppCompatImageView`，加载
`ref_arrow_back_left_line_500` 后直接 `setImageDrawable`，没有另设黑色 tint；
该资源是 PUA 图标 `U+EA03`，其 `IconTextDrawable` 内部 `Paint` 初始为白色。

本轮用真实 APK 类、`assets/iconfont.ttf` 和当前 runtime 做无窗口颜色探针：
同一字形分别请求白色 `#ffffffff` 与黑色 `#ff000000`，两次都有 2808 个着色像素，
且每个非透明像素都保持请求的 RGB。结果证明当前 Canvas/PUA 绘制会遵守 Paint
颜色；返回箭头变黑不能归因于字形字体或 Canvas 强制黑色。与此同时撤回了
`ImageView.setImageDrawable` 上有意跳过 `setColorFilter(null)` 的临时偏差，恢复
AOSP 的颜色过滤状态传播；`ImageViewLevelTest` 已覆盖非空过滤器的替换重放和
显式清除。最终黑箭头来源仍需在解锁后的图形窗口里读取 `U+EA03` 实际绘制
颜色和调用栈，不能再用猜测修改默认行为。

共享帧池在最新 Java-only 构建后重新通过真实 EGL/IOSurface FIFO、容量边界、
旧令牌、GPU 颜色、销毁，以及 64x64 合成读回逐像素对照；关键 native window
生命周期测试也通过并发 owner、JNI 最终释放、失败回退和最后 owner 清理。
测试版本已同步并重新 ad-hoc 签名，strict `codesign` 校验通过；尚未安装或替换
正式共享 runtime。


## 第二十一轮：共享 GL 与 Surface IOSurface 的同条件能耗复核

针对“播放功耗又变高”的现场，先在同一测试外壳、同一 APK、同一固定 b23 短链、
同一 Sidecar 副屏和同一 `MACAPK_PRESENT_IOSURFACE_CS=none` 下做短时 A/B。
两组都开启自适应心跳和相同 Surface/Choreographer 默认值，只切换
`MACAPK_GL_WINDOW_SHARED_EXPERIMENT`；每组使用 6 个 5 秒 `powermetrics` 样本。

| 路径 | CPU + GPU 功率均值 | 应用 CPU | WindowServer CPU | `avconferenced` CPU | GPU 功率 |
|---|---:|---:|---:|---:|---:|
| 共享 GL → `copyArgb` → Canvas | 8.219 W | 39.44% | 33.44% | 47.05% | 1.037 W |
| 原 Surface IOSurface 交付 | 5.893 W | 52.83% | 23.87% | 44.54% | 0.446 W |

共享 GL 路径把应用自身 CPU 降低约 13.4 个百分点，应用 energy impact 也下降，
但 WindowServer energy impact 从均值 621.8 升到 1065.3，`avconferenced` 从
122.4 升到 689.3，GPU 功率增加约 132%，最终 CPU+GPU 组合功率反而高约 39.5%。
这复现了此前的“CPU 局部下降、系统合成总功耗反弹”现象；当前高功耗不能归因于
返回箭头修复、自适应心跳或残留 tint 日志。

暂停状态也未把系统负载降到基线：暂停后应用仍约 20%–55%、WindowServer
约 38%–55%、`avconferenced` 约 66%–89%、GPU 约 75%–84%。因此下一阶段应优先
消除逐帧 `copyArgb`/Canvas 重绘和 Sidecar 对重复画面的合成，而不是只继续压低
应用进程 CPU。低功耗候选继续保持共享 GL 实验关闭；证据位于
`power-recheck-shared0-r1/` 与 `power-recheck-shared1-r1/`。


## 第二十二轮：静态页空闲呈现门控与三轮能耗对照

`MACAPK_IDLE_PRESENT_GATE=1` 在主机 `drawInMTKView` 入口跳过纯 View/Canvas 的重复
呈现；UI traversal、Surface 新帧、首帧、resize 和低频安全心跳都会唤醒，连续 GL、
原生 EGL 与解耦呈现保持原契约。策略由独立纯函数测试覆盖。

本轮 Sidecar 已断开，系统只报告内建 Retina Display，因此没有移动用户窗口或修改
显示设置，而是显式使用内建屏完成 A/B。每组预热 30 秒、采样 60 秒，顺序为
OFF/ON、ON/OFF、OFF/ON；六张起始截图均确认是 B 站推荐首页，窗口均为 360×672。

| 三轮截尾均值 | OFF | ON | ON 相对变化 |
|---|---:|---:|---:|
| 应用 CPU | 15.99% | 10.22% | -36.1% |
| WindowServer CPU | 24.04% | 23.72% | -1.3% |
| GPU 设备利用率 | 20.64% | 20.31% | -1.6% |
| 应用 energy impact/s | 301.62 | 239.64 | -20.5% |
| WindowServer energy impact/s | 35.28 | 35.86 | +1.7% |
| CPU+GPU 组合功率 | 1.011 W | 0.947 W | -6.4% |
| CPU 功率 | 930.64 mW | 865.49 mW | -7.0% |
| GPU 功率 | 79.50 mW | 81.21 mW | +2.1% |

组合功率仍受系统后台波动影响，但三轮应用 CPU 和 energy impact 方向一致；不能把
结果外推为 Sidecar 或整机电池续航结论。门控开启时 `[idle-present]` 通常报告
`work=9–13 / raw=300`，其中 `heartbeat=9–10`；进入原生 EGL 视频后变为
`work=300 / forced=300`，返回首页后恢复跳过约 97%。

交互回归覆盖首页滚动、切页、打开普通视频和返回。连续两帧截图不同且 IJK 解码/
渲染线程持续推进，未观察到拉伸、白屏或闪烁。暂停控件仍无法由当前自动点击或空格
输入得到可重复状态，暂停/恢复标记 NOT_RUN。

原始数据位于 `idle-gate-home-{off,on}-r{1,2,3}/`、
`idle-gate-home-summary.json` 和 `idle-gate-interaction-r1/`。开关继续默认关闭；
提升默认前需覆盖跨应用动画、缺失 invalidate 样本和重新连接的 Sidecar。


## 第二十三轮：全屏旋转停顿定位到资源索引重建

播放普通视频时进入系统全屏、使用 `View > Rotate` 切换横屏并退出全屏。日志顺序显示：

| 切换 | framework 资源解析 | ResourceIndex 重建 | 后续动作 |
|---|---:|---:|---|
| 进入全屏 | 577 ms | 847 ms | `onConfigurationChanged`、1208×2148 Surface |
| 全屏旋转横屏 | 850 ms | 962 ms | `onConfigurationChanged`、3420×1924 Surface |
| 退出全屏 | 871 ms | 2001 ms | display commit 等待 30 帧、1280×720 Surface |

每次切换还重复全量 ingest `resources.arsc`。这些同步工作先于活动配置回调和 Surface
resize 完成，正好覆盖用户观察到的画面与声音共同停顿窗口。当前证据足以确认停顿发生
在 Mac.apk 兼容层；尚未量化资源重建、超大 Surface 分配和播放器自身配置处理各自的
占比。已建立 [GFX-001](tasks/GFX-001.md)，优先做配置索引缓存和配置/Surface transaction
合并，同时保持 AOSP 的 `onConfigurationChanged` 语义。


## 第二十四轮：旋转资源缓存原型与 Surface geometry 双提交

`GFX-001` 的首个缓存原型复用同一 APK-set 的已解析 ARSC 表，把配置切换时的资源选择
改为按 `(member, config)` 分组，并让 portrait/landscape framework resolution view
同时驻留；完整 `ResourceIndex` 也增加了配置键控 LRU。首次进入全屏由 847 ms 降到
224 ms，首次旋转横屏由 962 ms 降到 414 ms，资源选择分别只占 39 ms 和 19 ms。

停顿仍可感知，说明解析缓存不足以完成修复。首次横屏时 framework resolution view
仍要首次构建并耗时 386 ms；日志还显示窗口先变为 `3420x1924`，交换链却先按旧
Surface 尺寸 `1208x680` 重建，下一拍才变成 `3420x1440`。因此下一阶段必须把
configuration、最终 view geometry 和 native Surface resize 合并成一次提交，并保留
最后一帧直到新交换链可呈现。资源缓存仍需逐项等价性验证和第二次切换命中计时，任务
状态保持 `PENDING`。证据位于 `work/upgrade/VIDEO-PWR-001/gfx-cache-r1.log`。


## 第二十五轮：Surface 布局事务的负向 A/B

为缩短 `GFX-001` 中“旧 Surface 尺寸先提交、下一帧才提交最终尺寸”的窗口，新增了只供
实验的立即布局提交：窗口 `measure/layout` 返回后同步收敛所有已创建 SurfaceView。
第二次缓存命中已确认，完整 `ResourceIndex` 重建为 `0 ms`，所以本轮只测试 geometry。

内建 Retina Display 上按普通视频、系统全屏、`View > Rotate` 复测。实验没有消除旧
交换链，反而在 B 站尚未完成后续布局时多提交了一次中间高度：

| 路径 | 横屏交换链序列 | 结果 |
|---|---|---|
| 当前逐帧收敛 | `1208x680` → `3420x1440` | 两次，下一帧得到最终尺寸 |
| 立即布局提交 | `1208x680` → `3420x680` → `3420x1440` | 三次，额外重建 |

因此立即提交代码已撤回。这个负结果排除了“把现有收敛简单提前”的方案，下一实现需要
延迟 Surface 回调、保留最后一帧，并在 B 站完成后续 layout 后只发布最终 geometry。
`GFX-001` 保持 `PENDING`，不能把本轮当成旋转流畅度修复。证据位于
`gfx-cache-r2.log` 与 `gfx-layout-commit-r1.log`。


## 第二十六轮：旋转 geometry transaction 未触发且未保留

为验证“等一次 Java layout 完成后再发布最终 Surface geometry”的设想，增加了一个
默认关闭、带 500 ms 超时的 transaction 原型。内建 Retina Display 上完成竖屏全屏、
横屏、竖屏、再次横屏和退出全屏。首次横屏仍重建 `ResourceIndex` 用时 373 ms，后两次
命中缓存为 0 ms；交换链都直接使用最终 `1280x720` 或 `720x405`，画面稳定，无白屏、
闪烁、拉伸或冻结。

当前 B 站路径在 transaction 结束数秒后才触发 renderer geometry，等待分支一次也没有
执行，因此本轮不能证明 transaction 消除了旋转停顿。代码审查还确认该原型只延迟了
等待前的 `w/h` 请求，没有在 layout 完成后重新读取最终 holder geometry；主线程同步
`surfaceChanged` 与 renderer 互等的极端路径也要等到超时。为避免保留一个看似有效、
实际契约不完整的开关，本轮源码已撤回，只保留负向/未触发实验记录。

结论保持不变：Mac.apk 的首次资源重建和旧尺寸 Surface 提交责任已经确认，`GFX-001`
继续 `PENDING`。声音是否完全连续本轮没有可靠断言；Sidecar 未连接，原 `3420` 场景
`NOT_RUN`。证据位于 `gfx-geometry-txn-r1.log`、`gfx-geometry-txn-r1-before.png` 和
`gfx-geometry-txn-r1-after.png`。


## 第二十七轮：全屏目标尺寸预热与旋转往返

为避免把普通窗口的横屏尺寸误当成全屏旋转目标，新增原生全屏容器尺寸查询，并让预热按
兼容层相同的 aspect-fit 规则计算目标。竖屏全屏时日志成功预热 `1710x962dp land`
（`3420x1924`），耗时 `23ms`。

内建 Retina Display 上完成竖屏全屏 → 横屏全屏 → 竖屏全屏 → 横屏全屏 → 退出全屏。
第一次横屏的严格完整 Configuration 键仍未命中，但同步构建已降至 `22ms`；之后竖屏与
横屏资源索引均命中缓存，重建均为 `0ms`。用户可感知停顿因此不再由资源解析主导。

Surface 仍有确定性两阶段提交：每次转横屏都先创建 `1208x680` 交换链，随后才创建
`3420x1440`；转回竖屏依次出现 `1208x1440`、`1208x680`。此外本轮稳定复现了横屏画面
只占左侧窄区域、右侧大片空白，以及退出全屏后视频仍偏在左侧的布局错误。这些现象和同步
配置回调足以确认 Mac.apk 兼容层有明确待修责任；B 站自身可能也有配置处理，但缺少 Android
真机对照，不能据此把故障推给 B 站。`GFX-001` 保持 `PENDING`，Sidecar 和声音连续性仍
为 `NOT_RUN`。证据位于 `gfx-prewarm-r5d/app.log`。


## 第二十八轮：旋转问题的 B 站源码对照与责任归类

对 Bilibili 9.16.1 的视频详情 Activity 做了静态对照。`UnitedBizDetailsActivity` 的清单
已声明自行处理 `orientation`、`screenSize` 等配置变化；其反编译的
`onConfigurationChanged()` 只调用父类并记录配置，没有主动创建、停止或重配置播放器。
因此“B 站每次旋转都会自己重建整个播放器”不是当前现象的主要解释，但父类与播放器组件仍
可能有次要响应，现阶段不能把声音停顿精确拆成单一调用。

Mac.apk 的日志则稳定出现旧尺寸 Surface 先提交、配置回调随后执行、最终尺寸再次重建，
并且在最终图层 frame/drawable 已正确时仍出现横屏视频只占左侧的视觉结果。这不符合上述
Activity 接收配置后继续使用原实例的契约，已明确归入 [GFX-001](tasks/GFX-001.md) 待修项。
任务保持 `PENDING`；验收要求覆盖竖屏全屏 → 横屏全屏 → 竖屏全屏往返时无中间交换链、
无拉伸/半屏、画面和声音均无可感知中断。证据位于 `gfx-geometry-trace-r3/`、
`bili-gfx-jadx-r1/` 和 `bili-res-r2/AndroidManifest.xml`。
