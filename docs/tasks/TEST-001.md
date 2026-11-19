# TEST-001：建立可重复的测试入口

- 状态：DONE
- 前置：BASE-002
- 基础提交：`1ffda6f82a58915c3540b47679f572d087e26777`
- 证据目录：`work/upgrade/TEST-001/`

## 目标与范围

把已有测试整理成任何新会话都能按说明重跑的检查。
允许新增测试运行脚本/清单并更新 TESTING.md；生产运行时修改另开任务。
不要一开始承诺所有 `debug/` 文件都能在同一环境运行。

## 步骤

1. 逐项清点测试，分类为裸 JVM、native 小测试、真实 runtime、GPU、GUI、安装态。
2. 写明每项编译/运行命令、classpath/模块参数、fixture、平台和断言。
3. 先为已确认可离线自动执行的一组测试建立统一入口，再列出其他组的补测条件。
4. 保留每项真实退出码；汇总 PASS/FAIL/SKIP/NOT_RUN。必需组中缺少条件应非零失败。
5. 特别验证 OwnerFrameBarrier 的先编译再运行，以及 runtime 测试不可裸 JVM 替代。
6. 对 flaky 测试保留首轮失败与重试次数；不得只报最后一次通过。
7. 入口至少提供清单/无执行模式和明确的组选择；默认基础组不得启动 GUI、安装、杀用户进程或同步 bundle。
   输出机器可读结果（JSON/JUnit 任选）和可读摘要，含 test ID、实际命令、退出码、耗时、分类、输入/构建身份。
   CLI 名称由实现者选择并写入 TESTING.md；本路线图不虚构已存在的统一 runner。

## 验收

- [x] 一条文档化命令重跑明确的基础测试组，并给出逐项结果。
- [x] 故意缺失 fixture/条件时不会伪装全绿；真实断言失败能向上传递失败状态。
- [x] 尚未自动化的组有清单、原因和后续入口。
- [x] 不删除/削弱断言、不吞异常、不改生产行为。

## 回退与记录

2026-11-14：全部必需验收完成。
- 新增 `scripts/run_tests.py`（纯 Python 3 标准库，54 个注册条目覆盖 `bare_jvm`、`jvm_launch_sensitive`、`native_unit`、`worktree_bundle`、`gpu_pixel`、`codegen_check`、`runtime_fixture`、`installed_bundle`；默认 `offline` 组 39 项）。
- 更新 `TESTING.md`、`docs/tasks/TEST-001.md`、`docs/UPGRADE_PLAN.md`、`PROJECT_STATE.md`。
- 实测结果（基于 `1ffda6f82a58915c3540b47679f572d087e26777` + `JAVA_ID=24653ab436234457` / `NATIVE_ID=2140bedbbc35c18c`）：
  - `python3 scripts/run_tests.py --list`：PASS（exit 0，54 项清单）
  - `python3 scripts/run_tests.py`（默认 `offline`）：PASS（exit 0，`PASS=39 FLAKY_PASS=0 FAIL=0 SKIP=0 NOT_RUN=15`）
  - `python3 scripts/run_tests.py --self-test`：PASS（exit 0，5/5 隔离自检通过：断言失败 exit 1、缺必需前置 exit 2、stdout `SKIP:` 不伪装 PASS、flaky `--retries 1` 记录首轮失败与 `FLAKY_PASS`、`OwnerFrameBarrierTest` 单文件源码启动被 `IllegalAccessError` 拒绝）
  - `NOT_RUN` 组（15 项）：`gpu_pixel`（7）、`codegen_check`（2）、`runtime_fixture`（3，含 `RemoteServiceProcessTest` 及需 `--apk` 用例）、`installed_bundle`（3），均在 `scripts/run_tests.py --list` 与 `TESTING.md` 写明条件与显式入口。
- 证据位置（Git 忽略）：`work/upgrade/TEST-001/report.json`、`work/upgrade/TEST-001/report.xml`、`work/upgrade/TEST-001/summary.txt`、`work/upgrade/TEST-001/logs/*.log`、`work/upgrade/TEST-001/self-test.log`、`work/upgrade/TEST-001/offline.exit`。
- 回退：撤销本卡提交（`scripts/run_tests.py` 及上述文档更新）即可，未修改任何生产运行时或 `debug/` 现有测试。

### 交接（2026-11-14 TEST-001 完成）

```text
任务 / 状态 / 当前负责人：TEST-001 / DONE / builder
实际 worktree / 分支 / 基础与检查点 SHA：/Users/xrdemac/.gemini/antigravity/worktrees/Mac.apk-compat/mac_apk_architecture_upgrade / mac_apk_architecture_upgrade / 基础 1ffda6f82a58915c3540b47679f572d087e26777（检查点见本任务最新本地提交）
尚未提交的本任务文件及原因：无（work/upgrade/TEST-001/ 为 gitignored 证据输出）
完成的行为与变更文件：新增 scripts/run_tests.py；更新 TESTING.md、docs/tasks/TEST-001.md、docs/UPGRADE_PLAN.md（TEST-001->DONE，CAP-001 & CI-001->READY）、PROJECT_STATE.md
检查（PASS/FAIL/SKIP/NOT_RUN、命令、退出码）：
  - PASS: python3 scripts/run_tests.py --list (exit 0, 54 registered checks)
  - PASS: python3 scripts/run_tests.py (exit 0, offline group: PASS=39 FAIL=0 SKIP=0 NOT_RUN=15)
  - PASS: python3 scripts/run_tests.py --self-test (exit 0, 5/5 negative/retry/launcher checks)
  - NOT_RUN: gpu_pixel / codegen_check / runtime_fixture / installed_bundle (15 checks, opt-in via --group)
证据位置与脱敏摘要（标明是否仅在本机可用）：work/upgrade/TEST-001/（仅本机可用）：report.json、report.xml、summary.txt、logs/、self-test.log、offline.exit
剩余项及精确解除条件：非默认组需各自前提（gpu_pixel 需 Metal/ANGLE；runtime_fixture 需 --apk 或完整 runtime+broker；installed_bundle 需 --allow-installed / dist bundle）
源码 / 构建物 / 安装态回退：git revert 本卡提交；未修改生产运行时、debug/ 用例、/Applications/Mac.apk.app 或共享 runtime
下一任务卡与第一条动作：CAP-001（docs/tasks/CAP-001.md）或 CI-001（docs/tasks/CI-001.md）均已 READY；COMP-001 仍需等待 SEC-001 合入
```
