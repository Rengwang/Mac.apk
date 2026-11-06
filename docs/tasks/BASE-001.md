# BASE-001：验证真实构建基线

- 状态：DONE
- 前置：FND-001
- 基础提交：`0f56272ef889d80b2cb854a43483df50a8611e11`
- 证据目录：`work/upgrade/BASE-001/`

## 目标与范围

确认当前源码在本机、独立工作区中能构建出可识别的 runtime 和 launcher。
允许：依赖检查、本地构建、脱敏记录与必要的构建阻碍分析。
不包含：性能优化、兼容修复、安装/promote、打开日常 APK、工具链大升级。

## 步骤

1. 记录实际 cwd、分支、完整 SHA、工作区变更和 Git remote；确认框架提交已包含。
2. 阅读 `BUILDING.md`、`TESTING.md` 和实际构建脚本，检查忽略的 framework-res/本机依赖。
3. 运行 `scripts/check-requirements.sh` 默认只报告；记录真实 JDK、SDK、编译器和缺失项。
4. 按 `TESTING.md` 的 `MACAPK_NO_BUNDLE_SYNC=1` 分步构建；不 promote，不运行 `rebuild_and_run.sh`。
5. 显式检查本地 bundle 的依赖与 runtime entitlements，保存实际 build ID 和目标路径。
6. 失败时区分环境、既有构建缺陷和本任务新增问题；先保留首个有效错误，不扩大到运行时重构。

## 验收

- [x] 环境、命令、退出码、构建产物和内容标识均有记录。
- [x] runtime 与 launcher 构建 PASS；本地 bundle 审计与权限检查结果明确。
- [x] 没有改写正式安装/共享 runtime，没有使用正式 APK 数据。
- [x] 更新 PROJECT_STATE；只有相关必需检查通过才标 DONE。

## 回退与记录

2026-11-03：全部必需检查 PASS，详情见 [构建验收报告](../BASELINE_BUILD_2026-11-03.md)。
仅本地构建产物；回退文档提交不影响正式安装。不要删除工作区以外的缓存。
发现缺失依赖时记录解决范围；安装新依赖应先明确其必要性与宿主变更。
