# CONTRACT-001：建立第一批可追溯离线契约测试

- 状态：DONE
- 前置：CAP-001、TEST-001、COMP-001、GAPLOG-001
- 证据目录：`work/upgrade/CONTRACT-001/`

## 目标与范围

在已有测试基础上增加少量高价值 Android 契约断言，建立“预期来自哪里”的记录。
产出 `docs/compat/CONTRACT_TESTS.md`、用例/运行入口及版本化已知差异清单。
本卡只建立验证，不随手修所有 FAIL；真实 Android 差分由 ORACLE-001 负责。

## 步骤

1. 从 GAPLOG-001 复现的高优先级缺口中选首批针对性用例，至少覆盖一个可用于首项修复的失败。
   Parcel/Bundle、Intent、Looper/Handler、资源和 ServiceManager 按问题实际影响选取。复用 `ParcelWireTest`、`ParcelBinderTransportTest`、`OwnerFrameBarrierTest` 等合适入口。
2. 每例标 AOSP tag/commit/路径或官方 API、适用 API/target SDK、输入、预期、harness 与 capability ID。
   CTS 用例只抽所需逻辑，保留来源/许可；SDK android.jar 的 stub 不作执行 oracle。
3. 正常、边界、无效参数、异常/事件顺序各有适用断言。用 fake clock 只能测受控逻辑，
   真实 Looper/JNI/显示相关用例明确所需 harness，不能伪装纯 JVM 通过。
4. 新断言在当前运行时 FAIL 时记录精确差异与 gap ID；保留真实 FAIL 结果，分离已知差异与新回归。
   不自动更新 expected 值，不复制被测实现生成 golden。
5. 校验工具链：注入一个确定断言失败、一项缺 fixture、一个输出格式破坏，入口应非零。
   如门禁容许固定已知差异，必须显式 pin 用例/预期/版本并报告它，新增差异仍非零。

## 验收

- [x] 首个高优先级复现有可重跑的确定断言，并包含适用的正常和边界对照。API 版本、结果与证据关联完整（`CTR-IPC-XFAIL-001` 等 7 项钉住 XFAIL + 7 项 PASS 见 [CONTRACT_TESTS.md](../compat/CONTRACT_TESTS.md) 与 [contract_baseline.json](../compat/contract_baseline.json)）。
- [x] harness 自检 PASS；已知产品差异与测试工具失败分开记录，不要求为了本卡把 runtime 全修绿（`scripts/run_contract_tests.py --self-test` 校验常态、严格模式 `--strict-xfail`、`--inject-regression`、缺失基线与 `--corrupt-output`）。
- [x] 未在 Android 上执行的用例标 NOT_RUN/android-compared=false；不称 CTS 认证（`android_compared: false`、`cts_certified: false`、`oracle_task: ORACLE-001 (DEFERRED)`）。
- [x] 缺陷进入能力台账，已有基础回归未被削弱（新增 4 项 `GAP-CONTRACT-*` 进入 [GAPS.md](../compat/GAPS.md) / [gaps.json](../compat/gaps.json) 与 [capabilities.json](../compat/capabilities.json)，并注册 `ContractSuiteRunner` / `ContractSuiteSelfTest` 至 `scripts/run_tests.py`）。

## 记录与回退

- **已完成**：新增 [ContractSuiteTest.java](../../debug/ContractSuiteTest.java)、[contract_baseline.json](../compat/contract_baseline.json)、[run_contract_tests.py](../../scripts/run_contract_tests.py) 与 [CONTRACT_TESTS.md](../compat/CONTRACT_TESTS.md)，更新 [GAPS.md](../compat/GAPS.md)、[gaps.json](../compat/gaps.json)、[capabilities.json](../compat/capabilities.json)、[CAPABILITIES.md](../compat/CAPABILITIES.md) 及 [run_tests.py](../../scripts/run_tests.py)，`runtime/` 与 `launcher/` 零修改。
- **回退**：撤销本卡测试/基线/文档变更即可完全回退（零运行时或安装态副作用）；`GAP-*` 与契约基线继续保留供 [ARCH-001](ARCH-001.md) 选择首项修复及 [AOSP-EVAL-001](AOSP-EVAL-001.md) 评估复用，真实 Android 对照仍由 [ORACLE-001](ORACLE-001.md) 负责。
