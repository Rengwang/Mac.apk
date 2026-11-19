# SURFACE-002：有界 buffer 所有权与队列同步

- 状态：DONE
- 前置：SURFACE-001
- 证据目录：`work/upgrade/SURFACE-002/`

## 目标与修复范围

复核并收敛 IOSurface / SharedGL 帧池与 ANativeWindow buffer 队列在慢消费、disconnect/abandon 与尺寸切换下的所有权转移与容量上限，防止复用仍在被消费的 buffer 或发生队列泄漏。

## 允许修改文件

- `runtime/src/cocoa_bridge.mm`、`runtime/src/native_methods.cpp`、`runtime/translator/src/bionic_shim.cpp`
- `runtime/java/android/view/Surface.java`、`runtime/java/android/graphics/SurfaceTexture.java`
- `debug/native_window_lifetime_test.py`、`debug/perf/shared_gl_frames_test.mm`

## 构建与验收命令

1. 离线构建：`MACAPK_NO_BUNDLE_SYNC=1 runtime/scripts/build.sh`
2. 针对性回归与离线门禁：`python3 scripts/run_tests.py --group offline --strict`（若环境具备 Metal/GUI 则加跑 `shared_gl_frames_test` / `SharedGlFramesJniTest`）

## 验收与回退

- [x] 队列容量有界、非法/过期 token 被拒绝，`offline` 门禁全部 `rc=0`。
- **回退**：`git revert` 本卡提交并重跑离线构建。


## 实现与证据

- 基点 `696e9ae`。沿用现有四槽 Bitmap → IOSurface ring 及三个待提交事务的全局上限。原实现用 key 和槽序号归还，旧回调可能清掉同 key 重建后的同序号槽，ring map 也保留空对象。现在 map 持有当前 ring，挂起回调持有原 ring 的共享引用。清理在 AppKit 主线程移除 map entry 并标记已退役，旧回调不会复活图层或触碰新 ring，最后一个持有者离开后原 IOSurface 释放。
- 可见图层的 slot 保留到 Core Animation 事务完成，随后仍以 `IOSurfaceIsInUse` 拒绝重用系统占用的表面，参考 [WebKit IOSurface 的使用状态接口](https://github.com/WebKit/WebKit/blob/main/Source/WebCore/platform/graphics/cocoa/IOSurface.h)。被隐藏/退役时释放原 slot。关闭实验路径可用 `MACAPK_SURFACE_IOSURFACE_PRESENT=0`。
- `python3 work/upgrade/SURFACE-002/ring_probe.py` PASS，rc=0。该本机探针抽取生产 ring，使用实际 IOSurface API 验证旧引用、同 key 重建、容量四槽、resize 和最终 map 归零。证据 `ring.log`。GPU/JNI 的 `shared_gl_frames_test` 和 `SharedGlFramesJniTest` PASS，rc=0，见 `checks/report.json`。
- `MACAPK_NO_BUNDLE_SYNC=1 runtime/scripts/build.sh` 首轮 FAIL，rc=2，匿名命名空间嵌套使前置声明类型不完整；修正后最终构建 PASS，rc=0，见 `build-first.log` 与 `build-final.log`。最终 `python3 scripts/run_tests.py --group offline --strict --out-dir work/upgrade/SURFACE-002/offline-final` 为 42 PASS、0 FAIL、0 SKIP、15 NOT_RUN，rc=0。
- 交互窗口与显示帧性能未采集；下一项 [SURFACE-003](SURFACE-003.md)。
