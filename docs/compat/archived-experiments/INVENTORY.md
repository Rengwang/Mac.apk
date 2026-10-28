# Other-worktree inventory, 2026-10-28

The comparison base is commit `b2af6ed167de26ad876e7bed26abbba414e1c572`, shared by the inspected `macapk-safe`, `macapk-codex-audit` and `macapk-live` checkouts. Uncommitted source changes were compared by content; a common HEAD does not imply identical runtime code.

## Meaningful work absent from the safe snapshot

| Area | Audit source | Archive |
| --- | --- | --- |
| Owner-thread async frame dispatch | `android/os/Handler.java`, `android/os/MessageQueue.java`, selected `ViewCanvasDriver.java` blocks | 0001 |
| EGL10 direct SurfaceView producer | `MacApkEGL10.java`, `angle_glue.cpp`, `SurfaceView.java`, `cocoa_bridge.mm` | 0002 |
| Tick-based idle heartbeat, full-frame hook and tiny-damage publishing | Remaining `ViewCanvasDriver.java` changes | 0003 |
| Java-thread CPU / readback attribution and deliberate readback bypass | `ThreadCpuTrace.java` (previously untracked), `MacApkGlWindow.java`, constructor start hook | 0003 |
| Resource-constrained native builds | `runtime/scripts/build.sh` | 0004 |

## Differences intentionally not duplicated

The audit copies of `AssetManager.java`, `TypedArray.java`, `ImageView.java`, `ResourceIndex.java` and `ViewTextDump.java` matched the safe copies at inspection. Their fixes are already present in the source snapshot and are not separate missing features.

The audit `Resources.java` changes are already in safe; safe additionally contains the icon-font resource inflater. The audit `Canvas.java` changes are also present or superseded in safe, which has an additional font-quantization correction and opt-in PUA diagnostics. Copying the audit versions would remove those newer changes.

The audit `video_toolbox.mm` is an older decoder revision. Safe retains the important shared-csd SPS/PPS fix and has additional configuration/framing validation. In particular, safe normalizes output NAL lengths while tracking input configuration; the older audit code does not preserve that newer implementation. No decoder replacement patch was produced.

Safe also independently contains native-window identity/lifetime and requested-alpha work that the older P0 experiment does not include. The P0 archive is a historical implementation to study and port selectively, not an alternative current runtime.

## What the older live tree contributes

`macapk-live` has the same diagnostic and tiny-damage experiments as the audit tree. Its resource changes are older subsets of the fixes already in safe. The significant differing implementations are superseded:

- Its frame-stall/rejected-post path could call `doFrame()` directly on the OS render thread while an owner-looper message was pending. This defeats owner-thread serialization and risks concurrent traversal. The audit version in 0001 retries/logs instead and uses an actual async owner-only handler.
- Its direct layer creation treated Android surface pixels as Cocoa points and multiplied drawable dimensions by Retina scale again. Audit corrected that conversion and added ongoing geometry/visibility synchronization.
- Its direct layers lacked the audit destroy/release path. Audit improves that gap but still has the same-key delayed-release race described in README.md.
- Its direct-layer EGL identity used `identityHashCode(Surface)` rather than the key used for framework surface geometry pulls. The audit version uses the geometry key, with the lifetime risk above.

These older alternatives were not duplicated as competing patches: they add no verified capability missing from the newer archive and retain identified correctness problems. Their exclusion is deliberate; no claim is made that the safe tree contains every prior experimental implementation.

## Evidence and publication boundaries

This inventory is supported by source diffs and the following local engineering records: `Workbuddy审核与后续修复记录_2026-10-28.md`, `P0性能复核与实验记录_2026-10-28.md`, `交接指南_性能优化与P0实验_2026-10-28.md`, and `Workbuddy本轮复核与解码边界修复_2026-10-28.md`. Those records describe different moments and sometimes correct earlier hypotheses; their benchmark figures must not be combined into one matched comparison.

No other worktree was reset, merged, built or deployed during this inventory. The manifest binds the archived source to its contents. It does not attest to complete application compatibility, successful danmaku rendering, a matched native-iOS performance result, or production readiness of P0.
