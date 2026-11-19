# ADR-0003：AOSP 单模块源码复用与边界评估 (`AOSP-EVAL-001`)

- 日期：2026-11-14
- 状态：Proposed（`AOSP-EVAL-001` 评估完成：**局部 GO** — `android.os.PatternMatcher` `PATTERN_ADVANCED_GLOB` 子算法；其余高耦合/小修复模块为 **NO-GO** 或条件抽取）
- 基础提交：`9978a0c80eb9321c9808bf321aab4f991e4bb29f`
- 关联任务：[AOSP-EVAL-001](../tasks/AOSP-EVAL-001.md)
- 依据文档：[CAPABILITIES.md](../compat/CAPABILITIES.md)、[CONTRACT_TESTS.md](../compat/CONTRACT_TESTS.md)、[GAPS.md](../compat/GAPS.md)、[ADR-0001](0001-contract-driven-runtime.md)、[ADR-0002](0002-sandbox-and-isolation-boundaries.md)
- 隔离实验目录：`work/upgrade/AOSP-EVAL-001/`（未改动 `runtime/` 与 `launcher/`）

## 1. 评估目标与选取原则

根据 [ADR-0001](0001-contract-driven-runtime.md) 与 [AOSP-EVAL-001](../tasks/AOSP-EVAL-001.md)，从 `CAP-001` / `CONTRACT-001` 已钉住的实际行为缺口中选取低依赖、高收益、纯 Java 的算法/子模块，严格对比“引入 AOSP 上游实现”与“保留 Mac.apk 自研并做通用最小修复”。

- **不为了替换而替换**：仓内 [SparseArray.java](../../runtime/java/android/util/SparseArray.java) 与 `ArrayMap.java` 已从 `android-11.0.0_r48` 移植（见 `SparseArray.java:2`），且 `CONTRACT-001` 未报缺口，直接跳过。
- **不将未核对第三方代码链接进交付产物**：本评估仅在隔离测试目录 `work/upgrade/AOSP-EVAL-001/` 使用 clean-room 原型对照现行 `android-framework.jar`，生产 classpath 与构建产物零变动。

## 2. 主选评估对象：`android.os.PatternMatcher`（`PATTERN_ADVANCED_GLOB`）

### 2.1 来源、许可与依赖闭包

| 维度 | 核查结论 |
|---|---|
| 上游参考路径 | `frameworks/base/core/java/android/os/PatternMatcher.java`（fixed AOSP tag：`android-16.0.0_r2`，API 36） |
| 许可与声明 | Apache-2.0（AOSP `frameworks/base/NOTICE` / Apache-2.0 头）；移植时须保留文件级来源 tag 与 Apache-2.0 声明 |
| 现行实现与缺口 | [PatternMatcher.java](../../runtime/java/android/os/PatternMatcher.java)（166 行）：`:95-99` 将 `PATTERN_ADVANCED_GLOB` (`3`) 退化调用 `matchGlobPattern()`，`:22-25` 构造函数不做语法校验（对应 `GAP-CONTRACT-PM-001` / `CTR-PM-XFAIL-001`） |
| 依赖闭包 | **100% 纯 Java**，仅依赖同包 `android.os.Parcel`、`android.os.Parcelable` 与标准库 `java.util.Arrays`；**零 JNI/native 调用、零 `libcore` 私有依赖、零 `@hide` 跨模块调用** |
| 仓内宿主接缝 | 1. [PathPermission.java](../../runtime/java/android/content/pm/PathPermission.java#L20) 继承 `PatternMatcher` 并经 `PatternMatcher(Parcel)` (`PatternMatcher.java:32-35`) 反序列化；<br>2. [WifiNetworkSpecifier.java](../../runtime/java/android/net/wifi/WifiNetworkSpecifier.java#L45) 调用非 AOSP 兼容别名 `getPattern()`（`PatternMatcher.java:48`），**必须保留**；<br>3. [IntentFilter.java](../../runtime/java/android/content/IntentFilter.java#L397-L453)、[Manifest.java](../../runtime/java/com/macapk/loader/Manifest.java#L1683) 与 [UriRelativeFilter.java](../../runtime/java/android/content/UriRelativeFilter.java#L41) 已解析/构造 `PATTERN_ADVANCED_GLOB` |

### 2.2 隔离测试与同条件对照（`work/upgrade/AOSP-EVAL-001/PatternMatcherEval.java`）

在保留 `runtime/build/lib/android-framework.jar` 原样不变的前提下，于 `work/upgrade/AOSP-EVAL-001/` 编译并对比 Mac.apk 现行 `android.os.PatternMatcher` 与 clean-room 线性扫描 token 原型（`CleanRoomAdvancedMatcher`），覆盖 `LITERAL`/`PREFIX`/`SIMPLE_GLOB`、字符集 `[0-9]+`、否定字符集 `[^/]+`、量词范围 `[a-f]{2,4}`、转义点号 `v1\.0`、3 组非法语法异常及 `PathPermission` Parcel 往返（日志见 `work/upgrade/AOSP-EVAL-001/eval.log`）：

| 用例 | Mac.apk 现行行为 | 原型预期行为 | 结论与偏差定位 |
|---|---|---|---|
| `literal_hit` / `prefix_hit` / `simple_glob_dotstar` | `true` / `true` / `true` | `true` / `true` / `true` | 保留原逻辑，无回归 |
| `adv_digits_plus` (`[0-9]+` vs `"42"`) | `false` | `true` | 修复 `CTR-PM-XFAIL-001` 主失败 |
| `adv_neg_slash` / `adv_neg_slash_reject` (`/items/[^/]+/edit`) | `false` / `false` | `true` / `false` | 正确支持路径段排除匹配 |
| `adv_quant_range_{ok,short,long}` (`[a-f]{2,4}`) | `false` / `false` / `false` | `true` / `false` / `false` | 正确支持最小/最大重复次数 |
| `adv_escaped_dot_miss` (`v1\.0` vs `"v1X0"`) | **`true`（误匹配）** | `false` | 暴露现行 `matchGlobPattern` `:153` 检查 `c != '.'` 未排除 `escaped=true` 缺陷 |
| `adv_malformed_bracket` / `brace` / `dangling_plus` (`[0-9`, `a{2`, `+abc`) | 返回 `PatternMatcher` 对象，匹配返回 `false` | 构造期抛出 `IllegalArgumentException` | 对齐 AOSP `parseAndVerifyAdvancedPattern` 编译期校验契约 |
| `PARCEL PathPermission` | 往返保留路径/别名，`match('/items/42')=false` | 序列化 token 数组后恢复匹配 | 需同步在 `writeToParcel`/`PatternMatcher(Parcel)` 读写 `mParsedPattern`（`writeIntArray`/`createIntArray`） |
| 100k 次热路径匹配（`[a-z0-9]+` vs `len=19`） | `2,871,209 ns`（`hits=0`，首字符不匹配直接退出） | `8,704,916 ns`（`hits=100,000`，~`87 ns/call`） | 无正则回溯爆炸风险，线性可预测 |

### 2.3 代码规模与维护成本对比

- **完全手写 `ADVANCED_GLOB` 状态机 vs 引入 AOSP `PatternMatcher` 算法**：二者改动范围均集中在单文件 `PatternMatcher.java`（现行 166 行；完整 AOSP token 编译与匹配约增加 220–260 行纯 Java 代码，不新增任何类或依赖）。直接移植 AOSP `android-16.0.0_r2` 的 `parseAndVerifyAdvancedPattern` / `matchPattern` 子算法并保留 `getPattern()` 别名，比从零手写更不易遗漏转义/范围/量词边界。

## 3. 候选方案 GO / NO-GO 决策矩阵

| 候选模块 / 缺口 | 现行代码锚点与规模 | 耦合与阻塞分析 | 决策 | 推荐去向 |
|---|---|---|---|---|
| `android.os.PatternMatcher` (`GAP-CONTRACT-PM-001`) | [PatternMatcher.java](../../runtime/java/android/os/PatternMatcher.java)（166 行） | 纯 Java，仅 `Parcel`/`Parcelable`/`Arrays`；无 JNI/libcore；保留 `getPattern()` 即可兼容 `WifiNetworkSpecifier` | **GO**（限单模块子算法移植，保留宿主接缝） | 转 [ARCH-001](../tasks/ARCH-001.md) 评估列为小修/移植候选，修完翻转 `CTR-PM-XFAIL-001` |
| `Intent.parseUri` `URI_ANDROID_APP_SCHEME` (`GAP-CONTRACT-INTENT-001`) | [Intent.java](../../runtime/java/android/content/Intent.java#L1550-L1693)（全类 2,126 行） | 全类重度耦合 `ActivityManager`/`UserHandle`/`ClipData` 仅做局部不可整类替换；但 `android-app://` URI 解析子函数约 60 行纯字符串逻辑 | **NO-GO（整类） / CONDITIONAL GO（~60 行子算法）** | 仅移植/补齐 `android-app://` 解析分支，不动 `Intent` 其余构造体 |
| `Bundle` / `BaseBundle` API 33 类型校验 (`GAP-CONTRACT-BUNDLE-001`) | [Bundle.java](../../runtime/java/android/os/Bundle.java#L290-L309)（314 行）+ [BaseBundle.java](../../runtime/java/android/os/BaseBundle.java)（398 行） | `BaseBundle` 绑定 Mac.apk 既有 Parcel wire 与类加载器接缝；所需修复仅为 `clazz.isInstance` 要素检查（~20 行） | **NO-GO**（改用本地 ~20 行通用小修） | [ARCH-001](../tasks/ARCH-001.md) 直接在 `Bundle.java:294-309` 补类型校验 |
| `ServiceManager` (`GAP-IPC-001`) | [ServiceManager.java](../../runtime/java/android/os/ServiceManager.java)（34 行） | AOSP 上游依赖 `IServiceManager` AIDL、`BinderInternal`、原生 servicemanager 进程；Mac.apk 所需仅为新增 `ServiceNotFoundException` 与 `getServiceOrThrow` 抛出逻辑（~15 行） | **NO-GO**（改用本地 ~15 行通用小修） | [ARCH-001](../tasks/ARCH-001.md) 首选小修候选 |
| `AudioTrack` / `MediaExtractor` / `MediaCodec` (`GAP-MED-001..002`, `GAP-CONTRACT-AUDIO-001`) | `AudioTrack.java`（1,349 行）、`MediaExtractor.java`（319 行） | 直接绑定 macOS CoreAudio / AudioToolbox / VideoToolbox / FFmpeg JNI 与 `HONEST_OPEN` 模型，整类移植无法编译或破坏音视频后端 | **NO-GO**（在现行封装补参数/边界检查） | 在 `AudioTrack.getMinBufferSize` 与 `MediaExtractor` 边界做本地通用修复 |
| `SparseArray` / `ArrayMap` | [SparseArray.java](../../runtime/java/android/util/SparseArray.java)（368 行） | 已自 `android-11.0.0_r48` 完整移植，无已观测契约缺口 | **NO-GO**（无需重复移植） | 保持现状 |

## 4. 迁移与回退方案（供 `ARCH-001` / `PILOT-001` 使用）

- **小范围落地条件（`ARCH-001` 若选中 `PatternMatcher`）**：
  1. 仅引入或实现 `PATTERN_ADVANCED_GLOB` 编译与匹配子算法（基于 AOSP `android-16.0.0_r2` Apache-2.0，保留头部来源 tag 与 NOTICE 归属），**保留** `PATTERN_SUFFIX`、`PathPermission` 所用的 `PatternMatcher(Parcel)` 及 `WifiNetworkSpecifier` 所用的 `getPattern()` 别名。
  2. 同步修正 `matchGlobPattern()` 在 `c == '.'` 处未判断 `!escaped` 的顺带问题（`v1\.0` 应只匹配 literal `.`）。
  3. 在 [contract_baseline.json](../compat/contract_baseline.json) 将 `CTR-PM-XFAIL-001` 翻转为 `PASS`，并补充对 `IntentFilter`、`PathPermission` Parcel 往返、`WifiNetworkSpecifier` 及异常输入的回归断言。
  4. 跑通 `scripts/ci_gate.sh` 与 `ContractSuiteRunner`。
- **回退方案**：`PatternMatcher` 为单一纯 Java 文件且无原生 ABI 符号变更；回退仅需 `git revert` 该提交（恢复原 `PatternMatcher.java` 与 `contract_baseline.json` 中 `CTR-PM-XFAIL-001`），不影响 native 二进制或已安装应用数据结构。

## 5. 未知项、限制边界与 Oracle 声明

- 本评估仅验证并限定于 `PatternMatcherEval.java` 中测试的 15 项契约输入、`PathPermission` Parcel 往返及静态依赖扫描，**不外推至整个 `android.os` 或 `frameworks/base` 其他类**。
- `android_compared = false`、`cts_certified = false`（真机同 API 差分由已延后的 `ORACLE-001` 负责）；若上游 `PatternMatcher` 在 Unicode 代理对（surrogate pair）或极端超长量词上存在特定截断细节，将在后续引入真实文件核对并交由 `ORACLE-001` 二次比对。
