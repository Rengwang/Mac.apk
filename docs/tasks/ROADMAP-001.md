# ROADMAP-001：核实多份 AI 建议并整合执行路线

- 状态：DONE
- 前置：无（只读核查与文档整理）
- 基础提交：`7cb38df7bfcced3412901af262f4d109f6b4f9fc`
- 分支：`codex/roadmap-integration-20261114`
- 证据目录：`work/upgrade/ROADMAP-001/`

## 目标与范围

综合用户提供的 ChatGPT、Sonnet、DeepSeek、Kimi、两份 Gemini、hy4 和末尾综合草案，
对照本地源码及第一方资料，只将成立的结论转化为工程要求。
本卡只修改项目文档、任务卡与 AI 入口，不实施运行时升级。

## 验收

- [x] 重要分歧有逐项裁决、依据及采纳边界，未验证的收益不写成事实。
- [x] 沿用已有任务编号；说明历史状态、当前下一步、依赖、产出、验收和回退。
- [x] 后续 AI 可自行选择已满足条件的任务、完成检查并继续，无需用户逐卡分派。
- [x] 远端 CI、Android 对照环境等外部条件不阻塞无关本地工作，也不伪称通过。
- [x] 本地链接、任务依赖、状态一致性及 diff 检查通过；没有产品代码或二进制改动。

## 执行记录

2026-11-14：确认原 checkout 为 main/7cb38df，已有未跟踪 `.workbuddy/`，保持不动。
其他 worktree 包含正在进行的 2379 更新及历史 prunable 项，均未修改或清理。
已先搜索 GitHub 的 Robolectric、Android translation layer、Androguard、LIEF，
读取官方架构/README；阅读项目入口、相关源码和 Android/Apple 官方资料。

交付：ROADMAP、34 项 AI 观点裁决、ADR-0001、连续执行协议、更新的入口/状态/任务表，
以及构建刷新、能力/测试/诊断和后续模块任务卡。共 37 个 Markdown 文件；不包含验证用的本地忽略目录。
旧 TASK 证据保留。TEST-001 改依赖 BASE-002；远端 CI 从 CI-001 明确拆出，未假报实跑。

| 检查 | 状态 | 实际命令 / 退出码 | 证据与边界 |
|---|---|---|---|
| Markdown 链接、任务表/卡状态、依赖无环、READY 前置、文档范围 | PASS | `python3 work/upgrade/ROADMAP-001/validate_docs.py` / 0 | `work/upgrade/ROADMAP-001/validation.txt`：37 文件、95 本地链接、30 个主线/候选/独立验证节点 |
| diff 空白/冲突及新增文件检查 | PASS | `git diff --check`、`git diff --cached --check` / 均 0 | 暂存检查覆盖全部 37 个 Markdown 文件；`git diff --cached --numstat` 确认没有二进制 |
| 源码范围与用户文件保留 | PASS | `git status --short --branch`、`git diff --name-only` / 0 | 变更仅 AGENTS、PROJECT_STATE 与 docs；`.workbuddy/` 保留未跟踪 |
| runtime/launcher 构建、产品单测、GUI/安装/性能 | NOT_RUN | 未执行 / 不适用 | 本卡为纯文档；下一卡 BASE-002 才验证当前产品构建，未测不算通过 |

本地验证脚本和清单保存在忽略的证据目录；不要求它们成为产品测试基础设施。
下一卡：BASE-002，先核对当前 HEAD/独立 worktree 的公共构建链与产物身份。
结果提交可通过 `git log --oneline -- docs/tasks/ROADMAP-001.md` 定位，不在本提交里自引用未来 SHA。

## 回退

本卡只有文档变更。保留分支及证据后，仅撤销本卡所列文件的变更或独立提交；
不使用全仓库 reset/clean，不触及 `.workbuddy/`、其他 worktree、安装包或应用数据。
