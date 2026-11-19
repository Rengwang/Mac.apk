# MEDIA-001：修复 VideoImageSelfTest EOS 与 MediaExtractor 边界契约

- 状态：DONE
- 前置：ARCH-001、OBS-001
- 证据目录：`work/upgrade/MEDIA-001/`

## 目标与修复范围

1. **`VideoImageSelfTest` / `MediaCodec` EOS 补发（消除 `out_try_again=405` 空转）**：在 `runtime/java/com/macapk/loader/VideoImageSelfTest.java` 输入帧送完后显式送入带 `MediaCodec.BUFFER_FLAG_END_OF_STREAM` 的输入 buffer，让 VideoToolbox 立即吐出被缓存的末尾帧，并在收到 EOS 或全部帧解出后立即跳出循环，避免 400×5ms 轮询超时等待（实测由 `eos_q=0, out_deq=3, out_try_again=405` 优化至 `eos_q=1, out_deq=5, out_try_again=3`，耗时由 >2s 降至 <1s）；同时优化 `MediaCodec.java` 中 `asyncDispatchLoop`，改用 `mAsyncLock.wait()` / `wait(10)` 与 `notifyAll()`，避免 `Thread.sleep(5)` 空轮询。
2. **`MediaExtractor` 边界与非本地 URI 校验（`GAP-MED-001` / `CTR-MEDIA-XFAIL-001`）**：在 `runtime/java/android/media/MediaExtractor.java` 中对 null URI 抛 `IllegalArgumentException`，对非本地 `file://` 的 URI 抛出 `IOException("MediaExtractor: unsupported scheme " + scheme)`，并在 `getTrackFormat(index)`（未初始化抛 `IllegalStateException`，越界抛 `IllegalArgumentException`）与 `selectTrack(index)` / `unselectTrack(index)` 中校验越界抛 `IllegalArgumentException`，将 `contract_baseline.json` 中 `CTR-MEDIA-XFAIL-001` 翻转为 `PASS`。

## 允许修改文件

- `runtime/java/com/macapk/loader/VideoImageSelfTest.java`
- `runtime/java/android/media/MediaCodec.java`
- `runtime/java/android/media/MediaExtractor.java`
- `docs/compat/contract_baseline.json`、`debug/ContractSuiteTest.java`、`debug/CodecObsTest.java`
- 状态与台账文件：`docs/compat/GAPS.md`、`docs/compat/gaps.json`、`docs/compat/capabilities.json`、`docs/compat/CAPABILITIES.md`、`docs/compat/inventory.json`、`docs/tasks/MEDIA-001.md`、`docs/UPGRADE_PLAN.md`、`PROJECT_STATE.md`

## 构建与验收命令

1. 离线构建：`MACAPK_NO_BUNDLE_SYNC=1 runtime/scripts/build.sh`
2. 契约与单测：`python3 scripts/run_contract_tests.py`、`MACAPK_CODEC_OBS=1 MACAPK_VIDEO_IMAGE_SELFTEST=1 build/Mac.apk.app/Contents/Resources/macapk-runtime --apk work/upgrade/COMP-001/fixtures/comp-surface-media.apk`（核验 `eos_q>=1`、`out_deq=5` 且耗时显著下降）
3. 离线总门禁：`python3 scripts/run_tests.py --group offline --strict`

## 验收与回退

- [x] `CTR-MEDIA-XFAIL-001` 翻转为 `PASS`；`VideoImageSelfTest` 送入 EOS 后解出完整 5 帧且消除无意义轮询等待（`eos_q=1, out_deq=5, out_try_again=3`，`RESULT frames=5 ... -> PASS`）。
- [x] `run_tests.py --group offline --strict` 全通过（`Counts: PASS=42 FLAKY_PASS=0 FAIL=0 SKIP=0 NOT_RUN=15`）。
- **回退**：`git revert` 本卡提交并重新执行离线构建。

## 验收记录（2026-11-14 16:50）

- **构建结果**：`MACAPK_NO_BUNDLE_SYNC=1 runtime/scripts/build.sh` 编译通过（exit 0）。
- **契约测试**：`python3 scripts/run_contract_tests.py` -> `PASS: 13, XFAIL: 1, XPASS: 0, REGRESSION: 0`（`CTR-MEDIA-XFAIL-001` 翻转为 PASS）。
- **Selftest 结果**：`MACAPK_CODEC_OBS=1 MACAPK_VIDEO_IMAGE_SELFTEST=1 build/Mac.apk.app/Contents/Resources/macapk-runtime --apk work/upgrade/COMP-001/fixtures/comp-surface-media.apk` -> `[codec-obs] eos_q=1 out_deq=5 out_try_again=3 rel=5 rel_render=0`，`RESULT frames=5 motionDelta=8.572977701822918 refDelta=-1.0 -> PASS`，无 2s 轮询等待。
- **离线总门禁**：`python3 scripts/run_tests.py --group offline --strict` -> `PASS=42, FAIL=0, SKIP=0`（exit 0）。
- **证据存储**：`work/upgrade/MEDIA-001/contract_suite.log`、`work/upgrade/MEDIA-001/video_image_selftest.log`、`work/upgrade/MEDIA-001/run_tests_offline.log`。

---

## GAP-MED-002 增补（2026-11-18）：VideoToolbox / AudioToolbox 硬件编码器闭环

- 状态：DONE（worktree 待 integrator 合入后更新台账）
- 闭环缺口：`GAP-MED-002`（`MediaCodec.createEncoderByType` / `createByCodecName` / `createInputSurface` / `signalEndOfInputStream` / `MediaCodecList` 编码器发现）

### 改动范围

1. **音频 AAC-LC 编码器（`runtime/src/media_decode.mm`）**：实现 `AudioConverterNew`（S16LE PCM -> `kAudioFormatMPEG4AAC`）编码后端与状态保持流式缓冲（`kMacApkNoDataNow`）、2 字节 MPEG-4 `AudioSpecificConfig` 提取及解码器短 ASC `ES_Descriptor` 自动包装、EOS 冲刷输出；通过 `RegisterNativeMediaMethods` 注册 6 个 `nativeAudioEnc*` JNI 方法。
2. **视频 H.264/HEVC 编码器（`runtime/src/video_toolbox.mm`）**：实现 `VTCompressionSessionCreate`（请求硬件编码加速、RealTime、禁用重排、支持码率/帧率/I帧间隔/Profile/码率模式）、`I420`/`NV12`/`YUV420Flexible`/`BGRA32`/`ARGB32`/`ABGR32`/`ARGB` int[] 输入转 `CVPixelBufferRef`、参数集转 Annex-B `csd-0`/`csd-1`、AVCC/HVCC 转 Annex-B、条件变量/`jweak` 事件驱动回调（无轮询等待）、`flush()` 后强制首帧 IDR 与无死锁关闭；通过 `RegisterNativeVideoMethods` 注册 12 个 `nativeVideoEncode*`/`nativeVidEnc*` JNI 方法并缓存 `nativeVideoCapabilities`。
3. **Java 框架层（`NativeMedia.java`、`MediaCodecInfo.java`、`MediaCodecList.java`、`MediaCodec.java`）**：暴露编码器能力表（`OMX.macapk.enc.audio/mp4a-latm`、`OMX.macapk.enc.video/avc`、`OMX.macapk.enc.video/hevc`，含 `EncoderCapabilities` 与 `colorFormats`），补齐 `createEncoderByType`、`configure(..., CONFIGURE_FLAG_ENCODE)`、同步与异步 `setCallback`、编码器 `getOutputBuffer`/`getInputImage`/`setParameters(PARAMETER_KEY_REQUEST_SYNC_FRAME)`、`createInputSurface()`（`SurfaceTexture` + `OnFrameAvailableListener` + `Canvas`/`lockCanvas`/`unlockCanvasAndPost`）、`createPersistentInputSurface()`/`setInputSurface()` 及 `signalEndOfInputStream()` 契约检查。
4. **端到端验证（`debug/MediaCodecEncoderTest.java`）**：验证发现契约、异常边界（null/未知 MIME/`createInputSurface` 非法状态）、AAC 编码->解码往返、H.264 多种像素格式（I420/NV12/BGRA32/YUV420Flexible `getInputImage`）与 `InputSurface`/`PersistentInputSurface`/异步回调/`flush()` 往返、以及 HEVC 编码->解码往返。

### 验收记录（2026-11-18）

- `MACAPK_NO_BUNDLE_SYNC=1 ./runtime/scripts/build.sh` -> exit 0（`CONVERTER_ID=c0207dd09bc1489b FRAMEWORK_ID=268c97bb300b5556 JAVA_ID=1ec69a1e06fc5311 JARS_ID=0f4cdb9be5f1f512 NATIVE_ID=5cce30aa26e85345`）。
- `debug.MediaCodecEncoderTest`（链接 `media_decode.mm`、`video_toolbox.mm`、`video_caps_probe.mm` 真机 JNI）-> exit 0：`ALL MediaCodecEncoderTest CHECKS PASSED`（AAC `asc=1210`、`H.264` I420/NV12/BGRA32/YUV420Flexible `csd0=13B csd1=8B`、`InputSurface` 6 帧、`Async + PersistentInputSurface` 4 帧、`Async AAC` 9 包、`flush()` 恢复 3 帧、`HEVC` 5 帧）。
- `python3 scripts/run_tests.py --group bare_jvm,jvm_launch_sensitive,native_unit --strict` -> exit 0：`Counts: PASS=45 FLAKY_PASS=0 FAIL=0 SKIP=0 NOT_RUN=18`。
