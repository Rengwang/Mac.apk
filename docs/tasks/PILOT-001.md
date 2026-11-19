# PILOT-001：首批 Framework API 契约修复（Batch 1）

- 状态：DONE
- 前置：ARCH-001、TEST-001
- 证据目录：`work/upgrade/PILOT-001/`

## 目标与修复范围

针对 [CONTRACT-001](CONTRACT-001.md) 与 [ADR-0003](../adr/0003-aosp-module-evaluation.md) 已锁定的 5 项纯 Java 框架契约缺口实施最小通用修复，并将 `docs/compat/contract_baseline.json` 中对应 5 项 `XFAIL` 翻转为 `PASS`：

1. **`ServiceManager.getServiceOrThrow` (`GAP-IPC-001` / `CTR-IPC-XFAIL-001`)**：在 `runtime/java/android/os/ServiceManager.java` 新增 `public static class ServiceNotFoundException extends Exception`，当 `getService(name) == null` 时抛出 `new ServiceNotFoundException(name)`。
2. **`Bundle` API 33 类型安全读取 (`GAP-CONTRACT-BUNDLE-001` / `CTR-BUNDLE-XFAIL-001`)**：在 `runtime/java/android/os/Bundle.java` 的 `getParcelableArrayList(k, Class)`、`getParcelableArray(k, Class)`、`getSerializable(k, Class)`、`getSparseParcelableArray(k, Class)` 中校验返回值与容器元素的 `clazz.isInstance(...)`，类型不匹配时按 AOSP 契约返回 `null`（不泄露不兼容元素）。
3. **`PatternMatcher` `PATTERN_ADVANCED_GLOB` 与转义点号 (`GAP-CONTRACT-PM-001` / `CTR-PM-XFAIL-001`)**：在 `runtime/java/android/os/PatternMatcher.java` 实现 `PATTERN_ADVANCED_GLOB` 语法校验（非法字符集/量词抛 `IllegalArgumentException`）、字符集 `[...]`/`[^...]`、`+`/`*`/`{m,n}` 匹配与 `Parcel` 往返，修正 `matchGlobPattern` 中转义 `\.` 误匹配任意字符的问题，并保留 `getPattern()` 别名（供 `WifiNetworkSpecifier` 使用）及 `PatternMatcher(Parcel)` 构造器（供 `PathPermission` 使用）。
4. **`Intent.parseUri` `URI_ANDROID_APP_SCHEME` (`GAP-CONTRACT-INTENT-001` / `CTR-INTENT-XFAIL-001`)**：在 `runtime/java/android/content/Intent.java` 支持解析 `android-app://<package>[/<scheme>[/<host><path>]][#Intent;...;end]`，提取目标 `package` 与内部 data URI。
5. **`AudioTrack.getMinBufferSize` 参数校验 (`GAP-CONTRACT-AUDIO-001` / `CTR-AUDIO-XFAIL-001`)**：在 `runtime/java/android/media/AudioTrack.java` 校验 `sampleRateInHz`（`4000..192000`）、`channelConfig` 与 `audioFormat`，非法参数返回 `ERROR_BAD_VALUE` (`-2`)。

## 允许修改文件

- `runtime/java/android/os/ServiceManager.java`
- `runtime/java/android/os/Bundle.java`
- `runtime/java/android/os/PatternMatcher.java`
- `runtime/java/android/content/Intent.java`
- `runtime/java/android/media/AudioTrack.java`
- `docs/compat/contract_baseline.json`、`debug/ContractSuiteTest.java`（如需补充断言）
- 台账与状态文件：`docs/compat/GAPS.md`、`docs/compat/gaps.json`、`docs/compat/capabilities.json`、`docs/compat/CAPABILITIES.md`、`docs/compat/inventory.json`、`docs/tasks/PILOT-001.md`、`docs/UPGRADE_PLAN.md`、`PROJECT_STATE.md`

## 构建与验收命令

1. 离线构建：`MACAPK_NO_BUNDLE_SYNC=1 runtime/scripts/build.sh`
2. 契约回归：`python3 scripts/run_contract_tests.py --out-dir work/upgrade/PILOT-001`（预期 `PASS=12, XFAIL=2, XPASS=0, REGRESSION=0`，`rc=0`）与 `python3 scripts/run_contract_tests.py --self-test`（`rc=0`）
3. 离线总门禁：`python3 scripts/run_tests.py --group offline --strict --out-dir work/upgrade/PILOT-001/run_tests_offline`（`FAIL=0, SKIP=0`，`rc=0`）

## 验收与回退

- [x] 上述 5 项契约用例由 `XFAIL` 转为 `PASS`（`PASS=12, XFAIL=2, XPASS=0, REGRESSION=0`），`run_contract_tests.py` 与 `run_tests.py --group offline --strict` 全部 `rc=0`。
- [x] 无包名/类名特例，不触碰 `/Applications/Mac.apk.app` 或共享 runtime。
- **回退**：`git revert` 本卡提交并重跑 `MACAPK_NO_BUNDLE_SYNC=1 runtime/scripts/build.sh`。
