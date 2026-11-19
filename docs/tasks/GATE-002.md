# GATE-002：统一启动入口开关默认与环境清理

- 状态：DONE
- 前置：GATE-001
- 证据目录：`work/upgrade/GATE-002/`

## 验收结果与实证

1. **离线构建**：`MACAPK_NO_BUNDLE_SYNC=1 runtime/scripts/build.sh && MACAPK_NO_BUNDLE_SYNC=1 scripts/build_launcher.sh` 成功（`rc=0`），生成自包含 bundle 并通过 codesign 与依赖自包含审计。
2. **开关门禁**：`python3 scripts/validate_gates.py --check-source-inventory`（`rc=0`，gates=75, locations=148, mismatches=5, errors=0）。
3. **单元门禁**：`python3 debug/FeatureGatesTest.py`（`rc=0`，PASS=23 FAIL=0 NOT_RUN=4）。
4. **能力台账**：`python3 -m unittest debug/CapRegistryTest.py -v`（`rc=0`，Ran 12 tests, OK）。
5. **离线总门禁**：`python3 scripts/run_tests.py --group offline --strict`（`rc=0`，Counts: PASS=42 FLAKY_PASS=0 FAIL=0 SKIP=0 NOT_RUN=15）。

## 验收与回退

- [x] `GAP-GATE-001..005` 代码与台账同步收敛，`validate_gates.py`、`FeatureGatesTest.py` 与 `run_tests.py --group offline --strict` 全部 `rc=0`。
- **回退**：`git revert` 本卡提交并重跑离线构建。

直接修复 `GATE-001` 盘点出的 5 项开关与入口不一致（`GAP-GATE-001` ~ `GAP-GATE-005`），无需长时 GUI 采样或前置 ABBA 仪式：

1. **`GAP-GATE-001`（4 个 `MACAPK_EXPERIMENTAL_*` 启动入口对齐）**：将 `MACAPK_EXPERIMENTAL_NATIVE_SURFACE_LAYERS`、`MACAPK_EXPERIMENTAL_NATIVE_SURFACETEXTURE`、`MACAPK_EXPERIMENTAL_PRESERVE_SURFACE_ALPHA`、`MACAPK_EXPERIMENTAL_CHOREO_PIPE_WAKE` 在 runtime 侧（或统一入口基线）对齐为默认开启并保留显式 `=0` opt-out，使直接 CLI、`MACAPK_BROKER_RUNTIME`、Control Panel 与 App Stub 行为一致。
2. **`GAP-GATE-002`（日志默认与过时注释清理）**：清理 `NativeLog.java:35`、`Log.java:111`、`native_methods.cpp:72` 残留的过时注释，并在 `SystemServer.java` / 启动入口对齐默认日志环境变量说明。
3. **`GAP-GATE-003`（测试/自检环境变量清理）**：在 `AppStubMain.swift`（及 `macapk-runtime` 正常应用启动入口按需）同步清理测试专用环境变量，防止脏 shell 污染应用运行。
4. **`GAP-GATE-004`（`PresentedSdk.java` 注释修正）**：更新 `runtime/java/com/macapk/loader/PresentedSdk.java:94-96,241-270` 中与现行 `!= "0"` 默认开启实现矛盾的旧注释。
5. **`GAP-GATE-005`（`MACAPK_EXPERIMENTAL_ALPHA_PROBE` 判定统一）**：将 `runtime/translator/src/bionic_shim.cpp:38052` 与 `runtime/src/cocoa_bridge.mm:9229,9884` 的非空指针判定统一为 `strcmp(..., "1") == 0`，与 `SurfaceView.java:4253,4379` 严格一致。

## 允许修改文件

- `launcher/MacApkLauncherApp.swift`、`launcher/AppStubMain.swift`
- `runtime/java/android/view/SurfaceView.java`、`runtime/java/android/graphics/SurfaceTexture.java`、`runtime/java/android/view/Choreographer.java`、`runtime/java/com/macapk/loader/PresentedSdk.java`、`runtime/java/com/macapk/loader/NativeLog.java`、`runtime/java/android/util/Log.java`、`runtime/java/com/macapk/loader/SystemServer.java`
- `runtime/src/cocoa_bridge.mm`、`runtime/src/native_methods.cpp`、`runtime/translator/src/bionic_shim.cpp`
- `docs/compat/feature_gates.json`、`docs/compat/FEATURE_GATES.md`、`debug/FeatureGatesTest.py`、`docs/MACAPK_UPGRADE_HANDBOOK.md`、`docs/tasks/GATE-002.md`、`docs/UPGRADE_PLAN.md`、`PROJECT_STATE.md`

## 构建与验收命令

1. 离线构建：`MACAPK_NO_BUNDLE_SYNC=1 runtime/scripts/build.sh && MACAPK_NO_BUNDLE_SYNC=1 scripts/build_launcher.sh`
2. 门禁校验：`python3 scripts/validate_gates.py --check-source-inventory` 与 `python3 debug/FeatureGatesTest.py`
3. 离线总门禁：`python3 scripts/run_tests.py --group offline --strict`

## 验收与回退

- [ ] `GAP-GATE-001..005` 代码与台账同步收敛，`validate_gates.py`、`FeatureGatesTest.py` 与 `run_tests.py --group offline --strict` 全部 `rc=0`。
- **回退**：`git revert` 本卡提交并重跑离线构建。
