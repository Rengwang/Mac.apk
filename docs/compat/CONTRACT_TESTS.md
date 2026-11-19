# Android API 契约测试套件说明 (`CONTRACT-001`)

- **测试套件源码**：[ContractSuiteTest.java](../../debug/ContractSuiteTest.java)（纯 Java / `bare_jvm` 运行时对照，无 `macapk-loader.jar` 或 JNI 依赖）
- **基线配置文件**：[contract_baseline.json](contract_baseline.json)
- **执行器与自检脚本**：[run_contract_tests.py](../../scripts/run_contract_tests.py)
- **总注册与 CI 门禁入口**：[run_tests.py](../../scripts/run_tests.py)（注册为 `ContractSuiteRunner` 与 `ContractSuiteSelfTest`，归属 `bare_jvm` / `offline` 组）
- **关联台账**：[CAPABILITIES.md](CAPABILITIES.md) (`CAP-001`)、[GAPS.md](GAPS.md) / [gaps.json](gaps.json) (`GAPLOG-001`)

## 1. 上游参考来源、许可与 Oracle 边界

- **上游契约来源**：AOSP `android-16.0.0_r2`（API 36，Apache-2.0 许可）。所有断言按 `android-16.0.0_r2` 公开文档化 API 契约以 clean-room 方式编写（在 `contract_baseline.json` 中逐条记录对应 `aosp_source`），**未复制 CTS 源码**，亦**不将 SDK `android.jar` stub 作为执行 oracle**。
- **对比状态声明（诚实标注）**：
  - `android_compared`: **`false`**
  - `cts_certified`: **`false`**
  - `oracle_task`: **`ORACLE-001 (DEFERRED)`** — 尚无真实 Android 设备/模拟器对照通道（`ORACLE-001` 仍为 `DEFERRED`），因此本套件验证的是“Mac.apk 现行 `android-framework.jar` 行为与文档化 AOSP `android-16.0.0_r2` 契约的一致性及已钉住偏差基线”，不宣称通过 CTS 或真机 oracle 对照。

## 2. 离线契约矩阵（14 项：`13 PASS + 1 XFAIL`）

### 2.1 当前一致（`expected_status = PASS`，13 项）

| Contract ID | 能力 ID | 目标 API / SDK | AOSP 参考锚点 | 验证要点 |
|---|---|---|---|---|
| `CTR-PARCEL-001` | `IPC-001` | API 29..36 | `frameworks/base/core/java/android/os/Parcel.java` | 原生类型/数组/Bundle/Binder/EOF 截断安全默认与超限 `IOException` |
| `CTR-BINDER-001` | `IPC-002` | API 29..36 | `frameworks/base/core/java/android/os/Binder.java` | 本地接口快速路径、`transact()` `reply` 游标归零、`clearCallingIdentity`/`restoreCallingIdentity` |
| `CTR-BUNDLE-001` | `IPC-001` | API 29..36 | `frameworks/base/core/java/android/os/Bundle.java` | `Bundle.EMPTY` 不可变、Parcel 往返（不含非空 `PersistableBundle`）、单对象 `getParcelable(k, Class)` 类型过滤 |
| `CTR-INTENT-001` | `RES-001` | API 29..36 | `frameworks/base/core/java/android/content/Intent.java` | `filterEquals`、`fillIn`、`parseUri(..., URI_INTENT_SCHEME)` 剥离 grant flags、类型化 `getParcelableArrayListExtra` |
| `CTR-FILTER-001` | `RES-001` | API 29..36 | `frameworks/base/core/java/android/content/IntentFilter.java` | `typeOnly` 对 `null`/`content` 与 `http` 区分、空迭代器 `null`、空 scheme `NPE`、非法 MIME `MalformedMimeTypeException` |
| `CTR-PM-MATCH-001` | `RES-001` | API 29..36 | `frameworks/base/core/java/android/os/PatternMatcher.java` | `PATTERN_LITERAL` / `PATTERN_PREFIX` / `PATTERN_SUFFIX` / `PATTERN_SIMPLE_GLOB`（`*` 与 `.*`） |
| `CTR-AUDIO-001` | `MED-003` | API 29..36 | `frameworks/base/media/java/android/media/AudioTrack.java` | 合法参数 `getMinBufferSize(48000, STEREO, PCM_16BIT)` 返回正值（实测 `4800`）与 `getMinVolume`/`getMaxVolume` |
| `CTR-IPC-XFAIL-001` | `IPC-004` | API 29..36 | `frameworks/base/core/java/android/os/ServiceManager.java` | **`PILOT-001` 已修复**：`ServiceManager.getServiceOrThrow` 对不存在服务抛出 `ServiceNotFoundException` |
| `CTR-BUNDLE-XFAIL-001` | `IPC-001` | API 33..36 | `frameworks/base/core/java/android/os/Bundle.java` | **`PILOT-001` 已修复**：API 33 `getParcelableArrayList`/`getParcelableArray`/`getSerializable`/`getSparseParcelableArray` 执行 `clazz.isInstance` 校验 |
| `CTR-INTENT-XFAIL-001` | `LIFE-001` | API 29..36 | `frameworks/base/core/java/android/content/Intent.java` | **`PILOT-001` 已修复**：`Intent.parseUri(..., URI_ANDROID_APP_SCHEME)` 解析 `android-app://` 包名与内部 data URI |
| `CTR-PM-XFAIL-001` | `RES-001` | API 26..36 | `frameworks/base/core/java/android/os/PatternMatcher.java` | **`PILOT-001` 已修复**：`PATTERN_ADVANCED_GLOB` 支持字符集/量词/语法校验并修正 `SIMPLE_GLOB` 转义 `\.` |
| `CTR-AUDIO-XFAIL-001` | `MED-003` | API 29..36 | `frameworks/base/media/java/android/media/AudioTrack.java` | **`PILOT-001` 已修复**：`AudioTrack.getMinBufferSize` 对非法采样率/声道/编码返回 `ERROR_BAD_VALUE (-2)` |
| `CTR-MEDIA-XFAIL-001` | `MED-002` | API 29..36 | `frameworks/base/media/java/android/media/MediaExtractor.java` | **`MEDIA-001` 已修复**：`MediaExtractor` 校验非 `file://` 抛出 `IOException`，`getTrackFormat/selectTrack/unselectTrack` 越界校验抛出 `IllegalArgumentException` |

> 说明：现有基础契约回归（`ParcelWireTest`、`ParcelBinderTransportTest`、`OwnerFrameBarrierTest`、`ResourceIndexCacheKeyTest`、`UpstreamResourceAliasTest`、`MediaCodecDequeueTest`、`CodecObsTest` 等）保留在 `scripts/run_tests.py` 的 `offline` 组内未削弱，与本套件共同运行。

### 2.2 显式钉住预期失败（`expected_status = XFAIL`，1 项）

每项均绑定具体 `gap_id`，校验时必须精确失败并命中 `expected_failure_substr`。若未来修复其中某项却未同步更新基线，Runner 将报告 `XPASS` 并以非零退出；传入 `--strict-xfail` 时任何 `XFAIL` 亦导致非零退出。

| Contract ID | Gap ID | 能力 ID | 目标 SDK | 实测偏离现象（匹配子串） | 归属后续任务 |
|---|---|---|---|---|---|
| `CTR-IPC-XFAIL-002` | `GAP-IPC-002` | `IPC-002` | API 29..36 | `Binder.getCallingUidOrThrow()` 在非事务上下文返回 `10000` (`outside transaction`) | `IPC-001` |

## 3. 延迟到真运行时 / GPU / 硬件夹具的契约

下列条目明确保留在独立 harness，不伪装成 `bare_jvm` 离线通过：

- `RemoteServiceProcessTest` (`IPC-003` / `GAP-IPC-003`)：需 `macapk-runtime` + `macapk-system` broker 才能验证跨进程 `:worker` 隔离；离线 `offline` 组维持原有范围。
- `gpu_pixel`（7 项，`SURF-002` / `GAP-GFX-001`）：需带窗口与 Metal/ANGLE/Sidecar 的 GUI 环境。
- 原生音视频硬件解码（`MED-001` / `GAP-MED-002`）：`MediaCodecDequeueTest` 覆盖 Java 侧有界队列契约，AudioToolbox / VideoToolbox 硬件管线需真实 `macapk-runtime`（如 `VideoImageSelfTest` / `AMEDIA_SELFTEST`）。

## 4. 负向故障注入与防假绿自检 (`--self-test`)

`python3 scripts/run_contract_tests.py --self-test` 验证了 4 类防假绿门禁（已注册为 `ContractSuiteSelfTest` 随 `offline` 与 `ci_gate.sh` 自动执行）：

1. **基线常态检查**：7 `PASS` + 7 `XFAIL` 精确匹配，`REGRESSION=0`、`XPASS=0` → `rc=0`。
2. **`--strict-xfail` 严格模式**：任何 `XFAIL` 存在即非零退出（`rc=1`），防止把预期失败误报为全量兼容。
3. **注入回归 (`--inject-regression`)**：破坏 `CTR-PARCEL-001` 返回值后检测到 `REGRESSION` 并返回 `rc=1`。
4. **缺失基线与格式损坏 (`--corrupt-output`)**：基线不存在返回 `rc=2`；JSON 截断或无输出返回 `rc=3`。

## 5. 给 `AOSP-EVAL-001` 与 `ARCH-001` 的输入接口

- **`AOSP-EVAL-001` 重点评估候选**：`PatternMatcher`（`GAP-CONTRACT-PM-001`，纯 Java 且无 Mac 宿主耦合，适合评估直接引入 AOSP 上游类与测试）、`Intent` URI 解析（`GAP-CONTRACT-INTENT-001`）、`Bundle` API 33 校验（`GAP-CONTRACT-BUNDLE-001`）。
- **`ARCH-001` 重点修复输入**：`ServiceManager.getServiceOrThrow` + `ServiceNotFoundException`（`GAP-IPC-001`，score=27）、`Binder.getCallingUidOrThrow`（`GAP-IPC-002`）、`PatternMatcher(PATTERN_ADVANCED_GLOB)`、`AudioTrack.getMinBufferSize` 与 `MediaExtractor` 边界；每修复一项，先将对应 `XFAIL` 在 `contract_baseline.json` 中显式翻转为 `PASS`（否则 CI 将报错 `XPASS`）并重新跑通全量 `offline` 门禁。
