# SURFACE-003：窗口几何与图层合成状态一致提交

- 状态：DONE
- 前置：SURFACE-002
- 证据目录：`work/upgrade/SURFACE-003/`

## 目标与修复范围

确保 resize、横竖屏切换与透明叠层更新时，Surface 几何（size/crop/transform/alpha/z-order）与代际一致提交，避免旧尺寸帧错位显示在縮放后的图层上（例如 `SurfaceTexture` 缩小窗口时尺寸同步）。

## 允许修改文件

- `runtime/src/cocoa_bridge.mm`、`runtime/java/android/view/SurfaceView.java`、`runtime/java/android/graphics/SurfaceTexture.java`
- `debug/SurfaceZOrderRequestTest.java`、`debug/SurfaceMetalLayerAlphaTest.mm`、`debug/WindowDamageLifecycleTest.java`

## 构建与验收命令

1. 离线构建：`MACAPK_NO_BUNDLE_SYNC=1 runtime/scripts/build.sh`
2. 针对性回归与离线门禁：`python3 scripts/run_tests.py --group offline --strict`（GUI/Sidecar 跨屏几何在连接窗口环境时按需复核，离线未跑如实标 `NOT_RUN`）

## 验收与回退

- [x] `SurfaceZOrderRequestTest`、`SurfaceMetalLayerAlphaTest`、`WindowDamageLifecycleTest` 与 `offline` 组全部 `rc=0`。
- **回退**：`git revert` 本卡提交并重跑离线构建。


## 实现与证据

- 基点 `a2848f0`。从 [AOSP SurfaceView 的 BLAST/SurfaceControl 合成路径](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-16.0.0_r2/core/java/android/view/SurfaceView.java) 取“几何和 buffer 同一提交”的契约。Bitmap/IOSurface 的 Cocoa 提交携带宿主及 drawable 代际。AppKit 消费者使用同一无动画事务设置当前 geometry 和 contents，淘汰已经跨越宿主切换或 drawable resize 的旧回调，并在拒绝分支释放其帧租约。上次已显示图像仍可在拖动窗口时随几何缩放。没有把固定 producer buffer 尺寸当显示尺寸，也没有增加等待或轮询。
- `MACAPK_NO_BUNDLE_SYNC=1 runtime/scripts/build.sh` PASS，rc=0。抽取生产代际判断的本机 `python3 work/upgrade/SURFACE-003/geometry_probe.py` PASS，rc=0。旧宿主和旧 drawable 均命中拒绝条件。离线 `python3 scripts/run_tests.py --group offline --strict --out-dir work/upgrade/SURFACE-003/offline` 42 PASS、0 FAIL、0 SKIP、15 NOT_RUN，rc=0，含三项卡片门禁。
- GUI 用 COMP-001 自建 `comp-surface-media.apk`、独立 root、`MACAPK_SURFACE_IOSURFACE_PRESENT=1` 和 `MACAPK_DEBUG_AUTORESIZE=2:780x500,4:590x430` 真运行。内建 Retina 1710×1112 pt、2×、60 Hz，Sidecar 未连接。观察到 IOSurface 创建、图层可见和两次 resize（780×500、590×430 pt）。测试进程按预定时限终止。交互像素、B 站播放器旋转、跨屏和实际呈现时间为 NOT_RUN，见本卡 `window.log` / `window-bounds.log`。
- 下一项 [GPU-001](GPU-001.md)。
