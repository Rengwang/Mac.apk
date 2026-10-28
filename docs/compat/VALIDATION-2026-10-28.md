# 兼容性实验验收记录（2026-10-28）

本次提交是当前开发源码快照。**弹幕尚未修复，最终组合源码尚未完成运行验收，也不等于电脑上已安装应用的版本。** B 站仅作为复现应用；Surface、透明合成、引用计数和调度修改位于框架层，没有修改其 APK。

## 实际完成的验证

| 项目 | 结果与证据范围 |
| --- | --- |
| Java 框架、原生 runtime、Bionic shim 构建 | 开发工作树中的对应构建通过。原生编译、链接仍有警告；不代表全新环境完整构建通过。 |
| `debug/native_window_lifetime_test.py` | 在本次发布源码副本重新执行通过。提取实际 shim 函数，用计数式 JNI/Cocoa 替身测试；覆盖默认单例引用替换、实验句柄身份、重复取得引用、8 线程各 200 次取得/持有/释放、最后一次释放、无 key 回退及创建失败清理。未运行真实 JVM、EGL 或 GPU。 |
| `debug/SurfaceMetalLayerAlphaTest.mm` | 在发布源码副本严格编译后通过，0 failures。覆盖透明格式保护、驱动设置 opaque、格式切换及 `initWithLayer:` 拷贝；没有测试真实 Vulkan swapchain。运行环境出现 IOSurface 事件监听警告，测试退出状态为 0。 |
| 独立视频实验 | 较早的隔离构建启用按 Surface 分层及 alpha 保护后，保持 Vulkan 后端，测试视频 `BV1xx411c7mD` 画面可见；约 279 秒后主动停止，VideoToolbox 的 rendered 计数持续增加。该实验早于最终引用计数等修改，不能作为当前全部源码的运行验收。 |
| 原生帧回调诊断 | 记录到实际注册、刷新提升、回调进入及返回，间隔约 101–103ms；原生叠加层仍只观察到一次 swap。证明调度继续运行，但未证明弹幕数据已经取得或绘制。 |

`debug/DrawableIdentityAndTintTest.java` 和 `debug/ViewAttachedDisplayTest.java` 也包含在源码中；它们没有在本次发布副本中重新执行，不能据此将图标、显示器或所有资源布局问题标为通过。

## 可以复现的命令

在仓库根目录执行。以下两个独立测试已针对发布副本实际执行；需要 Python 3、Clang 和 macOS SDK：

```sh
python3 debug/native_window_lifetime_test.py
validation_dir="$(mktemp -d)"
clang++ -std=c++20 -Wall -Wextra -Werror -fno-objc-arc \
  -framework Foundation -framework QuartzCore -framework Metal \
  debug/SurfaceMetalLayerAlphaTest.mm \
  -o "$validation_dir/surface-alpha-test"
"$validation_dir/surface-alpha-test"
```

下列目标已在具备依赖且完成 CMake 配置的开发工作树中构建成功。发布副本不包含该工作树的构建缓存；首次配置及依赖安装应遵循项目构建文档：

```sh
MACAPK_NO_BUNDLE_SYNC=1 bash debug/build_java_only.sh
cmake --build runtime/build/native --target macapk-runtime -j 2
cmake --build runtime/translator/build --target macapk-bionic-shim -j 2
```

**构建副作用：** `runtime/scripts/build.sh` 默认可能把新产物同步进已安装的应用包。本轮曾因遗漏关闭同步而影响正式应用。研究、构建和测试时务必设置 `MACAPK_NO_BUNDLE_SYNC=1`，使用独立测试包和数据副本；上述编译成功不授权覆盖正式安装或用户数据。

## 仍然关闭的实验

| 开关 | 用途与限制 |
| --- | --- |
| `MACAPK_EXPERIMENTAL_NATIVE_SURFACE_LAYERS=1` | 为 SurfaceView 的原生消费者提供不同句柄和 Metal 图层；引用计数修复仅在此实验开启时生效。尚未完成真实 EGL 生命周期回归。 |
| `MACAPK_EXPERIMENTAL_PRESERVE_SURFACE_ALPHA=1` | 在首次 drawable 分配前保护透明 holder 的 alpha，避免 Vulkan 路径覆盖透明属性；需配合上一实验，尚未验收为正式方案。 |
| `MACAPK_NATIVE_CHOREO_TRACE=1` | 只诊断注册、队列提升和实际回调执行，不改变唤醒行为。 |
| `MACAPK_EXPERIMENTAL_ALPHA_PROBE=1` | 诊断格式、图层和采样；含少量 GL 回读，不用于性能结论。 |

不要把层放到视频下面、全局改用 Metal、只有硬解计数增长或只加载弹幕相关类，当作弹幕修复成功。`MACAPK_EXPERIMENTAL_SURFACE_BELOW_VIDEO` 仅用于遮挡对照，不能显示位于视频下面的弹幕。

## 后续阻塞项与风险

- **弹幕缺失仍未定位完整链路。** 数据获取、应用选择是否绘制、原生调度和最终合成需要分别取证。
- 原生 Choreographer 将回调提升为 ready 时只通知条件变量；带 FD 的 looper 实际阻塞在 `poll`，因而可能等待约 100ms。唤醒管道修复尚未实施；延迟回调 API 忽略 delay 的问题也仍存在。
- 原生窗口引用计数已通过替身测试，但还需真实应用验证：最后一个 ANativeWindow 释放后 EGLSurface 仍存活、反复创建/销毁、页面切换和失效 Surface。Java Surface 在原生引用存活期间仍被有意保留。
- 按 Surface 分层仍与全局原生窗口 owner、挖孔、缓冲几何及 EGL 状态共存。窗口缩放、全屏进出、隐藏/恢复、显示器迁移、多消费者及旧图层清理尚未完成回归；句柄宽高同步也需继续审核。
- 资源与字体改动含尚未完整验证的 icon-font 库路径选择。返回图标曾在隔离构建恢复，但“编辑”前加号、设置布局和跨应用通用性不能统一标为已解决。
- 源码保留此前 AVC 参数集解析修复（包含同一 CSD 同时携带 SPS/PPS，以及 avcC/hvcC 支持）。本次没有以新完整产物重新跑完横竖屏、不同编码及全屏回归，也没有完成与原生应用的公平性能对比。

## 源码与正式安装的差异

正式安装曾被恢复到较早备份，未精确恢复到本轮开始前已修过部分视频的版本；它可能缺少当前源码中的 CSD 等修复。较早的“正式应用未改动”记录已被后续构建同步事故取代。当前源码、隔离实验产物和正式安装必须分别标识，不可根据其中一个的表现宣布另一个验收通过。

本次不发布用户 APK、账号数据或原始运行日志。测试结论仅限上文列出的检查；没有宣称弹幕恢复、最终安装已更新或资源占用达到原生水平。
