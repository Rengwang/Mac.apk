# 下一步实施路线

## 1. 固定可复现的测试产物

为开发使用单独应用名、bundle ID、数据目录和日志目录。记录源码提交、native SHA-256、framework/loader JAR SHA-256、APK SHA-256、构建参数、ANGLE backend 和实验开关。关闭自动向正式应用同步。保留已验证 H.264 csd 修复；不要从别的工作树直接拷贝旧 native、JAR 或整个源码文件覆盖。

固定三类视频样本：横屏 `BV1sX8A6bEPr`、竖屏 `BV17ZXsBNERo`、曾有声无画的 `BV1AspE6EEuU`。还需可控的本地、合法测试流验证 SPS/PPS 合并/分离、avcC、hvcC、分辨率和清晰度切换。应用网络内容和编码可能变化，BV 号只能作为场景入口。测试使用同一深链直接进详情，记录最终实际播放内容，避免首页推荐变化。

完成无弹幕视频回归后再开启透明 Surface 实验。遇到黑屏先确认解码器是否成功、是否有输出帧，再检查图层，避免混淆两类故障。

## 2. 用最小应用复现弹幕依赖的通用行为

建立不依赖 Bilibili 代码/字体/协议的测试 APK：一个原生视频层加一个透明 SurfaceView，每帧绘制移动文字、线条和透明区域。分别测试 Java Canvas、GLES，以及原生 Choreographer 回调。

证据应覆盖：View attach/display → Surface create/change → ANativeWindow → EGL create/current → callback enter/return → glViewport/clear/draw → eglSwapBuffers → 图层合成。每条记录使用 Surface key 和 generation；记录交换结果和 EGL/GL 错误，禁止在默认模式逐帧大量输出或回读。

当前 `View.getDisplay()` 修复只有独立测试证据。先用上述样例验证其运行时行为，再判断它是不是弹幕只提交一帧的原因。不能因为 API 原来返回 null 就认定问题已经定位。

## 3. 完成 Surface 的通用几何与生命周期

当前 per-Surface 实验尚未形成完整事务模型。需要：

- 用同一个明确变换将 Android 物理像素映射到 Cocoa 点，处理实际 backingScale、Y 轴和祖先裁剪；窗口跨屏时重算，不能固定为 2。
- 区分 layer 的逻辑 frame、渲染 drawableSize 与应用缓冲大小，尺寸更新必须跟随 holder/ANativeWindow/EGL 生命周期；移除 setBuffersGeometry 对共享单例的依赖。
- 每个 Surface 拥有自己的视频/GL/打洞路由、alpha、层级及 owner；不通过“放在视频下方”永久掩盖透明错误。
- generation/token 防止旧 EGLSurface 的延迟释放关闭同 key 的新图层；引用归零、失败路径和 Java global ref 一致释放。
- 创建失败、主线程超时、首帧失败均有可证明的清理/回退；避免持锁 dispatch_sync 与主线程互等。
- 全屏/旋转/隐藏/切后台/多窗口/副屏断开不留下旧图层或旧打洞。

alpha 守卫需要单独验证完全不透明、TRANSPARENT、TRANSLUCENT 和运行中格式切换；不能把所有 Surface 强制透明。

## 4. 修复调度，随后评估 P0

原生 Choreographer 曾测得约 100 ms 回调间隔，但条件变量不能自动唤醒 poll 的判断还需要针对实现验证。应使用可唤醒的事件源/定时器和单调时间调度，验证回调不忙等、不重复，停播或不可见时能休眠。不要仅把 sleep 改短、持续轮询或不断重新注册回调。

异步帧消息只能越过 Android 同步屏障，必须仍归正确 owner。归档的 Handler/MessageQueue 改动先单独审查和测试，不能把任意异步消息都当帧，也不能失去取消、退出和顺序语义。

P0 的方向是减少 pbuffer GPU→CPU→GPU 往返，方向合理；生命周期、alpha、dirty region 和首帧恢复通过前不默认开启。先恢复连续正确画面，再验证回读为零；“没有回读”也可能只是没有新帧。禁止用丢弃损伤或跳过上传换取低 CPU。每一步在独立提交中进行。

## 5. 完成回归矩阵

每种 backend/实验组合至少覆盖：普通视频、打开/关闭弹幕、暂停/恢复、切视频/清晰度、横屏/竖屏反复进出、返回首页再打开、窗口缩放、隐藏恢复、移到不同缩放屏幕、断开副屏、多实例、退出重启。专门检查重复 Surface key、owner 变化、销毁后回调、快速切换与主线程压力。

资源/图标单独用两个包含不同 PUA 字体和相同字符码的测试 APK，验证资源身份、font family 和 Drawable 缓存。返回、加号、箭头、扫一扫不能靠 Bilibili 字符重映射修好。设置列表需对照 Android 实机/模拟器截图和布局测量，区分 theme、尺寸与文字颜色错误。

酷安评论闪退先收集崩溃栈并分类为 Java API、资源、native/JNI 或加载器问题。针对最小通用缺口修复；不要把重新脱壳或删除业务库当成框架兼容完成。

## 6. 用同条件基准判断性能

Mac.apk 与原生 iOS Bilibili 使用同一视频、相同可用编码/清晰度、弹幕开关、播放时长和相近窗口像素数；相同显示器/电源状态，避免同时播放争用硬件。预热后测首页静止、连续滚动、播放、暂停、后台五类场景，重复多轮。

记录进程 CPU、RSS/物理内存、线程/唤醒、帧间隔与丢帧、硬解会话标志、回读/上传字节数；测 GPU/能耗时单独注明系统级与进程级数据的区别。CPU 百分比不是功耗。VideoToolbox 硬解和 UI 使用 GPU 是两个独立验证项。

之后按实际热点优化：长存活缓存上限（含字体身份 map）、缓冲池尺寸、像素复制、JNI 高频调用、损伤合并、不可见 Surface 的调度和清理。正确性通过且指标改善、内存不持续增长后再准备可回退的发行包。
