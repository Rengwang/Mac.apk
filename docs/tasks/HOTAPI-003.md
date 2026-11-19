# HOTAPI-003：清零生态高频 Stub 剩余 broken setter/getter 对与常见兼容接口

- 状态：DONE
- 前置：`HOTAPI-001`、`HOTAPI-002`
- 依据清单：[HOT_API_STUBS.md](../compat/HOT_API_STUBS.md)（修复后 `142` 个核心 shadowed 文件、`830` 个单行 Stub、可修复 broken setter/getter pairs **`0`**，另保留 `3` 对经 AOSP 验证的刻意 inert/硬件门控对）

## 目标与范围

继续按生态高频清单做最小通用实现（纯 `runtime/java/` + 离线回归），**不涉及 GUI、不加包名特判、不改变已有 AOSP 契约语义**：

1. **优先清零剩余 21 对 broken setter/getter（让设置→回读真实一致）**：
   - `android.view.Window`（11 对：`EnterTransition` / `ReturnTransition` / `ExitTransition` / `ReenterTransition` / `SharedElement*Transition`、`TransitionBackgroundFadeDuration`、`Elevation`、`NavigationBarDividerColor`）
   - `android.view.accessibility.AccessibilityNodeInfo`（6 对：`Parent`、`TraversalBefore`、`TraversalAfter`、`LabelFor`、`LabeledBy` 等无障碍关系字段回读）
   - `android.view.View`（2 对：`setBackgroundTintMode/getBackgroundTintMode`、`setFrameContentVelocity/getFrameContentVelocity`）
   - `android.media.AudioManager`（1 对：`setWiredHeadsetOn/isWiredHeadsetOn`）
   - `android.media.RingtoneManager`（1 对：`setIncludeDrm/getIncludeDrm`）
2. **高频真实返回值 Stub（视复杂度分批）**：如 `Display.getAlternativeRefreshRates` / `getSupportedHdrTypes`、`Resources.getChangingConfigurations` 等可安全反映当前宿主状态的 getter。

## 验收与交付

- [x] 新增 `debug/HotApiBatch3Test.java` 并注册到 `scripts/run_tests.py`（`bare_jvm`），修复前复现 `FAIL (rc=1)`，修复后 `PASS (rc=0)`。
- [x] `MACAPK_NO_BUNDLE_SYNC=1 runtime/scripts/build.sh` 与 `MACAPK_NO_BUNDLE_SYNC=1 scripts/build_launcher.sh`、`scripts/run_tests.py --group offline --strict`（`45 PASS, 0 FAIL, 0 SKIP, 15 NOT_RUN`）、`DrawableIdentityAndTintTest` + `UpstreamPackageFeaturesTest`（`2 PASS`）、`CheckDocSyncTest` & `CapRegistryTest` 全部 PASS。
- [x] 刷新 [HOT_API_STUBS.md](../compat/HOT_API_STUBS.md)（可修复 `broken pairs -> 0`，并单列 3 对 AOSP inert 契约说明）、`scripts/cap_registry.py generate` 与 `PROJECT_STATE.md` / `docs/UPGRADE_PLAN.md`。

### 实施摘要
- **AOSP 契约核对**：依据 AOSP `PhoneWindow.java` 与 `AccessibilityNodeInfo.java`，识别并保留 `View.setFrameContentVelocity/getFrameContentVelocity`、`AudioManager.setWiredHeadsetOn/isWiredHeadsetOn`（API 5 `@Deprecated`）与 `RingtoneManager.setIncludeDrm/getIncludeDrm`（API 16 `@Deprecated`）3 对 AOSP 原生 inert/硬件门控契约（在 `scripts/hot_api_stub_scanner.py` 中通过 `VERIFIED_AOSP_INERT_PAIRS` 单列说明，不强行伪造状态）。
- **`Window` / `ActivityWindow` / `DialogWindow`**：在 `Window.java` 存储转场（`Enter`/`Return`/`Exit`/`Reenter`、`SharedElement*`、`TransitionBackgroundFadeDuration`、`SharedElementsUseOverlay`、`Allow*TransitionOverlap`）、`NavigationBarDividerColor` 与 `Elevation`；`ActivityWindow` 与 `DialogWindow` 遵循 Android 15 edge-to-edge 强制语义（`mEdgeToEdgeEnforced` / `mMacapkEdgeToEdgeEnforced`），`DialogWindow.getElevation()` 补充 `@Override`。
- **`AccessibilityNodeInfo`**：补齐 `mSourceNodeId`/`mWindowId` 与 `equals`/`hashCode`，修复 `obtain(View[, int])`/`setSource` 丢弃 source ID 与 `getLabeledByList()` 误返回空列表、`removeLabeledBy(View[, int])` 无法移除的实际缺陷，清除死字段 `mLabeledByViews`；在 `setParent`/`setTraversalBefore`/`setTraversalAfter`/`setLabelFor`/`setLabeledBy` 记录对应节点 ID，同时保持无连接状态下的 AOSP `null` 返回契约。
- **`View`**：`setBackgroundTintMode/getBackgroundTintMode` 独立存储 `macapkBackgroundTintMode`，保持与 `mBackgroundTintBlendMode` 解耦，不扰动已有 `BlendMode` 调用方。
