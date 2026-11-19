# 从这里继续升级 Mac.apk

主仓库目录是 `/Users/xrdemac/Developer/Mac.apk-compat`，当前升级分支 worktree 为 `/Users/xrdemac/.gemini/antigravity/worktrees/Mac.apk-compat/mac_apk_architecture_upgrade`（分支 `mac_apk_architecture_upgrade`；切会话先用 `git worktree list` 核对）。

## 文档导航

| 类别 | 文档 | 用途 |
|---|---|---|
| 当前现场与交接 | [PROJECT_STATE.md](../PROJECT_STATE.md) · [UPGRADE_PLAN.md](UPGRADE_PLAN.md) | 实时基线、Build ID、验证结果、`1+1` 唯一任务状态总表 |
| 决策、规则与执行 | [AGENTS.md](../AGENTS.md) · [ROADMAP.md](ROADMAP.md) · [AUTONOMOUS_EXECUTION.md](AUTONOMOUS_EXECUTION.md) | 约束红线、阶段路线与连续推进协议 |
| 兼容台账与开关 | [CAPABILITIES.md](compat/CAPABILITIES.md) · [GAPS.md](compat/GAPS.md) · [FEATURE_GATES.md](compat/FEATURE_GATES.md) · [HOT_API_STUBS.md](compat/HOT_API_STUBS.md) | 能力矩阵、观测缺口、实验开关与高频 API Stub 清单 |
| 构建、测试与性能 | [BUILDING.md](../BUILDING.md) · [TESTING.md](../TESTING.md) · [PERFORMANCE.md](../PERFORMANCE.md) | 源码编译、分层测试与性能采样规范 |
| 任务卡与 ADR | [tasks/](tasks/) · [adr/](adr/) | 单项任务交付证据与架构决策记录 |
| 历史档案（仅作参考） | [MACAPK_UPGRADE_HANDBOOK.md](MACAPK_UPGRADE_HANDBOOK.md) · [BASELINE_BUILD_CURRENT.md](BASELINE_BUILD_CURRENT.md) · [BASELINE_BUILD_2026-11-03.md](BASELINE_BUILD_2026-11-03.md) · [FORK_MERGE_2026-11-14.md](FORK_MERGE_2026-11-14.md) · [AI_PROPOSALS_REVIEW_2026-11-14.md](AI_PROPOSALS_REVIEW_2026-11-14.md) | 早期架构快照、基线验收与合入评估，不直接代表最新源码状态 |

> 当前实时进度说明：主基建、`PILOT-001` 至 `GC-001`、`FORK-MERGE` 与 `HOTAPI-001 .. HOTAPI-003` 均已 `DONE`；当前交接焦点为排查 [VIDEO-PWR-001](tasks/VIDEO-PWR-001.md)（`RemoteServiceProcessTest` `FAIL`、`ijk_ff_vout` ~107 ms `nanosleep` 突发、20s Sidecar 完整验收）、[GFX-001](tasks/GFX-001.md) 与 [DANMAKU-001](tasks/DANMAKU-001.md)（均以 [PROJECT_STATE.md](../PROJECT_STATE.md) 和 [UPGRADE_PLAN.md](UPGRADE_PLAN.md) 为准）。

## 接手短指令与工具入口

在 Codex / Antigravity / Claude Code（[CLAUDE.md](../CLAUDE.md)） / WorkBuddy（[CODEBUDDY.md](../CODEBUDDY.md)）打开目录后直接发送：

```text
从 PROJECT_STATE.md 接手 Mac.apk 升级。先读 AGENTS.md 和当前任务卡，核对分支与未提交改动，按卡直接执行。停下前把验证结果、检查点和下一条动作写回任务卡。
```

## 如何看完成情况

“任务卡写好了”和“实现完成”不同；“代码存在”和“已运行验证”也不同。
查看每张卡的验收、源码/构建版本、命令、退出码和证据。
切换 Agent 时，用同一 checkout 可以接续原工作区的源码和证据；独立 worktree 从当前检查点创建。详细规则见 [切换工具时交接](AUTONOMOUS_EXECUTION.md#6-切换工具时交接) 和 [同时工作的分工](AUTONOMOUS_EXECUTION.md#7-同时工作的分工)。

## 何时需要用户介入

AI 可以完成研究、实现、构建、测试、评审及本地检查点；文档本身不会后台自动执行。
只有缺设备/账号/系统权限、必须改变既有授权范围等外部条件才需要具体协助。
不能把没有远端 CI 变成全部本地工作停止，也不能把未运行的 Android 对照当通过。
失败时保留日志、定位有效错误；禁止 reset --hard/clean 清掉用户工作。
