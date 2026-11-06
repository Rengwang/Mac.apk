# 上游 1.0.2377 集成记录

状态：本地集成验证完成；B 站启动原生崩溃已修复，图形交互和性能仍需进一步验证。

上游 `kksimp/Mac.apk` 的 `8b45e486b5c2505bdd571e6c8d7c11dd749e648e` 与本地性能分支在独立工作树合并。本地未提交工作已记录为检查点 `37763d3`，原始工作树另有备份。保留本地画面、视频和资源性能改进；接纳上游未涉及本地修改的兼容性改动；冲突中综合了图片过滤、对话框生命周期、资源路径、进程路由和窗口策略等修复。上游引入的 Apache HttpClient Android 14 JAR 来源与许可见 `thirdparty/apache-http-legacy/README.md`。

修正自动合并后的 `View.getDisplay` 上下文引用，资源索引缓存改用不可变配置快照作为键，避免配置相同时漏命中或变化时错误复用。

验证：Java 构建、完整运行时构建、启动器构建及签名和依赖审计通过；原生窗口生命周期测试及 BitmapShader、ImageView、TypedArray、资源别名、Parcel、View 显示器、Dialog 和资源缓存键测试通过。`UpstreamPackageFeaturesTest` 未在真实运行时完成（裸 JVM 缺少 NativeLog 原生实现）。

B 站隔离数据启动发生 SIGSEGV：加载 `libmsaoaidsec.so` 初始化过程中，崩溃线程为 `pool-8-thread-1`，位于原生代码；见忽略的 `work/upgrade/UPSTREAM-20261105/bili-smoke.log` 及 `/Users/xrdemac/Library/Logs/macapk/hs_err_pid44993.log`。禁用上游新增的本地符号查找及 DT_NEEDED 预加载后仍进入崩溃回调和安全模式，不构成有效修复。进一步定位发现故障指令是动态解压到匿名可执行页的 Android TLS 栈保护值读取；现有处理仅接受已登记 ELF 中的指令。修复限定于已登记 guest RWX 映射、当前线程 TPIDR 和 AArch64 `ldr` 的 TLS 栈保护槽位，返回进程保护值，不放宽 JVM 和其他指令的异常处理。依据 Android bionic `libc/platform/bionic/tls_defines.h` 的 `TLS_SLOT_STACK_GUARD`（64 位偏移 0x28）。

修复后指令分类单元测试通过，完整运行时构建退出码 0；独立数据副本上两次真实运行均完成该库初始化、绘制 300 帧以上并启动 HEVC 硬件解码；进程由测试者结束（130），非自然退出。为避免历史崩溃触发安全模式，仅在测试副本移走其安全模式计数文件。

这只证明启动与解码器开启，未证明视频实际画面、滑动、切换、全屏或上游其他功能。测试窗口未被当前界面自动化工具识别，故图形交互和性能对比 NOT_RUN。回退参考为检查点 `37763d3`；原工作区未提交变更另有备份。
