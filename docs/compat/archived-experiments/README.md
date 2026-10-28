# Archived experiments from the other local worktrees

These are source archives, **not enabled features and not instructions to apply them to the current runtime**. The main source snapshot was assembled from `macapk-safe`; it is not the union of every local experiment. This archive preserves meaningful additional work found in `macapk-codex-audit` on 2026-10-28. The older `macapk-live` variants were compared and are described in [INVENTORY.md](INVENTORY.md).

All four patches use the historical commit `b2af6ed167de26ad876e7bed26abbba414e1c572` as their starting point, with the dependency below. They must be reviewed and ported to the current source, whose decoder, native surface identity, alpha handling and fonts have advanced independently. Applying an entire archived file over current source would lose newer fixes.

| Patch | Preserved work | Status / dependency |
| --- | --- | --- |
| `0001-async-owner-only-frame-scheduling.patch` | Async frame handler on the Android looper owner; barrier-aware message skipping; rejected-post retry; optional frame-stall stack logging | Candidate for a separate reviewed integration. Preserves owner-thread drawing; does not use the older off-thread fallback. |
| `0002-p0-direct-egl-surface-experiment.patch` | EGL10 SurfaceView output into dedicated CAMetalLayers, layer geometry/visibility synchronization, experimental layer ordering and release | Experimental, opt-in with `MACAPK_SV_GL_DIRECT=1`, default off in this archived implementation. Not required for patch 0001. |
| `0003-heartbeat-throttle-and-diagnostics-experiment.patch` | Heartbeat tick liveness change; unused full-frame invalidation hook; tiny-damage publishing experiment; thread CPU and GL readback diagnostics | Apply **after 0001** only on the historical baseline. Includes unproven behavior changes, not just passive diagnostics. |
| `0004-limit-local-build-parallelism.patch` | Caps native build parallelism at two jobs | Local build-resource precaution, no application runtime performance benefit. |

The changes are partitioned without duplicating modified source content. Patches 0001 and 0003 share `ViewCanvasDriver.java`: 0001 supplies an intermediate version; 0003 contains its remaining changes and therefore depends on 0001. All other source paths are disjoint between patches. Together, the selected files reproduce the audit tree byte-for-byte. This is an archival completeness check, not proof that the implementation is correct.

`VALIDATION.txt` records successful `git apply --check` and actual patch application to an isolated copy of the historical baseline. `MANIFEST.json` records source and patch SHA-256 hashes. This archival pass did not build or launch an application, modify any source worktree, or deploy any runtime.

## Known limits that must survive publication

- P0 has shown video and lower process CPU in some local samples, but page/fullscreen transitions did not consistently eliminate every readback. Earlier measurements also compared unequal states. No guaranteed CPU reduction or parity with the iOS app is established.
- The P0 release comment claims a unique EGL-surface key, while the implementation actually uses the SurfaceView key. A delayed release can delete a newly recreated layer with the same key. The unused `sDirectLayerIds` field does not fix that race.
- P0 still synchronously dispatches layer creation to AppKit. Main-thread waits, ownership changes, recovery after failed presentation, multiple windows/displays, overlapping transparent surfaces and animation require dedicated regression testing.
- The hierarchy-order helper claims a root-first key but constructs its bits while walking from leaf to root; child indexes are masked to five bits rather than saturated. Ordering has not been proven equivalent to Android surface ordering. Direct layers also receive the below-overlay order without an explicit on-top branch.
- This old P0 path lacks the current safe tree's holder-format alpha preservation work. It must not replace that work or be advertised as fixing danmaku. Video visibility alone is not evidence that danmaku renders.
- Patch 0003 enables an 80 ms tiny-damage publication throttle by default in the archived code; `MACAPK_TINY_PRESENT_MS=0` disables it. Its claimed correctness/performance benefit is unproven. Accumulating all unpublished damage and preserving animation/input latency must be checked before adoption.
- The `macapkForceFullFrame()` hook in patch 0003 has no caller in either archived tree. It is not a completed lifecycle repair.
- `MACAPK_SKIP_GL_READBACK=1` deliberately drops output and can freeze/blacken the picture. It is a measurement switch, never a shipping optimization. Thread diagnostics cover Java-visible threads; they do not measure the GPU or all native work, and their thresholded sum is not total Java CPU.

Before integrating any experiment, add focused regression tests and use a disposable data directory for visual tests. At minimum cover two players/surfaces, creation/destroy/recreation, transparent overlays, horizontal and vertical fullscreen return, hiding/resuming, resizing, display movement and failed decoder/surface startup. Preserve the current decoder configuration and native-window lifetime fixes.

Only source and these explanatory records are included. No APKs, converted code caches, application data, credentials, runtime binaries, recordings or device logs were copied.
