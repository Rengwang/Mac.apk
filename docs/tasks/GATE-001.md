# GATE-001：记录功能开关并核对启动入口一致性

- 状态：DONE
- 前置：CAP-001、TEST-001
- 证据目录：`work/upgrade/GATE-001/`

## 目标与范围

产出 `docs/compat/FEATURE_GATES.md` 及可校验的 gate 数据，关联 capability ID。
首轮不改默认、不全量改名、不删除 fallback；不能把“开关存在”算“能力已验证”。

## 步骤

1. 盘点 `MACAPK_*`/DEX gate 在 runtime、launcher、stub、broker、launchd 的读取与继承位置。
2. 记录默认、读取时机（进程初始化/运行时）、类型、相互依赖、owner、适用版本、测试、回退/删除条件。
   优先 SDK、Surface/IOSurface、缓存、GC/JVM、broker、旧 experimental 默认项。
3. 核对已安装启动与独立 runtime 的环境形成规则。用隔离测试验证默认值/opt-out，
   不修改用户 launchctl 环境；安装态尚未测必须标 NOT_RUN。
4. 区分诊断 gate、实验实现 gate 和对外 capability；不强行收进一个 runtime registry 导致热路径开销。
5. 将不一致登记新缺口卡；未来默认化需要对应性能/兼容门槛，不随本卡切换。

## 验收

- [x] 首批高风险 gate 有确定默认和读取路径；同名异义/未知默认显式列出。
- [x] 初始化敏感开关用新进程验证，不能在同 JVM 改 env 伪造覆盖。
- [x] 没有修改默认值、正式环境或 SDK 申报行为；适用本地检查 PASS。

## 记录与回退

已完成（基线 `304eb3297d31f69ac4cd2b4b25c6e56f2b60793b`）：

- 交付物：
  - [`docs/compat/feature_gates.schema.json`](../compat/feature_gates.schema.json)
  - [`docs/compat/feature_gates.json`](../compat/feature_gates.json)（75 个优先开关、148 处源码读取点、4 个启动入口基线、5 项入口/注释不一致 `GAP-GATE-001`..`005`）
  - [`docs/compat/FEATURE_GATES.md`](../compat/FEATURE_GATES.md)
  - [`scripts/validate_gates.py`](../../scripts/validate_gates.py)
  - [`debug/FeatureGatesTest.py`](../../debug/FeatureGatesTest.py)
  - [`docs/tasks/GATE-002.md`](GATE-002.md)（登记 `GAP-GATE-001`..`005` 后续整改与 4 个 `MACAPK_EXPERIMENTAL_*` 升级门槛）
- 验证命令与结果（均未修改 `runtime/`、`launcher/`、`UPGRADE_PLAN.md`、`PROJECT_STATE.md`、`launchctl` 或正式安装）：
  - `python3 scripts/validate_gates.py --check-source-inventory --json > work/upgrade/GATE-001/validate_gates_report.json` -> exit `0`，`PASS (gates=75, locations=148, mismatches=5, errors=0)`；基线扫描核验 `files_scanned=3353`、`distinct_tokens=2109`、`dex2jvm_tokens=43`、`experimental_tokens=7`。
  - `python3 debug/FeatureGatesTest.py --runtime-build-dir <primary_runtime_build> --out-dir work/upgrade/GATE-001`（默认 `./runtime/build`） -> exit `0`，`PASS=23 FAIL=0 NOT_RUN=4`，报告：`work/upgrade/GATE-001/feature_gates_test_report.json`。
  - `NOT_RUN` 项（不启动 GUI/正式包/不改 `launchctl`）：`FG-INST-001_launcher_panel`、`FG-INST-002_dock_stub`、`FG-INST-003_system_broker`、`FG-INST-004_experimental_live_render`（转 `GATE-002`）。
- 回退：仅新增台账与只读校验脚本，未改任何 runtime 默认行为或启动器注入；删除本卡新增文件并恢复 `docs/tasks/GATE-001.md` 即可完整撤销。
