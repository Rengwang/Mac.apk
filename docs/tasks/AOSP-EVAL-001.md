# AOSP-EVAL-001：验证一个小模块的源码复用价值

- 状态：DONE
- 前置：CAP-001、CONTRACT-001
- 证据目录：`work/upgrade/AOSP-EVAL-001/`

## 目标与范围

用一项有已知差异、依赖较小的算法/数据结构比较“最小修复”与“AOSP port”。
候选从 SparseArray 类算法、Intent 匹配子算法等选取；没有缺口时不为移植而替换。
不从整套 View/Parcel/Resources/ART/HWUI 开始。本卡可用有证据的 no-go 完成。

## 步骤

1. 查 GitHub 与固定 AOSP tag，选择一个 CAP/CONTRACT 有实际依据的子模块，记录选择理由。
2. 记录文件来源、commit、license/NOTICE、依赖闭包、native/hidden/libcore 调用与宿主接缝。
3. 在独立测试路径编译候选，保留现有实现；相同输入对照正常、边界、异常与版本差异。
4. 报告正确性变化、适配代码/依赖规模、维护更新成本；热点才做同条件性能/内存比较。
   只做评估，不在生产 classpath 直接替换同名类，不升级 JDK/Skia。
5. 写 `docs/adr/0003-aosp-module-evaluation.md`（按 ADR 编号顺序承接 `0002-sandbox-and-isolation-boundaries.md`）：选中模块、替代方案、go/no-go、迁移/回退和未知项。

## 验收

- [x] 至少一个具体候选有可追溯源码/依赖评估和可解释对照，或有确定的阻止复用证据（选中 `android.os.PatternMatcher` `PATTERN_ADVANCED_GLOB` 并在 `work/upgrade/AOSP-EVAL-001/PatternMatcherEval.java` 运行 15 组行为/边界/异常对照、`PathPermission` Parcel 往返与 100k 热路径基准，暴露现有 `PATTERN_ADVANCED_GLOB` 退化及转义 `\.` 误匹配）。
- [x] 不是“原码所以一定正确”；支持结论限定到已测行为，不外推整 framework（已在 [ADR-0003](../adr/0003-aosp-module-evaluation.md) 第 5 节显式限定结论作用域并保留 `android_compared=false` / `ORACLE-001` 声明）。
- [x] go 时为 ARCH-001 提供小范围实现卡；no-go 时保留当前实现，列推荐通用小修（`PatternMatcher` 为局部子算法 **GO** 候选并保留 `getPattern()` 别名；`Intent.parseUri` 仅子算法条件引入；`ServiceManager`、`Bundle`、`AudioTrack`/`MediaExtractor`/`MediaCodec` 与 `SparseArray`/`ArrayMap` 均明确 **NO-GO** 并给出小修/保留建议）。
- [x] 来源/许可记录完整，没有把第三方未经核对的代码链接进交付产物（已记录 AOSP `android-16.0.0_r2` Apache-2.0 来源，隔离实验未修改 `runtime/` 与生产 classpath）。

## 记录与回退

- **已完成**：隔离对照脚本 `work/upgrade/AOSP-EVAL-001/PatternMatcherEval.java` 实测结果存入 `work/upgrade/AOSP-EVAL-001/eval.log`，评估决策已写入 [ADR-0003](../adr/0003-aosp-module-evaluation.md)；`runtime/` 与 `launcher/` 保持零变更。
- **回退与下一步**：本卡仅产评估文档与 gitignored 隔离实验，撤销文档提交即可完全回退；下一步按 [UPGRADE_PLAN.md](../UPGRADE_PLAN.md) 推进 [ARCH-001](ARCH-001.md)（基于 `GAPS.md`、`FEATURE_GATES.md` 与本卡 GO/NO-GO 清单选定首项通用修复）。
