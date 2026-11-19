# BASE-002：刷新当前源码的可复现构建基线

- 状态：DONE
- 前置：BASE-001
- 基础提交：`7928611d19341a770ba5317d79021b1595aa49ce`（路线决策基点 `7cb38df7bfcced3412901af262f4d109f6b4f9fc`）
- 证据目录：`work/upgrade/BASE-002/`

## 目标与范围

旧 BASE-001 不证明当前 2379 集成能构建。用公共脚本确认当前 HEAD 的依赖、构建身份和缺失材料。
允许本地构建、身份/依赖报告和必要构建文档修正；不顺手修产品兼容行为。
按 [执行协议](../AUTONOMOUS_EXECUTION.md) 处理现有变更与 worktree。

## 步骤

1. 记录 Git/worktree 状态；读取 BUILDING、TESTING 和下列脚本，检查副作用。
2. 核验 `scripts/check-requirements.sh`、`runtime/scripts/build.sh`、`scripts/build_launcher.sh`、
   `scripts/build_framework_res.sh`。核对真实 JDK 版本、SDK、framework-res 来源与 hash。
3. 在独立 worktree 先查现有产物是否有同 HEAD/依赖/内容身份的证据；没有则分步构建：

   ```bash
   scripts/check-requirements.sh
   MACAPK_NO_BUNDLE_SYNC=1 runtime/scripts/build.sh
   MACAPK_NO_BUNDLE_SYNC=1 scripts/build_launcher.sh
   scripts/audit_bundle_deps.sh build/Mac.apk.app
   debug/entitlements_check.sh build/Mac.apk.app/Contents/Resources/macapk-runtime
   ```

4. framework-res 缺失按 BUILDING 的 API 36 / `android-16.0.0_r2` 配方补本地资源；
   不把 API 30 默认配方误用为交付输入。注释提到但不存在的私有脚本列缺口，不虚构执行。
5. 记录源码、JAR/native/build-id、JDK 链接、bundle payload 和命令真实退出码。
   验证没有把独立构建结果同步到共享 runtime。禁止用 rebuild_and_run --no-promote 代替离线构建。

## 验收

- [x] 当前 HEAD 的 runtime/launcher 构建与本地 bundle 审计 PASS，产物身份可追溯。
- [x] 可重跑命令和环境足够完整；缺材料时标 BLOCKED/FAIL，不引用旧版 PASS。
- [x] 没有启动 GUI、终止用户进程、触及正式应用数据或共享 runtime。
- [x] 输出脱敏摘要 `docs/BASELINE_BUILD_CURRENT.md`，TEST-001 可直接接着分类执行。

## 记录与回退

2026-11-14：全部必需构建与本地 bundle 审计 PASS，详情见 [当前基线报告](../BASELINE_BUILD_CURRENT.md)。
仅产生本 worktree 下的构建产物与 Git 忽略资源；未触及 `/Applications/Mac.apk.app`、共享 runtime、其他 worktree 或用户 APK 数据。文档变更通过独立本地提交回退。

### 交接（2026-11-14 BASE-002 完成）

```text
任务 / 状态 / 当前负责人：BASE-002 / DONE / builder
实际 worktree / 分支 / 基础与检查点 SHA：/Users/xrdemac/.gemini/antigravity/worktrees/Mac.apk-compat/mac_apk_architecture_upgrade / mac_apk_architecture_upgrade / 基础 7928611d19341a770ba5317d79021b1595aa49ce（检查点见本任务最新本地提交）
尚未提交的本任务文件及原因：无（work/upgrade/BASE-002/、build/、runtime/build/、thirdparty/framework-res/framework-res-api36.apk 均按规则为 gitignored 本机产物）
完成的行为与变更文件：新增 docs/BASELINE_BUILD_CURRENT.md；更新 docs/tasks/BASE-002.md、docs/UPGRADE_PLAN.md（BASE-002->DONE，TEST-001 & SEC-001->READY）、PROJECT_STATE.md
检查（PASS/FAIL/SKIP/NOT_RUN、命令、退出码）：
  - PASS: scripts/check-requirements.sh (exit 0)
  - PASS: MACAPK_NO_BUNDLE_SYNC=1 runtime/scripts/build.sh (exit 0)
  - PASS: MACAPK_NO_BUNDLE_SYNC=1 scripts/build_launcher.sh (exit 0)
  - PASS: scripts/audit_bundle_deps.sh build/Mac.apk.app (exit 0, 66 Mach-O, 0 external deps)
  - PASS: debug/entitlements_check.sh build/Mac.apk.app/Contents/Resources/macapk-runtime (exit 0)
  - PASS: codesign --verify --deep --strict --verbose=2 build/Mac.apk.app (exit 0)
  - NOT_RUN: debug/ 回归测试、GUI、APK 运行、性能采样（留待 TEST-001 及后续卡）
证据位置与脱敏摘要（标明是否仅在本机可用）：work/upgrade/BASE-002/（仅本机可用）与 docs/BASELINE_BUILD_CURRENT.md；Build ID 261114.1434-7928611，JAVA_ID=24653ab436234457，NATIVE_ID=2140bedbbc35c18c，framework-res-api36.apk SHA-256 aa6582552e614539ca85c0debd76a6283d80e26456895c829e11e755521d7cd4；shared-before/after.json 核验共享 runtime 与正式 app 零改动
剩余项及精确解除条件：无（BASE-002 已满足全部验收条件；debug/pairip_devhook/refs 按 BUILDING.md 属仓库外私有材料，不影响本卡验收）
源码 / 构建物 / 安装态回退：文档可通过 git revert 撤销；构建物仅限本 worktree，正式安装与共享 runtime 未变更
下一任务卡与第一条动作：TEST-001（docs/tasks/TEST-001.md），基于本 worktree 已构建的 runtime/build/ 与 build/Mac.apk.app 盘点并分类执行 debug/ 现有测试
```
