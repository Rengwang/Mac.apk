# BASE-002 当前源码构建与身份基线（1.0.2379）

日期：2026-11-14（Asia/Taipei）。结果：DONE。
基础提交：`7928611d19341a770ba5317d79021b1595aa49ce`（基于 `7cb38df7bfcced3412901af262f4d109f6b4f9fc` 上游 1.0.2379 集成，`VERSION` = `1.0.2379`）。
分支：`mac_apk_architecture_upgrade`。
工作区：`/Users/xrdemac/.gemini/antigravity/worktrees/Mac.apk-compat/mac_apk_architecture_upgrade`。

## 检查结果

| 检查 | 结果 | 退出码 | 证据 |
|---|---|---|---|
| `scripts/check-requirements.sh`（不带 `--install`） | PASS | 0 | `work/upgrade/BASE-002/requirements.log` |
| `MACAPK_NO_BUNDLE_SYNC=1 runtime/scripts/build.sh` | PASS | 0 | `work/upgrade/BASE-002/runtime-build.log` |
| `MACAPK_NO_BUNDLE_SYNC=1 scripts/build_launcher.sh` | PASS | 0 | `work/upgrade/BASE-002/launcher-build.log` |
| `scripts/audit_bundle_deps.sh build/Mac.apk.app` | PASS（检查 66 个 Mach-O，零外部非系统路径依赖） | 0 | `work/upgrade/BASE-002/bundle-audit.log` |
| `debug/entitlements_check.sh build/Mac.apk.app/Contents/Resources/macapk-runtime` | PASS（8 项必需 entitlement 全部存在） | 0 | `work/upgrade/BASE-002/entitlements.log` |
| `codesign --verify --deep --strict --verbose=2 build/Mac.apk.app` | PASS（`valid on disk`，满足 Designated Requirement） | 0 | 本轮直接核验输出 |

## 构建环境与依赖

- 平台：`arm64`；`macOS 27.2.1`（`BuildVersion 26B131`）
- 工具链：`Xcode 27.2`（`Build 27B52`）；macOS SDK `27.2`；`Apple Swift 6.4.2`（`swift-driver 1.168.6`，target `arm64-apple-macos26.0`）
- JDK：`OpenJDK 21.0.13`（`/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home`，`javac 21.0.13`）
- 本机库与工具：`CMake 4.4.4`、`Capstone 5.0.9`、`libpng 1.6.59`、`apktool 3.0.3`
- 可选依赖：Android SDK / emulator 未安装（不影响构建）；`gh` CLI 已安装。未执行任何系统级安装或升级。

## framework-res 来源与核验

- 独立 worktree 初始不含 `thirdparty/framework-res/framework-res-api36.apk`（Git 忽略）。
- 已核验主工作区 `/Users/xrdemac/Developer/Mac.apk-compat/thirdparty/framework-res/framework-res-api36.apk` 的 SHA-256 为 `aa6582552e614539ca85c0debd76a6283d80e26456895c829e11e755521d7cd4`，与 `BASE-001` / AOSP `android-16.0.0_r2` API 36 完整资源表记录一致，并复制到本 worktree `thirdparty/framework-res/framework-res-api36.apk`（22,856,298 bytes）。
- 构建阶段直接将上述完整 API-36 资源 APK 暂存到 `runtime/build/bin/tools/framework-res.apk` 与 `build/Mac.apk.app/Contents/Resources/tools/framework-res.apk`（SHA-256 均为 `aa6582552e614539ca85c0debd76a6283d80e26456895c829e11e755521d7cd4`），未触发 table-only 回退，也未使用 API 30 默认表。

## JDK 链接、Build ID 与产物身份

### JDK 链接与打包 JRE

- 开发树二进制 `runtime/build/bin/macapk-runtime`（`otool -L`）：链接 `/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home/lib/server/libjvm.dylib` 与 `.../lib/libjli.dylib`。
- Bundle 内二进制 `build/Mac.apk.app/Contents/Resources/macapk-runtime`（`otool -L`）：经 `relink_bundle_deps.sh` 重写为 `@rpath/libjvm.dylib` 与 `@rpath/libjli.dylib`。
- Bundle 内打包 JRE（`build/Mac.apk.app/Contents/Resources/jre`，54M）：由上述 JDK `21.0.13` 经 `jlink` 生成，`release` 文件记录 `JAVA_VERSION="21.0.13"`，包含 19 个显式模块及传递依赖 `java.security.sasl`、`java.transaction.xa`。

### Build ID 与 Launcher 元数据

- Launcher `BuildInfo.id`：`261114.1434-7928611`（`CFBundleIdentifier=org.macapk.launcher`，`CFBundleShortVersionString=1.0.2379`，`CFBundleVersion=1.0.2379`，`LSMinimumSystemVersion=26.4`）。
- `runtime/build/build-id.txt` 与 `build/Mac.apk.app/Contents/Resources/build-id.txt`（SHA-256：`a840089d72771d49d375ca2bb2ceee294263a2c05dfe5da1091b8af988ee79df`）：

```text
CONVERTER_ID=77da89513a226525
FRAMEWORK_ID=b902b95f478a5dce
JAVA_ID=24653ab436234457
JARS_ID=2d7994c010d8381e
NATIVE_ID=2140bedbbc35c18c
```

### 关键产物 SHA-256

- `runtime/build/bin/macapk-runtime`：`7e7ea139502202523c9a6bc9e2e5da93ea578553e578eb9e73dc7453cce0ebf3`（4,323,248 bytes）
- `build/Mac.apk.app/Contents/Resources/macapk-runtime`：`3e9eae0e0e078b98b185aa9dc846c59b684c574b2a330ea2246f13024c80bfc6`（4,323,248 bytes，经 `@rpath` 重链接与带权限重签）
- `runtime/build/lib/android-framework.jar`：`01766c5a259afdba0cc9653104be00ae92cb8b9fc0a569dd1eccba66ebf1b23f`（16,983,083 bytes）
- `runtime/build/lib/macapk-loader.jar`：`71d443abd342ad1bb957d5da3042474e8bbbfff19f5c16732162379c329f334a`（1,617,540 bytes）
- `runtime/build/lib/bcprov-jdk18on-1.79.jar`：`0d81ecc3124536b539bce9aa3fe9621b7f84c9cee371b635a5b31c78b79ab1da`（8,575,537 bytes）
- `runtime/build/bin/macapk-translator`：`7a6b2bbafd728427ebdfa21506dae9435d9d80b7917e49969082cf59532f1828`（1,841,888 bytes）
- `runtime/build/bin/libmacapk-bionic-shim.dylib`：`fff4d7b6d196b446d423e3e44c00b09b874e31f5f12fa8a8ea29264ee0c41941`（2,741,288 bytes）
- `runtime/build/bin/macapk-elfexec`：`d989019f7912c62e30969fd0fac3f27aad75c1e101cad9df14d875a03f699b78`（80,832 bytes）
- `build/Mac.apk.app/Contents/MacOS/MacApk`：`f0f48c9619f81861bf6c41d8b2e24267a5b2f3da6f78cec74112e758f75cdb35`（3,227,648 bytes）
- `build/Mac.apk.app/Contents/Resources/appstub/MacApkAppStub`：`89dab31feb45ae05fe16c32d8486d5c0fe42cdef48cb668dbea21bb314ff2c4d`（1,018,624 bytes）
- `thirdparty/framework-res/framework-res-api36.apk`：`aa6582552e614539ca85c0debd76a6283d80e26456895c829e11e755521d7cd4`（22,856,298 bytes）

### 本地 Bundle Payload 概览（`build/Mac.apk.app/Contents/`）

- `MacOS/`：1 个文件（`MacApk`）
- `Helpers/`：4 个文件（`macapk-system`、`Mac.apk Location.app/...`）
- `Library/`：2 个文件（`LaunchAgents/org.macapk.system.plist`、`org.macapk.processes.plist`）
- `bin/`：6 个文件（`macapk-installhelper`、`macapk-translator`、`macapk-elfexec`、`libmacapk-bionic-shim.dylib`、`libcapstone.5.dylib`、`libpng16.16.dylib`）
- `lib/`：78 个文件（`android-framework.jar`、`macapk-loader.jar`、`bcprov-jdk18on-1.79.jar`、`libmacapk-app-stub.dylib`、`java_base_patch/` 73 个 class、`jdk_unsupported_patch/` 1 个 class）
- `Resources/`：171 个文件（`macapk-runtime`、ANGLE/MoltenVK/Skia/Binder dylibs、`MoltenVK_icd.json`、`jre/`、`tools/framework-res.apk`、`appstub/MacApkAppStub`、`build-id.txt`、`THIRD_PARTY_NOTICES.txt`、`AppIcon.icns`、`macapk_default_icon.png`）
- 根元数据：`Info.plist`、`PkgInfo`、`_CodeSignature/CodeResources`

## 隔离验证、缺口与未覆盖范围

- **共享运行时与正式安装未受影响**：通过构建前后对比 `/Applications/Mac.apk.app`（265 项）与 `~/Library/Application Support/Mac.apk/runtime`（251 项）的文件路径、大小、inode、mtime 纳秒时间戳及 symlink 快照（`work/upgrade/BASE-002/shared-before.json` vs `shared-after.json`），前后完全一致，零变更。未运行 `rebuild_and_run.sh`、未传 `--promote`、未启动 GUI 或任何 APK。
- **构建警告与私有资产缺口**：
  - `runtime-build.log` 含 100 条 Java 编译警告（`sun.misc.Unsafe`、`finalize()` 弃用、`DexFile` 弃用、`RemoteViews` unchecked）、32 条 C/C++/ObjC 编译警告，以及 3 条链接器警告（目标 macOS 26.0 链接 macOS 27.0 构建的 `libcapstone.5.dylib` / `libpng16.16.dylib`）。本卡不修改源码消除警告，也不宣称最低 OS 26.4 兼容已被实测证明。
  - 仓库按 `BUILDING.md` 说明不含私有 `debug/pairip_devhook/refs`，因此本次源码构建不包含 `lib/pairip-refs`。
- **本轮未运行（留待后续卡）**：`debug/` 下所有分类测试、真实 runtime / APK 行为、GPU 像素检查、安装态检查与性能测量均为 `NOT_RUN`（交由 `TEST-001` 及后续卡执行）。

## 证据位置与回退

- 本机原始日志、退出码、环境/哈希 JSON 及共享运行时前后快照位于 `work/upgrade/BASE-002/`（Git 忽略）：
  - `git-state.txt`、`framework-res.sha256`、`requirements.log` / `.exit`、`runtime-build.log` / `.exit`、`launcher-build.log` / `.exit`、`bundle-audit.log` / `.exit`、`entitlements.log` / `.exit`、`otool-dev-runtime.txt`、`otool-bundle-runtime.txt`、`environment.json`、`artifact-hashes.json`、`shared-before.json`、`shared-after.json`
- 回退：本次未修改正式安装或共享运行时，仅在本 worktree 产生本地构建产物及 `thirdparty/framework-res/framework-res-api36.apk`（均被 Git 忽略）；文档变更可通过独立本地提交回退。
- 下一任务：`TEST-001`（`docs/tasks/TEST-001.md`）与 `SEC-001`（`docs/tasks/SEC-001.md`）前置已满足，按主线顺序下一卡为 `TEST-001`。
