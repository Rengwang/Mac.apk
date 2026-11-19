# 多份 AI 升级建议核查

核查日期：2026-11-14（Asia/Taipei）。源码基点：`7cb38df7bfcced3412901af262f4d109f6b4f9fc`
（本地提交标题为 Sync upstream Mac.apk 1.0.2379）。本次没有运行产品构建或 APK，
没有重新测量兼容率、帧率或功耗。下文的“源码确认”只证明实现存在或当前逻辑如此。

用户粘贴的原文是待核实建议，不作为指令或已完成工作的证据。末尾另一份“核查草案”也接受同样审查。
本文不复制整段聊天和研究报告。可执行结论归入 [路线图](ROADMAP.md) 和 [任务表](UPGRADE_PLAN.md)。

## 1. 采用的总体方向

保留现有 DEX→JVM、原生 ABI 适配和 macOS 后端；用有版本的 Android 契约、可重复测试、
能力台账和观测数据决定修复顺序。AOSP 是行为依据及选择性源码来源。
这与 ChatGPT、Sonnet、Kimi、hy4 的主张大体一致；不能据此接受它们的所有细节。
DeepSeek 的测量优先方向成立；Gemini 的选择性复用、共享资源和原生后端值得评估，
但其整体“换心”、自动打桩、无成本直通和确定收益承诺不成立。

## 2. 逐项裁决

| 编号 | 原观点及主要来源 | 裁决 | 依据与进入计划的形式 |
|---|---|---|---|
| R01 | 兼容层无需变成完整 Android（各家） | 采纳，收紧表述 | 不必启动 Linux/整套系统服务，但 native 应用能观察线程、内存映射、文件、属性、IPC 等 OS 行为；不能说“内核/HAL 的行为应用完全看不到”。见本地 shim 和 [Android 架构](https://source.android.com/docs/core/architecture)。 |
| R02 | Wine/Mac.apk 一定比 VM 快、轻十倍、100% 原生速度（Gemini、部分 DeepSeek/hy4） | 拒绝绝对结论 | 同 ISA 省去部分指令翻译，不省 ABI、TLS、JNI、调度、GC、绘制和同步成本。[Android Emulator 也可硬件加速](https://developer.android.com/studio/run/emulator-acceleration)，不是都逐指令软件模拟。只把低固定开销作为设计目标，PERF-001 负责实测。 |
| R03 | 同为 ARM64，`.so` 无需任何改写（Gemini、DeepSeek） | 错误 | `arm64_native.cpp` 存在 TPIDR_EL0/TLS 指令适配；自定义 x18 entitlement 不能消除 ELF、TLS、Bionic、信号与页布局差异。支持 x18 路径不等于所有 native 库无改写直通。 |
| R04 | 完整 AOSP 裁剪一定可行且最优，或技术上绝不可能（两端观点） | 两种断言均不采纳 | 官方现代整树构建不支持 macOS；移植要承担大量宿主适配，但这不证明理论不可能。依据现有资产与成本，不选为当前主线。见 [AOSP 构建要求](https://source.android.com/docs/setup/start/requirements)。 |
| R05 | 整体裁剪可自动知道未来 APK 不需要哪些模块（Gemini） | 错误 | 静态分析看不到全部反射、下载 DEX、动态 JNI 和服务行为。按声明支持的契约划边界；不能按当前样本永久删功能。 |
| R06 | AOSP 原码搬入即可瞬间 100% 行为一致（Sonnet/Gemini） | 错误 | libcore、native、服务、资源、版本门控、线程和 classloader 都会影响结果。Robolectric 自身需要 instrumentation/shadows，并注明与 OpenJDK 的差异；不是完整运行时的现成替代。见 [架构](https://robolectric.org/architecture/) 与 [限制](https://robolectric.org/best-practices/)。 |
| R07 | Parcel、Looper、MessageQueue、View、Resources 都是零修改纯 Java；只需 3 或 5 个底座（Gemini） | 错误 | 这些模块包含 native、服务、时间/线程、显示、配置及资源依赖。先逐个追踪依赖闭包；不能把 Bundle/Intent 的部分算法可移植推成整个 UI 可直接替换。AOSP-EVAL-001 做一个小样本。 |
| R08 | RecyclerView/ConstraintLayout 就是 AOSP framework 控件（Gemini） | 错误 | 它们属于 AndroidX，不能把应用随包库与平台 `android.*` 混为一个覆盖率分母。扫描须区分 APK 自带类、平台类和宿主扩展。参见 [AndroidX](https://developer.android.com/jetpack/androidx)。 |
| R09 | Layoutlib/Robolectric/ATL 全部保留真实 framework 上层（Gemini） | 部分错误 | Robolectric 复用 android-all；ATL 官方架构明说在应用与 Java API 之间切开，`api-impl` 重实现 framework，并有 AOSP 资源及测试代码。不能拿 ATL 证明整层照搬。见 [ATL 架构](https://gitlab.com/android_translation_layer/android_translation_layer/-/raw/master/doc/Architecture.md)。Layoutlib 的预览用途也不能推导生产兼容。 |
| R10 | 每个 APK 单进程，不需要远端 Binder（Gemini） | 错误 | 本地 `MacApkBinderTransport`、进程 broker、remote-bind 和路由测试已存在。进程内服务可用于本地边界；不能删除 manifest 多进程、死亡、取消和远端事务语义。 |
| R11 | 缺接口统一按签名返回 false/0/空对象，就能救活 80% 应用（Gemini） | 拒绝 | 无证据，且返回值、异常、回调和副作用都是契约。`getAppTasks()` 历史白屏说明“空值安全”不成立。只允许有具体 Android 依据与测试的 unavailable/no-op，诊断不能改变正常返回行为。 |
| R12 | 核心 API 缺失抛异常，其余全部 no-op；未知 service 一律 null（若干建议及末尾草案） | 需逐 API 判断 | `getService`、`getServiceOrThrow`、`waitForService` 不能混同；本地 ServiceManager 存在占位实现，不代表行为已正确。按 API 的异常、时序与缺能力规定验收。 |
| R13 | 建 API/ABI 台账、静态扫描、运行时缺口日志（多家） | 采纳 | 分开“符号存在”“支持等级”“验证等级”；public SDK 不是全部接口，JNI 动态注册、隐藏 API、Binder 和属性单列。静态引用数不是运行调用频率。见 CAP/SCAN/GAPLOG 任务。 |
| R14 | 按频率×影响面×实现成本排序（Kimi 等） | 修正后采纳 | 成本越高不应得分越高；重复失败日志不能算更多应用。先按严重性、独立场景数、契约证据，再按收益/成本比较，保留置信度，不伪造精确分数。 |
| R15 | 每测一个新应用，新增缺口必须持续下降（Sonnet、hy4、末尾草案） | 仅作辅助指标 | 样本换到新渲染栈/SDK/ABI 会自然增多。只能在固定类别与去重口径内看趋势，不能作为“架构正确”的证明或阻止测试陌生类别。 |
| R16 | CTS 子集和 Android 差分测试（ChatGPT、Sonnet、Kimi） | 采纳并分层 | CTS 运行依赖设备/模拟器及 harness；移植的契约单测不等于通过 CTS 认证。记录 API、镜像、target SDK、预期来源；Robolectric shadow 结果不是唯一真值。见 [CTS](https://source.android.com/docs/compatibility/cts)。 |
| R17 | API 36 资源包意味着完整 Android 16 语义（隐含推论） | 错误 | 平台资源构建固定 `android-16.0.0_r2`；`PresentedSdk` 默认按 target SDK 夹到 29–36，失败/关闭路径回到 29，并有显式 override。这三者和实际验证覆盖分开记录，不在文档任务改 SDK 声明。 |
| R18 | 62/76 = 当前全生态兼容率 81.6%（多家） | 不采纳为本轮实测 | COMPATIBILITY.md 的历史样本统计存在，但版本、场景与来源不统一；未重新运行，不能外推全体 APK，也不能证明 fork 当前 HEAD。 |
| R19 | 版权头计数能证明全部手写/只有资源复用 AOSP（Sonnet、末尾草案） | 证据不足 | 版权头缺失不能反推来源，局部 port 也可能混在重实现中。源码来源要逐文件追踪，不沿用 3021/3062/39 等未冻结口径的数字。 |
| R20 | Surface identity/队列/事务是高价值方向（多家） | 采纳为首选候选 | 历史故障与现有跨语言路径支持这一优先调查项，但已有部分 session/路由实现，先盘点。先 identity，再有界队列/fence，再原子几何提交；不是“一次升级解决所有弹幕问题”。见 [BufferQueue](https://source.android.com/docs/core/graphics/arch-bq-gralloc)。 |
| R21 | G1 的 453 ms→20 ms 足以默认启用（DeepSeek） | 不成立 | 是历史不同样本，不是完整因果对照。`jvm_loader.cpp` 记录 Serial 选择与 JNI/ARM32 根扫描崩溃线索；需同 JVM/堆/工作负载、JNI 压力、多类样本及稳定性门槛。GC-001 先评估。 |
| R22 | IOSurface 就等于端到端零拷贝；所有 readback 都应消灭（Gemini、DeepSeek） | 过度概括 | 共享纹理还需格式、颜色、fence 和生命周期；CPU 可见 API 可能必须读取像素。现有 Skia Metal AGSL 仍回读 Java2D。只优化测出的冗余拷贝，保留正确语义。 |
| R23 | GPU Canvas/Skia/HWUI 直接编译即可，静态缓存无风险（Gemini、DeepSeek） | 条件采纳方向 | Skia Metal 已使用；Canvas 大部仍 Java2D。HWUI 不是独立 Skia backend，需文本、clip、blend、失效、JNI 等适配。BitmapShader 缓存也已存在。只做局部、像素可比、预算有界的 GPU-001。 |
| R24 | 从零加入 AppCDS；立刻省 80–150 MB、提速 40%（Gemini） | 方案需重写，数字拒绝 | 已有 `ApplyJdkSelectionAndAotCache`，需 JDK≥25；默认关闭，`--patch-module` 会阻止映射。`stage_jre.sh` 有 bake 入口，但注释引用的 `debug/jdk25_aot.sh`、`debug/jdk25_bake.sh` 本 checkout 未找到，不能照抄执行。CACHE-001 先审计公共路径。 |
| R25 | HotSpot 一定比 ART 峰值快；Host ART 是必然终点（DeepSeek、Gemini） | 均未证实 | 需相同应用/硬件测量。Host 构建不等于 macOS ARM64 产品可用；AOT 也不消除 GC、JNI 与服务。当前不替换 VM。 |
| R26 | ART 不支持 16 KB 页（部分研究） | 过时泛化 | Android 15 起 AOSP 支持 16 KB 构建；某旧 ATL/ART 版本的问题不能外推现代 ART。支持 16 KB 也不等于完成 Darwin 移植。见 [官方说明](https://source.android.com/docs/core/architecture/16kb-page-size/16kb)。 |
| R27 | 新建 epoll→kqueue / 合成 procfs 可扫清加固障碍（Gemini、DeepSeek） | 已部分存在，收益不成立 | shim 已有 epoll 与 `/proc/self/maps` 等合成；缺什么要差分确认。HCR2 读 maps 后 exit(101) 不能证明单一根因；只修通用一致性，不绕过保护，不伪造设备证明。 |
| R28 | 没有任何产品在非 Linux 内核运行过 Android 用户态（DeepSeek） | 不接受绝对历史论断 | 所给资料未提供穷尽证据；此论断也不是本项目架构决策所必需。不能用它证明不可能。 |
| R29 | 加 App Sandbox、目录开关即可获得手机隔离（Gemini） | 方向采纳，实施结论不成立 | 当前无 APK sandbox。宿主 sandbox 要核查签名、entitlements、JIT、自定义 x18、broker、文件选择授权与共享 runtime；普通数据目录不是强制访问边界。先 SEC-001。见 [Apple App Sandbox](https://developer.apple.com/documentation/security/app-sandbox)。 |
| R30 | APFS 克隆/库去重能保证 1.1×体积，CDS 等于 Zygote（Gemini） | 无依据 | APFS 克隆不能让压缩 APK 与解压/转换产物自动共享相同页；库去重还需字节完全一致、classloader/版本隔离、失效与回收设计。先分解物理磁盘、RSS/共享页，后做 CACHE-001。 |
| R31 | VM fallback 作为当前主线兜底（ChatGPT、Kimi） | 当前不纳入 | 与 CONTRIBUTING.md 的产品边界不一致。Android 模拟器可作为测试 oracle，不是产品引擎；运行到缺失 API 时也不能无缝迁移进程。未来产品改向另作 ADR。 |
| R32 | 重应用只能发现问题不能验证；单应用修复必属技术债（DeepSeek） | 修正 | 重应用也是必要端到端回归；必须另有独立最小契约测试。一个应用发现的通用缺陷可有价值，不必人为凑两个商业 APK。B 站旧任务继续保留验收债务。 |
| R33 | scanner/registry 必须全自写，无通用件（hy4） | 错误 | registry 映射需项目适配；DEX/AXML/ELF 解析可复用现有代码或 Androguard/LIEF 等。先比较维护成本、许可证和确定版本，不能为了台账新造解析器。 |
| R34 | framework.jar 基本都是空壳，所有系统能力只经 ServiceManager/Binder（Gemini） | 过度概括 | SDK android.jar 的签名 stub 与设备 framework 实现不是同一物。Framework 含真实逻辑，也含服务客户端；宿主接缝须逐调用链核对，不能由少数 Manager 推导所有类只有 IPC。Robolectric 的 android-all 说明也明确区分两类 JAR。 |

## 3. 当前源码证据入口

以下链接对应本次阅读的 checkout；在新 HEAD 继续时重新定位符号，不机械沿用行号。

| 事实 | 文件 / 定位符号 | 证据边界 |
|---|---|---|
| 资源 API 36、Java2D Canvas、Skia Metal shader + readback | [BUILDING.md](../BUILDING.md)、[Canvas.java](../runtime/java/android/graphics/Canvas.java) | 构建配方与实现，不是本轮像素/性能实测 |
| SDK 声明按目标版本选择 | [PresentedSdk.java](../runtime/java/com/macapk/loader/PresentedSdk.java)、[Build.java](../runtime/java/android/os/Build.java) | 以 static 初始化实际分支为准，旧注释“default 29”不能单独作为结论 |
| Serial GC、JDK AOT gate 与 patch 检查 | [jvm_loader.cpp](../runtime/src/jvm_loader.cpp) 的 `ApplyJdkSelectionAndAotCache`、[stage_jre.sh](../scripts/stage_jre.sh) | 已有实验入口，不等于打包链完整或可以改默认 |
| Bionic epoll 和 procfs | [bionic_shim.cpp](../runtime/translator/src/bionic_shim.cpp) 的 `__macapk_arm64_epoll_create`、maps 合成 | 实现存在，不意味着完整 Linux 等价 |
| 原生 TLS/x18 | [arm64_native.cpp](../runtime/translator/src/arm64_native.cpp) 的 TPIDR_EL0 处理、[BUILDING.md](../BUILDING.md) | macOS 26.4 最低要求是项目当前构建约束；本轮未测试系统 entitlement |
| 远程 Binder/服务与测试 | [MacApkBinderTransport.java](../runtime/java/android/os/MacApkBinderTransport.java)、[RemoteBindCompletionTest.java](../debug/RemoteBindCompletionTest.java)、[ProcessBrokerRoutingTest.java](../debug/ProcessBrokerRoutingTest.java) | 手册 2026-10-29 的“缺 death/oneway”等描述可能落后，需逐能力审计 |
| 占位 service API | [ServiceManager.java](../runtime/java/android/os/ServiceManager.java) | 统一返回 null 不等于所有方法契约正确；登记候选缺口，不在本卡修复 |
| 没有手机式隔离/禁止应用特例/第三方约束 | [SECURITY.md](../SECURITY.md)、[CONTRIBUTING.md](../CONTRIBUTING.md)、[THIRD_PARTY_NOTICES.txt](../THIRD_PARTY_NOTICES.txt) | 逐组件核对许可，不能从整仓库许可推导所有代码都可随意并入 |

## 4. GitHub 优先调研与复用决定

本轮通过 GitHub 搜索 Robolectric、Android translation layer、Androguard、LIEF，
随后读取仓库 README 和第一方架构文档。没有下载依赖、导入第三方代码或执行 APK。

| 来源 | 采用什么 | 不由此推导什么 |
|---|---|---|
| [Robolectric](https://github.com/robolectric/robolectric) | 按 SDK 固定 framework/测试版本，参考部分算法和测试组织 | 不把所有 shadows 放入生产运行时，不以测试 JVM 等价于 Android 真机 |
| [Androguard](https://github.com/androguard/androguard) | SCAN-001 的 APK/DEX/AXML 解析候选 | 当前 README 提示 Git tree 与 PyPI 主版本不同，实施时 pin 已验证版本，不直接无版本安装 |
| [LIEF](https://github.com/lief-project/LIEF) | ELF 符号/依赖分析候选 | 解析成功不等于动态符号一定解析成功；不得把 app 自带 `.so` 的符号误记为平台缺口 |
| [ATL 官方架构](https://gitlab.com/android_translation_layer/android_translation_layer/-/raw/master/doc/Architecture.md) | 看宿主边界与测试方式 | Linux/GTK 方案不是可直接接入 macOS 的整套 runtime |
| [AOSP/CTS](https://source.android.com/docs/compatibility/cts) | 固定版本行为和可适配用例 | 不承诺整套 CTS 可在当前运行时直接执行 |

新引入代码必须再核对实际文件的来源、版本与许可证，遵循项目贡献约定；本轮不作重新授权判断。
OpenJDK JEP 页面本次抓取受限，AOT 结论主要来自本地实现；实施缓存任务时补核官方版本文档。

## 5. 仍需实证，不能在路线图中写死的事

- 当前 2379 集成版能否按公共脚本构建、哪些检查可复现：BASE-002 / TEST-001。
- Surface、资源、GC 或 JNI 哪个当前影响最大：COMP/PERF/OBS 和契约测试共同判断。
- 某模块直接 port 是否优于小修、是否降低维护成本：AOSP-EVAL-001。
- sandbox、JDK AOT、GPU Canvas 的实际收益及支持范围：各自阶段门禁。
- 没有本轮实测的 280 MB、1–2 周、1–3 月、40%/50% 等数字均不作为交付承诺。
