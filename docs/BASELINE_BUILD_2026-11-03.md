# BASE-001 构建基线验收报告

日期：2026-11-03（Asia/Shanghai）。结果：DONE。
基础提交：0f56272ef889d80b2cb854a43483df50a8611e11。
分支：codex/foundation/base-001-build-baseline。
工作区：/Users/xrdemac/.codex/worktrees/macapk-upgrade-foundation/Mac.apk-compat。

## 实际结果

| 检查 | 结果 | 退出码 |
|---|---|---|
| scripts/check-requirements.sh（不安装依赖） | PASS | 0 |
| MACAPK_NO_BUNDLE_SYNC=1 runtime/scripts/build.sh | PASS | 0 |
| MACAPK_NO_BUNDLE_SYNC=1 scripts/build_launcher.sh | PASS | 0 |
| scripts/audit_bundle_deps.sh build/Mac.apk.app | PASS，检查 64 个 Mach-O，无外部非系统绝对路径依赖 | 0 |
| debug/entitlements_check.sh build/Mac.apk.app/Contents/Resources/macapk-runtime | PASS | 0 |

环境：arm64；macOS 27.2（26B123）；Xcode 27.2（27B52）；SDK 27.2；
OpenJDK 21.0.13；CMake 4.4.4；Capstone 5.0.9；apktool 3.0.3。
必需依赖齐备，无新安装/升级。Android emulator/SDK 检查提示可选缺失，不影响此构建。
新工作区从 AOSP android-16.0.0_r2 下载并构建完整 API 36 framework-res，未启用 table-only 回退。

## 产物身份

本地 app：工作区内 build/Mac.apk.app。
启动器标识：261103.1035-0f56272+dirty。
其中 dirty 来自构建期间更新任务状态文档；生产源码与基础提交无差异。
本轮不是新版本发布。

```text
CONVERTER_ID=1b0ad561c01dc6ff
FRAMEWORK_ID=440bb2dbd3c3b4b6
JAVA_ID=81b79c4b93020dff
JARS_ID=72a6c4677cbf76d1
NATIVE_ID=a7539a9da72ee1a7
```

SHA-256：

- `runtime/build/bin/macapk-runtime`：`6847ac56ca432dfbfb09b1e6258bbbe0106c98c5514756910ba80d3c6abdbee9`
- `runtime/build/lib/android-framework.jar`：`947985e4d1dad0eb15280811504368f415704f3eb07f58b669547bbee337ece6`
- `runtime/build/lib/macapk-loader.jar`：`566101a2afed454df7f71f61509358617e67a94ff05bf79cf31fe06a472846cb`
- `thirdparty/framework-res/framework-res-api36.apk`：`aa6582552e614539ca85c0debd76a6283d80e26456895c829e11e755521d7cd4`

## 风险与未覆盖

- Java 编译有 120 项警告（包括内部 Unsafe API），本轮未为消除警告改变源码。
- 原生链接提示 Capstone/libpng 面向 macOS 27.0，而目标为 26.0；本机通过不证明最低系统兼容。
- 依赖审计确认链接路径自包含，不证明最低 OS、资源内容、运行行为全部正确。
- APK 功能、图形像素、安装态、回归测试、性能均 NOT_RUN，留给后续任务。
- 未打开 app、未启动 APK、未 promote、未推送远端；未使用正式应用数据。
- 对正式 app 与共享 runtime 的现有普通文件做前后大小/mtime 清单比较，结果相同。
  这是现有文件元数据一致的证据，不是文件访问级审计或整个宿主磁盘的内容证明。

## 证据与回退

原始证据在工作区 work/upgrade/BASE-001/（Git 忽略）：
requirements.log、runtime.log、launcher.log、bundle-audit.log、entitlements.log、
environment.json、results.json、artifact-hashes.json、installed-before/after.json。
资源构建日志位于 runtime/build/framework-res-build.log。

无需恢复正式安装；构建只产生本工作区缓存与产物。文档可通过独立提交回退。
下一任务 TEST-001：整理并实际运行现有回归测试，建立统一测试入口。
