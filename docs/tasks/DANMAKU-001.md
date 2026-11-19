# DANMAKU-001：恢复视频播放页的弹幕显示与开关状态

- 状态：PENDING（层级与取包进程链已有离线修复，交互式完整生命周期未验收）
- 前置：`VIDEO-PWR-001`、`GFX-001`
- 历史原文：`git show d283e5d:docs/tasks/DANMAKU-001.md`（保留 2026-10-29 至 11-05 完整排查、信号返回别名与过程表）

## 问题目标与保留的通用契约

- **目标**：验证开启弹幕时透明层文字持续横向移动，关闭立即消失、再开正常恢复；且在播放、暂停、seek、全屏（播放器右下角“竖屏全屏 / 横屏全屏”，非 macOS 系统全屏）、返回与窗口拖动后状态均正确。
- **已合入必须保留的通用契约**：
  - `SurfaceView` 每个隐藏/可见 Surface 使用稳定 key（不共享“最后一个 owner”）；
  - 视频与弹幕各自独立 `ANativeWindow` / Metal layer，透明弹幕层保留混合 alpha；
  - `Choreographer` ready callback 唤醒 native looper，避免弹幕帧提交后宿主阻塞；
  - `MediaCodec` 仅绑定明确登记的视频 `SurfaceTexture` 目标；
  - `SURFACE-001..003`、`SCHED-001`、`GFX-001`（`326979c`）、`VIDEO-PWR-001`（`5a34b2a`）离线修复均已合入当前分支。

## 范围约束

- 允许修改：`SurfaceView`、`Surface`、`SurfaceTexture`、透明 Metal layer、Cocoa 图层合成、`Choreographer`/looper、`ImageView` level state 及必要回归测试。
- 禁止：按应用包名/类名/资源 ID 加特判、改写 B 站 APK、降低视频帧率或删除弹幕透明层。

## 待 GUI/Sidecar 验收 Checklist

- [ ] 以隔离 `$HOME`/数据目录启动 `build/Mac.apk.app`（位于 Sidecar 副屏，记录实际窗口尺寸与 build ID）。
- [ ] 进入同一视频，分别采集 **弹幕开 → 关 → 再开** 三段日志与视觉截图/录屏，确认按钮状态、Surface key、帧提交与画面可见文字完全一致（不能仅凭线程日志断言）。
- [ ] 验证 **竖屏全屏 → 横屏全屏 → 竖屏全屏 → 退出全屏** 全流程下，弹幕开关与文字连续移动状态不丢失、退出后持续播放 ≥ 30 秒音画同步。

## 下一步

- 与 [GFX-001](GFX-001.md) / [VIDEO-PWR-001](VIDEO-PWR-001.md) 合并在一次 Sidecar 交互式实机验收中完成采集并关单。
- **2026-11-18 合入后（`261118.1447-24a8f2a+dirty`）实测状态**：Sidecar 25s 播放截图（`perf_t3.png` / `perf_t20.png`）视觉确认滚动弹幕与字幕随 HEVC 视频正常显示；新构建下独立“弹幕开 → 关 → 再开”功耗 A/B 与全屏往返仍为 `NOT_RUN`。
