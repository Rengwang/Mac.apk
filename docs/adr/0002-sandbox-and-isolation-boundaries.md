# ADR-0002：Mac.apk 安全边界现状与隔离（App Sandbox）可行性评估

- 日期：2026-11-14
- 状态：Proposed（SEC-001 评估完成；**NO-GO**：当前架构不可直接开启 macOS App Sandbox）
- 基础提交：`1ffda6f82a58915c3540b47679f572d087e26777`
- 关联任务：[SEC-001](../tasks/SEC-001.md)
- 依据文档：[SECURITY.md](../../SECURITY.md)、[ADR-0001](0001-contract-driven-runtime.md)、[BASELINE_BUILD_CURRENT.md](../BASELINE_BUILD_CURRENT.md)

## 1. 问题与评估范围

`SECURITY.md` 明确说明 Mac.apk 运行的 Android 应用本质上是持有当前 macOS 用户账户权限的普通原生与 JVM 代码，**目前不存在手机式 APK 沙箱**。随工程推进，需要把“开发工作区约束”“APK 逻辑路径布局”与“OS 强制隔离”严格区分开，并核实 macOS App Sandbox（`com.apple.security.app-sandbox`）及关联机制是否可作为后续隔离方案，明确记录技术约束、未验证组合与 go/no-go 条件。

本 ADR 仅做现状审计、边界验证与可行性决策，**不改变默认签名、entitlements 或运行模式，也不宣称已实现任何 OS 级 APK 隔离**。

## 2. 三层权限与访问边界（现状严格区分）

| 层次 | 载体与依据 | 实际作用域 | 是否构成 APK 隔离 |
|---|---|---|---|
| 1. Codex / Agent 开发工作区策略 | `AGENTS.md` 约定与开发工具沙箱 | 仅约束开发期 Agent 编辑范围与禁触用户敏感文件 | **否**（对运行中的 APK 零约束） |
| 2. APK 逻辑数据布局与 C 层路径重映射 | `InstallCore.swift` (`AppDataLayout`)、`bionic_shim.cpp` (`macapk_arm64_remap_path_unguarded` / `macapk_store_guard`) | 重写 `/data/data/<pkg>`、`/data/user/0/<pkg>`、`/sdcard/` 等逻辑路径；屏蔽 `/System/Library/CoreServices` 与 `appops/` 目录 | **否**（进程内 C shim 逻辑拦截；未识别绝对宿主路径直接透传 `return s;`，且可被 Java `java.io.File`/NIO、inline `svc #0` 与符号链接绕过） |
| 3. 操作系统强制隔离 | XNU / Sandbox / TCC / AMFI（`SECURITY.md` + 实测签名清单） | 无 App Sandbox；仅存在用户账户级 POSIX DAC、Hardened Runtime（放开 JIT/未签名可执行内存/禁库校验）及共享 `org.macapk.*` 的 TCC 授权 | **无 APK 间及 APK-宿主 OS 强制隔离** |

### 无敏感 Canary 验证结果（SEC-001 实证）

在隔离临时目录（`HOME=/tmp/macapk_sec001_fakehome_*`，`/tmp/macapk_sec001_canary_*.txt`，不触及任何用户文档、浏览器配置、密钥或 `/Applications/Mac.apk.app` 数据）针对 `1ffda6f82a58915c3540b47679f572d087e26777` 编译的 `libmacapk-bionic-shim.dylib` 调用 `__macapk_arm64_fopen` 验证：

1. **未识别宿主绝对路径透传**：通过 C shim 打开 `/tmp/macapk_sec001_canary_*.txt` 返回 `OPEN_OK`（读取到 37 字节 dummy 文本），实证 `bionic_shim.cpp:43997` (`return s;`) 对普通宿主绝对路径不做边界阻断。
2. **Android 逻辑路径重映射**：打开 `/data/data/com.example.sec001canary/files/canary.txt` 写入并读回，真实落盘于临时 `android_root/data/data/...` 下。
3. **宿主 OS 版本文件屏蔽**：探测 `/System/Library/CoreServices/SystemVersion.plist` 被 `MACAPK_HIDE_HOST_OS_VERSION` 改写至 `android_root` 下不存在路径，返回 `ENOENT`（`errno=2`）。
4. **`appops` 存储防护与同 UID 绕过对比**：针对临时 fake home 下的 `Library/Application Support/Mac.apk/appops/canary_appops.json`，C shim `__macapk_arm64_fopen` 被 `macapk_store_guard` 重定向至 `/var/empty/.macapk-denied/x` 并返回 `ENOENT`；而同进程直接 `std::fopen`（对应 guest Java 文件 I/O 或 inline `svc #0` 绕过路径）可直接读出 dummy JSON 内容。

## 3. 现状签名、Entitlements 与进程模型事实

根据 `runtime/scripts/jit-entitlements.plist`、`scripts/build_launcher.sh`、`scripts/build_dist.sh`、`launcher/BundleGenerator.swift`、`launcher/AppStubMain.swift` 及对本机 `/Applications/Mac.apk.app` 的 `codesign -dv` / `codesign -d --entitlements :-` 只读核验（全部为 `Signature=adhoc`、`TeamIdentifier=not set`）：

1. **共享 entitlements 清单（`runtime/scripts/jit-entitlements.plist`，共 8 个 key，无 `com.apple.security.app-sandbox`）**：
   - `com.apple.security.cs.allow-jit`
   - `com.apple.security.cs.allow-unsigned-executable-memory`
   - `com.apple.security.cs.disable-library-validation`
   - `com.apple.security.custom-x18-abi-toggle`
   - `com.apple.security.device.audio-input`
   - `com.apple.security.device.camera`
   - `com.apple.security.personal-information.location`
   - `com.apple.security.device.bluetooth`
2. **携带 Hardened Runtime（`flags=0x10002(adhoc,runtime)`）与全部 8 项 entitlement 的组件**：
   - `Mac.apk.app`（`org.macapk.launcher`，Runtime Version `27.2.0`）
   - `Contents/Resources/macapk-runtime`（`org.macapk.runtime`，内嵌 `__TEXT,__info_plist` `runtime/macapk-runtime-info.plist`，被暂存至 `~/Library/Application Support/Mac.apk/runtime/Resources/macapk-runtime`）
   - `Contents/Helpers/macapk-system`（`org.macapk.system`，承载 `libjvm.dylib` 运行 `SystemServer`，分别以 `org.macapk.system` 与 `--app-processes` `org.macapk.processes` 注册）
   - `Contents/Helpers/Mac.apk Location.app`（`org.macapk.location`，`LSUIElement=true`，通过 `CLLocationManager` stdin/stdout 桥接位置）
   - `Contents/bin/macapk-translator`、`Contents/bin/macapk-elfexec`（Runtime Version `27.2.0`）
   - `Contents/Resources/jre/bin/java`、`Contents/Resources/jre/lib/jspawnhelper`（Runtime Version `27.0.0`）
3. **无 entitlements 的二进制**：
   - `Contents/bin/macapk-installhelper`：`flags=0x10002(adhoc,runtime)`，无 entitlements；对 `--source android` 的安装/卸载强制执行 `LAContext(.deviceOwnerAuthentication)`（`InstallHelperMain.swift:282-412`），并用 ECDSA P-256 签写 `app-ops.json`（`InstallCore.swift:6718-6789`）。
   - `Contents/Resources/appstub/MacApkAppStub`（`org.macapk.appstub`）：在发布打包路径 `build_dist.sh:379` 签为 `flags=0x10002(adhoc,runtime)`、无 entitlements（`build_launcher.sh:157` 仅 ad-hoc 签名）。
4. **生成应用包（`<Label>.app`，`CFBundleIdentifier = org.macapk.app.<pkg>`）**：
   - 由 `BundleGenerator.swift:467-470` 在安装解包后本地执行 `/usr/bin/codesign --force --deep --sign - <bundle>`（ad-hoc、无 `--options runtime`、无 entitlements），并清除 quarantine。后续运行时写入仍可能使 bundle seal 失效（`BundleGenerator.swift:455-461` 已记录并接受一次通过 Gatekeeper 评估后的运行行为）。
   - 启动时 `AppStubMain.swift:1225-1268` 通过 `Contents/MacOS/.<Label>` 扁平符号链接直接 `execv()` 共享的 `~/Library/Application Support/Mac.apk/runtime/Resources/macapk-runtime`。
5. **共享数据目录与 LaunchAgent 机制**：
   - `AppDataLayout`（`InstallCore.swift:426-462`）将 `<App>.app/Contents/android` 指向 `~/Library/Application Support/MacApk/<folder>`，再由 `data/app` 指回 `<App>.app/Contents/app`；共享状态集中于 `~/Library/Application Support/Mac.apk/`（`installed_apps.json`、`runtime/`、`run/`、`launchd/`、`appops/`）。
   - `ProcessBrokerLaunchAgent.swift:59-149` 优先尝试 `SMAppService.agent`（`org.macapk.system.plist` / `org.macapk.processes.plist`），在本地 ad-hoc 构建返回 `-67028` 等错误时回退写入 `~/Library/Application Support/Mac.apk/launchd/*.plist` 并调用 `launchctl bootstrap`。

## 4. 威胁模型与边界矩阵

| 资产 / 边界 | 预期或假定语义 | 当前实际执行机制 | 越界 / 绕过向量 |
|---|---|---|---|
| 宿主用户文件（文档、凭证、浏览器配置等） | APK 仅读写自身 `data/data` 与模拟 `sdcard` | 仅依赖 macOS 同用户 POSIX DAC（无沙箱） | 未识别绝对路径在 `bionic_shim.cpp:43997` 直接透传；guest Java I/O、inline `svc #0`、symlink 可直接读写 |
| `appops/app-ops.json` 与签名公钥 | 仅 `macapk-installhelper` / 控制面板授权写入 | C shim `macapk_store_guard` 路径拦截 + ECDSA 绑定字段 | `signing-key.pub` 位于同目录；guest Java I/O、inline `svc #0` 或 symlink 可替换公钥并自签（`bionic_shim.cpp:42920-42929` 已明示非边界） |
| `macapk-installhelper --source android` | 必须经机主 Touch ID / 密码确认 | Helper 内 `LAContext` 校验并拒绝 `--no-ui` | 同 UID 进程可直接修改 `~/Library/Application Support/Mac.apk/` 或启动宿主脚本工具 |
| 跨进程 Binder / Broker IPC（`org.macapk.system`, `.processes`） | 按应用/包区分调用方身份 | Mach bootstrap 注册表 + 线协议 header `senderUid` | 任意同用户进程均可 `bootstrap_look_up` 并在消息头伪造 `senderUid`（`SystemServer.java:88-90`、`MacApkBinderTransport.java:30-32` 已注明待后续绑定 PID） |
| TCC 敏感设备权限（相机/麦克风/定位/蓝牙） | 按 Android 应用单独授权 | 绑定于宿主可执行身份 `org.macapk.runtime` / `org.macapk.location` | 任意一个 APK 获准 TCC 后，所有共用该 runtime/helper 二进制的 APK 均继承 macOS TCC 授权 |
| 代码完整性（`W^X` 与 guest `.so`） | Hardened Runtime + `jit-entitlements` | 内核 `W^X`（`PROT_EXEC` 与 `PROT_WRITE` 互斥） | `allow-unsigned-executable-memory` + `disable-library-validation` 允许 `mprotect(RX)` 与加载未签名/异团队 `.dylib`/`.so`；`jit_wx_guard.cpp` 使用 `dlopen_from` SPI 与 `fishhook` |

## 5. App Sandbox（`com.apple.security.app-sandbox`）兼容性与约束核实

以下严格区分**已有官方平台资料/源码实证约束**与**尚未实测的组合（需独立试验验证）**：

1. **HotSpot JVM JIT（`allow-jit` + `allow-unsigned-executable-memory`）**：
   - **依据**：OpenJDK `jdk.jpackage` 公开配置将 `com.apple.security.app-sandbox` 与 `com.apple.security.cs.allow-jit`、`com.apple.security.cs.allow-unsigned-executable-memory` 组合用于 `MAP_JIT` 与 `pthread_jit_write_protect_np`。
   - **待实测**：`jit_wx_guard.cpp` 在 macOS 27 硬件 TPRO 下的 `dlopen_from` + `fishhook` hook 在 App Sandbox profile 下是否仍保持正常。
2. **`com.apple.security.custom-x18-abi-toggle`**：
   - **依据**：本地 Xcode SDK（`MacOSX27.2.sdk/usr/include/os/arch/arm64.h:45-141`）声明 `os_set_custom_x18_abi_enabled(bool)` 为 `API_AVAILABLE(macos(26.4))`，并规定开启后禁止调用任何 macOS 框架/POSIX 接口。由于 `bionic_shim` 频繁调用 `libSystem`，`arm64_native.cpp:228-373` 改用直接置位 `TPIDR_EL0` bit 48，并依赖 `com.apple.security.custom-x18-abi-toggle`。
   - **待实测（关键未知）**：当二进制同时携带 `com.apple.security.app-sandbox`（尤其叠加 Developer ID / Notarization）时，XNU / AMFI 是否继续允许 `com.apple.security.custom-x18-abi-toggle` 并保留 `TPIDR_EL0` bit 48，尚未在本机独立沙箱实验环境中实测。
3. **Guest arm64 `.so` 动态加载、ARM32 AOT 与 `guest_jit_pages`**：
   - **依据**：Mac.apk 的 ARM32 路径（`arm32rc_driver.cpp`）生成静态 Mach-O dylib 并 `dlopen`，arm64 ELF loader（`arm64_native.cpp`）与 guest JIT（`guest_jit_pages.h`）依赖 `mmap` + `mprotect(RX)` 与 `dlopen`。
   - **约束**：在 App Sandbox 下，`dlopen` 只能映射沙箱容器内、bundle 内或经 security-scoped bookmark 授权路径上的 Mach-O；且 Apple Silicon 仍要求 `dlopen` 目标至少具备 ad-hoc 签名并依赖 `com.apple.security.cs.disable-library-validation`（容器外任意路径直接被 sandbox file-read/map 拦截）。
4. **文件选择授权、`com.apple.security.inherit` 与 Security-Scoped Bookmarks**：
   - **依据（Apple 官方 App Sandbox 文档）**：`com.apple.security.inherit` 仅继承父进程静态沙箱权限，**不会跨子进程继承动态 PowerBox / `NSOpenPanel` / security-scoped bookmark 授权**；且携带 `com.apple.security.inherit` 的子可执行体不得附带其他沙箱 entitlements。
   - **冲突**：当前 `AppStubMain.swift` 使用 `execv()` 跳转到 bundle 外部的共享 `macapk-runtime`（不走受控的 XPC/scoped helper），而 `document_picker.mm` 直接在 runtime 进程内拉起 `NSOpenPanel`。若引入 App Sandbox，文档选择与 bookmark 持久化必须由有权进程通过 XPC/IPC 显式传递 bookmark data 或由沙箱宿主代持。
5. **生成 App Bundle 身份（`org.macapk.app.<pkg>`）与容器分裂**：
   - **约束**：每个 `<Label>.app` 拥有独立的 `CFBundleIdentifier` 且在用户机器本地 ad-hoc 签名。开启 App Sandbox 后，macOS 会为每个 `CFBundleIdentifier` 分配隔离的 `~/Library/Containers/<bundleID>/`，导致当前共用的 `~/Library/Application Support/Mac.apk/`（共享 runtime、注册表 `installed_apps.json`、`run/`、`MacApk/<folder>`）及 `<App>.app/Contents/android` 跨目录符号链接全部失效，且 ad-hoc 签名的生成包无法通过同一 `Team ID` 加入 App Group。
6. **LaunchAgent / `SMAppService` / Process Broker IPC**：
   - **依据**：`ProcessBrokerLaunchAgent.swift` 在 ad-hoc 环境下因 `SMAppService` 报错（`-67028`）回退 `launchctl bootstrap`。
   - **约束**：在 App Sandbox 下，任意向 `org.macapk.system` / `org.macapk.processes` 发起 `bootstrap_look_up` 会被沙箱阻止，除非主应用、XPC/LaunchAgent 具备同一 **Team ID**、匹配的 `com.apple.security.application-groups` 且服务名按 Apple 规则绑定 App Group / bundle 内 XPC Service。当前本地 ad-hoc 生成的 per-app `.app` 无法共享该 Team ID。

## 6. 决策：Go / No-Go 与后续路线

### 决策结论

1. **当前架构直接开启 App Sandbox：NO-GO**。
   在不重构“ad-hoc 生成独立 `CFBundleIdentifier` app + `execv` 外部共享 runtime + 共享 `~/Library/Application Support/Mac.apk/` + 无 TeamID `launchctl` 回退”的条件下，强行加上 `com.apple.security.app-sandbox` 会直接破坏 runtime 启动、共享数据/APK 访问、Broker Mach IPC 与动态库加载，且尚未验证 `custom-x18-abi-toggle` 与沙箱并存行为。
2. **保持诚实边界声明**：继续维持 `SECURITY.md` 的明确告知，不以“后续规划”对外宣称 Mac.apk 已具备沙箱隔离。

### 独立沙箱试验（SEC-002 候选 Spike）的 GO 条件

只有在满足以下前置条件时，才建议另立隔离试验分支开展 `SEC-002` 原型验证（严禁污染日常构建与默认签名路径）：

1. **内核能力最小探针（先于架构改造）**：构建独立最小测试二进制（不改动 `runtime/scripts/jit-entitlements.plist`），在 macOS 26.4+/27 上以 `com.apple.security.app-sandbox` + `allow-jit` + `allow-unsigned-executable-memory` + `disable-library-validation` + `custom-x18-abi-toggle` 签名，实测：
   - `TPIDR_EL0` bit 48 跨阻塞/上下文切换是否存活（`x18` 哨兵不归零）；
   - `guest_jit_pages` `mprotect(RX)` 与 `jit_wx_guard` `dlopen_from` 在沙箱 profile 下是否工作；
   - 是否存在 Developer ID / 公证与 `custom-x18-abi-toggle` + `app-sandbox` 的签名拒绝。
2. **身份与存储架构方案明确**：在试验前确定二选一的隔离拓扑并写明 Team ID 签名约束：
   - **方案 A（统一宿主运行器 / XPC 化）**：由同一 Team ID 签名的 `Mac.apk.app` 内嵌或通过 App Group 托管 runtime 与 Broker XPC Service，`document_picker` 等宿主能力经 XPC 代理并显式传递 security-scoped bookmarks；
   - **方案 B（单 App 隔离容器 / 轻量 Seatbelt 替代）**：评估在无 App Store / 无全局容器分裂的前提下，使用受控子进程模型或独立容器化设计对第三方/不受信 APK 进行隔离，并明确各应用 TCC 身份归属问题。

## 7. 不依赖 App Sandbox 的纵深防御 Backlog（独立后续任务）

以下改进可在不引入 App Sandbox 的前提下独立立项、单独验收（它们提升局部抗篡改与最小权限卫生，但**仍不构成 OS 级 APK 沙箱**）：

1. **SystemServer / `MachBinder` 调用方身份校验**：在 `MacApkBinderTransport` / `SystemServer.java` 利用 Mach audit trailer PID（启动记录绑定）核验 `senderUid` 与包身份，消除同机进程通过 header 伪造 `senderUid` 的跨应用越权路径（当前 `SystemServer.java:88-90` 已标注为待办）。
2. **`bionic_shim` / Java I/O 非边界说明对齐**：在文档和相关测试（如 `appops` / `SpecialAccessStore`）中保持一致声明，避免将 C shim 逻辑路径规则误当作安全边界。
3. **TCC 与 Helper 权限边界细化**：审视 `Mac.apk Location.app` 与 `macapk-runtime` 共享 TCC 身份的提示文案与运行时内部开关（如按应用包名二次门控），并在真正实现 per-app 进程/容器身份之前明确告知“macOS 系统级 TCC 授权按宿主组件共享”。
4. **独立只读基线回归门禁**：将 `debug/entitlements_check.sh` 与三层边界检查纳入安全基线回归清单，防止构建回归静默丢失 `custom-x18-abi-toggle` 或意外扩大默认权限。

## 8. 影响与回退

- 本 ADR 仅记录评估结论与后续条件，未修改任何源码、构建脚本、签名 plist、正式安装 `/Applications/Mac.apk.app` 或共享运行时 `~/Library/Application Support/Mac.apk/runtime`。
- 若未来 `SEC-002` 实测推翻或确认上述 `custom-x18-abi-toggle` / `app-sandbox` 组合行为，应通过新 ADR 或修订本 ADR 的状态与实测表更新结论。
