# Mac.apk 首轮升级计划

日期：2026-11-01。先安排十项可验收任务，不承诺六个月内重写整个运行时。
已完成 FND-001、BASE-001；下一项 TEST-001。没有安排后台自动执行。

## 首轮任务

| 顺序 | 任务 | 产出 | 前置 | 状态 |
|---|---|---|---|---|
| 1 | [FND-001](tasks/FND-001.md) 工程框架 | 规则、现状、任务卡、入口 | 无 | DONE |
| 2 | [BASE-001](tasks/BASE-001.md) 实际构建基线 | 环境清单、本地构建与身份记录 | FND-001 | DONE |
| 3 | [TEST-001](tasks/TEST-001.md) 统一测试入口 | 分类清单和可重复检查命令 | BASE-001 | READY |
| 4 | [CI-001](tasks/CI-001.md) 自动回归 | 可用 runner 上的测试流程 | TEST-001 | TODO |
| 5 | [COMP-001](tasks/COMP-001.md) 兼容性样本 | 场景矩阵、fixture 来源与复现步骤 | BASE-001 | TODO |
| 6 | [PERF-001](tasks/PERF-001.md) 性能基线 | 固定场景的原始结果和摘要 | BASE-001、COMP-001 | TODO |
| 7 | [OBS-001](tasks/OBS-001.md) 补齐观测缺口 | 最小必要指标、口径与开销结果 | PERF-001 | TODO |
| 8 | [SEC-001](tasks/SEC-001.md) 安全边界评估 | 当前访问边界与 sandbox 可行性结论 | BASE-001 | TODO |
| 9 | [ARCH-001](tasks/ARCH-001.md) 选择改造切口 | 第一份 ADR 与后续小任务 | TEST-001、PERF-001、OBS-001、SEC-001 | TODO |
| 10 | [PILOT-001](tasks/PILOT-001.md) 首个实际改进 | 一项可测、可回退的通用改进 | ARCH-001、CI-001 | TODO |

执行顺序按表推进；同一时间只有一个任务修改代码。环境阻碍先记录，不误标 DONE。
OBS-001 若确认现有工具已够用，可以用有证据的评估完成，无需为了任务而加代码。
SEC-001 尽早开展，可在完成 BASE-001 后提前，不需要等性能问题修完。

## 状态含义

- READY：前置已满足，尚未开工。
- TODO：计划内，前置尚待核验。
- IN_PROGRESS：已开始，记录当前动作和证据位置。
- BLOCKED：缺明确外部条件；写明解除条件，不能把困难本身当阻碍。
- DONE：任务卡的适用验收条件有证据，未覆盖事项已明确。

任务卡是状态的详细记录，本表是导航。改状态时两处同步。

## 本轮之后再决定的方向

- Surface identity/generation、显式合成状态、队列和生命周期。
- Choreographer/Looper 的调度、唤醒与背压。
- 减少实测热路径 readback/copy，局部 GPU Canvas 试验。
- MediaCodec/VideoToolbox、资源与主题、DEX/JVM 或 Binder 语义缺口。
- APK Doctor 和 capability registry。

这些不是已经批准的重写清单。以 BASE/TEST/COMP/PERF/OBS/SEC 的结果确定优先级。
VM fallback 也不属于本轮实施范围。

## 每项任务统一交付

结果及实际行为、变更范围、命令与退出码、证据位置、未完成项、风险、回退办法、下一任务。
详细约定见 [任务模板](tasks/TEMPLATE.md)、[测试说明](../TESTING.md) 和 [性能规范](../PERFORMANCE.md)。
