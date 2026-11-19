# SCHED-001：收敛 Choreographer / MessageQueue 调度与空闲唤醒

- 状态：DONE
- 前置：ARCH-001、CI-001
- 证据目录：`work/upgrade/SCHED-001/`

## 目标与修复范围

收敛 `Choreographer`、`Looper`、`MessageQueue` 与 `MediaCodec.asyncDispatchLoop` 中的轮询/延迟回调或空闲 vsync 唤醒开销（如消除不必要固定 `Thread.sleep(5)` 忙等或无效唤醒），保持同步屏障、延迟 deadline 与取消语义正确。

## 允许修改文件

- `runtime/java/android/view/Choreographer.java`、`runtime/java/android/os/MessageQueue.java`、`runtime/java/android/os/Handler.java`、`runtime/java/android/media/MediaCodec.java`
- `runtime/src/cocoa_bridge.mm`
- `debug/OwnerFrameBarrierTest.java`、`debug/idle_present_policy_test.cpp`

## 构建与验收命令

1. 离线构建：`MACAPK_NO_BUNDLE_SYNC=1 runtime/scripts/build.sh`
2. 针对性回归：`python3 scripts/run_tests.py --test OwnerFrameBarrierTest --test idle_present_policy_test --strict`
3. 离线总门禁：`python3 scripts/run_tests.py --group offline --strict`

## 验收与回退

- [x] 主线程取消、到期顺序及屏障回归与 `offline` 门禁均 `rc=0`。
- **回退**：`git revert` 本卡提交并重跑离线构建。


## 实现与验证（2026-11-14）

- 基点 `eb0822c`。依据 Android 16 / API 36 [Choreographer.CallbackQueue](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-16.0.0_r2/core/java/android/view/Choreographer.java)，主线程队列保留调用方 token，按 action/token 身份（null 为通配）取消，并在每个 phase 内稳定地按 deadline 派发。取消与待处理队列提取使用同一锁，回调在锁外执行。
- 基线两项目标测试 PASS，rc=0。加断言后用旧 JAR 执行 `python3 scripts/run_tests.py --test OwnerFrameBarrierTest --strict --out-dir work/upgrade/SCHED-001/fail-before`，FAIL，rc=1，两个 token 共用 action 时本应剩一次回调，实测零次。
- `MACAPK_NO_BUNDLE_SYNC=1 runtime/scripts/build.sh` PASS，rc=0，`JARS_ID=7d06d77782d217e0`。随后 `python3 scripts/run_tests.py --group offline --strict --out-dir work/upgrade/SCHED-001/offline`，42 PASS、0 FAIL、0 SKIP、15 NOT_RUN，rc=0。含 `OwnerFrameBarrierTest`、`idle_present_policy_test` 与新断言。证据在本卡目录。
- 本次度量的是同一 action 在取消单一 token 后保留的回调次数（0 → 1），以及固定 50 ms 延迟的截止顺序。宿主 GUI vsync、CPU 唤醒率和耗时改善为 NOT_RUN。按用户最新优先级，下一张为 [RES-001](RES-001.md)，GPU/CACHE 候选保持待激活。
