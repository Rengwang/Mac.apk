# Mac.apk 通用 Android 兼容运行时升级手册

- 最后整理：2026-10-29
- 已验证实现提交：`4706bf2`
- 工作分支：`codex/universal-android-runtime-20261029`
- 上游仓库：<https://github.com/kksimp/Mac.apk>
- 上游兼容分支基点：`25bc68b`（v1.0.2303）
- 许可：AGPL-3.0

这份文档是 DSH 兼容分支的总入口。它记录了升级方向、系统结构、从旧方案
转向通用能力的完整过程、当前实现、验证证据、已知风险和后续路线。旧的
`README-COMPAT.md`、应用专用诊断报告和静态补丁快照已经合并到这里并从
仓库移除；它们不再代表当前实现，也不应被继续当作开发依据。

## 1. 目标和边界

目标是把 Mac.apk 升级成一种类似 Wine 的兼容层：尽量不修改 APK、不依赖
Android 模拟器、不启动 Linux 内核，由通用运行时解释 Android 对进程、JVM、
资源、窗口、图形、多媒体、Binder 和系统服务的契约。应用成功运行只是发现
缺口的手段，不能成为把应用包名、类名或资源 ID 写进运行时分叉的依据。

这项工作遵循以下原则：

1. 优先修正 Android API 与宿主系统之间的语义差异，而不是修改目标 APK。
2. 用 AOSP 的公开行为作为判断标准；宿主实现可以不同，可观察行为应一致。
3. 先做最小可逆实验，把临时开关、日志和失败条件保留下来，再收敛成默认能力。
4. 任何“只对某个应用有效”的判断都必须迁移到公共 API、资源选择器、ABI、
   Surface 所有权或清单元数据等通用边界；业务代码补丁不得进入运行时。
5. 一个修复必须至少解释一个可复现的 Android 契约缺口，并在其他应用或
   独立单元测试上成立，不能只以“某个页面亮了”为完成标准。

旧路线曾经直接脱壳、改 Smali、删除广告 SDK、替换生命周期方法并重签 APK。
这些实验帮助找到了 `isTaskRoot()`、资源回退、签名查询、脱壳工具和 native
初始化等缺口，但它们不是本分支交付的产品能力。以后遇到同类问题，应先判断
Mac.apk 缺的是哪一项 Android 语义；只有业务自身依赖的 native VM 或服务端正
在完成的应用级兼容层，不进入主运行时。

## 2. 当前基线

本分支当前相对上游 `main` 分为两条历史：

- 上游 `main` 为 `ce1499e`。
- 本地兼容链从 `25bc68b` 分出，当前已验证实现点为 `4706bf2`。
- `4706bf2` 相对共同基点共有 39 个兼容提交，同时少 4 个只修改 README、赞助和
  CLA 状态的上游提交。
- 没有对远端主线强推，也没有改写任何历史。整理结果保存在独立分支，便于
  分阶段审查和后续合并。

已完成的最终构建验证记录如下：

```text
CONVERTER_ID=ff20d6c275f1c044
FRAMEWORK_ID=1ed4df263da048a6
JAVA_ID=133337705a8f4258
JARS_ID=7ccf23276b3a970c
NATIVE_ID=773fd23055a186aa
```

构建后的运行包已同步到：

```text
/Applications/Mac.apk.app
/Applications/哔哩哔哩.app
```

应用名只说明当时的交互验收对象，不表示运行时中存在哔哩哔哩专用分支。最新
提交中的所有生产默认值、Surface 路由、颜色、布局、事件和 Binder 修改都位于
通用框架、原生桥接、翻译器或启动器层。注释中出现的应用类名用于保存复现
证据和根因，不用于运行时识别目标应用。

## 3. 源码地图

| 路径 | 职责 | 修改时的主要约束 |
|---|---|---|
| `launcher/` | macOS 控制面板、安装管线、每个 Android 应用的启动 stub、外部安装 helper | 生成的 bundle 不重新签名；Dock 与控制面板必须使用相同环境默认值 |
| `runtime/src/main.cpp` | 创建 JVM、启动 Java 引导层并持有 Cocoa 主循环 | 不得破坏直接运行和已安装应用两条入口 |
| `runtime/src/apk_unpacker.cpp` | APK、split APK 安装布局和包数据目录 | 保持 Android 的 `data/app`、`data/data` 目录约定 |
| `runtime/src/cocoa_bridge.mm` | AppKit 窗口、输入、Canvas 合成、EGL/Metal 层、Surface 展示 | 当前多 Surface 修复的核心；绘制提交和 Surface 所有权必须分开 |
| `runtime/src/video_toolbox.mm` | H.264、HEVC、VideoToolbox 解码和 CSD 处理 | 同时接受 Annex-B 与 length-prefixed AVCC/HVCC，并保持异步队列有界 |
| `runtime/src/media_*.mm` | MediaCodec、音视频轨道、VP8/VP9 等后端 | 队列、时戳、格式变化必须遵守 Android MediaCodec 行为 |
| `runtime/src/surface_metal_layer.h` | 透明 CAMetalLayer 的 alpha 约束 | 透明弹幕层不得被后端强制设为 opaque |
| `runtime/src/native_methods.cpp` | Java native 方法注册和 GLES/EGL 桥 | Java 与 native 的 Surface key、尺寸和生命周期必须一致 |
| `runtime/src/native_os.cpp` | `android.system.Os` 等宿主桥 | 把 Bionic/Android 常量转换为 Darwin 语义 |
| `runtime/src/webview_bridge.mm` | Android WebView、WKWebView、输入和层级 | 与 Surface 上下层关系共用窗口层级规则 |
| `runtime/src/window_policy.cpp` | AOSP 风格窗口尺寸、方向和响应式策略 | 避免应用专用屏幕尺寸判断 |
| `runtime/translator/src/bionic_shim.cpp` | Android arm64 原生库的 libc/libm/ANativeWindow/JNI/EGL/字体等兼容层 | 最大的跨系统 ABI 边界；每个替换符号都应有行为测试 |
| `runtime/translator/src/arm32*` | ARM32 到 arm64 的指令翻译和 Mach-O 链接 | 与 64 位引导链隔离，任何失败都应可定位 |
| `runtime/java/` | Android Framework、核心库和 `com.macapk.loader` 的 Java 实现 | 尽量匹配 AOSP 可观察语义，保留 opt-out 诊断开关 |
| `runtime/java/com/macapk/loader/` | APK 引导、类加载、清单、资源索引、Activity、窗口、输入、跨应用调用 | 优先把普适修复放在这里，禁止增加业务分支 |
| `runtime/java/android/content/res/` | `Resources`、`AssetManager`、主题和 `TypedArray` | 资源 identity、cookie、selector、overlay 和 alias 必须一起考虑 |
| `runtime/java/android/graphics/` | Canvas、字体、Bitmap、SurfaceTexture 和绘制记录 | Java2D/Skia/GL 三条路径必须保持像素和 alpha 语义一致 |
| `runtime/java/android/view/` | View、ViewGroup、SurfaceView、窗口和布局 | 几何、z-order、display 绑定、生命周期和 damage 必须分离 |
| `runtime/java/android/os/` | Looper、Handler、MessageQueue、Parcel 和 Binder 基础 | 线程所有权、同步屏障、对象引用和消息顺序是高风险区域 |
| `runtime/java_base_patch/` | 无法通过在 classpath 中替代而必须替换 JDK 类的补丁 | 只覆盖明确测量的宿主差异，保持 JDK 21 兼容 |
| `runtime/jdk_unsupported_patch/` | `sun.misc.Unsafe` 等 JVM 内部接口适配 | 修改必须同时检查加载器、反编译器和应用反射 |
| `debug/` | 独立 Java、Objective-C++、Python 回归测试和开发脚本 | 测试应直接验证契约，不复制生产实现 |
| `thirdparty/` | ANGLE、MoltenVK、Skia 预编译库、许可与来源 | 升级二进制时同步 provenance、许可和安装 payload |
| `scripts/` | 构建、依赖审计、打包、发布和安装 | 主构建必须产出 self-contained app bundle |

## 4. 启动与运行数据流

### 4.1 安装

1. 控制面板或 `macapk-installhelper` 接收 APK 或分包格式。
2. `InstallCore` 建立安装记录，并把 APK 写入每个应用 bundle 的 Android
   `data/app` 目录。
3. `BundleGenerator` 复制预先签名的 `MacApkAppStub`，生成图标和 `Info.plist`。
4. 应用数据保存在版本化的 `data/data` 下；更新 bundle 时迁移或保留用户数据。
5. 应用从 Dock 启动时，stub 通过 `execv` 进入共享 runtime；控制面板直接启动
   时也必须设置同一组生产兼容默认值。

### 4.2 Java 与 DEX

1. runtime 创建 HotSpot JVM，加载 Java 引导和 Android Framework jar。
2. 自定义 class loader 读取 DEX，`dex2jvm` 将其转换为 JVM bytecode。
3. `ClassFileRewriter` 修补 JVM 与 Android 的少量结构性差异，例如 boot class
   loader 和 mixin 生成本应只存在于 `<init>`/`<clinit>` 的 final 写入。
4. `ActivityHost`、`ViewRootImpl`、Looper 和 Cocoa bridge 共同建立 Android
   生命周期、窗口、输入与绘制循环。

### 4.3 原生库

APK 的 arm64 共享库由 Darwin `dlopen` 和自定义符号解析链加载；ARM32 库经翻译
后生成 arm64 可执行代码。Android arm64 库看到的 libc、pthread、libm、JNI、
EGL、GLES、`ANativeWindow` 和系统服务由 `bionic_shim.cpp` 转换。任何缺失符号
都不能直接调用 `abort()`，特别是已经附加到 JVM 的原生线程；否则会进入 JVM
错误处理并等待该线程无法到达的 safepoint，表现为整进程冻结。

### 4.4 资源与主题

`ResourceIndex` 解析 `resources.arsc`，`BinaryXmlParser` 解析 AXML，
`Resources`/`AssetManager` 负责把资源 ID、配置、alias、overlay、主题 bag、
颜色状态和文件条目串联起来。一个资源通常同时携带：

- 查询的资源 ID；
- 实际目标资源 ID；
- 字符串池索引；
- AssetManager mount cookie；
- 当前配置与主题。

这几类 identity 不能混用。旧实现把 `assetCookie` 固定为 1，导致主题 XML
被误认为来自 framework APK；后来又修正为同时保存目标文件和实际 cookie。

### 4.5 窗口、Surface 与视频

Android 应用通常同时创建多个消费者：窗口 Canvas、`SurfaceView` 视频层、透明
弹幕层、WebView、相机预览或游戏 HUD。它们不是同一个缓冲区：

```text
Android app / codec
       |
       +-- Surface(key=A) -> ANativeWindow(A) -> CAMetalLayer(A) -> video rect
       +-- Surface(key=B) -> ANativeWindow(B) -> CAMetalLayer(B) -> alpha overlay
       +-- window Canvas   -> shared drawing target -------------------+
```

每个 Surface 需要独立 key、buffer 尺寸、显示矩形、转换矩阵、alpha、z-order
和生命周期。解码是否继续、Canvas 是否继续刷新、Metal layer 是否提交必须分开
观测；否则很容易把“视频仍解码，但错误图层盖住画面”误判为“视频卡住”。

### 4.6 Binder 与跨进程服务

当前实现为同机应用进程提供服务发现、连接和私有 Parcel 传输接缝。Parcel
导出时把本地 `IBinder` 替换为连接作用域 handle，导入时再解析为本地对象，并在
`appendFrom`、自复制和裁剪时重建对象 offset。这个边界已经支持测试所需的
显式 transport，但仍不是完整 AOSP Binder 驱动。

## 5. 完整演进时间线

### 5.1 2026-10-21 至 2026-10-23：吸收 AOSP 语义修复

这一阶段修复 shrunk APK 的属性名称、XML 尺寸取整、weight 资源、包 archive
feature、SearchableInfo、Activity 创建期间输入排队、DialogFragment 生命周期、
ImageView tint、vector tint、drawable child state、private platform attr 和
app `defStyleAttr`。这些问题来自多个应用，说明兼容层正在从“能启动”转向
AOSP 可观察行为一致。

### 5.2 2026-10-26：旧兼容实验和通用能力暴露

最初工作是直接处理公考雷达、c001apk、酷安和哔哩哔哩。实验产出了以下通用
修复：资源与 Assets 回退、`ActivityThread` 兼容字段、DexVMP 空方法安全桩、
隐藏系统代理、原始签名覆盖、真实 `isTaskRoot()`、`finish()` 后的图层回退、
`mmap`/`munmap`/`msync`，以及通用 DEX 转储工具。

同一时期也确认了边界：商业壳、native 方法虚拟化、服务端完整性检查和业务层
native 初始化不能由 Framework 自动补全。对酷安详情页和哔哩哔哩 Gripper 的
Smali/native 补丁只属于实验记录，不保留为本分支交付物。

### 5.3 2026-10-27：从单点修补转向通用运行时

哔哩哔哩作为覆盖面较大的压力测试，暴露并修复了多处跨应用缺口：

- `DrawableContainerState` 实际类型与 AOSP 契约不一致；
- 桌面鼠标需要 8dp touch slop；
- Activity 在 `onResume` 中 finish 后仍被标记为 resumed；
- `/proc/<pid>/cmdline`、boot class loader 和 `times(NULL)` 语义缺失；
- JVM 不接受 mixin helper 对 `final` 字段的合法写入；
- 数字资源引用只识别十进制，造成洋红色 UI；
- `isnanf`、`posix_fallocate` 等 Bionic 符号缺失会使原生解码线程冻结；
- VideoToolbox、SurfaceTexture、EGL window surface 和 Canvas 视频路径需要
  独立帧队列、有界轮询、正确 transform、release 和 damage 管理。

这些修改全部进入 JVM 重写器、Bionic shim、VideoToolbox、Surface、Cocoa 或
Android Framework，没有把应用包名写进代码分支。

### 5.4 2026-10-28 至 2026-10-29：多 Surface、完整资源和交互状态

弹幕不显示和视频冻结被拆分成了不同问题。弹幕一开始写入离屏 pbuffer，之后
又和视频 Surface 共用 owner，使共享 Canvas 被错误提升 z-order；透明层还会
被 CAMetalLayer 的 opaque 设置吞掉 alpha。最终实现为按 Surface key 分配独立
ANativeWindow 和 Metal layer，并把显示几何、buffer 尺寸、view alpha、z-order、
关闭的 Activity、Choreographer 和 frame callback 分开管理。

与此同时补齐了 avcC/hvcC、主题 tint、私有属性、文件资源 cookie、图标字体、
ImageView level、attached display、远程 service process、Parcel/Binder handle
和多项生命周期测试。用户最终确认视频与弹幕正常、按钮状态会变化，交互验收
完成。

### 5.5 完整提交台账

下表按兼容分支的拓扑顺序列出从 `25bc68b` 到 `4706bf2` 的全部 39 个提交。
拓扑顺序优先于作者时间，因为部分提交从上游 cherry-pick 而来，仍保留原始作者
日期；表中的顺序才是它们在兼容分支中的依赖关系。

| # | 提交 | 日期 | 内容 |
|---:|---|---|---|
| 1 | `7b421d9` | 2026-10-26 | 首批通用补丁：资源回退、Tinker 字段、隐藏代理、DexVMP 安全桩、签名覆盖 |
| 2 | `e9040a2` | 2026-10-26 | 记录首批补丁、构建、安装和验证方法 |
| 3 | `509750b` | 2026-10-26 | 验证 c001apk Flutter/Impeller EGL 渲染 |
| 4 | `5fb1a16` | 2026-10-26 | 酷安脱壳实验、重打包与 Pangle native 库移除 |
| 5 | `77059cf` | 2026-10-26 | 记录酷安 native lifecycle 限制 |
| 6 | `02d869c` | 2026-10-26 | 实现真实 `isTaskRoot()` 任务栈语义 |
| 7 | `fd9de63` | 2026-10-26 | 记录详情页 isTaskRoot 修复和 feed 数据缺口 |
| 8 | `e8a1fb8` | 2026-10-26 | 酷安详情页通过 feedId + loader 实验性恢复 |
| 9 | `c50bf52` | 2026-10-26 | `isTaskRoot()` 与 `finish` in `onCreate` 图层回退固化 |
| 10 | `9e76546` | 2026-10-26 | 新增识别被抹头、4 字节偏移和匿名内存 DEX 的通用 dump 工具 |
| 11 | `149b35c` | 2026-10-26 | 修正 DEX dump 的 `/proc/.../maps` 正则 |
| 12 | `6839007` | 2026-10-26 | 汇总早期通用修复和转储工具 |
| 13 | `6a42a54` | 2026-10-26 | 增加 `PROT_*`/`MAP_*`、`Os.mmap` 与 NativeOs 桥 |
| 14 | `a6ba981` | 2026-10-26 | 记录哔哩哔哩启动阻塞和 mmap 修复 |
| 15 | `5ffc61a` | 2026-10-27 | 修复 Drawable state 类型、8dp touch slop、onResume 后 finish、munmap/msync、static final 重写 |
| 16 | `1583035` | 2026-10-27 | 提供 `/proc/<pid>/cmdline`、非 null boot class loader、`times(NULL)` |
| 17 | `987841e` | 2026-10-27 | 支持 instance-final mixin helper；修正 `TypedValue.assetCookie=0` |
| 18 | `e4d4640` | 2026-10-27 | 同时解析 `@0x...` 与 `@十进制` 资源引用；补 `isnanf`、`posix_fallocate` |
| 19 | `a01d5ac` | 2026-10-27 | 接管 Antigravity 的视频 WIP：VideoToolbox、SurfaceTexture、EGL、GL 外部纹理和窗口层 |
| 20 | `1e91b88` | 2026-10-27 | 绘制成本加入文字；1:1 bitmap blit 使用精确快速路径 |
| 21 | `184ec85` | 2026-10-27 | 修正视频 Surface 路由、裁剪孔洞、有界 codec 轮询和宿主屏幕选择 |
| 22 | `273b20c` | 2026-10-27 | 支持 per-app fit/free 窗口和延迟启动 refit |
| 23 | `3020b7d` | 2026-10-27 | 缓存 JNI 反射、隔离每流 buffer pool、使用 `glTexSubImage2D`、释放缓存 |
| 24 | `6bf50fd` | 2026-10-27 | ViewRootImpl damage authority 与局部绘制隔离 |
| 25 | `404b45c` | 2026-10-27 | 修正视频矩阵纵向裁剪，向量化 GL pbuffer 回读 |
| 26 | `d69eeed` | 2026-10-27 | detach/destroy 时释放 SurfaceTexture/EGL，并注销 video sink |
| 27 | `4b998ab` | 2026-10-27 | 修复沉浸式全屏反馈、透明对话框、渲染健壮性和窗口尺寸恢复 |
| 28 | `8f2949b` | 2026-10-26* | DialogFragment 按 create/fill/show/hide/dismiss 生命周期工作 |
| 29 | `d2d6d2a` | 2026-10-21* | 读取 shrunk APK 属性、XML 尺寸取整、解析 weight 资源 |
| 30 | `05c86d0` | 2026-10-23* | 修正 package archive 的 requested features |
| 31 | `852d362` | 2026-10-23* | 创建中的 Activity 输入进入自身 looper |
| 32 | `5e618ab` | 2026-10-23* | 解析 searchable metadata 并实现 `getSearchableInfo` |
| 33 | `d950ea3` | 2026-10-28 | 增加 imported resource alias 和 package features 测试 |
| 34 | `69929c5` | 2026-10-23* | ImageView 从 style/theme 读取 `android:tint` |
| 35 | `abb41b8` | 2026-10-23* | 只对声明 tint 的白色 vector 使用灰色 fallback |
| 36 | `ebee626` | 2026-10-23* | `addStatesFromChildren` 合并 child drawable state |
| 37 | `1781edb` | 2026-10-23* | 主题 bag 保留 private platform attribute |
| 38 | `b2af6ed` | 2026-10-23* | app `defStyleAttr` 先按自身 identity 解析 |
| 39 | `4706bf2` | 2026-10-29 | 多 Surface/透明弹幕、avcC/hvcC、Choreographer、资源和 Binder 综合修复与测试 |

`*` 标记的是从上游 cherry-pick 的提交，表格中的日期是原作者日期；它们在本兼容
分支中位于 `d950ea3` 之后，不能仅按日期推断依赖。

## 6. `4706bf2` 的逐项修改

该提交共修改 39 个文件：3347 行新增、213 行删除。以下按能力拆分，而不是按
diff 的文件顺序堆叠。

### 6.1 通用多 Surface 与视频合成

- `surface_metal_layer.h` 新增可强制持有透明 alpha 的 CAMetalLayer 子类，禁止
  后端把弹幕层设为 opaque。
- `cocoa_bridge.mm` 从“最后一个共享 owner”改为 keyed native window/Metal layer；
  每个 `SurfaceView` 的隐藏 Surface 也获得稳定 key。
- 无 SurfaceView identity 的第二个消费者不再退化为不可见 pbuffer，而是得到
  独立的合成层。这个 fallback 解决了没有 `extra consumers` key 的通用场景，
  但仍是软件近似，未来要改成真正的 BufferQueue/owner identity。
- 视频 buffer size 与屏幕显示矩形分离：codec 可以输出自己的分辨率，图层仍按
  View rect 缩放；修正非全屏 SurfaceView、fixed-size holder 和首帧回调闪烁。
- 原生桥回传 layer frame、display rect、z-order、view alpha、prefix format 和
  surface state，Cocoa 端不再从共享全局状态猜测。
- 停止的 Activity 不再保留旧视频 layer 覆盖新页面；返回、竖向全屏和栈切换时
  释放或隐藏过期 Surface。
- `SurfaceView` 实现 AOSP 的 Media、MediaOverlay、Panel sublayer 状态，避免
  “先 setZOrderOnTop 再 setZOrderMediaOverlay”被错误合并。
- `Surface`/`SurfaceTexture` 暴露稳定 native key、owner 大小和显式 video target；
  普通 `new Surface(SurfaceTexture)` 不再抢占解码帧，只有 `MediaCodec.configure`
  绑定输出时才登记确切目标。
- keyed ANativeWindow 的记录、引用计数、release、detach、destroy 和重复创建都有
  独立生命周期测试。

相关文件：`cocoa_bridge.mm`、`surface_metal_layer.h`、`native_methods.cpp`、
`bionic_shim.cpp`、`Surface.java`、`SurfaceTexture.java`、`SurfaceView.java`、
`ViewCanvasDriver.java`。

### 6.2 H.264/HEVC 配置和 VideoToolbox

- 支持 H.264 `avcC` configuration record。
- 支持 HEVC `hvcC` configuration record 和 VPS/SPS/PPS arrays。
- 同时接受：
  - Annex-B 风格，SPS/PPS 位于各自 CSD buffer；
  - Annex-B 风格，SPS/PPS 拼在 `csd-0`；
  - length-prefixed AVCC/HVCC payload。
- 从 configuration record 读取 NAL 长度域大小，并在扫描 Annex-B 前验证长度前缀，
  避免把真实压缩数据误判为配置。
- 异步解码队列保持有界；flush 等待在途帧，回调欠载和紧急队列有保护。
- 保留 codec 的 width、height、stride、slice-height、crop、旋转和显示变换信息。

相关文件：`video_toolbox.mm`、`native_methods.cpp`、`SurfaceTexture.java`、
`MediaCodec` 路径及 `debug/VideoPixelsTest.cpp` 等既有测试。

### 6.3 Choreographer、Looper 和帧顺序

- Java Choreographer 移动 ready callback 时唤醒 native looper，解决“计时器已到
  但宿主仍阻塞”的 60Hz 弹幕零显示问题。
- 为 `ViewCanvasDriver` traversal 建立私有 frame handler，只允许这条框架帧消息
  穿过同步屏障；普通同步消息、生命周期和 Surface 工作仍被屏障阻挡。
- `framePending` 在 `post()` 失败时立即复位，避免一次失败永久吞掉后续帧。
- 旧的 Unity vsync feed 特例只对确实来自 `libunity.so` 的 callback 生效。
- `MACAPK_EXPERIMENTAL_CHOREO_PIPE_WAKE` 默认启用，仍可设为 `0` 做回归 bisect。

相关文件：`Handler.java`、`Looper.java`、`MessageQueue.java`、
`ViewCanvasDriver.java`、`bionic_shim.cpp`、`cocoa_bridge.mm`。

### 6.4 资源、主题与字体

- `Resources.getValue()` 对文件资源保留 string-pool index，返回实际 AssetManager
  mount cookie，并在跟随 alias 时保留查询资源 ID。
- `AssetManager` 建立 path 到 cookie 的缓存，并给缺失 XML 提供有界诊断。
- `ResourceIndex.macapkFileValueOf()` 返回 app member path，主题和 XML 能正确匹配。
- AppCompat 的 `homeAsUpIndicator` 回退改用真正的
  `abc_ic_ab_back_material`，不再落到不存在的 drawable。
- 支持 XML `<icon-font app:code="...">` 和 APK 内常规 `iconfont.ttf` 路径。
- `TypedArray` 和 `Resources` 修正 alias、private attr、theme tint 和 typed value
  的值/cookie 传播。
- `Canvas` 修正 `SRC_IN` 等 Porter-Duff 颜色过滤，使透明源像素不会把目标 alpha
  变为不透明；量化文字绘制使用实际选择的字体。
- 资源解析和 Private Use Area 字体追踪分别由 `MACAPK_TRACE_RESOLVEATTR` 与
  `MACAPK_TRACE_PUA` 控制。

相关文件：`AssetManager.java`、`Resources.java`、`TypedArray.java`、
`ResourceIndex.java`、`Canvas.java`、`MacApkFont.java`、`RecordingCanvas.java`、
`bionic_shim.cpp`。

### 6.5 ImageView、Drawable、View 和 Window

- `ImageView.setImageLevel()` 保存 level、立即应用到 drawable，并在 drawable
  替换后重放；固有尺寸变化时重新布局和重绘。这样播放/暂停、弹幕开关等
  `LevelListDrawable` 图标状态会跟随应用逻辑。
- `ViewGroup` 在 `FLAG_ADD_STATES_FROM_CHILDREN` 时合并 child state，子 View
  state 变化会刷新父容器。
- `View.getDisplay()` 返回已 attach View 的逻辑 Display；没有 attach 时通过
  `DisplayManager` 选择。
- 窗口层级和 Surface 层级分离；透明对话框、沉浸式全屏和旧 Activity Surface
  在返回时的清理路径更接近 AOSP。
- `ViewTextDump` 增加 display、surface、z-order 和尺寸诊断，便于确认图层问题
  而不是只从截图猜测。

相关文件：`ImageView.java`、`View.java`、`ViewGroup.java`、`ViewTextDump.java`、
`SurfaceView.java`、`cocoa_bridge.mm`。

### 6.6 Binder、Parcel 与 service 进程

- 新增 `MacapkParcelWire`，为私有主机连接提供 bounded frame：最大 1 MiB、最多
  65536 个对象引用。
- `Parcel` 新增 export/import transport seam，把本地 `IBinder` 转为连接作用域
  handle；不支持的 live object 会被明确拒绝。
- `writeStrongBinder`、`appendFrom`、自复制、`setDataSize` 和对象 offset 更新都
  保持同步，避免 bytes 与对象表错位。
- `MacApkPackageManager` 解析 manifest service 的 `android:process`，返回真实
  `ServiceInfo.processName`，而不是一律使用主进程名。
- 新增 wire、Binder transport 和 remote service 单元测试。

相关文件：`Parcel.java`、`MacapkParcelWire.java`、`MacApkPackageManager.java`、
`debug/ParcelWireTest.java`、`debug/ParcelBinderTransportTest.java`、
`debug/RemoteServiceProcessTest.java`。

### 6.7 生产默认值与启动入口

以下四个能力在 Dock stub、LaunchServices 环境和控制面板三条路径中保持一致：

```text
MACAPK_EXPERIMENTAL_NATIVE_SURFACE_LAYERS=1
MACAPK_EXPERIMENTAL_NATIVE_SURFACETEXTURE=1
MACAPK_EXPERIMENTAL_PRESERVE_SURFACE_ALPHA=1
MACAPK_EXPERIMENTAL_CHOREO_PIPE_WAKE=1
```

用户显式设置的值最后覆盖默认值，因此诊断时仍可设 `0`。`MACAPK_LOG_LEVEL=4`
和 `MACAPK_QUIET_CLASSLOAD=1` 也由两条入口共同设置，避免 Dock 启动写过多类加载
日志而拖慢视频。

### 6.8 新增测试与诊断

新增 12 个测试/支撑文件：

| 文件 | 验证目标 |
|---|---|
| `debug/DrawableIdentityAndTintTest.java` | drawable identity、tint 和替代 ConstantState |
| `debug/ImageViewLevelTest.java` | level 保存、应用、替换 drawable 后重放与布局 |
| `debug/OwnerFrameBarrierTest.java` | owner frame 可穿屏障，普通同步消息不可 |
| `debug/ParcelWireTest.java` | bounded frame 与 Parcel 序列化 |
| `debug/ParcelBinderTransportTest.java` | Binder handle 导出/导入和不支持对象拒绝 |
| `debug/RemoteServiceProcessTest.java` | service processName 与远程进程启动 |
| `debug/SurfaceMetalLayerAlphaTest.mm` | 透明层不能由后端强制 opaque |
| `debug/SurfaceVideoTargetIsolationTest.java` | 无关 Surface 不会偷走视频目标 |
| `debug/ViewAttachedDisplayTest.java` | attached View 获得稳定 Display |
| `debug/native_window_lifetime_test.py` | ANativeWindow 记录、Surface key、释放与引用计数 |
| `runtime/java/android/os/MacapkParcelWire.java` | 主机 Binder 私有传输帧 |
| `runtime/src/surface_metal_layer.h` | 公共 alpha 约束实现 |

调试开关只为复现和 bisect 保留，不应成为长期产品配置。尤其不要在不理解上层
契约时直接切换 Surface 或 Choreographer 开关来“消除现象”，否则很容易从图层
遮挡问题变成帧顺序问题。

## 7. 验证与可重复检查

### 7.1 构建

```bash
scripts/check-requirements.sh
scripts/rebuild_and_run.sh
```

只构建不改 `/Applications`：

```bash
scripts/rebuild_and_run.sh --no-promote
```

分步构建：

```bash
runtime/scripts/build.sh
scripts/build_launcher.sh
debug/build_java_only.sh
```

发布前还要运行仓库已有的依赖与自包含审计：

```bash
scripts/audit_bundle_deps.sh build/Mac.apk.app
debug/dist_parity.sh
debug/staleness.sh
debug/entitlements_check.sh
```

### 7.2 当前证据

在 `4706bf2` 打包前已确认：

- 完整 Mac.apk 构建成功；
- runtime、Java framework、转换器和 launcher 均有新的 build ID；
- `/Applications/Mac.apk.app` 已按最终构建同步；
- `/Applications/哔哩哔哩.app` 已更新到同一 runtime；
- self-containment 审计通过；
- `ImageViewLevelTest` 通过；
- `OwnerFrameBarrierTest` 在一次观察到 flaky 后重跑通过；
- 用户实际验收：视频可播放，弹幕可见，后续视频和竖向全屏返回正常，播放/弹幕
  控件状态会变化。

2026-10-29 文档整理时又独立复验了 9 个不需要启动完整 runtime 的 Java 测试：
`ImageViewLevelTest`、`OwnerFrameBarrierTest`、`ParcelBinderTransportTest`、
`ParcelWireTest`、`SurfaceTextureRoutingTest`、`SurfaceVideoTargetIsolationTest`、
`TypedArrayVisibilityTest`、`UpstreamResourceAliasTest` 和
`ViewAttachedDisplayTest`。`SurfaceMetalLayerAlphaTest.mm` 与
`native_window_lifetime_test.py` 也通过。

三个需要真实 Mac.apk runtime 的检查不能在裸 `java -cp android-framework.jar` 中
替代完整宿主：`DrawableIdentityAndTintTest` 需要 JNI 日志绑定和真实 APK，
`RemoteServiceProcessTest` 需要实际独立 worker 进程，`WindowDamageLifecycleTest`
会通过 native `NativeLog` 初始化 Looper。它们应通过 runtime harness 或安装态
回归运行；把裸 JVM 的 `UnsatisfiedLinkError` 或同 PID 结果视为产品失败会产生
错误结论。

`OwnerFrameBarrierTest` 的一次 flaky 仍是一个风险。它没有阻止发布，但未来要把
线程调度和帧到达固定成可重复的确定性测试。

### 7.3 最小回归原则

每项修改至少选择以下一层验证：

1. 纯 Java 单元测试：资源 identity、Parcel、ImageView、View 状态；
2. Objective-C++ 小测试：Metal layer alpha、frame 尺寸和颜色空间；
3. Python/native 提取测试：从真实 shim 源提取函数，验证引用计数和 ABI；
4. 单 APK 直跑：检查启动、输入、窗口和日志；
5. 多应用矩阵：至少包含 Flutter/Impeller、Unity、MediaCodec、WebView 和
   普通 AppCompat 应用，避免只回归主验收应用；
6. 安装态测试：Dock stub、控制面板和 Finder 三条路径必须一致。

## 8. 已知风险和未完成问题

### 8.1 Surface 仍不是完整 BufferQueue/SurfaceControl

当前 keyed layer 是符合多数应用的软件近似。它解决了多消费者、透明覆盖、独立
尺寸和 z-order，但没有完整的 BufferQueue 生产者/消费者状态机、事务式
SurfaceControl 树、跨窗口 reparent、裁剪链、transform hint 和 fence 合并。

无 key 的额外 Surface 目前假设为全窗口 overlay。未来应用如果同时创建多个无
SurfaceView identity 的 Surface，仍可能得到错误矩形或排序。这是后续首要架构
风险。

### 8.2 VideoToolbox 只覆盖已测格式

avcC/hvcC、split/combined CSD 和 length-prefixed NAL 已覆盖，但还需要验证：

- H.264 length size 1、2、4 位；
- hvcC VPS/SPS/PPS 分离与组合；
- 8-bit 和 10-bit HEVC；
- rotation、crop、aspect ratio 和 clean aperture；
- 中途 reconfiguration、seek、flush、EOS；
- B-frame 时戳、丢帧和长时间播放内存稳定性；
- HDR、Dolby Vision 和受保护视频。

### 8.3 Choreographer 延迟语义不完整

native Choreographer 当前记录了 `requestedDelayMs`，但部分路径仍立即入队。
普通 `postFrameCallbackDelayed`、嵌套 Looper、生命周期切换和同步屏障压力下
还缺系统性测试。

### 8.4 Binder/Parcel 不是完整进程间 Binder

当前实现只保证私有 transport 连接的 framing 与 handle 映射；尚无：

- 真正的 Binder 驱动和全局节点引用；
- file descriptor 传输；
- oneway 与 synchronous 的完整事务语义；
- death recipient、linkToDeath、transaction code 调度；
- 权限、UID/PID sender identity 和 AIDL service manager 全语义。

### 8.5 字体缓存可能长期持有对象

`MacApkFont` 的 kerning 和 ligature 缓存从 weak map 变为 `IdentityHashMap`。
这避免字体 identity 被错误合并，但也可能让已释放的字体对象长期保留。需要做
生命周期测试，或在字体销毁/类加载器卸载时显式清理。

### 8.6 Icon-font 解析范围有限

当前支持 XML `app:code` 和两个常见嵌入路径。使用不同 XML 元素、字符映射表、
资源包拆分或运行时下载字体的库可能仍无法匹配。应该从解析结果建立统一的
`Typeface`/glyph fallback，而不是继续增加路径特例。

### 8.7 功能开关命名与默认值需要治理

四个 `MACAPK_EXPERIMENTAL_*` 开关已经是生产默认值，但名字仍带 experimental。
它们需要版本化的 feature gate，包含默认行为、稳定 fallback、自动诊断和未来
移除条件。仓库其他兼容逻辑也有大量 `MACAPK_*` 诊断开关，必须避免开关组合
形成不可测试的状态空间。

### 8.8 上游主线已分叉

本分支与 `origin/main` 各有提交。直接 rebase 会改变已部署验证的 base 和 build
ID，也会使提交冲突难以判断。应在新分支或专用合并分支上逐项整合上游的 4 个
README/CLA 改动，再重跑全套验证；不要把现有兼容分支 force-push。

### 8.9 测试矩阵仍偏窄

当前证据集中在用户验收的视频应用、NoNonsense Notes、WhatsApp、DialogFragment
测试和若干独立单元测试。还需要 Flutter、Unity、WebView 登录、相机、传感器、
后台 service 和不同 Android target SDK 的持续回归。

## 9. 后续升级路线

### 阶段 0：稳定当前分支

验收标准：

- 固定 `4706bf2` 的 build ID 和 app bundle 校验值；
- `OwnerFrameBarrierTest` 连续 100 次通过；
- Java/native 测试纳入一条可重复脚本；
- 记录 Dock、控制面板、Finder 的启动环境完全一致；
- 将 4 个稳定兼容 feature gate 做显式版本号和 fallback。

### 阶段 1：真正的 Surface identity 和 synthesized BufferQueue

目标是让每次 Android `Surface` 创建都拥有独立 producer identity，而不是从
“哪个 layer 没有绑定”反推。计划：

1. 在 Java 层给每个 producer-facing Surface 分配不可复用的 session ID；
2. native ANativeWindow 持有 session、consumer、格式、buffer geometry、generation；
3. 建立显式 BufferQueue 状态：dequeued、queued、acquired、released；
4. Metal/Cocoa consumer 通过 transaction 更新 frame、crop、transform、alpha、z-order；
5. 活动 destroy、Surface release 和 producer disconnect 都通过同一条生命周期路径；
6. 用多视频、视频+弹幕、相机预览、游戏 HUD 和 WebView overlay 做压力测试。

这是在现有 worktree 上最值得投入的架构升级，也会显著提高其他 Android 应用的
同屏多媒体成功率。

### 阶段 2：VideoToolbox 和 MediaCodec 完整化

1. 为 Annex-B、avcC、hvcC 建立逐格式 fixture；
2. 覆盖 H.264/HEVC 8-bit/10-bit、旋转、crop 和动态分辨率；
3. 验证 flush、seek、reconfigure、EOS、queued input/output depth；
4. 为 VP8/VP9 与硬件解码建立统一 capability 查询；
5. 加长时间播放的内存、线程和 frame-drop 观测；
6. 受保护视频只报告真实能力，不做静默降级。

### 阶段 3：Choreographer、窗口和 damage

1. 实现 delayed callback 的真实 deadline；
2. 把 owner frame、lifecycle、input、Surface transaction 分类排序；
3. 加嵌套 Looper、多 Activity、横竖屏和后台恢复压力测试；
4. damage 统一使用祖先 transform 和旧/新 bounds 合并；
5. 多显示器、窗口缩放和 different backing scale factor 纳入回归。

### 阶段 4：Binder 与多进程服务

1. 引入稳定 node handle、引用计数和 death notification；
2. 支持真实 transaction code、oneway 和同步返回值；
3. 加 fd 传递、权限检查和 sender identity；
4. 统一 `ServiceInfo.processName`、isolated process 和 manifest 声明；
5. 用本地 service、ContentProvider、BroadcastReceiver 和跨应用 launch 建立矩阵。

### 阶段 5：资源和主题系统收敛

1. 把 resource value 的 query ID、target ID、pool index、cookie 和 configuration
   建模成不可混用的对象；
2. 覆盖 alias、overlay、bag、private attr、style cascade 和 defStyleAttr；
3. 为 Material/AppCompat/Dynamic Color 建立 snapshot 测试；
4. 统一 vector tint、ImageView tint、drawable state 和 LevelListDrawable；
5. 自动检测缺失字体、icon-font 和 PUA glyph，不依赖开发者临时日志。

### 阶段 6：工程化与上游化

- 把实验开关改成带版本、owner、fallback、删除条件的 capability registry；
- 每个修复附最小 AOSP 依据、复现步骤、跨应用证据和自动化测试；
- 把只能依赖应用类名的诊断移出生产逻辑；
- 对生成文件、缓存、日志和二进制加入持续仓库卫生检查；
- 按可独立审查的主题拆成上游 PR，而不是继续扩大单一大型提交；
- 如果上游主线接受相关修改，优先在主线收敛，兼容分支只保留尚未合并的扩展。

## 10. 继续开发的标准流程

遇到新的 Android 应用失败时，按以下顺序处理：

1. 保存可重复输入、版本、target SDK、ABI、日志和首个异常栈。
2. 区分安装、DEX、Java Framework、native ABI、资源、窗口、输入、合成、网络和
   业务自身问题。
3. 找到 AOSP 或公开 Android API 的行为，写出可以独立运行的最小测试。
4. 在通用层实现最小修复，并保留一个临时 opt-out 开关用于 bisect。
5. 在原应用上验证第一个失败点消失，并检查没有引入帧卡住、黑屏、错误 alpha、
   错误线程或内存泄漏。
6. 在至少一个结构和原应用不同的应用中复测。
7. 把开关默认策略、诊断方法、风险和测试命令写回本手册。
8. 删除应用专用 workaround；如果无法删除，就明确标为调试策略，不能进入生产默认值。
9. 运行 `git diff --check`、构建、自包含审计和适用测试。

禁止把以下内容提交到 Git：

- 用户 APK、测试数据目录、数据库、Cookie、账号缓存和截图；
- 脱壳 DEX、重打包 APK、签名证书和 `signature-overrides`；
- `build/`、`runtime/build/`、translator build 和 framework-res 展开缓存；
- 含个人路径、设备状态或无法公开分发的日志；
- 只说明“某页面现在能打开”但没有通用原理解释的最终文档。

## 11. 仓库整理结果

本次整理完成了以下事项：

- 保留上游 `README.md`、`BUILDING.md`、`COMPATIBILITY.md` 和治理文档；
- 新增本手册，作为兼容分支的总设计、历史、测试、风险和路线记录；
- 删除 `README-COMPAT.md`，其首批补丁和旧安装方式已概括并更新到本手册；
- 删除 `artifacts/` 下三份阶段性诊断和 570 行旧 patch；
- 删除内容已由 Git 历史保留，可从提交 `e9040a2`、`6839007`、`a6ba981`
  追溯，不需要继续占用工作树；
- README 和 BUILDING 都增加了本手册入口；
- `.gitignore` 已排除构建目录、framework-res 展开缓存、release 凭据、签名文件、
  IDE 状态和日志；
- `build/`、`runtime/build/`、`runtime/translator/build/` 和
  `thirdparty/framework-res/` 是可再生本地产物，不应进入提交或发布分支。

如果以后要恢复旧报告，使用：

```bash
git show <旧提交>:<路径>
```

历史仍完整保存在提交图中，不需要把过期副本继续留在默认分支。

## 12. 常用诊断入口

最常用的日志：

```text
~/Library/Logs/macapk/
```

与本次能力最相关的开关包括：

| 开关 | 用途 |
|---|---|
| `MACAPK_VIDEO_FRAME_TRACE=1` | codec 帧、队列深度和排队状态 |
| `MACAPK_VIDEO_PRESENT_TRACE=1` | frame 到 Metal/Cocoa 的提交与展示 |
| `MACAPK_TRACE_SURFACE_DISPATCH=1` | Surface 回调分派线程与目标 |
| `MACAPK_TRACE_SURFACE_SIZE=1` | buffer 尺寸和显示矩形 |
| `MACAPK_TRACE_GLVIEWPORT_STACK=1` | GL viewport 压栈和恢复 |
| `MACAPK_TRACE_IMAGEVIEW_SET=1` | ImageView drawable 和 level 变化 |
| `MACAPK_TRACE_IMAGEVIEW_DRAW=1` | ImageView 实际绘制 |
| `MACAPK_TRACE_PUA=1` | Private Use Area 字体和字形匹配 |
| `MACAPK_TRACE_RESOLVEATTR=1` | theme/style/attr 解析链 |
| `MACAPK_THEME_APP_ATTR_CASCADE=0` | 关闭严格 app attr identity，做兼容性 bisect |
| `MACAPK_IMAGEVIEW_THEME_TINT=0` | 关闭 style/theme tint 读取 |
| `MACAPK_EXPERIMENTAL_NATIVE_SURFACE_LAYERS=0` | 回到旧共享 layer 路径 |
| `MACAPK_EXPERIMENTAL_NATIVE_SURFACETEXTURE=0` | 关闭 native SurfaceTexture 合成 |
| `MACAPK_EXPERIMENTAL_PRESERVE_SURFACE_ALPHA=0` | 关闭透明 Surface alpha 保护 |
| `MACAPK_EXPERIMENTAL_CHOREO_PIPE_WAKE=0` | 关闭 Choreographer native 唤醒 |

诊断 Surface 问题时同时记录 codec、present、size、z-order 和 owner 五类信息；
只看“是否仍在解码”无法判断屏幕是否被另一个透明或不透明层覆盖。

## 13. 交付检查单

- [ ] 了解该问题对应的 AOSP 契约，而不只是目标应用的调用栈。
- [ ] 没有新增包名、类名或资源 ID 的生产分支。
- [ ] 没有修改目标 APK 来完成应由 Framework 完成的行为。
- [ ] 有最小独立测试或明确的跨应用复现。
- [ ] 有可逆 feature gate 和可定位日志。
- [ ] 构建 ID 已更新，Java/native/launcher 产物一致。
- [ ] Dock、控制面板和直接运行三条路径都已验证。
- [ ] 已检查线程、Surface、EGL、Metal 和 Bitmap 的生命周期。
- [ ] 已运行 storage、依赖和 self-containment 审计。
- [ ] 已更新本手册的时间线、风险或路线，避免知识只留在聊天记录中。

这份手册应当随实现演进持续更新。任何与源码不一致的章节都应优先修正，而不是
在仓库中再增加一份新的临时说明。
