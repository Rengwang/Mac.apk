# SURFACE-001：稳定 Surface / SurfaceTexture producer 身份与代际

- 状态：DONE
- 前置：ARCH-001、CI-001
- 证据目录：`work/upgrade/SURFACE-001/`

## 目标与修复范围

排查并收敛 `Surface` / `SurfaceView` / `SurfaceTexture` 在 release/recreate 或多图层切换时的代际（generation）与目标路由边界，确保旧 generation 的异步帧不会提交到新目标或释放后的窗口，且不引入第二套平行身份体系。

## 允许修改文件

- `runtime/java/android/view/Surface.java`、`runtime/java/android/view/SurfaceView.java`、`runtime/java/android/graphics/SurfaceTexture.java`
- `runtime/java/javax/microedition/khronos/egl/MacApkGlWindow.java`（EGL10/EGL14 共用的提交调用方）
- `runtime/src/native_methods.cpp`、`runtime/src/cocoa_bridge.mm`、`runtime/translator/src/bionic_shim.cpp`
- `debug/SurfaceTextureRoutingTest.java`、`debug/SurfaceVideoTargetIsolationTest.java`、`debug/native_window_lifetime_test.py`

## 构建与验收命令

1. 离线构建：`MACAPK_NO_BUNDLE_SYNC=1 runtime/scripts/build.sh`
2. 针对性回归：`python3 scripts/run_tests.py --test SurfaceTextureRoutingTest --test SurfaceVideoTargetIsolationTest --test native_window_lifetime_test --strict`
3. 离线总门禁：`python3 scripts/run_tests.py --group offline --strict`（GUI/Sidecar 像素测试在有窗口环境时按需复核，离线未跑如实记 `NOT_RUN`）

## 验收与回退

- [x] EGL 回读提交在连接被替换或消费者释放后拒绝迟到帧，排队缓冲正确归还；针对性回归与 `offline` 门禁全部 `rc=0`。
- **回退**：`git revert` 本卡提交并重新执行 `MACAPK_NO_BUNDLE_SYNC=1 runtime/scripts/build.sh`。

## 实现与证据（2026-11-14）

- 基点 `4272419`。参照 Android 16 / API 36 的 [BufferQueueProducer::queueBuffer / disconnect](https://android.googlesource.com/platform/frameworks/native/+/android-16.0.0_r2/libs/gui/BufferQueueProducer.cpp) 和 [SurfaceTexture.release()](https://developer.android.com/reference/android/graphics/SurfaceTexture#release())。AOSP 在队列锁内验证连接及 buffer slot，消费者 abandon 后禁止新提交。复用本项目已有的 `mProducer` 对象身份，每个 EGL window 实例即一次连接；选定软件回读及 IOSurface 回读后的入队路径。扩大文件范围一处以接入真实 EGL10/EGL14 共用调用点。
- EGL 线程在回读后携带同一个连接实例入队，队列锁与 connect/disconnect/release 共用。旧连接的迟到帧既不替换待消费画面，也不发出帧通知；已被消费者接手的缓冲由消费者归还。release 封闭队列并取走未消费 slot，回收回调在锁外执行；release 与内部解码回收共用状态检查，已销毁 EGL window 的回收丢弃其数组。持有 SurfaceView 私有 EGL 流的 Surface 负责 abandon，其余传入 SurfaceTexture 的消费者仍由创建者管理。
- 本轮未更改 native compositor、ANativeWindow、MediaCodec 的旧全局 sink 或直接提交 IOSurface 的实验分支。回归覆盖提交边界；已进入 Cocoa 的合成事务、窗口关闭/前后台、真实 App 的 GFX/DANMAKU 及 Sidecar 像素验收为 NOT_RUN。

| 验证 | 命令与结果 | 本地证据 |
|---|---|---|
| 基线 | `python3 scripts/run_tests.py --test SurfaceTextureRoutingTest --test SurfaceVideoTargetIsolationTest --test native_window_lifetime_test --strict`，3 PASS，rc=0 | `baseline.log` |
| 旧版反例 | 新增 release 归还断言后用旧 JAR 执行 `python3 scripts/run_tests.py --test SurfaceTextureRoutingTest --strict --out-dir work/upgrade/SURFACE-001/fail-before`，FAIL，rc=1，`abandon must return the queued buffer exactly once` | `fail-before/report.json` |
| 离线构建 | `MACAPK_NO_BUNDLE_SYNC=1 runtime/scripts/build.sh`，PASS，rc=0，`JARS_ID=8826510658a30924`，`NATIVE_ID=954280b075df9764` | `build.log` |
| 目标与总门禁 | `python3 scripts/run_tests.py --group offline --strict --out-dir work/upgrade/SURFACE-001/offline`，42 PASS、0 FAIL、0 SKIP、15 NOT_RUN，rc=0。含两种像素（不透明与半透明）、重建、迟到提交与回收计数 | `offline/report.json` |
| GPU / JNI | `python3 scripts/run_tests.py --test SharedGlFramesJniTest --test shared_gl_frames_test --strict --out-dir work/upgrade/SURFACE-001/gpu`，2 PASS，rc=0，Metal/EGL 像素及原生 pool 生命周期 | `gpu/report.json` |
| 真实 runtime | 用 COMP-001 自建 `comp-surface-media.apk`、`probe_clip.h264` 与独立 Android root 运行 [无窗口 VideoImageSelfTest](../compat/SCENARIOS.md)，PASS，rc=0，5 帧、`eos_q=1`。覆盖解码/像素通道，未走 Surface 合成 | `video.log` |

继续 [SCHED-001](SCHED-001.md)。工作树为交接指定的 `mac_apk_architecture_upgrade`，源码检查点见本卡提交。
