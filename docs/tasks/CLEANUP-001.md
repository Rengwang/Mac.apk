# CLEANUP-001：升级前清理项目

- 状态：DONE
- 前置：无
- 基础提交：`dfe3fe2`
- 证据目录：`work/upgrade/CLEANUP-001/`

## 清理结果

- 将 `PROJECT_STATE.md` 缩为当前状态、本机测试交付约定和历史入口。原状态记录见 `git show dfe3fe2:PROJECT_STATE.md`。
- 删除已被路线图替代的手册计划，移除重复整理记录，统一从 `TESTING.md` 获取开发构建命令。
- 将旧视频功耗长稿的结论和限制并入 `VIDEO-PWR-001`，逐轮原文仍可从 `dfe3fe2` 读取。GFX-001 和 DANMAKU-001 的验收状态保留。
- 核对上游与本地构建脚本的资源输入，将约 216 MiB 可重新生成的 `thirdparty/framework-res/work-android-16.0.0_r2/` 及 Finder 元数据移入废纸篓。完整 API 36 资源包及 aapt2 保留，供下一构建使用。
- 更正 `--no-promote` 的过时说明。该模式打开本地构建的 GUI，主工作区运行时可能停止现有进程。

## 验证与边界

| 检查 | 状态 | 命令与退出码 |
|---|---|---|
| 文档链接、任务状态和依赖 | PASS | `python3 work/upgrade/CLEANUP-001/validate_docs.py` / 0 |
| 完整资源包可暂存且内容一致 | PASS | `scripts/stage_framework_res.sh`（临时目录）、`cmp`、`unzip -tq`、`shasum -a 256` / 均 0 |
| 脚本语法及实际变更仅在注释 | PASS | `bash -n scripts/rebuild_and_run.sh`、比较清理前后非注释行 / 均 0 |
| 变更检查 | PASS | `git diff --check`、`git diff --cached --check` / 均 0 |
| 完整构建、产品测试和安装 | NOT_RUN | BASE-002 执行当前构建验收 |

## 回退和下一项

文档可撤销本任务的独立提交。缓存可从本机废纸篓中名称以 `Mac.apk-compat-cleanup-20261114-` 开头的目录按相对路径恢复，或按 `BUILDING.md` 的 API 36 配方重新生成。下一个任务为 BASE-002。
