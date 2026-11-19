// android.media.MediaCodec -- the low-level codec API. AOSP frameworks/base
// media/java/android/media/MediaCodec.java.
//
// Audio (AAC/MPEG) and video (H.264/HEVC/VP9/AV1/MPEG-4/MPEG-2/H.263) are both
// real: configure() below opens a real decoder (AudioToolbox AudioConverter
// or VideoToolbox VTDecompressionSession, via com.macapk.loader.NativeMedia)
// for any mime the corresponding native backend reports as supported, and the
// synchronous dequeue/queue methods actually decode. A mime neither backend
// recognizes (an unsupported audio codec, or a video mime this host's
// VideoToolbox cannot decode -- VP8 is the notable current gap, tracked for a
// software libvpx path) falls back to the prior honest "no codec" stub:
// dequeueInputBuffer returns TRY_AGAIN forever and output buffers stay empty,
// matching a device with no usable HW/SW codec for that format rather than an
// unresolved-symbol abort.
//
// Video specifics: VideoToolbox decodes asynchronously with temporal
// (B-frame) reordering enabled, so a queued access unit's decoded frame
// surfaces later via NativeMedia.nativeVidPoll rather than synchronously
// inside queueInputBuffer -- see dequeueOutputBuffer/tryDequeueOutputLocked.
// releaseOutputBuffer(index, true) composites the frame onto the configured
// Surface for real (video_toolbox.mm -> cocoa_bridge.mm's
// CocoaPresentVideoFrame); with no Surface configured, getOutputImage
// returns a real YUV_420_888 Image (3 planes, real per-plane row/pixel
// strides, real per-frame geometry and timestamp -- see NativeVideoImage),
// converted in Java from the same decoded BGRA VideoToolbox already produces
// for the Surface path, because that is the only pixel format the shared
// native session hands out today. CLEAR (unencrypted) content only:
// queueSecureInputBuffer here does not decrypt anything, matching this
// project's no-DRM-circumvention boundary.
//
// Asynchronous mode (setCallback) rides the SAME real backends for both
// audio and video: asyncDispatchLoop() below drives the synchronous
// dequeue/queue pair proactively and pushes availability through Callback
// instead of requiring the app to poll, so an ExoPlayer/Media3-class
// AsynchronousMediaCodecAdapter (the default on modern targets, and what a
// media3 app like PlayTube uses) gets real decoded frames instead of a
// permanently-silent callback.
package android.media;
import java.nio.ByteBuffer;

public final class MediaCodec {
    public static final int BUFFER_FLAG_KEY_FRAME = 1;
    public static final int BUFFER_FLAG_SYNC_FRAME = 1;
    public static final int BUFFER_FLAG_CODEC_CONFIG = 2;
    public static final int BUFFER_FLAG_END_OF_STREAM = 4;
    public static final int BUFFER_FLAG_PARTIAL_FRAME = 8;
    public static final int BUFFER_FLAG_DECODE_ONLY = 32;

    public static final int CONFIGURE_FLAG_ENCODE = 1;
    public static final int CONFIGURE_FLAG_USE_BLOCK_MODEL = 2;

    public static final int CRYPTO_MODE_UNENCRYPTED = 0;
    public static final int CRYPTO_MODE_AES_CTR = 1;
    public static final int CRYPTO_MODE_AES_CBC = 2;

    public static final int INFO_TRY_AGAIN_LATER = -1;
    public static final int INFO_OUTPUT_FORMAT_CHANGED = -2;
    public static final int INFO_OUTPUT_BUFFERS_CHANGED = -3;

    public static final int VIDEO_SCALING_MODE_SCALE_TO_FIT = 1;
    public static final int VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING = 2;

    public static final String PARAMETER_KEY_REQUEST_SYNC_FRAME = "request-sync";
    public static final String PARAMETER_KEY_VIDEO_BITRATE = "video-bitrate";
    public static final String PARAMETER_KEY_SUSPEND = "drop-input-frames";
    public static final String PARAMETER_KEY_OFFSET_TIME = "time-offset-us";

    public static final class BufferInfo {
        public int offset;
        public int size;
        public long presentationTimeUs;
        public int flags;
        public void set(int newOffset, int newSize, long newTimeUs, int newFlags) {
            this.offset = newOffset;
            this.size = newSize;
            this.presentationTimeUs = newTimeUs;
            this.flags = newFlags;
        }
    }

    // AOSP async-mode callback. Subclassable; fires for real once a real
    // decoder (audio or video) is open -- see asyncDispatchLoop.
    public static abstract class Callback {
        public abstract void onInputBufferAvailable(MediaCodec codec, int index);
        public abstract void onOutputBufferAvailable(MediaCodec codec, int index, BufferInfo info);
        public abstract void onError(MediaCodec codec, CodecException e);
        public abstract void onOutputFormatChanged(MediaCodec codec, MediaFormat format);

        /** API 34. Fired instead of onError when the failure was a DECRYPT
         *  failure. Never here: Mac.apk implements no DRM (see MediaDrm), so no
         *  sample is ever decrypted and none can fail to be. AOSP's default
         *  body forwards to onError, and this one does the same, so an app that
         *  only overrode onError still hears about it. */
        public void onCryptoError(MediaCodec codec, CryptoException e) {
            onError(codec, new CodecException(
                    e == null ? 0 : e.getErrorCode(),
                    e == null ? "crypto error" : e.getMessage()));
        }

        /** API 34. Delivers the codec's final metrics just before it is
         *  reconfigured or released. AOSP's default body is empty. */
        public void onMetricsFlushed(MediaCodec codec, android.os.PersistableBundle metrics) { }

        /** API 34, block model only. Never here: the block model cannot be
         *  configured (see configure()). AOSP's default body is empty. */
        public void onOutputBuffersAvailable(MediaCodec codec, int index,
                java.util.ArrayDeque<BufferInfo> infos) { }
    }
    public static interface OnFrameRenderedListener {
        void onFrameRendered(MediaCodec codec, long presentationTimeUs, long nanoTime);
    }
    public static interface OnFirstTunnelFrameReadyListener {
        void onFirstTunnelFrameReady(MediaCodec codec);
    }

    // AOSP MediaCodec.CodecException -- thrown on configure/queue failures.
    public static final class CodecException extends IllegalStateException {
        public static final int ERROR_INSUFFICIENT_RESOURCE = 1100;
        public static final int ERROR_RECLAIMED = 1101;
        private final int mErrorCode;
        private final boolean mRecoverable;
        private final boolean mTransient;
        CodecException(int errorCode, String detail) {
            super(detail);
            mErrorCode = errorCode;
            mRecoverable = false;
            mTransient = false;
        }
        public int getErrorCode() { return mErrorCode; }
        public boolean isRecoverable() { return mRecoverable; }
        public boolean isTransient() { return mTransient; }
        public String getDiagnosticInfo() { return getMessage(); }
    }
    // AOSP MediaCodec.CryptoInfo -- per-sample encryption metadata used by
    // queueSecureInputBuffer and MediaExtractor.getSampleCryptoInfo. Mac.apk
    // never produces encrypted samples, but the type must resolve.
    public static final class CryptoInfo {
        public int numSubSamples;
        public int[] numBytesOfClearData;
        public int[] numBytesOfEncryptedData;
        public byte[] key;
        public byte[] iv;
        public int mode;
        public CryptoInfo() {}
        public void set(int newNumSubSamples, int[] newNumBytesOfClearData,
                        int[] newNumBytesOfEncryptedData, byte[] newKey, byte[] newIV, int newMode) {
            numSubSamples = newNumSubSamples;
            numBytesOfClearData = newNumBytesOfClearData;
            numBytesOfEncryptedData = newNumBytesOfEncryptedData;
            key = newKey;
            iv = newIV;
            mode = newMode;
        }
        public static final class Pattern {
            private int mEncryptBlocks;
            private int mSkipBlocks;
            public Pattern(int blocksToEncrypt, int blocksToSkip) {
                mEncryptBlocks = blocksToEncrypt;
                mSkipBlocks = blocksToSkip;
            }
            public void set(int blocksToEncrypt, int blocksToSkip) {
                mEncryptBlocks = blocksToEncrypt;
                mSkipBlocks = blocksToSkip;
            }
            public int getEncryptBlocks() { return mEncryptBlocks; }
            public int getSkipBlocks() { return mSkipBlocks; }
        }
        private Pattern mPattern;
        public void setPattern(Pattern newPattern) { mPattern = newPattern; }
        /** API 31. AOSP: the pattern set by setPattern, or null. It used to be
         *  dropped -- setPattern stored nothing -- so an app that set a pattern
         *  and read it back was told it had set none. */
        public Pattern getPattern() { return mPattern; }
    }

    public static final class CryptoException extends RuntimeException
            implements MediaDrmThrowable {
        private final int mErrorCode;
        private final CryptoInfo mCryptoInfo;
        public CryptoException(int errorCode, String detailMessage) {
            this(errorCode, detailMessage, null);
        }
        /** API 34. AOSP's newer form carries the CryptoInfo of the sample that
         *  failed, so an app can tell WHICH subsample the plugin rejected. */
        public CryptoException(int errorCode, String detailMessage, CryptoInfo cryptoInfo) {
            super(detailMessage);
            mErrorCode = errorCode;
            mCryptoInfo = cryptoInfo;
        }
        public int getErrorCode() { return mErrorCode; }
        /** @Nullable in AOSP: null when the exception was raised without one. */
        public CryptoInfo getCryptoInfo() { return mCryptoInfo; }
    }

    private MediaCodec() {}
    private MediaCodec(String name) {
        mName = name;
        if (name != null && name.startsWith("OMX.macapk.enc.")) {
            mIsEncoder = true;
        }
    }
    private String mName = "macapk.null.codec";

    /** Can this build actually decode `mime`?
     *
     *  Deliberately keyed on the SAME predicate configure() uses below, not on
     *  MediaCodecList: (a) it cannot drift from configure(), and (b) it avoids
     *  making the first createDecoderByType call trigger MediaCodecList's
     *  static init and its VTDecompressionSessionCreate trial probes on
     *  whatever thread got there first. */
    private static boolean macapkCanDecode(String mime) {
        if (mime == null) return false;
        if (MediaFormat.MIMETYPE_AUDIO_AAC.equals(mime)
         || MediaFormat.MIMETYPE_AUDIO_MPEG.equals(mime)
         || MediaFormat.MIMETYPE_AUDIO_OPUS.equals(mime)
         || MediaFormat.MIMETYPE_AUDIO_FLAC.equals(mime)
         || MediaFormat.MIMETYPE_AUDIO_ALAC.equals(mime)
         || MediaFormat.MIMETYPE_AUDIO_AC3.equals(mime)
         || MediaFormat.MIMETYPE_AUDIO_EAC3.equals(mime)
         || MediaFormat.MIMETYPE_AUDIO_AMR_NB.equals(mime)
         || MediaFormat.MIMETYPE_AUDIO_AMR_WB.equals(mime)
         || MediaFormat.MIMETYPE_AUDIO_G711_ALAW.equals(mime)
         || MediaFormat.MIMETYPE_AUDIO_G711_MLAW.equals(mime)
         || MediaFormat.MIMETYPE_AUDIO_RAW.equals(mime)
         || MediaFormat.MIMETYPE_AUDIO_VORBIS.equals(mime)) return true;
        if (mime.startsWith("video/")) {
            // A stale runtime can make the native probe throw UnsatisfiedLinkError;
            // treating that as "cannot decode" is the honest answer and matches
            // what MediaCodecList's provider already does with it.
            try { return isVideoMimeSupported(mime); } catch (Throwable t) { return false; }
        }
        return false;
    }

    /** AOSP: "@throws IOException if the codec cannot be created".
     *
     *  We used to return a codec for ANY mime and then silently drop every
     *  access unit -- queueInputBuffer discarded and dequeueOutputBuffer
     *  returned TRY_AGAIN forever, so an app waited for output that could never
     *  come. That is a HANG with no error anywhere, which is strictly worse than
     *  the loud, in-contract failure a device gives.
     *
     *  Scope is deliberate: only the by-TYPE factories throw. createByCodecName
     *  never does -- every media3/ExoPlayer/VLC path in the corpus walks
     *  MediaCodecList first and instantiates by NAME, so a throw there would put
     *  an exception in front of code the registry has already gated, including
     *  VLC's JNI. */
    public static MediaCodec createDecoderByType(String type) throws java.io.IOException {
        java.util.Objects.requireNonNull(type, "type");   // AOSP documents NPE on null
        if (!macapkCanDecode(type)) {
            throw new java.io.IOException("no decoder for " + type + " on this host");
        }
        return new MediaCodec("OMX.macapk.dec." + type);
    }

    private static boolean macapkCanEncode(String mime) {
        if (mime == null) return false;
        if (MediaFormat.MIMETYPE_AUDIO_AAC.equals(mime)) return true;
        if (mime.startsWith("video/")) {
            try { return isVideoEncodeMimeSupported(mime); } catch (Throwable t) { return false; }
        }
        return false;
    }

    private static boolean isVideoEncodeMimeSupported(String mime) {
        String[] supported = com.macapk.loader.NativeMedia.nativeVideoEncodeSupportedMimes();
        if (supported == null) return false;
        for (String s : supported) {
            if (mime.equals(s)) return true;
        }
        return false;
    }

    public static MediaCodec createEncoderByType(String type) throws java.io.IOException {
        java.util.Objects.requireNonNull(type, "type");
        if (!macapkCanEncode(type)) {
            throw new java.io.IOException("no encoder for " + type + " on this host");
        }
        return new MediaCodec("OMX.macapk.enc." + type);
    }

    public static MediaCodec createByCodecName(String name) throws java.io.IOException {
        java.util.Objects.requireNonNull(name, "name");
        return new MediaCodec(name);
    }

    public String getName() { return mName; }
    public String getCanonicalName() { return mName; }

    // ---- Audio-decoder backing (AudioToolbox AudioConverter via NativeMedia).
    // For a supported audio mime (AAC/MP3) start() opens a native decoder and the
    // sync buffer protocol below decodes each queued access unit to S16 PCM. For
    // an unsupported mime (an audio codec neither backend knows, or a video mime
    // this host can't decode) mDec/mVidDec both stay 0 and the class behaves as
    // the honest "no codec" shape (dequeueOutputBuffer never yields a buffer).
    private long mDec = 0;
    private int  mFormatId = 0, mSampleRate = 0, mChannels = 0;
    private byte[] mCsd0 = null;
    private boolean mStarted = false, mFormatReported = false;
    // MACAPK_MEDIA_PROBE (com.macapk.loader.MediaStats): playback-attempt
    // counters for the owner-issue checker. null unless the probe is on, so
    // every hook below is a no-op on a normal launch.
    private final com.macapk.loader.MediaStats.Codec mStat = com.macapk.loader.MediaStats.newCodec();
    private String mStatMime = "";
    static final boolean CODEC_OBS = "1".equals(System.getenv("MACAPK_CODEC_OBS"));
    private final ObsRecorder mObs = CODEC_OBS ? new ObsRecorder() : null;

    // ---- Video-decoder backing (VideoToolbox via NativeMedia). See the file
    // header. mIsVideo is decided at configure() time from
    // NativeMedia.nativeVideoSupportedMimes() (the single source of truth this
    // lane and the MediaCodecList/MediaCodecInfo lane both read); mVidDec is
    // the actual native session, opened at start().
    private long mVidDec = 0;
    private boolean mIsVideo = false;
    private boolean mIsEncoder = false;
    private boolean mConfigured = false;
    private boolean mReleased = false;
    private long mEnc = 0;     // AudioToolbox AAC encoder handle
    private long mVidEnc = 0;  // VideoToolbox encoder handle
    private int mEncBitrate = 0;
    private float mEncFrameRate = 30.0f;
    private float mEncIFrameInterval = 1.0f;
    private int mEncColorFormat = MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible;
    private int mEncStride = 0;
    private int mEncSliceHeight = 0;
    private int mEncProfile = 0;
    private int mEncBitrateMode = MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR;
    private boolean mEncEosQueued = false;
    private MediaFormat mInputFormat;
    private MediaFormat mEncOutFormat;
    private android.view.Surface mInputSurface;
    private boolean mOwnsInputSurface = false;
    private String mVidMime;
    private int mVidWidth, mVidHeight;
    private byte[] mCsd0Vid, mCsd1Vid;
    private static final class OutputTarget {
        final android.view.Surface surface;
        final boolean hasSurface;
        final long gen;
        OutputTarget(android.view.Surface surface, boolean hasSurface, long gen) {
            this.surface = surface;
            this.hasSurface = hasSurface;
            this.gen = gen;
        }
    }
    private final java.util.concurrent.atomic.AtomicLong mCodecSurfaceGen =
            new java.util.concurrent.atomic.AtomicLong(1L);
    private volatile OutputTarget mOutputTarget = new OutputTarget(null, false, 1L);

    private OutputTarget setOutputTargetLocked(android.view.Surface surface) {
        long gen = mCodecSurfaceGen.incrementAndGet();
        OutputTarget next = new OutputTarget(surface, surface != null && surface.isValid(), gen);
        mOutputTarget = next;
        return next;
    }

    private void bumpOutputGen() {
        long gen = mCodecSurfaceGen.incrementAndGet();
        OutputTarget cur = mOutputTarget;
        mOutputTarget = new OutputTarget(cur.surface, cur.hasSurface, gen);
    }
    // Parallel to mOut (index-for-index): holds the native retained
    // CVPixelBufferRef (as a long) for whichever output slots currently carry
    // a polled-but-not-yet-released video frame. 0 = empty slot. Video output
    // never populates mOut itself (getOutputBuffer(index) returns null for
    // video, matching AOSP's "null if configured with an output surface"
    // contract -- and even without a surface there is no meaningful ByteBuffer
    // view of an opaque native pixel buffer, see getOutputImage instead).
    private long[] mOutPixbuf;
    // Parallel to mOutPixbuf: the presentation timestamp (microseconds, from
    // nativeVidPoll) of whichever frame currently occupies that slot, so
    // getOutputImage can hand it to Image.getTimestamp() instead of the
    // fixed 0 a shape-only stand-in would return.
    private long[] mOutPts;

    // IN_CAP is the FLOOR for an input buffer, not the size. A real MediaCodec
    // sizes its input buffers from the component, and the client can raise that
    // with MediaFormat.KEY_MAX_INPUT_SIZE ("max-input-size"); AOSP's own
    // ACodec/CCodec honour that key when allocating the input port. We ignored
    // it and pinned every input buffer at 64 KiB, which is fine for audio (an
    // AAC/Opus access unit is a couple of KiB) and far too small for video.
    //
    // Measured on PlayTube 1.3.5, 2026-08-18: media3 hands us the size it needs
    // (MediaCodecVideoRenderer.getMediaFormat reads CodecMaxValues.inputSize
    // into KEY_MAX_INPUT_SIZE via MediaFormatUtil.maybeSetInteger -- confirmed
    // in that APK's OWN bytecode, not from upstream source), then reads the
    // buffer back through getInputBuffer() and throws when it is short:
    //   MediaCodecVideoRenderer: Video codec error
    //   DecoderInputBuffer$InsufficientCapacityException: Buffer too small
    //       (65536 < 81979)   [then 87702, then 92900]
    // Video decode died on the first keyframe while audio kept playing, which
    // is exactly the "audio works, video is black" the owner reported.
    private static final int IN_BUFS = 8, OUT_BUFS = 8, VIDEO_OUT_BUFS = 24, IN_CAP = 1 << 16;
    /** Per-instance input capacity, resolved in configure(). Never below
     *  IN_CAP, so every audio path keeps the size it has always had. */
    private int mInCap = IN_CAP;
    private ByteBuffer[] mIn;
    private ByteBuffer[] mOut;
    private final java.util.ArrayDeque<Integer> mInFree  = new java.util.ArrayDeque<>();
    private final java.util.ArrayDeque<Integer> mOutFree = new java.util.ArrayDeque<>();
    private static final class OutEntry {
        final byte[] pcm; final long pts; final int flags;
        OutEntry(byte[] p, long t, int f) { pcm = p; pts = t; flags = f; }
    }
    private final java.util.ArrayDeque<OutEntry> mOutQ = new java.util.ArrayDeque<>();

    // ---- Asynchronous (Callback) mode --------------------------------------
    // AOSP: calling setCallback(Callback) before start() (frameworks/base
    // media/java/android/media/MediaCodec.java, "Asynchronous processing
    // using buffers") switches the codec into async mode, where the codec
    // itself PUSHES buffer availability through Callback instead of the app
    // polling dequeueInputBuffer/dequeueOutputBuffer. The AAC/MPEG + video
    // decode backends below are real and already fully driven by the
    // synchronous dequeue/queue pair (tryDequeueOutputLocked, shared by both
    // the sync and async paths); async mode only needed something to call
    // that same pair proactively -- see asyncDispatchLoop(). mAsyncLock also
    // guards the shared buffer-pool state against the one real cross-thread
    // hazard: stop()/reset()/release()/flush() called from a different thread
    // while the async dispatch thread (or, in sync mode, a second app thread
    // -- AOSP documents separate input/output threads as supported even in
    // sync mode) is mid-access.
    private final Object mAsyncLock = new Object();
    private Callback mCallback;
    private Thread mAsyncThread;
    private volatile boolean mAsyncRunning;
    private volatile boolean mAsyncPaused;
    private int mVidWaiters = 0;
    private volatile long mFlushSeq = 0;
    private long[] mStashedVidFrame = null;

    // Descriptor (Landroid/media/MediaFormat;Landroid/view/Surface;Landroid/media/MediaCrypto;I)V.
    public void configure(MediaFormat format, android.view.Surface surface,
                          MediaCrypto crypto, int flags) {
        if (mReleased) throw new IllegalStateException("MediaCodec has been released");
        if (format == null) return;
        String mime = format.getString(MediaFormat.KEY_MIME, "");
        boolean flagEncode = (flags & CONFIGURE_FLAG_ENCODE) != 0;
        if (flagEncode) mIsEncoder = true;
        releaseOwnedInputSurface();

        mStatMime = mime;
        mFormatId = 0;
        mIsVideo = false;
        mInputFormat = new MediaFormat(format);
        mEncOutFormat = null;
        mEncEosQueued = false;

        if (mIsEncoder) {
            if (MediaFormat.MIMETYPE_AUDIO_AAC.equals(mime)) {
                mFormatId = 1;
            } else if (mime.startsWith("video/") && isVideoEncodeMimeSupported(mime)) {
                mIsVideo = true;
            }
            mSampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE, 44100);
            mChannels   = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT, 2);
            mEncBitrate = format.getInteger(MediaFormat.KEY_BIT_RATE, 0);
            mEncFrameRate = format.getFloat(MediaFormat.KEY_FRAME_RATE, 30.0f);
            mEncIFrameInterval = format.getFloat(MediaFormat.KEY_I_FRAME_INTERVAL, 1.0f);
            mEncColorFormat = format.getInteger(MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);
            mVidMime = mIsVideo ? mime : null;
            mVidWidth  = format.getInteger(MediaFormat.KEY_WIDTH, 0);
            mVidHeight = format.getInteger(MediaFormat.KEY_HEIGHT, 0);
            mEncStride = format.getInteger(MediaFormat.KEY_STRIDE, mVidWidth);
            mEncSliceHeight = format.getInteger(MediaFormat.KEY_SLICE_HEIGHT, mVidHeight);
            mEncProfile = format.getInteger(MediaFormat.KEY_PROFILE, 0);
            mEncBitrateMode = format.getInteger(MediaFormat.KEY_BIT_RATE_MODE,
                    MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR);
            mInCap = resolveEncoderInputCapacity(
                    format, mIsVideo, mEncColorFormat, mVidWidth, mVidHeight, mEncStride, mEncSliceHeight);
            setOutputTargetLocked(null);
            mCsd0 = null;
            mCsd0Vid = null;
            mCsd1Vid = null;
            mConfigured = true;
            return;
        }

        // formatId 1-2 pre-date this table; 3-13 route to the AudioToolbox
        // (and, for Vorbis, stb_vorbis) formats media_decode.mm's
        // macapk_md_dec_open added -- one mime compare per row, matching
        // docs/audiotoolbox-codec-widening_2026-08-16.md section 2's
        // formatId<->AudioFormatID map exactly. Only Vorbis (13) needs more
        // than a mime compare here: its csd is packed differently below.
        if (MediaFormat.MIMETYPE_AUDIO_AAC.equals(mime)) mFormatId = 1;
        else if (MediaFormat.MIMETYPE_AUDIO_MPEG.equals(mime)) mFormatId = 2;
        else if (MediaFormat.MIMETYPE_AUDIO_OPUS.equals(mime)) mFormatId = 3;
        else if (MediaFormat.MIMETYPE_AUDIO_FLAC.equals(mime)) mFormatId = 4;
        else if (MediaFormat.MIMETYPE_AUDIO_ALAC.equals(mime)) mFormatId = 5;
        else if (MediaFormat.MIMETYPE_AUDIO_AC3.equals(mime)) mFormatId = 6;
        else if (MediaFormat.MIMETYPE_AUDIO_EAC3.equals(mime)) mFormatId = 7;
        else if (MediaFormat.MIMETYPE_AUDIO_AMR_NB.equals(mime)) mFormatId = 8;
        else if (MediaFormat.MIMETYPE_AUDIO_AMR_WB.equals(mime)) mFormatId = 9;
        else if (MediaFormat.MIMETYPE_AUDIO_G711_ALAW.equals(mime)) mFormatId = 10;
        else if (MediaFormat.MIMETYPE_AUDIO_G711_MLAW.equals(mime)) mFormatId = 11;
        else if (MediaFormat.MIMETYPE_AUDIO_RAW.equals(mime)) mFormatId = 12;
        else if (MediaFormat.MIMETYPE_AUDIO_VORBIS.equals(mime)) mFormatId = 13;
        else if (mime.startsWith("video/") && isVideoMimeSupported(mime)) mIsVideo = true;
        // else: unsupported (an audio codec neither backend knows, or a video
        // mime this host's VideoToolbox can't decode) -> honest no-op codec.
        mSampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE, 0);
        mChannels   = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT, 0);
        mInCap = resolveInputCapacity(format, mIsVideo);
        ByteBuffer csd = format.getByteBuffer("csd-0", null);
        if (csd != null) { mCsd0 = new byte[csd.remaining()]; csd.duplicate().get(mCsd0); }
        if (mFormatId == 13) {
            // Vorbis is not AudioConverter-backed (media_decode.mm dispatches
            // it to audio_vorbis_decode.mm's stb_vorbis wrapper instead -- see
            // docs/audiotoolbox-codec-widening_2026-08-16.md section 5), and
            // its opener wants csd-0 (Identification header) AND csd-1 (Setup
            // header) packed into the ONE byte[] nativeDecOpen already
            // accepts, not csd-0 alone: [u32 LE len0][bytes0][u32 LE
            // len1][bytes1], matching media_decode.mm's own
            // parse_vorbis_combined_csd byte for byte (verified against that
            // function's source, not guessed -- section 3b).
            ByteBuffer csd1 = format.getByteBuffer("csd-1", null);
            byte[] c0 = mCsd0 != null ? mCsd0 : new byte[0];
            byte[] c1 = new byte[0];
            if (csd1 != null) { c1 = new byte[csd1.remaining()]; csd1.duplicate().get(c1); }
            mCsd0 = packVorbisCsd(c0, c1);
        }
        if (mIsVideo) {
            mVidMime = mime;
            mVidWidth  = format.getInteger(MediaFormat.KEY_WIDTH, 0);
            mVidHeight = format.getInteger(MediaFormat.KEY_HEIGHT, 0);
            ByteBuffer c0 = format.getByteBuffer("csd-0", null);
            mCsd0Vid = c0 != null ? bufToBytes(c0) : null;
            ByteBuffer c1 = format.getByteBuffer("csd-1", null);
            mCsd1Vid = c1 != null ? bufToBytes(c1) : null;
            OutputTarget next = setOutputTargetLocked(surface);
            // Bind the video plane's geometry to the Surface's owner view (a
            // no-op registry clear for SurfaceTexture-backed and bare
            // Surfaces). Done at configure time because that is the moment
            // the codec-output-to-surface association exists on real Android.
            updateVideoSink(null, next.hasSurface ? next.surface : null);
        } else {
            mVidMime = null;
            setOutputTargetLocked(null);
        }
        mConfigured = true;
    }
    private static byte[] bufToBytes(ByteBuffer b) {
        byte[] out = new byte[b.remaining()];
        b.duplicate().get(out);
        return out;
    }
    // See the mFormatId==13 branch in configure(): media_decode.mm's
    // parse_vorbis_combined_csd reads this exact layout back
    // (rd32 there is also little-endian, byte for byte).
    private static byte[] packVorbisCsd(byte[] c0, byte[] c1) {
        byte[] out = new byte[8 + c0.length + c1.length];
        putLE32(out, 0, c0.length);
        System.arraycopy(c0, 0, out, 4, c0.length);
        putLE32(out, 4 + c0.length, c1.length);
        System.arraycopy(c1, 0, out, 8 + c0.length, c1.length);
        return out;
    }
    private static void putLE32(byte[] buf, int off, int v) {
        buf[off]     = (byte) (v & 0xff);
        buf[off + 1] = (byte) ((v >>> 8) & 0xff);
        buf[off + 2] = (byte) ((v >>> 16) & 0xff);
        buf[off + 3] = (byte) ((v >>> 24) & 0xff);
    }
    // VP8 (libvpx, runtime/src/video_vp8_decode.mm) is a SECOND video
    // backend, parallel to VideoToolbox: statically linked, so -- unlike
    // every VideoToolbox codec -- its availability is not a per-host probe
    // result, it is unconditional. mVidMime (set in configure(), unchanged
    // until the next configure()) is enough to route every call site below
    // to the right backend; no extra field needed.
    private static final String MIME_VP8 = "video/x-vnd.on2.vp8";
    private boolean isVp8() { return MIME_VP8.equals(mVidMime); }
    // VP9 (libvpx, runtime/src/video_vp9_decode.mm) is a THIRD video
    // backend, same shape as VP8: VideoToolbox probes VP9 as genuinely
    // absent on this host (OSStatus -12906 kVTCouldNotFindVideoDecoderErr
    // even fed a real config record -- docs/videotoolbox-probe-audit_2026-
    // 08-16.md section 3), so libvpx (already linked for VP8) serves it
    // instead, also unconditionally available.
    private static final String MIME_VP9 = "video/x-vnd.on2.vp9";
    private boolean isVp9() { return MIME_VP9.equals(mVidMime); }
    // Single source of truth for "can this host decode this mime" -- see
    // com.macapk.loader.NativeMedia.nativeVideoSupportedMimes's own doc:
    // MediaCodecList/MediaCodecInfo are expected to read the SAME
    // NativeMedia methods rather than keep a second, hand-maintained list
    // that could drift from what this class actually opens a decoder for.
    private static boolean isVideoMimeSupported(String mime) {
        if (MIME_VP8.equals(mime)) return true;   // libvpx: always present, see isVp8()'s doc
        if (MIME_VP9.equals(mime)) return true;   // libvpx: always present, see isVp9()'s doc
        String[] mimes = com.macapk.loader.NativeMedia.nativeVideoSupportedMimes();
        if (mimes == null) return false;
        for (String m : mimes) if (m.equals(mime)) return true;
        return false;
    }
    // VP8's and VP9's five natives each (nativeVp{8,9}Open/_Decode/_Poll/
    // _Flush/_Close) are registered by native_methods.cpp calling
    // video_vp8::RegisterNativeVp8Methods / video_vp9::
    // RegisterNativeVp9Methods. VP8's registration call was ORIGINALLY
    // deferred to a separate, concurrently-routed change (v1.0.984's own
    // "WIRING STILL NEEDED" note) and stayed missing until this session
    // landed both calls together (see native_methods.cpp) -- so this catch
    // guarded a REAL, live gap for VP8, not just a theoretical race, and
    // keeping it now (for both engines) costs nothing and survives any
    // future re-ordering of registration vs. first use. Catching here turns
    // a missing registration into "failed to open" (0), which start()'s
    // EXISTING loud-CodecException-on-zero path already handles cleanly --
    // one failure shape, not a raw-link-error one.
    private static long safeVp8Open(String mime, int w, int h, byte[] csd0, byte[] csd1, boolean wantSurface) {
        try {
            return com.macapk.loader.NativeMedia.nativeVp8Open(mime, w, h, csd0, csd1, wantSurface);
        } catch (LinkageError e) {
            return 0;
        }
    }
    private static long safeVp9Open(String mime, int w, int h, byte[] csd0, byte[] csd1, boolean wantSurface) {
        try {
            return com.macapk.loader.NativeMedia.nativeVp9Open(mime, w, h, csd0, csd1, wantSurface);
        } catch (LinkageError e) {
            return 0;
        }
    }
    // Shared by queueInputBuffer's two call sites (a real access unit, and
    // the codec-config+EOS combination). Once mVidDec != 0 the open call
    // already succeeded for whichever backend isVp8()/isVp9() names, so no
    // LinkageError guard is needed here (see safeVp8Open's doc: open is the
    // only site that can observe the not-yet-registered window).
    private void vidDecode(long dec, byte[] au, long presentationTimeUs, boolean eos) {
        if (dec == 0) return;
        if (isVp8()) com.macapk.loader.NativeMedia.nativeVp8Decode(dec, au, presentationTimeUs, eos);
        else if (isVp9()) com.macapk.loader.NativeMedia.nativeVp9Decode(dec, au, presentationTimeUs, eos);
        else com.macapk.loader.NativeMedia.nativeVidDecode(dec, au, presentationTimeUs, eos);
    }
    // AOSP: registering a callback before start() (or after flush(), which
    // must be followed by start() again in async mode -- see flush() below)
    // is what switches this codec into asynchronous mode.
    public void setCallback(Callback cb) {
        synchronized (mAsyncLock) {
            mCallback = cb;
            mAsyncLock.notifyAll();
        }
    }
    public void setCallback(Callback cb, android.os.Handler handler) {
        setCallback(cb);
    }
    public void setOnFrameRenderedListener(OnFrameRenderedListener listener, android.os.Handler handler) {}
    public void setVideoScalingMode(int mode) {}
    // AOSP allows swapping the output surface after configure() (adaptive
    // surface change). Our present path targets one shared per-window video
    // layer for a SurfaceView-owned or bare Surface, and the Surface's own
    // SurfaceTexture when it has one (routeVideoTo, below; see cocoa_bridge.mm's
    // CocoaPresentVideoFrame). The geometry is surface-specific too -- a
    // SurfaceView-owned Surface pins the video plane to that view's on-screen
    // rect (SurfaceView.macapkRegisterVideoSink) -- which is why both must
    // follow every swap.
    public void setOutputSurface(android.view.Surface surface) {
        android.view.Surface previous;
        OutputTarget next;
        synchronized (mAsyncLock) {
            previous = mOutputTarget.surface;
            next = setOutputTargetLocked(surface);
        }
        synchronized (mTimedLock) {
            mTimedLock.notifyAll();
        }
        updateVideoSink(previous, next.hasSurface ? next.surface : null);
    }

    private static void updateVideoSink(android.view.Surface previous, android.view.Surface next) {
        if (previous != null && previous != next && previous.macapkIsRemote()) {
            previous.macapkNoteVideoSinkActive(false);
        }
        if (next != null) {
            next.macapkNoteVideoSinkActive(true);
        }
        routeVideoTo(previous, next);
    }

    // Decoded frames go first to SurfaceTexture's one active video target
    // (most recent registration wins; see SurfaceTexture.macapkOfferDecodedFrame),
    // and only otherwise to the window's video plane. `new Surface(texture)`
    // registers its texture, so a Surface made BEFORE the real output kept the
    // frames: Media3 configures the codec with its PlaceholderSurface (a
    // SurfaceTexture-backed Surface) when prepare() runs before the app's view
    // is attached, then switches here with setOutputSurface, and the video
    // stayed black with its sound playing (GitHub #147). So the target follows
    // this codec's output, as the BufferQueue behind a Surface does on a
    // device: the previous output's texture gives the target up, if it still
    // holds it, and a texture-backed new output takes it. SurfaceView-owned
    // SurfaceTextures are excluded because SurfaceView manages its own sink.
    private static void routeVideoTo(android.view.Surface previous, android.view.Surface next) {
        boolean prevUnowned = previous != null && previous.macapkGetOwnerSurfaceView() == null;
        boolean nextUnowned = next != null && next.macapkGetOwnerSurfaceView() == null;
        android.graphics.SurfaceTexture was =
                prevUnowned ? previous.macapkGetSurfaceTexture() : null;
        android.graphics.SurfaceTexture now =
                nextUnowned ? next.macapkGetSurfaceTexture() : null;
        if (was != null && was != now) was.macapkUnregisterVideoTargetIfSelf();
        // A released texture would displace a live target with one that
        // takes no frames (macapkHasActiveVideoTarget reads it as none).
        if (now != null && !now.isReleased()) now.macapkRegisterVideoTarget();
    }

    private final Object mEncSubmitLock = new Object();

    public void start() {
        synchronized (mAsyncLock) {
            if (mReleased) throw new IllegalStateException("MediaCodec has been released");
            if (mIsEncoder) {
                mEncEosQueued = false;
                mFormatReported = false;
                mOutQ.clear();
                if (mFormatId == 1 && mSampleRate > 0 && mChannels > 0) {
                    mEnc = com.macapk.loader.NativeMedia.nativeAudioEncOpen(
                            mFormatId, mSampleRate, mChannels, mEncBitrate);
                    if (mEnc == 0) {
                        throw new CodecException(0, "AudioToolbox: failed to open AAC encoder");
                    }
                    MediaFormat outFmt = MediaFormat.createAudioFormat(
                            MediaFormat.MIMETYPE_AUDIO_AAC, mSampleRate, mChannels);
                    if (mEncBitrate > 0) outFmt.setInteger(MediaFormat.KEY_BIT_RATE, mEncBitrate);
                    outFmt.setInteger(MediaFormat.KEY_AAC_PROFILE,
                            MediaCodecInfo.CodecProfileLevel.AACObjectLC);
                    byte[] asc = com.macapk.loader.NativeMedia.nativeAudioEncCsd0(mEnc);
                    if (asc != null && asc.length > 0) {
                        outFmt.setByteBuffer("csd-0", ByteBuffer.wrap(asc.clone()));
                        mOutQ.add(new OutEntry(asc.clone(), 0L, BUFFER_FLAG_CODEC_CONFIG));
                    }
                    mEncOutFormat = outFmt;
                } else if (mIsVideo) {
                    mVidEnc = com.macapk.loader.NativeMedia.nativeVidEncOpen(
                            mVidMime, mVidWidth, mVidHeight, mEncBitrate, mEncFrameRate,
                            mEncIFrameInterval, mEncProfile, mEncBitrateMode, this);
                    if (mVidEnc == 0) {
                        throw new CodecException(0, "VideoToolbox: failed to open encoder for " + mVidMime);
                    }
                    MediaFormat outFmt = MediaFormat.createVideoFormat(mVidMime, mVidWidth, mVidHeight);
                    if (mEncBitrate > 0) outFmt.setInteger(MediaFormat.KEY_BIT_RATE, mEncBitrate);
                    outFmt.setFloat(MediaFormat.KEY_FRAME_RATE, mEncFrameRate);
                    mEncOutFormat = outFmt;
                }
                if (mInputSurface != null) {
                    mIn = new ByteBuffer[0];
                } else {
                    mIn = new ByteBuffer[IN_BUFS];
                }
                mOut = new ByteBuffer[OUT_BUFS];
                mOutPixbuf = null;
                mOutPts = null;
                mInFree.clear();
                mOutFree.clear();
                if (mInputSurface == null) {
                    for (int i = 0; i < IN_BUFS; i++) {
                        mIn[i] = ByteBuffer.allocateDirect(mInCap);
                        mInFree.add(i);
                    }
                }
                for (int i = 0; i < OUT_BUFS; i++) mOutFree.add(i);
                mStarted = true;
                com.macapk.loader.MediaStats.codecStart(mStat, mStatMime, mInputSurface != null);
                if (mObs != null) mObs.onStart(mStatMime, mInputSurface != null);
                mAsyncPaused = false;
                mAsyncLock.notifyAll();
                if (mInputSurface instanceof EncoderInputSurface) {
                    ((EncoderInputSurface) mInputSurface).attachCodec(this);
                }
                if (mCallback != null && mAsyncThread == null) {
                    mAsyncRunning = true;
                    mAsyncThread = new Thread(new Runnable() {
                        @Override public void run() { asyncDispatchLoop(); }
                    }, "MediaCodec-async");
                    mAsyncThread.setDaemon(true);
                    mAsyncThread.start();
                }
                return;
            }
            if (mFormatId != 0 && mSampleRate > 0 && mChannels > 0) {
                mDec = com.macapk.loader.NativeMedia.nativeDecOpen(mFormatId, mSampleRate, mChannels, mCsd0);
                if (mDec == 0) {
                    // Loud, not silent -- the same reasoning as the video
                    // branch below (added alongside it), extended here now
                    // that mFormatId covers 11 more formats
                    // (docs/audiotoolbox-codec-widening_2026-08-16.md):
                    // configure() already matched a mime MediaCodecList
                    // advertises as decodable, so a failure to actually open
                    // one here is a real error (bad csd, an unsupported
                    // sample-rate/channel combination for this formatId),
                    // never the expected "unrecognized mime" shape. Without
                    // this, a listed-but-dead audio codec would reproduce
                    // exactly the silent PlayTube-class failure the video
                    // branch's own comment describes, just one mFormatId
                    // family later.
                    throw new CodecException(0, "AudioToolbox: failed to open decoder for formatId " + mFormatId);
                }
            } else if (mIsVideo) {
                boolean wantSurface = mOutputTarget.hasSurface;
                mVidDec = isVp8()
                    ? safeVp8Open(mVidMime, mVidWidth, mVidHeight, mCsd0Vid, mCsd1Vid, wantSurface)
                    : isVp9()
                    ? safeVp9Open(mVidMime, mVidWidth, mVidHeight, mCsd0Vid, mCsd1Vid, wantSurface)
                    : com.macapk.loader.NativeMedia.nativeVidOpen(
                          mVidMime, mVidWidth, mVidHeight, mCsd0Vid, mCsd1Vid, wantSurface);
                if (mVidDec == 0) {
                    // Loud, not silent. AOSP throws from start() when a codec
                    // genuinely cannot be stood up, and this IS that case: at
                    // configure() time isVideoMimeSupported(mVidMime) already
                    // said yes (VideoToolbox reported it decodable on this
                    // host), so a failure here means the csd was unusable or
                    // VTDecompressionSessionCreate itself failed -- a real
                    // error, never the expected "unsupported mime" shape. The
                    // previous version of this class silently dropped every
                    // buffer for ANY video mime, which is exactly the bug
                    // measured on PlayTube ("dispatch #2200, zero
                    // exceptions"); this class must not reproduce that shape
                    // for a codec it claimed to support.
                    throw new CodecException(0, "VideoToolbox: failed to open decoder for " + mVidMime);
                }
            }
            final int outBufs = mIsVideo ? VIDEO_OUT_BUFS : OUT_BUFS;
            mIn  = new ByteBuffer[IN_BUFS];
            mOut = new ByteBuffer[outBufs];
            mOutPixbuf = mIsVideo ? new long[outBufs] : null;
            mOutPts    = mIsVideo ? new long[outBufs] : null;
            mInFree.clear(); mOutFree.clear(); mOutQ.clear();
            for (int i = 0; i < IN_BUFS; i++) { mIn[i] = ByteBuffer.allocateDirect(mInCap); mInFree.add(i); }
            for (int i = 0; i < outBufs; i++) mOutFree.add(i);
            mStarted = true; mFormatReported = false;
            boolean hasSurfaceNow = mOutputTarget.hasSurface;
            com.macapk.loader.MediaStats.codecStart(mStat, mStatMime, hasSurfaceNow);
            if (mObs != null) mObs.onStart(mStatMime, hasSurfaceNow);
            // AOSP: "you must call start() after flush() ... to transition
            // the codec to the Running sub-state and start receiving input
            // buffers" in async mode -- start() always clears the pause, and
            // spawns the dispatch thread the first time a callback is armed.
            mAsyncPaused = false;
            mAsyncLock.notifyAll();
            if (mCallback != null && mAsyncThread == null) {
                mAsyncRunning = true;
                mAsyncThread = new Thread(new Runnable() {
                    @Override public void run() { asyncDispatchLoop(); }
                }, "MediaCodec-async");
                mAsyncThread.setDaemon(true);
                mAsyncThread.start();
            }
        }
    }
    public void stop()    { bumpOutputGen();
                            com.macapk.loader.MediaStats.codecEnd(mStat, "stop");
                            if (mObs != null) mObs.emitOnce("stop");
                            detachInputSurface();
                            stopAsyncDispatch(); releaseDec(); mStarted = false; mConfigured = false; }
    public void reset()   { bumpOutputGen();
                            com.macapk.loader.MediaStats.codecEnd(mStat, "reset");
                            if (mObs != null) mObs.emitOnce("reset");
                            detachInputSurface(); releaseOwnedInputSurface();
                            stopAsyncDispatch(); releaseDec(); mStarted = false; mConfigured = false;
                            mEncOutFormat = null; mInputFormat = null; mEncEosQueued = false; }
    public void release() {
        bumpOutputGen();
        com.macapk.loader.MediaStats.codecEnd(mStat, "release");
        if (mObs != null) mObs.emitOnce("release");
        detachInputSurface();
        releaseOwnedInputSurface();
        stopAsyncDispatch(); releaseDec();
        mConfigured = false;
        mReleased = true;
        drainTimedFrames(true);
        // Drop the video-plane geometry binding IF it is still ours (a newer
        // codec's registration wins -- macapkUnregisterVideoSink compares the
        // Surface identity, so releasing an old codec after a new one bound
        // does not blank the new one's video).
        android.view.Surface s = mOutputTarget.surface;
        if (s != null) {
            s.macapkNoteVideoSinkActive(false);
        }
    }
    public void flush() {
        long encToFlush = 0;
        long vidEncToFlush = 0;
        synchronized (mEncSubmitLock) {
            synchronized (mAsyncLock) {
                encToFlush = mEnc;
                vidEncToFlush = mVidEnc;
            }
            if (encToFlush != 0) com.macapk.loader.NativeMedia.nativeAudioEncFlush(encToFlush);
            if (vidEncToFlush != 0) com.macapk.loader.NativeMedia.nativeVidEncFlush(vidEncToFlush);
            synchronized (mAsyncLock) {
                mFlushSeq++;
                bumpOutputGen();
                mOutQ.clear();
                mEncEosQueued = false;
                releasePendingVideoFrames();
                if (mVidDec != 0) {
                    if (isVp8()) com.macapk.loader.NativeMedia.nativeVp8Flush(mVidDec);
                    else if (isVp9()) com.macapk.loader.NativeMedia.nativeVp9Flush(mVidDec);
                    else com.macapk.loader.NativeMedia.nativeVidFlush(mVidDec);
                }
                if (mIn != null && !(mIsEncoder && mInputSurface != null)) {
                    mInFree.clear();
                    for (int i = 0; i < Math.min(IN_BUFS, mIn.length); i++) mInFree.add(i);
                }
                if (mOut != null) { mOutFree.clear(); for (int i = 0; i < mOut.length; i++) mOutFree.add(i); }
                // AOSP: in async mode flush() suspends buffer delivery until the
                // next start() (see start() above); sync-mode callers (mCallback
                // == null) are unaffected.
                if (mCallback != null) mAsyncPaused = true;
                mAsyncLock.notifyAll();
            }
        }
    }
    // Signals the async dispatch thread to stop and forgets it, WITHOUT
    // waiting for it to actually exit. That is safe rather than sloppy: the
    // thread only ever touches mDec/mIn/mOut/mInFree/mOutFree/mOutQ inside
    // mAsyncLock (see asyncDispatchLoop), so the caller's next acquisition of
    // that same lock -- releaseDec(), right after this returns -- cannot run
    // concurrently with anything the dispatch thread is still mid-way through;
    // it can only ever run strictly before or strictly after.
    private void stopAsyncDispatch() {
        synchronized (mAsyncLock) {
            mAsyncRunning = false;
            mAsyncThread = null;
            mAsyncLock.notifyAll();
        }
    }
    // Releases any polled-but-not-yet-released video frames (native
    // CVPixelBuffers) before the index pool that tracks them gets dropped or
    // reset, so they don't leak. Called with mAsyncLock held.
    /** AOSP MediaCodec hands decoded video out in PRESENTATION order: the
     *  codec component reorders B-frames. VideoToolbox does not -- its output
     *  callback fires in DECODE order even with kVTDecodeFrame_Enable
     *  TemporalProcessing (FFmpeg's videotoolbox hwaccel reorders itself for
     *  the same reason). Measured on ytdlnis's ExoPlayer preview (H.264 720p
     *  with B-frames): consecutive release timestamps stepped -66.7 / +33.4 /
     *  +133.5 ms, i.e. frames shown out of order -- the "stuttery" playback
     *  (owner report 2026-10-19). This holds VideoToolbox output in a
     *  pts-ordered window and emits the earliest frame once more than
     *  mReorderDepth frames are held (or the stream ended). The depth starts at
     *  2 and grows whenever a frame arrives older than one already emitted
     *  (that frame is dropped, as a component never emits backwards), capped at
     *  16 = H.264's max_dec_frame_buffering. MACAPK_VIDEO_REORDER=0 restores
     *  decode-order output. VP8/VP9 (libvpx) already output in order. */
    static final boolean VIDEO_REORDER = !"0".equals(System.getenv("MACAPK_VIDEO_REORDER"));
    private final VideoReorder mReorder = new VideoReorder();
    private static final java.util.function.LongConsumer RELEASE_FRAME =
        com.macapk.loader.NativeMedia::nativeVidReleaseFrame;

    /** Called with mAsyncLock held, like tryDequeueOutputLocked0. */
    private long[] nextVideoToolboxPolledLocked(long dec) {
        if (mStashedVidFrame != null) {
            long[] stashed = mStashedVidFrame;
            mStashedVidFrame = null;
            return stashed;
        }
        return com.macapk.loader.NativeMedia.nativeVidPoll(dec);
    }

    private long[] reorderPoll() {
        final long dec = mVidDec;
        return mReorder.poll(() -> nextVideoToolboxPolledLocked(dec), RELEASE_FRAME);
    }
    private void resetReorder() { mReorder.reset(RELEASE_FRAME); }

    /** The presentation-order window (see VIDEO_REORDER above). A frame is
     *  {pixbuf, ptsUs, flags}; pixbuf 0 is the end-of-stream marker. Static and
     *  source-agnostic so debug/videoreorder_gate.sh drives it offline. */
    static final class VideoReorder {
        private final java.util.PriorityQueue<long[]> held =
            new java.util.PriorityQueue<>(16, (a, b) -> Long.compare(a[1], b[1]));
        private long[] eos;
        int depth = 2;
        private long lastPts = Long.MIN_VALUE;

        public int size() {
            return held.size();
        }

        long[] poll(java.util.function.Supplier<long[]> src, java.util.function.LongConsumer release) {
            long[] f;
            // Backpressure: only pull more frames if held queue has not reached required depth.
            // Do NOT drain the native outQ completely into held on every call.
            while (eos == null && held.size() <= depth && (f = src.get()) != null) {
                if (f[0] == 0) { eos = f; break; }               // EOS marker: drain everything
                if (f[1] < lastPts) {
                    // Arrived after a later frame already went out: a component
                    // never emits backwards, so drop it and widen the window.
                    release.accept(f[0]);
                    if (depth < 16) depth++;
                    continue;
                }
                held.add(f);
            }
            // Strict safety cap: never let held accumulate unbounded unrendered frames
            while (held.size() > 20) {
                long[] dropped = held.poll();
                if (dropped != null && dropped[0] != 0) {
                    release.accept(dropped[0]);
                }
            }
            if (!held.isEmpty() && (held.size() > depth || eos != null)) {
                long[] out = held.poll();
                lastPts = out[1];
                return out;
            }
            if (eos != null && held.isEmpty()) {
                long[] e = eos;
                eos = null;
                lastPts = Long.MIN_VALUE;
                return e;
            }
            return null;
        }
        /** flush / stop / release: drop everything held; the learned depth stays. */
        void reset(java.util.function.LongConsumer release) {
            for (long[] f : held) release.accept(f[0]);
            held.clear();
            eos = null;
            lastPts = Long.MIN_VALUE;
        }
    }

    private void releasePendingVideoFrames() {
        resetReorder();
        drainTimedFrames(false);
        if (mStashedVidFrame != null) {
            if (mStashedVidFrame[0] != 0) {
                com.macapk.loader.NativeMedia.nativeVidReleaseFrame(mStashedVidFrame[0]);
            }
            mStashedVidFrame = null;
        }
        if (mOutPixbuf == null) return;
        for (int i = 0; i < mOutPixbuf.length; i++) {
            if (mOutPixbuf[i] != 0) {
                com.macapk.loader.NativeMedia.nativeVidReleaseFrame(mOutPixbuf[i]);
                mOutPixbuf[i] = 0;
            }
        }
    }
    private void releaseDec() {
        long encToClose = 0;
        long vidEncToClose = 0;
        synchronized (mAsyncLock) {
            mStarted = false;
            mAsyncLock.notifyAll();
            boolean interrupted = false;
            while (mVidWaiters > 0) {
                try { mAsyncLock.wait(); }
                catch (InterruptedException ie) { interrupted = true; }
            }
            if (interrupted) Thread.currentThread().interrupt();
            if (mEnc != 0) { encToClose = mEnc; mEnc = 0; }
            if (mVidEnc != 0) { vidEncToClose = mVidEnc; mVidEnc = 0; }
            if (mDec != 0) { com.macapk.loader.NativeMedia.nativeDecClose(mDec); mDec = 0; }
            if (mVidDec != 0) {
                releasePendingVideoFrames();
                drainTimedFrames(true);
                if (isVp8()) com.macapk.loader.NativeMedia.nativeVp8Close(mVidDec);
                else if (isVp9()) com.macapk.loader.NativeMedia.nativeVp9Close(mVidDec);
                else com.macapk.loader.NativeMedia.nativeVidClose(mVidDec);
                mVidDec = 0;
            }
            android.view.Surface targetSurface = mOutputTarget.surface;
            if (targetSurface != null && targetSurface.macapkGetSurfaceTexture() != null) {
                android.graphics.SurfaceTexture.macapkUnregisterVideoTarget(targetSurface.macapkGetSurfaceTexture());
            }
            mOutQ.clear();
        }
        synchronized (mEncSubmitLock) {
            if (encToClose != 0) com.macapk.loader.NativeMedia.nativeAudioEncClose(encToClose);
            if (vidEncToClose != 0) com.macapk.loader.NativeMedia.nativeVidEncClose(vidEncToClose);
        }
    }

    // The async dispatch loop: mirrors dequeueInputBuffer/dequeueOutputBuffer's
    // own selection logic (tryDequeueOutputLocked, shared with the sync path)
    // but PUSHES the result through Callback instead of waiting for the app to
    // poll. The callback is invoked directly (this codebase's existing
    // MediaPlayer/SoundPool listener-dispatch idiom, not a real Handler/Looper
    // post) and, critically, OUTSIDE mAsyncLock -- so when the app calls back
    // into queueInputBuffer()/releaseOutputBuffer() from within its callback
    // (the normal, expected async usage), that call is a fresh, non-nested
    // lock acquisition made from THIS SAME thread, never a concurrent one from
    // a second thread. That is what lets the existing synchronous
    // queueInputBuffer/releaseOutputBuffer bodies below be reused completely
    // unchanged for the async path, for BOTH audio and video.
    private boolean hasExternalVideoFeed() {
        return mVidDec != 0 && !isVp8() && !isVp9();
    }

    private void asyncDispatchLoop() {
        while (true) {
            Callback cb = null;
            boolean formatChanged = false;
            int inIdx = -1, outIdx = -1;
            BufferInfo info = null;
            long waitDec = 0;
            long flushSeqBefore = 0;
            synchronized (mAsyncLock) {
                if (!mAsyncRunning) return;
                if (mCallback == null || mAsyncPaused) {
                    try {
                        mAsyncLock.wait();
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    continue;
                }
                cb = mCallback;
                BufferInfo tmp = new BufferInfo();
                int r = tryDequeueOutputLocked(tmp);
                if (r == INFO_OUTPUT_FORMAT_CHANGED) {
                    formatChanged = true;
                } else if (r >= 0) {
                    outIdx = r;
                    info = tmp;
                } else if (!mInFree.isEmpty()) {
                    inIdx = mInFree.poll();
                }
                if (!formatChanged && outIdx < 0 && inIdx < 0) {
                    if (mStarted && hasExternalVideoFeed() && !mOutFree.isEmpty()
                            && com.macapk.loader.NativeMedia.nativeVidQueueDepth(mVidDec) > 0) {
                        waitDec = mVidDec;
                        flushSeqBefore = mFlushSeq;
                        mVidWaiters++;
                    } else {
                        try {
                            mAsyncLock.wait();
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                        continue;
                    }
                }
            }
            if (waitDec != 0) {
                long[] waitedFrame = null;
                boolean linkFallback = false;
                try {
                    try {
                        waitedFrame = com.macapk.loader.NativeMedia.nativeVidWaitPoll(waitDec, 2000L);
                    } catch (UnsatisfiedLinkError ule) {
                        linkFallback = true;
                        waitedFrame = com.macapk.loader.NativeMedia.nativeVidPoll(waitDec);
                    }
                } finally {
                    synchronized (mAsyncLock) {
                        mVidWaiters--;
                        if (mVidWaiters == 0) mAsyncLock.notifyAll();
                        if (waitedFrame != null) {
                            if (mStarted && mVidDec == waitDec && mFlushSeq == flushSeqBefore) {
                                if (mStashedVidFrame != null && mStashedVidFrame[0] != 0) {
                                    RELEASE_FRAME.accept(mStashedVidFrame[0]);
                                }
                                mStashedVidFrame = waitedFrame;
                                waitedFrame = null;
                                mAsyncLock.notifyAll();
                            }
                        } else if (linkFallback && mAsyncRunning && mStarted && !mAsyncPaused) {
                            try { mAsyncLock.wait(2); }
                            catch (InterruptedException ie) { Thread.currentThread().interrupt(); return; }
                        }
                    }
                    if (waitedFrame != null && waitedFrame[0] != 0) {
                        RELEASE_FRAME.accept(waitedFrame[0]);
                    }
                }
                continue;
            }
            if (cb != null) {
                try {
                    if (formatChanged)    cb.onOutputFormatChanged(this, getOutputFormat());
                    else if (outIdx >= 0) cb.onOutputBufferAvailable(this, outIdx, info);
                    else if (inIdx >= 0)  cb.onInputBufferAvailable(this, inIdx);
                } catch (Throwable ignore) {}
            }
        }
    }

    /**
     * How big an input buffer this configuration needs.
     *
     * <p>Order mirrors a real component's input-port sizing:
     * <ol>
     *   <li>{@link MediaFormat#KEY_MAX_INPUT_SIZE} when the client declared it.
     *       This is the authoritative answer -- the client is stating the
     *       largest access unit it will ever queue, and AOSP's ACodec/CCodec
     *       size the input port to at least it. media3 always sets it for
     *       video.</li>
     *   <li>Otherwise, for video, the same 4:2:0 estimate media3 itself uses to
     *       COMPUTE that key, so a client that never sets it still gets a
     *       frame-sized buffer: pixels rounded up to whole 16x16 macroblocks,
     *       times 3/2 for 4:2:0, divided by a minimum compression ratio of 2
     *       (media3's value for H264/H265/VP8/VP9) -- i.e. maxPixels * 3 / 4.
     *       ~1.5 MiB at 1080p.</li>
     *   <li>Otherwise the historic 64 KiB floor, which is what every audio
     *       path has always used and is ample for one AAC/Opus access unit.</li>
     * </ol>
     *
     * <p>The result is never below {@link #IN_CAP}, so no configuration that
     * works today gets a smaller buffer than it had.
     */
    private static int resolveInputCapacity(MediaFormat format, boolean isVideo) {
        // A declared size that is absurd would otherwise become 8 direct
        // allocations of it. Real components fail configure() on an
        // unsatisfiable port size; clamping is the gentler equivalent and
        // 32 MiB is far past any real access unit (8K HEVC keyframes are
        // single-digit MiB).
        final int CEILING = 32 << 20;
        int declared = format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 0);
        if (declared > 0) {
            return Math.min(CEILING, Math.max(IN_CAP, declared));
        }
        if (isVideo) {
            int w = format.getInteger(MediaFormat.KEY_WIDTH, 0);
            int h = format.getInteger(MediaFormat.KEY_HEIGHT, 0);
            if (w > 0 && h > 0) {
                long maxPixels = (long) ceilDiv(w, 16) * ceilDiv(h, 16) * 16 * 16;
                long est = (maxPixels * 3) / 4;
                return (int) Math.min(CEILING, Math.max(IN_CAP, est));
            }
        }
        return IN_CAP;
    }

    private static int ceilDiv(int num, int den) { return (num + den - 1) / den; }

    private static int resolveEncoderInputCapacity(MediaFormat format, boolean isVideo,
                                                   int colorFormat, int width, int height,
                                                   int stride, int sliceHeight) {
        final int CEILING = 32 << 20;
        int declared = format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 0);
        long rawMin = IN_CAP;
        if (isVideo && width > 0 && height > 0) {
            int effW = Math.max(width, stride);
            int effH = Math.max(height, sliceHeight);
            boolean is32BitRgb =
                    colorFormat == MediaCodecInfo.CodecCapabilities.COLOR_Format32bitBGRA8888
                 || colorFormat == MediaCodecInfo.CodecCapabilities.COLOR_Format32bitARGB8888
                 || colorFormat == MediaCodecInfo.CodecCapabilities.COLOR_Format32bitABGR8888
                 || colorFormat == MediaCodecInfo.CodecCapabilities.COLOR_FormatRGBAFlexible;
            if (is32BitRgb) {
                int rowBytes = (stride >= width * 4) ? stride : effW * 4;
                rawMin = (long) rowBytes * (long) effH;
            } else {
                rawMin = ((long) effW * (long) effH * 3L + 1L) / 2L;
            }
        }
        long base = Math.max(IN_CAP, rawMin);
        if (declared > 0) base = Math.max(base, (long) declared);
        return (int) Math.min(CEILING, base);
    }

    public ByteBuffer[] getInputBuffers()  { return mIn  != null ? mIn  : new ByteBuffer[0]; }
    public ByteBuffer[] getOutputBuffers() { return mOut != null ? mOut : new ByteBuffer[0]; }
    public ByteBuffer getInputBuffer(int index)  { return (mIn  != null && index >= 0 && index < mIn.length)  ? mIn[index]  : null; }
    // AOSP: "the ByteBuffer... will be null if configured with an output
    // surface". For a video decoder output is an opaque native pixel buffer;
    // for a video encoder output is a compressed bitstream ByteBuffer.
    public ByteBuffer getOutputBuffer(int index) {
        if (mIsVideo && !mIsEncoder) return null;
        return (mOut != null && index >= 0 && index < mOut.length) ? mOut[index] : null;
    }
    public Image getInputImage(int index) {
        if (!mIsEncoder || !mIsVideo || mInputSurface != null || mIn == null
                || index < 0 || index >= mIn.length || mVidWidth <= 0 || mVidHeight <= 0) {
            return null;
        }
        return new NativeInputYuvImage(mIn[index], mVidWidth, mVidHeight,
                Math.max(mVidWidth, mEncStride), Math.max(mVidHeight, mEncSliceHeight),
                mEncColorFormat);
    }
    public Image getOutputImage(int index) {
        if (!mIsVideo || mIsEncoder || mOutPixbuf == null || index < 0 || index >= mOutPixbuf.length) return null;
        long pb = mOutPixbuf[index];
        if (pb == 0) return null;
        long ptsUs = (mOutPts != null) ? mOutPts[index] : 0;
        return new NativeVideoImage(pb, ptsUs, mVidWidth, mVidHeight);
    }

    private static final class NativeInputYuvImage extends Image {
        private final ByteBuffer mBacking;
        private final int mW, mH;
        private final Plane[] mPlanes;
        private long mTsUs = 0;
        private boolean mClosed = false;

        NativeInputYuvImage(ByteBuffer backing, int w, int h, int stride, int sliceHeight, int colorFormat) {
            mBacking = backing;
            mW = w;
            mH = h;
            boolean semi = (colorFormat == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar
                         || colorFormat == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420PackedSemiPlanar);
            int cw = (w + 1) / 2;
            int ch = (h + 1) / 2;
            int uvStride = (stride + 1) / 2;
            int uvSlice = (sliceHeight + 1) / 2;
            int yBytes = stride * sliceHeight;

            ByteBuffer yBuf = backing.duplicate();
            yBuf.position(0);
            yBuf.limit(Math.min(backing.capacity(), yBytes));
            ByteBuffer ySlice = yBuf.slice();

            if (semi) {
                int uvBytes = stride * uvSlice;
                ByteBuffer uBuf = backing.duplicate();
                int off = Math.min(backing.capacity(), yBytes);
                uBuf.position(off);
                uBuf.limit(Math.min(backing.capacity(), off + uvBytes));
                ByteBuffer uvSliceBuf = uBuf.slice();
                ByteBuffer vSliceBuf = uvSliceBuf.duplicate();
                if (vSliceBuf.remaining() > 1) vSliceBuf.position(1);
                mPlanes = new Plane[] {
                    new InputPlane(ySlice, stride, 1),
                    new InputPlane(uvSliceBuf, stride, 2),
                    new InputPlane(vSliceBuf.slice(), stride, 2),
                };
            } else {
                int uvBytes = uvStride * uvSlice;
                int uOff = Math.min(backing.capacity(), yBytes);
                ByteBuffer uBuf = backing.duplicate();
                uBuf.position(uOff);
                uBuf.limit(Math.min(backing.capacity(), uOff + uvBytes));
                int vOff = Math.min(backing.capacity(), uOff + uvBytes);
                ByteBuffer vBuf = backing.duplicate();
                vBuf.position(vOff);
                vBuf.limit(Math.min(backing.capacity(), vOff + cw + (ch - 1) * uvStride));
                mPlanes = new Plane[] {
                    new InputPlane(ySlice, stride, 1),
                    new InputPlane(uBuf.slice(), uvStride, 1),
                    new InputPlane(vBuf.slice(), uvStride, 1),
                };
            }
        }

        private void checkOpen() {
            if (mClosed) throw new IllegalStateException("Image is closed");
        }
        @Override public int getFormat() { return 35; } // ImageFormat.YUV_420_888
        @Override public int getWidth()  { return mW; }
        @Override public int getHeight() { return mH; }
        @Override public long getTimestamp() { return mTsUs; }
        @Override public void setTimestamp(long ts) { mTsUs = ts; }
        @Override public Plane[] getPlanes() { checkOpen(); return mPlanes; }
        @Override public void close() { mClosed = true; }

        private static final class InputPlane extends Plane {
            private final ByteBuffer mBuf;
            private final int mRowStride, mPixelStride;
            InputPlane(ByteBuffer buf, int rowStride, int pixelStride) {
                mBuf = buf; mRowStride = rowStride; mPixelStride = pixelStride;
            }
            @Override public ByteBuffer getBuffer() { return mBuf; }
            @Override public int getPixelStride() { return mPixelStride; }
            @Override public int getRowStride() { return mRowStride; }
        }
    }

    // android.media.Image backed by a polled, not-yet-released decoded video
    // frame: a real, spec-shaped YUV_420_888 image (3 planes, 4:2:0
    // subsampling, real per-plane row/pixel strides, real per-frame geometry
    // and crop, real timestamp), not a shape-only stand-in.
    //
    // WHERE THE BYTES COME FROM, AND WHY THIS CONVERTS RATHER THAN EXPOSING
    // NATIVE PLANES DIRECTLY. Every VideoToolbox session -- Surface or not --
    // is opened with a packed-BGRA destination pixel format
    // (kCVPixelFormatType_32BGRA; see video_toolbox.mm's
    // CreateSessionInternal), because the Surface-composite path
    // (releaseOutputBuffer(index, true) -> nativeVidRender ->
    // CocoaPresentVideoFrame) wants exactly that packed-RGB shape for Metal
    // compositing. A BGRA CVPixelBuffer is genuinely non-planar
    // (CVPixelBufferGetPlaneCount == 0): there is no Y/Cb/Cr plane to hand
    // out directly, so producing YUV_420_888 here means converting.
    //
    // This class converts in Java, from the SAME raw BGRA bytes the Surface
    // path already reads via NativeMedia.nativeVidFrameBgra (that native
    // method itself is unchanged). The forward matrix is the standard
    // ITU-R BT.601 full-range RGB->YCbCr transform -- the exact algebraic
    // inverse of the BT.601 full-range YCbCr->RGB transform
    // android.graphics.YuvImage already uses elsewhere in this codebase (see
    // that file's yuvToArgb: r=y+1.402v, g=y-0.344136u-0.714136v,
    // b=y+1.772u): Y=0.299R+0.587G+0.114B, Cb=-0.168736R-0.331264G+0.5B+128,
    // Cr=0.5R-0.418688G-0.081312B+128 (see y601/cb601/cr601 below). Chroma is
    // a genuine 2x2 box average of the source pixels, not a point-sampled
    // skip: because the matrix is affine, averaging the four source R/G/B
    // triples first and running the Cb/Cr formula once is exactly equal to
    // averaging four independently-computed Cb/Cr pairs, so this loses
    // nothing in exchange for 3 additions instead of 6.
    //
    // WHAT IS STILL APPROXIMATE, NAMED PLAINLY: the planes are DERIVED from
    // VideoToolbox's own internal YUV->RGB conversion rather than being its
    // native planar output, so there is a second 8-bit quantization step
    // this class cannot avoid without changing video_toolbox.mm's
    // destination pixel format -- which this session does not touch (a
    // concurrently-active sibling lane owns that file this turn; see
    // CLAUDE.md's per-turn file ownership). The exact, zero-extra-loss fix
    // is a one-line change to CreateSessionInternal's destAttrs: request
    // kCVPixelFormatType_420YpCbCr8BiPlanarFullRange (matching this class's
    // full-range choice) instead of BGRA, then read
    // CVPixelBufferGetBaseAddressOfPlane(0)/(1) +
    // GetBytesPerRowOfPlane(0)/(1) directly for the two planes -- exactly
    // the pattern camera_capture.mm already uses to feed ImageReader's own
    // YUV_420_888 CameraImage (see that class's "PLANE LAYOUT" doc in
    // ImageReader.java, verified by debug/yuv_map_test.sh). Once that lands,
    // this class's conversion loop collapses to a straight copy of the real
    // planes and the two accompanying attempt-then-retry buffer sizing and
    // the colour-matrix math below can both go.
    //
    // ROW STRIDE. The SOURCE bgra[] is read using the REAL CoreVideo-reported
    // stride (whs[2] from nativeVidFrameBgra == CVPixelBufferGetBytesPerRow)
    // -- never assumed from width, since CoreVideo pads rows and an assumed
    // stride silently shears the image. (The PRIOR version of this class had
    // exactly that bug: it read nativeVidFrameBgra's real stride into whs[2]
    // and then discarded it, reporting getRowStride()==mW*4 regardless.) The
    // OUTPUT planes are freshly allocated, tightly packed buffers -- no
    // padding exists to preserve, because nothing maps them onto native
    // memory -- so their reported row strides are simply the true byte width
    // of a packed row: truthful by construction, not a second guess.
    //
    // GEOMETRY / CROPPING. Width/height start as the configure()-time
    // MediaFormat values (the only thing known before any frame has
    // decoded) and are OVERWRITTEN, the first time getPlanes() actually
    // reads a frame, with the real CVPixelBufferGetWidth/Height CoreVideo
    // reports for that buffer. That is what makes getCropRect() (inherited
    // from Image, defaults to the full width/height) reflect the real
    // display size rather than a possibly-padded coded size.
    //
    // COLOUR RANGE. getOutputFormat() reports this class's choice (full
    // range, BT.601) via KEY_COLOR_RANGE/KEY_COLOR_STANDARD/
    // KEY_COLOR_TRANSFER for the no-Surface case only -- meaningless for the
    // Surface path, which never runs this conversion.
    //
    // LIFETIME. Does NOT own the native pixel buffer -- releaseOutputBuffer
    // (index, ...), not close(), is what frees IT (matching MediaCodec's
    // real ownership model, where the index rather than the Image is the
    // handle the codec tracks). close() only drops this instance's OWN
    // derived buffer, so it is collectible right away instead of pinned for
    // whatever remains of this MediaCodec's life, and makes further access
    // throw like AOSP's own Image does post-close.
    private final class NativeVideoImage extends Image {
        private final long mPixbuf;
        private final long mTimestampUs;
        private int mW, mH;        // corrected to the real decoded size once known
        private Plane[] mPlanes;   // materialized lazily, once, on first getPlanes()
        private boolean mClosed;

        NativeVideoImage(long pixbuf, long timestampUs, int w, int h) {
            mPixbuf = pixbuf;
            mTimestampUs = timestampUs;
            mW = Math.max(1, w);
            mH = Math.max(1, h);
        }

        private void checkOpen() {
            // Mirrors ImageReader.CameraImage's own checkOpen() (this
            // codebase's established convention for this exact message) and
            // AOSP's Image.throwISEIfImageIsInvalid(): plane buffers point
            // at memory whose lifetime is not this Image's own past close(),
            // so a getter that kept answering after close() could hand the
            // app a window onto freed or reused memory.
            if (mClosed) throw new IllegalStateException("Image is already closed");
        }

        @Override public int getFormat() { checkOpen(); return android.graphics.ImageFormat.YUV_420_888; }
        @Override public int getWidth()  { checkOpen(); return mW; }
        @Override public int getHeight() { checkOpen(); return mH; }
        @Override public long getTimestamp() { checkOpen(); return mTimestampUs; }

        @Override public Plane[] getPlanes() {
            checkOpen();
            if (mPlanes == null) materialize();
            return mPlanes;
        }

        @Override public void close() {
            mClosed = true;
            mPlanes = null;
        }

        // Reads the real decoded BGRA pixels (via the existing, unchanged
        // NativeMedia.nativeVidFrameBgra), converts to planar YUV_420_888,
        // and builds the 3 direct-ByteBuffer Plane views. Runs at most once
        // per Image; a second call to getPlanes() is the normal AOSP
        // "you may call this more than once" case and must not recompute.
        private void materialize() {
            final long obsT0 = mObs != null ? System.nanoTime() : 0L;
            byte[] bgra = null;
            int[] whs = new int[3];
            int rc = -1;
            // CoreVideo/IOSurface may pad each row past width*4 bytes, and
            // the native readout (macapk_vtb_frame_copy_bgra, see its own
            // doc) reports failure with no size hint when the buffer is too
            // small -- so this rounds generously up front (a page's worth of
            // per-row headroom, negligible next to any real frame) and only
            // falls back to a second, much larger attempt if even that was
            // short. Belt and suspenders, not a guess dressed up as a fact:
            // either attempt reads back the REAL stride in whs[2] on
            // success, which is what every byte offset below actually uses.
            for (int attempt = 0; attempt < 2 && rc != 0; attempt++) {
                int stride = ((mW * 4) + 4095) & ~4095;
                if (attempt == 1) stride *= 4;
                byte[] tryBuf = new byte[stride * mH];
                rc = com.macapk.loader.NativeMedia.nativeVidFrameBgra(mPixbuf, tryBuf, whs);
                if (rc == 0) bgra = tryBuf;
            }
            if (rc != 0 || bgra == null || whs[0] <= 0 || whs[1] <= 0) {
                mPlanes = new Plane[0];
                if (mObs != null) mObs.recordOutputImage(System.nanoTime() - obsT0, 0, mTimestampUs);
                return;
            }
            final int srcW = whs[0], srcH = whs[1], srcStride = whs[2];
            mW = srcW;
            mH = srcH;

            final int chromaW = (srcW + 1) / 2;
            final int chromaH = (srcH + 1) / 2;
            final int ySize = srcW * srcH;
            final int cSize = chromaW * chromaH * 2;
            final byte[] yuv = new byte[ySize + cSize];

            for (int by = 0; by < srcH; by += 2) {
                final int bh = Math.min(2, srcH - by);
                for (int bx = 0; bx < srcW; bx += 2) {
                    final int bw = Math.min(2, srcW - bx);
                    long sumR = 0, sumG = 0, sumB = 0;
                    int cnt = 0;
                    for (int dy = 0; dy < bh; dy++) {
                        final int row = by + dy;
                        final int rowOff = row * srcStride;
                        for (int dx = 0; dx < bw; dx++) {
                            final int col = bx + dx;
                            final int off = rowOff + col * 4;
                            final int b = bgra[off]     & 0xff;
                            final int g = bgra[off + 1] & 0xff;
                            final int r = bgra[off + 2] & 0xff;
                            yuv[row * srcW + col] = (byte) y601(r, g, b);
                            sumR += r; sumG += g; sumB += b; cnt++;
                        }
                    }
                    final double ar = sumR / (double) cnt, ag = sumG / (double) cnt, ab = sumB / (double) cnt;
                    final int cIdx = ySize + ((by / 2) * chromaW + (bx / 2)) * 2;
                    yuv[cIdx]     = (byte) cb601(ar, ag, ab);
                    yuv[cIdx + 1] = (byte) cr601(ar, ag, ab);
                }
            }

            final ByteBuffer direct = ByteBuffer.allocateDirect(yuv.length);
            direct.put(yuv);
            direct.rewind();

            // Three planes over one shared allocation: the same
            // position/limit/slice technique AOSP's own internal MediaImage
            // uses over one native buffer, and the same semiplanar
            // convention this codebase's ImageReader.CameraImage already
            // documents for camera frames -- plane 0 is Y; planes 1 and 2
            // are two interleaved views of the SAME chroma region, one byte
            // apart, each with pixelStride 2. Cr's slice is one byte shorter
            // than Cb's so its view (which starts one byte later) never
            // reads past the allocation's true end.
            final ByteBuffer yView  = sliceAt(direct, 0, ySize);
            final ByteBuffer cbView = sliceAt(direct, ySize, cSize);
            final ByteBuffer crView = sliceAt(direct, ySize + 1, cSize - 1);
            final int cRowStride = chromaW * 2;

            mPlanes = new Plane[] {
                plane(yView, srcW, 1),
                plane(cbView, cRowStride, 2),
                plane(crView, cRowStride, 2),
            };
            if (mObs != null) {
                mObs.recordOutputImage(System.nanoTime() - obsT0, (srcStride * srcH) + yuv.length, mTimestampUs);
            }
        }

        private ByteBuffer sliceAt(ByteBuffer base, int offset, int length) {
            ByteBuffer d = base.duplicate();
            d.clear();
            d.position(offset);
            d.limit(offset + length);
            return d.slice();
        }

        private Plane plane(final ByteBuffer buf, final int rowStride, final int pixelStride) {
            return new Plane() {
                @Override public ByteBuffer getBuffer() { return buf.duplicate(); }
                @Override public int getPixelStride() { return pixelStride; }
                @Override public int getRowStride() { return rowStride; }
            };
        }

        // Standard ITU-R BT.601 full-range RGB -> YCbCr. KR=0.299, KB=0.114
        // (identical for the COLOR_STANDARD_BT601_PAL and _NTSC MediaFormat
        // constants -- they differ only in line count, 625 vs 525, not
        // colourimetry, per AOSP MediaFormat.java's own javadoc on those two
        // fields, both "KR = 0.299, KB = 0.114").
        private int y601(int r, int g, int b) {
            return clamp255((int) Math.round(0.299 * r + 0.587 * g + 0.114 * b));
        }
        private int cb601(double r, double g, double b) {
            return clamp255((int) Math.round(-0.168736 * r - 0.331264 * g + 0.5 * b + 128));
        }
        private int cr601(double r, double g, double b) {
            return clamp255((int) Math.round(0.5 * r - 0.418688 * g - 0.081312 * b + 128));
        }
        private int clamp255(int v) { return v < 0 ? 0 : (v > 255 ? 255 : v); }
    }

    private int timedQueueSize() {
        synchronized (mTimedLock) {
            return mTimed.size();
        }
    }

    private long mWatermarkNextNs;
    private void checkWatermarks(int reorderCount, int timedCount) {
        if (!PRESENT_TRACE) return;
        long now = System.nanoTime();
        if (now >= mWatermarkNextNs) {
            mWatermarkNextNs = now + 2_000_000_000L;
            com.macapk.loader.NativeLog.i("MediaCodec",
                "[codec-watermark] outFree=" + mOutFree.size() +
                " inFree=" + mInFree.size() +
                " reorder=" + reorderCount +
                " timed=" + timedCount);
        }
    }

    public int dequeueInputBuffer(long timeoutUs) {
        final long startedNs = System.nanoTime();
        int r = INFO_TRY_AGAIN_LATER;
        synchronized (mAsyncLock) {
          if (mIsEncoder && mInputSurface != null) {
              throw new IllegalStateException("Cannot dequeueInputBuffer when input Surface is configured");
          }
          while (mStarted) {
            // AOSP backpressure: if output buffer slots are exhausted or downstream
            // queues (native outQ/inFlight, reorder, timed) are backlogged, throttle input rather than free-running
            boolean ready = !mInFree.isEmpty();
            if (ready && mVidDec != 0) {
                int nativeDepth = com.macapk.loader.NativeMedia.nativeVidQueueDepth(mVidDec);
                int timedCount = timedQueueSize();
                int reorderCount = mReorder.size();
                checkWatermarks(reorderCount, timedCount);
                int reorderCap = Math.max(12, mReorder.depth + 4);
                if (mOutFree.isEmpty() || nativeDepth >= 16 || reorderCount >= reorderCap || timedCount >= 12) {
                    ready = false;
                }
            }
            if (ready) { r = mInFree.poll(); break; }
            if (!waitForBufferLocked(timeoutUs, startedNs)) break;
          }
        }
        if (mObs != null) mObs.recordDequeueIn(r, System.nanoTime() - startedNs);
        return r;
    }

    // Poll bounded at 2 ms while VideoToolbox has in-flight frames or timed queue
    // pressure needs external drain; otherwise cap at 20 ms (or exact remaining
    // timeout) so notifyAll() still wakes immediately without 500 Hz idle spin.
    // timeoutUs == 0 is non-blocking; negative means wait indefinitely.
    private boolean waitForBufferLocked(long timeoutUs, long startedNs) {
        if (timeoutUs == 0) return false;
        final boolean needShortPoll = timedQueueSize() >= 12
            || (hasExternalVideoFeed() && !mOutFree.isEmpty()
                && com.macapk.loader.NativeMedia.nativeVidQueueDepth(mVidDec) > 0);
        long waitNs = needShortPoll ? 2_000_000L : 20_000_000L;
        if (timeoutUs > 0) {
            long remaining = java.util.concurrent.TimeUnit.MICROSECONDS.toNanos(timeoutUs)
                - (System.nanoTime() - startedNs);
            if (remaining <= 0) return false;
            waitNs = Math.min(waitNs, remaining);
        }
        try {
            mAsyncLock.wait(waitNs / 1_000_000L, (int)(waitNs % 1_000_000L));
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
    public void queueInputBuffer(int index, int offset, int size, long presentationTimeUs, int flags) {
        if (PRESENT_TRACE && mIsVideo) traceIn();
        final long obsT0 = mObs != null ? System.nanoTime() : 0L;
        boolean eos = false;
        boolean accepted = false;
        long vidDecHandle = 0;
        long vidDecFlushSeq = 0;
        byte[] vidAu = null;
        long vidEncHandle = 0;
        long audioEncHandle = 0;
        ByteBuffer vidInBuf = null;
        byte[] audioPcm = null;
        boolean codecConfig = false;

        synchronized (mAsyncLock) {
            if (!mStarted) return;
            if (mIsEncoder && mInputSurface != null) {
                throw new IllegalStateException("Cannot queueInputBuffer when input Surface is configured");
            }
            accepted = true;
            eos = (flags & BUFFER_FLAG_END_OF_STREAM) != 0;
            codecConfig = (flags & BUFFER_FLAG_CODEC_CONFIG) != 0;
            if (size > 0 && !codecConfig) com.macapk.loader.MediaStats.codecIn(mStat);

            if (mIsEncoder && mVidEnc != 0) {
                vidEncHandle = mVidEnc;
                vidInBuf = (!codecConfig && size > 0 && mIn != null && index >= 0 && index < mIn.length)
                        ? mIn[index] : null;
                if (eos) mEncEosQueued = true;
            } else if (mIsEncoder && mEnc != 0) {
                audioEncHandle = mEnc;
                if (!codecConfig && size > 0 && mIn != null && index >= 0 && index < mIn.length) {
                    ByteBuffer b = mIn[index].duplicate();
                    b.position(offset);
                    b.limit(offset + size);
                    audioPcm = new byte[size];
                    b.get(audioPcm);
                }
                if (eos) mEncEosQueued = true;
            } else {
                byte[] au = null;
                if (size > 0 && !codecConfig && mIn != null && index >= 0 && index < mIn.length) {
                    ByteBuffer b = mIn[index].duplicate(); b.position(offset); b.limit(offset + size);
                    au = new byte[size]; b.get(au);
                }
                if (mVidDec != 0) {
                    if (!codecConfig || eos) {
                        vidDecHandle = mVidDec;
                        vidDecFlushSeq = mFlushSeq;
                        vidAu = codecConfig ? null : au;
                        mVidWaiters++;
                    } else {
                        if (index >= 0 && index < IN_BUFS) mInFree.add(index);
                        mAsyncLock.notifyAll();
                    }
                } else {
                    if (mDec != 0 && !codecConfig) {
                        byte[] pcm = com.macapk.loader.NativeMedia.nativeDecDecode(mDec, au, eos);
                        if (pcm != null && (pcm.length > 0 || eos))
                            mOutQ.add(new OutEntry(pcm, presentationTimeUs, eos ? BUFFER_FLAG_END_OF_STREAM : 0));
                    } else if (eos) {
                        mOutQ.add(new OutEntry(new byte[0], presentationTimeUs, BUFFER_FLAG_END_OF_STREAM));
                    }
                    if (index >= 0 && index < IN_BUFS) mInFree.add(index);
                    mAsyncLock.notifyAll();
                }
            }
        }

        if (vidDecHandle != 0) {
            try {
                synchronized (mEncSubmitLock) {
                    if (mStarted && mVidDec == vidDecHandle && mFlushSeq == vidDecFlushSeq) {
                        vidDecode(vidDecHandle, vidAu, presentationTimeUs, eos);
                    }
                }
            } finally {
                synchronized (mAsyncLock) {
                    mVidWaiters--;
                    if (mIn != null && index >= 0 && index < IN_BUFS && mFlushSeq == vidDecFlushSeq) {
                        mInFree.add(index);
                    }
                    mAsyncLock.notifyAll();
                }
            }
        } else if (vidEncHandle != 0) {
            if (!codecConfig || eos) {
                ByteBuffer directBuf = (vidInBuf != null && vidInBuf.isDirect()) ? vidInBuf : null;
                byte[] heapArr = (vidInBuf != null && !vidInBuf.isDirect() && vidInBuf.hasArray())
                        ? vidInBuf.array() : null;
                int bufOff = (heapArr != null) ? (vidInBuf.arrayOffset() + offset) : offset;
                int submitSize = (vidInBuf != null && !codecConfig) ? size : 0;
                synchronized (mEncSubmitLock) {
                    if (mVidEnc == vidEncHandle) {
                        com.macapk.loader.NativeMedia.nativeVidEncEncodeBuffer(
                                vidEncHandle, directBuf, heapArr, bufOff, submitSize,
                                mEncColorFormat, mEncStride, mEncSliceHeight,
                                presentationTimeUs, eos);
                    }
                }
            }
            synchronized (mAsyncLock) {
                pollVideoEncoderLocked();
                if (mIn != null && index >= 0 && index < mIn.length && mInputSurface == null) {
                    mInFree.add(index);
                }
                mAsyncLock.notifyAll();
            }
        } else if (audioEncHandle != 0) {
            synchronized (mEncSubmitLock) {
                if (mEnc == audioEncHandle) {
                    com.macapk.loader.NativeMedia.nativeAudioEncEncode(
                            audioEncHandle, audioPcm, 0,
                            audioPcm != null ? audioPcm.length : 0,
                            presentationTimeUs, eos);
                }
            }
            synchronized (mAsyncLock) {
                pollAudioEncoderLocked();
                if (mIn != null && index >= 0 && index < mIn.length) {
                    mInFree.add(index);
                }
                mAsyncLock.notifyAll();
            }
        }

        if (mObs != null && accepted) mObs.recordQueueIn(index, size, presentationTimeUs, eos, System.nanoTime() - obsT0);
    }
    public void queueSecureInputBuffer(int index, int offset, CryptoInfo info,
                                       long presentationTimeUs, int flags) {
        queueInputBuffer(index, offset, 0, presentationTimeUs, flags);
    }
    public int dequeueOutputBuffer(BufferInfo info, long timeoutUs) {
        final long startedNs = System.nanoTime();
        int result = INFO_TRY_AGAIN_LATER;
        while (true) {
            long waitDec = 0;
            long waitUs = 0;
            long flushSeqBefore = 0;
            synchronized (mAsyncLock) {
                result = tryDequeueOutputLocked(info);
                if (result != INFO_TRY_AGAIN_LATER || !mStarted || timeoutUs == 0) break;
                if (timeoutUs > 0) {
                    long remainingNs = java.util.concurrent.TimeUnit.MICROSECONDS.toNanos(timeoutUs)
                        - (System.nanoTime() - startedNs);
                    if (remainingNs <= 0) break;
                }
                if (hasExternalVideoFeed() && !mOutFree.isEmpty()
                        && com.macapk.loader.NativeMedia.nativeVidQueueDepth(mVidDec) > 0) {
                    waitDec = mVidDec;
                    flushSeqBefore = mFlushSeq;
                    if (timeoutUs < 0) {
                        waitUs = 2000L;
                    } else {
                        long remNs = java.util.concurrent.TimeUnit.MICROSECONDS.toNanos(timeoutUs)
                            - (System.nanoTime() - startedNs);
                        waitUs = Math.min(2000L, Math.max(1L, (remNs + 999L) / 1000L));
                    }
                    mVidWaiters++;
                } else {
                    if (!waitForBufferLocked(timeoutUs, startedNs)) break;
                    continue;
                }
            }
            long[] waitedFrame = null;
            boolean linkFallback = false;
            boolean keepWaiting = true;
            try {
                try {
                    waitedFrame = com.macapk.loader.NativeMedia.nativeVidWaitPoll(waitDec, waitUs);
                } catch (UnsatisfiedLinkError ule) {
                    linkFallback = true;
                    waitedFrame = com.macapk.loader.NativeMedia.nativeVidPoll(waitDec);
                }
            } finally {
                synchronized (mAsyncLock) {
                    mVidWaiters--;
                    if (mVidWaiters == 0) mAsyncLock.notifyAll();
                    if (waitedFrame != null) {
                        if (mStarted && mVidDec == waitDec && mFlushSeq == flushSeqBefore) {
                            if (mStashedVidFrame != null && mStashedVidFrame[0] != 0) {
                                RELEASE_FRAME.accept(mStashedVidFrame[0]);
                            }
                            mStashedVidFrame = waitedFrame;
                            waitedFrame = null;
                            mAsyncLock.notifyAll();
                        }
                    } else if (linkFallback && mStarted && mVidDec == waitDec && mFlushSeq == flushSeqBefore) {
                        keepWaiting = waitForBufferLocked(timeoutUs, startedNs);
                    }
                }
                if (waitedFrame != null && waitedFrame[0] != 0) {
                    RELEASE_FRAME.accept(waitedFrame[0]);
                }
            }
            if (!keepWaiting) break;
        }
        if (mObs != null) {
            int outSz = (result >= 0 && info != null) ? Math.max(0, info.size) : 0;
            long outPts = (result >= 0 && info != null) ? info.presentationTimeUs : -1L;
            mObs.recordDequeueOut(result, outSz, outPts, System.nanoTime() - startedNs);
        }
        return result;
    }
    // Shared output-selection logic for the synchronous dequeueOutputBuffer()
    // and the async dispatch loop (asyncDispatchLoop mirrors this rather than
    // hand-rolling its own copy -- see that method's header comment). MUST be
    // called with mAsyncLock held. Handles audio (mOutQ of decoded PCM),
    // video (a poll into the native VideoToolbox output queue), and the
    // no-codec stub (neither mDec nor mVidDec open) uniformly.
    private int tryDequeueOutputLocked(BufferInfo info) {
        int r = tryDequeueOutputLocked0(info);
        com.macapk.loader.MediaStats.codecPoll(mStat, r);
        return r;
    }
    private int tryDequeueOutputLocked0(BufferInfo info) {
        if (!mStarted) return INFO_TRY_AGAIN_LATER;
        if (mIsEncoder && mIsVideo) {
            pollVideoEncoderLocked();
            if (!mFormatReported) {
                if (mOutQ.isEmpty()) return INFO_TRY_AGAIN_LATER;
                if (mEncOutFormat != null && mVidEnc != 0) {
                    byte[] c0 = com.macapk.loader.NativeMedia.nativeVidEncCsd0(mVidEnc);
                    if (c0 != null && c0.length > 0) {
                        mEncOutFormat.setByteBuffer("csd-0", ByteBuffer.wrap(c0));
                    }
                    byte[] c1 = com.macapk.loader.NativeMedia.nativeVidEncCsd1(mVidEnc);
                    if (c1 != null && c1.length > 0) {
                        mEncOutFormat.setByteBuffer("csd-1", ByteBuffer.wrap(c1));
                    }
                }
                mFormatReported = true;
                return INFO_OUTPUT_FORMAT_CHANGED;
            }
            if (mOutQ.isEmpty() || mOutFree.isEmpty()) return INFO_TRY_AGAIN_LATER;
            OutEntry e = mOutQ.poll();
            int oi = mOutFree.poll();
            mOut[oi] = ByteBuffer.wrap(e.pcm);
            info.set(0, e.pcm.length, e.pts, e.flags);
            return oi;
        }
        if (mIsEncoder) {
            pollAudioEncoderLocked();
        }
        // AOSP reports the output format once before the first buffer.
        if (!mFormatReported) { mFormatReported = true; return INFO_OUTPUT_FORMAT_CHANGED; }
        if (mVidDec != 0) {
            if (mOutFree.isEmpty()) return INFO_TRY_AGAIN_LATER;
            long[] polled = isVp8()
                ? com.macapk.loader.NativeMedia.nativeVp8Poll(mVidDec)
                : isVp9()
                ? com.macapk.loader.NativeMedia.nativeVp9Poll(mVidDec)
                : VIDEO_REORDER ? reorderPoll()
                : nextVideoToolboxPolledLocked(mVidDec);
            if (polled == null) return INFO_TRY_AGAIN_LATER;
            int oi = mOutFree.poll();
            if (PRESENT_TRACE) traceOut();
            if (polled[0] != 0) {
                android.view.Surface.macapkRecordProducerStat(
                    mOutputTarget.surface, android.view.Surface.STAT_DEQUEUED_OUT);
            }
            mOutPixbuf[oi] = polled[0];
            mOutPts[oi] = polled[1];
            // No real "compressed size" applies to an opaque decoded pixel
            // buffer; report a nonzero placeholder for a real frame (some
            // consumers treat size==0 as "nothing to render" regardless of
            // flags) and 0 for the synthetic end-of-stream-only marker
            // (polled[0]==0, see video_toolbox.mm's eos handling).
            int sz = polled[0] != 0 ? Math.max(1, mVidWidth * mVidHeight * 4) : 0;
            info.set(0, sz, polled[1], (int) polled[2]);
            return oi;
        }
        if (mOutQ.isEmpty() || mOutFree.isEmpty()) return INFO_TRY_AGAIN_LATER;
        OutEntry e = mOutQ.poll();
        int oi = mOutFree.poll();
        // ⚠ LITTLE_ENDIAN IS MANDATORY, and ByteBuffer.wrap() does NOT give it.
        // java.nio defaults every new buffer to BIG_ENDIAN, but ExoPlayer/media3
        // asserts the opposite before touching a single sample:
        //   DefaultAudioSink.handleBuffer -> checkArgument(buffer.order() == LITTLE_ENDIAN)
        // (androidx/media release, DefaultAudioSink.java). So the FIRST decoded
        // PCM buffer we handed it threw IllegalArgumentException, ExoPlayer
        // wrapped that as "ExoPlaybackException: Unexpected runtime error", and
        // playback died in the AUDIO renderer before the video renderer ever
        // opened a decoder.
        //
        // MEASURED 2026-08-16 on PlayTube: a real Linus Tech Tips video resolved
        // its stream, the audio decoder opened and decoded, then this killed the
        // player -- 0:00/0:00 on screen with ZERO video-toolbox opens, which read
        // exactly like "video decode does not work" and was nothing of the kind.
        // Little-endian is also simply correct: Android PCM 16-bit is
        // little-endian, and so is every Apple Silicon host we run on.
        mOut[oi] = ByteBuffer.wrap(e.pcm).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        info.set(0, e.pcm.length, e.pts, e.flags);
        return oi;
    }
    public void releaseOutputBuffer(int index, boolean render) {
        long pb = 0;
        long ptsUs = 0L;
        OutputTarget target = null;
        boolean videoSlot = false;
        synchronized (mAsyncLock) {
            try {
                if (mIsVideo && !mIsEncoder && mOutPixbuf != null && index >= 0 && index < mOutPixbuf.length) {
                    videoSlot = true;
                    pb = mOutPixbuf[index];
                    ptsUs = (mOutPts != null && index < mOutPts.length) ? mOutPts[index] : 0L;
                    mOutPixbuf[index] = 0;
                    target = mOutputTarget;
                    if (mOut != null && index < mOut.length) mOut[index] = null;
                    mOutFree.add(index);
                } else {
                    if (mOut != null && index >= 0 && index < mOut.length) { mOut[index] = null; mOutFree.add(index); }
                }
            } finally {
                mAsyncLock.notifyAll();
            }
        }
        if (videoSlot) {
            if (pb != 0) {
                try {
                    android.view.Surface.macapkRecordProducerStat(
                        target.surface, render ? android.view.Surface.STAT_RELEASE_TRUE
                                               : android.view.Surface.STAT_RELEASE_FALSE);
                    // render=true is the whole point of this project's video
                    // work: composite the decoded frame onto the configured
                    // Surface for real (video_toolbox.mm's
                    // macapk_vtb_render_frame -> cocoa_bridge.mm's
                    // CocoaPresentVideoFrame), not merely decode it.
                    if (PRESENT_TRACE && !render) mTraceAppDrops++;
                    if (render && target.hasSurface && target.gen == mCodecSurfaceGen.get()) {
                        if (PRESENT_TRACE) tracePresent();
                        // Android API 23+ contract: releaseOutputBuffer(i, true)
                        // attaches presentationTimeUs * 1000 as the SurfaceTexture
                        // nanosecond presentation timestamp.
                        renderDecodedFrameToTarget(target, pb, ptsUs * 1000L);
                    }
                } finally {
                    com.macapk.loader.NativeMedia.nativeVidReleaseFrame(pb);
                }
            }
            if (mObs != null) mObs.recordReleaseOut(index, render);
            return;
        }
        if (mObs != null) mObs.recordReleaseOut(index, render);
    }
    /** AOSP releaseOutputBuffer(index, renderTimestampNs): the frame is shown
     *  AT that System.nanoTime() timestamp (SurfaceFlinger latches, per vsync,
     *  the newest queued buffer that is due and drops the ones it supersedes;
     *  a timestamp more than a second away is treated as "now"). We ignored the
     *  timestamp and presented on release. ExoPlayer's MediaCodecVideoRenderer
     *  releases each frame up to 50 ms EARLY with its vsync-aligned display
     *  time, so frames landed early by a varying amount: uneven cadence, i.e.
     *  judder (ytdlnis's preview player, owner report 2026-10-19).
     *  MACAPK_VIDEO_TIMED_RELEASE=0 restores present-on-release. */
    public void releaseOutputBuffer(int index, long renderTimestampNs) {
        OutputTarget checkTarget = mOutputTarget;
        if (!mIsVideo || mIsEncoder || !checkTarget.hasSurface) { releaseOutputBuffer(index, true); return; }
        long pb = 0;
        OutputTarget target;
        synchronized (mAsyncLock) {
            try {
                target = mOutputTarget;
                if (mOutPixbuf != null && index >= 0 && index < mOutPixbuf.length) {
                    pb = mOutPixbuf[index];
                    mOutPixbuf[index] = 0;
                    if (mOut != null && index < mOut.length) mOut[index] = null;
                    mOutFree.add(index);
                } else {
                    if (mOut != null && index >= 0 && index < mOut.length) { mOut[index] = null; mOutFree.add(index); }
                    if (mObs != null) mObs.recordReleaseOut(index, false);
                    return;
                }
            } finally {
                mAsyncLock.notifyAll();
            }
        }
        if (mObs != null) mObs.recordReleaseOut(index, pb != 0);
        if (pb == 0) return;
        android.view.Surface.macapkRecordProducerStat(
            target.surface, android.view.Surface.STAT_RELEASE_TIMED);
        if (!VIDEO_TIMED_RELEASE) {
            try {
                if (target.gen == mCodecSurfaceGen.get()) {
                    if (PRESENT_TRACE) tracePresent();
                    renderDecodedFrameToTarget(target, pb, renderTimestampNs);
                }
            } finally {
                com.macapk.loader.NativeMedia.nativeVidReleaseFrame(pb);
            }
            return;
        }
        long now = System.nanoTime();
        if (PRESENT_TRACE) { traceEarly(renderTimestampNs - now); traceDue(renderTimestampNs); }
        long due = renderTimestampNs;
        if (due < now || due - now > 1_000_000_000L) due = now;   // late, or "not within a second": now
        synchronized (mTimedLock) {
            if (target.gen != mCodecSurfaceGen.get()) {
                com.macapk.loader.NativeMedia.nativeVidReleaseFrame(pb);
                return;
            }
            while (mTimed.size() >= 8) {
                long[] old = mTimed.pollFirst();
                if (old != null && old[0] != 0) {
                    com.macapk.loader.NativeMedia.nativeVidReleaseFrame(old[0]);
                    android.view.Surface.macapkRecordProducerStat(
                        target.surface, android.view.Surface.STAT_TIMED_OVERFLOW);
                }
            }
            mTimed.addLast(new long[]{ pb, due, renderTimestampNs, target.gen });
            if (mTimedThread == null || !mTimedThread.isAlive()) {
                mTimedQuit = false;
                mTimedThread = new Thread(this::timedPresentLoop, "MediaCodec:VideoPresent");
                mTimedThread.setDaemon(true);
                mTimedThread.start();
            }
            mTimedLock.notifyAll();
        }
    }
    /** MACAPK_VIDEO_PRESENT_TRACE=1: every 120 presents, log the present-to-
     *  present interval spread (the judder measure) and superseded drops. */
    static final boolean PRESENT_TRACE = "1".equals(System.getenv("MACAPK_VIDEO_PRESENT_TRACE"));
    private long mTraceLast, mTraceDrops, mTraceAppDrops;
    private final long[] mTraceIv = new long[120];
    private int mTraceN;
    private long mOutLast; private final long[] mOutIv = new long[120]; private int mOutN;
    private final long[] mEarly = new long[120]; private int mEarlyN;
    private long mInLast; private final long[] mInIv = new long[120]; private int mInN;
    private void traceIn() {
        long t = System.nanoTime();
        if (mInLast != 0) mInIv[mInN++] = t - mInLast;
        mInLast = t;
        if (mInN == mInIv.length) { traceSummary("decoder input", mInIv); mInN = 0; }
    }
    private long mDueLast; private final long[] mDueIv = new long[120]; private int mDueN;
    private void traceDue(long due) {
        if (mDueLast != 0) mDueIv[mDueN++] = due - mDueLast;
        mDueLast = due;
        if (mDueN == mDueIv.length) { traceSummary("release due-to-due", mDueIv); mDueN = 0; }
    }
    private void traceOut() {
        long t = System.nanoTime();
        if (mOutLast != 0) mOutIv[mOutN++] = t - mOutLast;
        mOutLast = t;
        if (mOutN == mOutIv.length) { traceSummary("decoder output", mOutIv); mOutN = 0; }
    }
    private void traceEarly(long early) {
        mEarly[mEarlyN++] = early;
        if (mEarlyN == mEarly.length) { traceSummary("release early(+)/late(-)", mEarly); mEarlyN = 0; }
    }
    private static void traceSummary(String what, long[] v) {
        long[] a = v.clone(); java.util.Arrays.sort(a);
        com.macapk.loader.NativeLog.i("MediaCodec", String.format("%s: p5=%.1fms p50=%.1f p95=%.1f min=%.1f max=%.1f",
            what, a[6] / 1e6, a[60] / 1e6, a[113] / 1e6, a[0] / 1e6, a[119] / 1e6));
    }
    private void tracePresent() {
        long t = System.nanoTime();
        if (mTraceLast != 0) mTraceIv[mTraceN++] = t - mTraceLast;
        mTraceLast = t;
        if (mTraceN == mTraceIv.length) {
            long[] a = mTraceIv.clone();
            java.util.Arrays.sort(a);
            double mean = 0; for (long v : a) mean += v; mean /= a.length;
            double sd = 0; for (long v : a) sd += (v - mean) * (v - mean); sd = Math.sqrt(sd / a.length);
            com.macapk.loader.NativeLog.i("MediaCodec", String.format(
                "video present: n=%d mean=%.1fms sd=%.1fms p5=%.1f p50=%.1f p95=%.1f max=%.1f superseded=%d appDropped=%d timed=%b",
                a.length, mean / 1e6, sd / 1e6, a[6] / 1e6, a[60] / 1e6, a[113] / 1e6, a[119] / 1e6,
                mTraceDrops, mTraceAppDrops, VIDEO_TIMED_RELEASE));
            mTraceN = 0; mTraceDrops = 0; mTraceAppDrops = 0;
        }
    }
    static final boolean VIDEO_TIMED_RELEASE =
        !"0".equals(System.getenv("MACAPK_VIDEO_TIMED_RELEASE"));
    private final Object mTimedLock = new Object();
    private final java.util.ArrayDeque<long[]> mTimed = new java.util.ArrayDeque<>();
    private Thread mTimedThread;
    private boolean mTimedQuit;

    /** Present queued frames at their due time. When several are due at once
     *  only the newest is shown and the older ones are released unshown --
     *  SurfaceFlinger's latch rule. Frames are released in queue order, which
     *  is ExoPlayer's presentation order. */
    private void timedPresentLoop() {
        synchronized (mTimedLock) {
            while (!mTimedQuit) {
                while (!mTimed.isEmpty() && mTimed.peekFirst()[3] != mCodecSurfaceGen.get()) {
                    long[] stale = mTimed.pollFirst();
                    if (stale != null && stale[0] != 0) {
                        com.macapk.loader.NativeMedia.nativeVidReleaseFrame(stale[0]);
                    }
                }
                long[] head = mTimed.peekFirst();
                if (head == null) {
                    try { mTimedLock.wait(); } catch (InterruptedException e) { return; }
                    continue;
                }
                long wait = head[1] - System.nanoTime();
                if (wait > 0) {
                    try { mTimedLock.wait(wait / 1_000_000L, (int) (wait % 1_000_000L)); }
                    catch (InterruptedException e) { return; }
                    continue;
                }
                long curGen = mCodecSurfaceGen.get();
                long[] show = mTimed.pollFirst();
                if (show == null) continue;
                if (show[3] != curGen) {
                    if (show[0] != 0) com.macapk.loader.NativeMedia.nativeVidReleaseFrame(show[0]);
                    continue;
                }
                long now = System.nanoTime();
                while (!mTimed.isEmpty() && mTimed.peekFirst()[1] <= now) {
                    long[] next = mTimed.pollFirst();
                    if (next == null) break;
                    if (next[3] != curGen) {
                        if (next[0] != 0) com.macapk.loader.NativeMedia.nativeVidReleaseFrame(next[0]);
                        continue;
                    }
                    com.macapk.loader.NativeMedia.nativeVidReleaseFrame(show[0]);   // superseded
                    android.view.Surface.macapkRecordProducerStat(
                        mOutputTarget.surface, android.view.Surface.STAT_TIMED_SUPERSEDED);
                    show = next;
                    if (PRESENT_TRACE) mTraceDrops++;
                }
                OutputTarget renderTarget = mOutputTarget;
                if (show[3] != renderTarget.gen || show[3] != mCodecSurfaceGen.get()) {
                    com.macapk.loader.NativeMedia.nativeVidReleaseFrame(show[0]);
                    continue;
                }
                if (PRESENT_TRACE) tracePresent();
                // Rendering under mTimedLock: drainTimedFrames (flush / stop /
                // release) waits for an in-flight present instead of freeing
                // the buffer underneath it.
                try {
                    renderDecodedFrameToTarget(renderTarget, show[0], show[2]);
                } catch (Throwable ignored) {}
                com.macapk.loader.NativeMedia.nativeVidReleaseFrame(show[0]);
            }
        }
    }

    private void renderDecodedFrameToTarget(OutputTarget target, long pb, long presentationTimeNs) {
        android.view.Surface s = target != null ? target.surface : null;
        if (s != null && s.macapkIsRemote()) {
            s.macapkPresentRemotePixelBuffer(pb, mVidWidth, mVidHeight, presentationTimeNs);
        } else {
            com.macapk.loader.NativeMedia.nativeVidRenderToSurface(pb, mVidWidth, mVidHeight,
                s != null ? s.macapkGetSurfaceTexture() : null, presentationTimeNs);
        }
        com.macapk.loader.MediaStats.codecRendered(mStat);
    }

    /** Release every queued, not-yet-shown frame (flush / stop / release). */
    private void drainTimedFrames(boolean quit) {
        synchronized (mTimedLock) {
            for (long[] f : mTimed) com.macapk.loader.NativeMedia.nativeVidReleaseFrame(f[0]);
            mTimed.clear();
            if (quit) { mTimedQuit = true; mTimedThread = null; }
            mTimedLock.notifyAll();
        }
    }

    public MediaFormat getOutputFormat() {
        if (mIsEncoder) {
            synchronized (mAsyncLock) {
                if (mEncOutFormat != null) return new MediaFormat(mEncOutFormat);
            }
            if (mIsVideo) return MediaFormat.createVideoFormat(mVidMime, mVidWidth, mVidHeight);
            return MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, mSampleRate, mChannels);
        }
        if (mIsVideo) {
            boolean hasSurfaceNow = mOutputTarget.hasSurface;
            MediaFormat f = MediaFormat.createVideoFormat(mVidMime, mVidWidth, mVidHeight);
            f.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                hasSurfaceNow ? MediaFormat.COLOR_FormatSurface : MediaFormat.COLOR_FormatYUV420Flexible);
            f.setInteger(MediaFormat.KEY_STRIDE, mVidWidth);
            f.setInteger(MediaFormat.KEY_SLICE_HEIGHT, mVidHeight);
            f.setInteger(MediaFormat.KEY_CROP_LEFT, 0);
            f.setInteger(MediaFormat.KEY_CROP_TOP, 0);
            f.setInteger(MediaFormat.KEY_CROP_RIGHT, mVidWidth > 0 ? mVidWidth - 1 : 0);
            f.setInteger(MediaFormat.KEY_CROP_BOTTOM, mVidHeight > 0 ? mVidHeight - 1 : 0);
            if (!hasSurfaceNow) {
                // Describes NativeVideoImage's OWN derived conversion, not
                // VideoToolbox's internal decode colourimetry -- meaningless
                // for the Surface path, which composites VideoToolbox's raw
                // BGRA directly and never runs that conversion. See that
                // class's doc for the exact matrix these three keys name.
                f.setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_FULL);
                f.setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT601_NTSC);
                f.setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO);
            }
            return f;
        }
        int sr = mDec != 0 ? com.macapk.loader.NativeMedia.nativeDecOutSampleRate(mDec) : mSampleRate;
        int ch = mDec != 0 ? com.macapk.loader.NativeMedia.nativeDecOutChannels(mDec) : mChannels;
        MediaFormat f = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_RAW, sr, ch);
        f.setInteger(MediaFormat.KEY_PCM_ENCODING, 2);  // ENCODING_PCM_16BIT
        return f;
    }
    public MediaFormat getInputFormat() {
        return mInputFormat != null ? new MediaFormat(mInputFormat) : new MediaFormat();
    }
    public MediaFormat getOutputFormat(int index) { return getOutputFormat(); }

    public MediaCodecInfo getCodecInfo() { return MediaCodecInfo.forName(mName); }

    public void setParameters(android.os.Bundle params) {
        if (params == null) return;
        long vidEnc = 0;
        synchronized (mAsyncLock) { vidEnc = mVidEnc; }
        if (vidEnc != 0 && params.containsKey(PARAMETER_KEY_REQUEST_SYNC_FRAME)) {
            int v = params.getInt(PARAMETER_KEY_REQUEST_SYNC_FRAME, 0);
            if (v != 0) com.macapk.loader.NativeMedia.nativeVidEncRequestKeyFrame(vidEnc);
        }
    }

    /** The metrics-bundle keys AOSP publishes for getMetrics(). */
    public static final class MetricsConstants {
        private MetricsConstants() { }
        public static final String CODEC = "android.media.mediacodec.codec";
        public static final String ENCODER = "android.media.mediacodec.encoder";
        public static final String HEIGHT = "android.media.mediacodec.height";
        public static final String MIME_TYPE = "android.media.mediacodec.mime";
        public static final String MODE = "android.media.mediacodec.mode";
        public static final String MODE_AUDIO = "audio";
        public static final String MODE_VIDEO = "video";
        public static final String ROTATION = "android.media.mediacodec.rotation";
        public static final String SECURE = "android.media.mediacodec.secure";
        public static final String WIDTH = "android.media.mediacodec.width";
    }

    /** AOSP throws this from a block-model call made on a codec that was not
     *  configured with CONFIGURE_FLAG_USE_BLOCK_MODEL. */
    public static class IncompatibleWithBlockModelException extends RuntimeException {
        IncompatibleWithBlockModelException(String message) { super(message); }
    }

    /** AOSP throws this when a queued BufferInfo carries a flag combination the
     *  codec cannot accept. */
    public static class InvalidBufferFlagsException extends RuntimeException {
        InvalidBufferFlagsException(String message) { super(message); }
    }

    public android.os.PersistableBundle getMetrics() { return new android.os.PersistableBundle(); }

    public void subscribeToVendorParameters(java.util.List<String> names) { }
    public void unsubscribeFromVendorParameters(java.util.List<String> names) { }

    public android.view.Surface createInputSurface() {
        synchronized (mAsyncLock) {
            if (mReleased || !mConfigured || !mIsEncoder || !mIsVideo || mStarted) {
                throw new IllegalStateException(
                        "createInputSurface requires configured, non-started video encoder");
            }
            if (mInputSurface != null) {
                throw new IllegalStateException("Input surface already created or set");
            }
            EncoderInputSurface sur = new EncoderInputSurface(mVidWidth, mVidHeight);
            mInputSurface = sur;
            mOwnsInputSurface = true;
            return sur;
        }
    }

    public void signalEndOfInputStream() {
        android.view.Surface inSur;
        long vidEnc;
        synchronized (mAsyncLock) {
            if (mReleased || !mConfigured || !mIsEncoder || !mIsVideo || !mStarted || mInputSurface == null) {
                throw new IllegalStateException(
                        "signalEndOfInputStream requires running video encoder with input surface");
            }
            inSur = mInputSurface;
            vidEnc = mVidEnc;
            if (mEncEosQueued) return;
        }
        if (inSur instanceof EncoderInputSurface) {
            ((EncoderInputSurface) inSur).flushBarrier();
        }
        if (vidEnc != 0) {
            synchronized (mEncSubmitLock) {
                if (mVidEnc == vidEnc) {
                    com.macapk.loader.NativeMedia.nativeVidEncEndOfStream(vidEnc);
                }
            }
        }
        synchronized (mAsyncLock) {
            mEncEosQueued = true;
            if (mVidEnc != 0) {
                pollVideoEncoderLocked();
            } else {
                mOutQ.add(new OutEntry(new byte[0], 0L, BUFFER_FLAG_END_OF_STREAM));
            }
            mAsyncLock.notifyAll();
        }
    }

    public void detachOutputSurface() { }

    public static android.view.Surface createPersistentInputSurface() {
        return new EncoderInputSurface(0, 0);
    }

    public void setInputSurface(android.view.Surface surface) {
        java.util.Objects.requireNonNull(surface, "surface must not be null");
        synchronized (mAsyncLock) {
            if (mReleased || !mConfigured || !mIsEncoder || !mIsVideo || mStarted) {
                throw new IllegalStateException("MediaCodec is not a configured video encoder");
            }
            if (!(surface instanceof EncoderInputSurface)) {
                throw new IllegalArgumentException("surface must be a persistent input surface");
            }
            if (mInputSurface != null) {
                throw new IllegalStateException("Input surface already created or set");
            }
            ((EncoderInputSurface) surface).configureSize(mVidWidth, mVidHeight);
            mInputSurface = surface;
            mOwnsInputSurface = false;
        }
    }

    // Must be called with mAsyncLock held.
    private void pollAudioEncoderLocked() {
        if (mEnc == 0) return;
        long[] meta = new long[2];
        while (true) {
            byte[] pkt = com.macapk.loader.NativeMedia.nativeAudioEncPoll(mEnc, meta);
            if (pkt == null) break;
            mOutQ.add(new OutEntry(pkt, meta[0], (int) meta[1]));
        }
    }

    // Must be called with mAsyncLock held.
    private void pollVideoEncoderLocked() {
        if (mVidEnc == 0) return;
        long[] meta = new long[2];
        while (true) {
            byte[] pkt = com.macapk.loader.NativeMedia.nativeVidEncPoll(mVidEnc, meta);
            if (pkt == null) break;
            mOutQ.add(new OutEntry(pkt, meta[0], (int) meta[1]));
        }
    }

    // Invoked by native VideoToolbox EncOutputCallback (outside VtEncoder::mu).
    void macapkOnNativeOutput() {
        synchronized (mAsyncLock) {
            mAsyncLock.notifyAll();
        }
    }

    private void detachInputSurface() {
        if (mInputSurface instanceof EncoderInputSurface) {
            ((EncoderInputSurface) mInputSurface).attachCodec(null);
        }
    }

    private void releaseOwnedInputSurface() {
        if (mInputSurface != null) {
            if (mOwnsInputSurface) {
                try { mInputSurface.release(); } catch (Throwable ignored) {}
            } else if (mInputSurface instanceof EncoderInputSurface) {
                ((EncoderInputSurface) mInputSurface).attachCodec(null);
            }
            mInputSurface = null;
            mOwnsInputSurface = false;
        }
    }

    boolean isEncoderStartedAndOpen() {
        synchronized (mAsyncLock) {
            return mStarted && !mReleased && mVidEnc != 0 && !mEncEosQueued;
        }
    }

    void submitInputSurfacePixels(int[] argb, int width, int height, long ptsUs) {
        if (argb == null || width <= 0 || height <= 0) return;
        long vidEnc;
        synchronized (mAsyncLock) {
            if (!mStarted || mReleased || mVidEnc == 0 || mEncEosQueued) return;
            vidEnc = mVidEnc;
        }
        synchronized (mEncSubmitLock) {
            if (mVidEnc == vidEnc) {
                com.macapk.loader.NativeMedia.nativeVidEncEncodeArgb(vidEnc, argb, width, height, ptsUs);
            }
        }
        synchronized (mAsyncLock) {
            pollVideoEncoderLocked();
            mAsyncLock.notifyAll();
        }
    }

    private static final class EncoderInputSurface extends android.view.Surface {
        private final android.graphics.SurfaceTexture mSt;
        private final android.os.HandlerThread mThread;
        private final android.os.Handler mHandler;
        private final Object mLock = new Object();
        private MediaCodec mCodec;
        private int mWidth;
        private int mHeight;
        private boolean mReleased = false;
        private long mFirstTsNs = Long.MIN_VALUE;
        private long mLastPtsUs = -1L;

        private android.graphics.Bitmap mDrawBitmap;
        private android.graphics.Canvas mDrawCanvas;
        private boolean mCanvasLocked = false;

        EncoderInputSurface(int width, int height) {
            super(createBackingSurfaceTexture(width, height));
            mSt = super.macapkGetSurfaceTexture();
            mWidth = width;
            mHeight = height;
            mThread = new android.os.HandlerThread("MediaCodec-InputSurface");
            mThread.setDaemon(true);
            mThread.start();
            mHandler = new android.os.Handler(mThread.getLooper());
            mSt.setOnFrameAvailableListener(new android.graphics.SurfaceTexture.OnFrameAvailableListener() {
                @Override public void onFrameAvailable(android.graphics.SurfaceTexture surfaceTexture) {
                    drainLatestFrame();
                }
            }, mHandler);
        }

        private static android.graphics.SurfaceTexture createBackingSurfaceTexture(int w, int h) {
            android.graphics.SurfaceTexture st = new android.graphics.SurfaceTexture(false);
            if (w > 0 && h > 0) st.setDefaultBufferSize(w, h);
            return st;
        }

        void configureSize(int w, int h) {
            synchronized (mLock) {
                mWidth = w;
                mHeight = h;
                if (w > 0 && h > 0) mSt.setDefaultBufferSize(w, h);
            }
        }

        void attachCodec(MediaCodec codec) {
            boolean shouldDrain;
            synchronized (mLock) {
                mCodec = codec;
                shouldDrain = (codec != null && !mReleased);
            }
            if (shouldDrain) postDrain();
        }

        void postDrain() {
            android.os.Handler h;
            synchronized (mLock) {
                if (mReleased || mHandler == null || !mThread.isAlive()) return;
                h = mHandler;
            }
            h.post(new Runnable() {
                @Override public void run() { drainLatestFrame(); }
            });
        }

        private long nextPtsUs(long tsNs) {
            long ptsUs;
            if (mFirstTsNs == Long.MIN_VALUE) {
                mFirstTsNs = tsNs;
                ptsUs = 0L;
            } else {
                ptsUs = Math.max(0L, (tsNs - mFirstTsNs) / 1000L);
            }
            if (ptsUs <= mLastPtsUs) ptsUs = mLastPtsUs + 1L;
            mLastPtsUs = ptsUs;
            return ptsUs;
        }

        void drainLatestFrame() {
            MediaCodec codec;
            int[] px;
            int w;
            int h;
            long ptsUs;
            synchronized (mLock) {
                if (mReleased || mCodec == null || !mCodec.isEncoderStartedAndOpen()) return;
                int prevGen = mSt.macapkLatchGeneration();
                android.graphics.Bitmap bm = mSt.macapkLatchBitmap();
                int newGen = mSt.macapkLatchGeneration();
                if (bm == null || newGen == prevGen) return;
                px = bm.macapkLiveArgbOrNull();
                if (px == null) px = bm.macapkArgbBuffer();
                if (px == null || px.length == 0) return;
                w = bm.getWidth();
                h = bm.getHeight();
                long tsNs = mSt.getTimestamp();
                ptsUs = nextPtsUs(tsNs > 0 ? tsNs : System.nanoTime());
                codec = mCodec;
            }
            codec.submitInputSurfacePixels(px, w, h, ptsUs);
        }

        void flushBarrier() {
            android.os.Handler h;
            synchronized (mLock) {
                if (mReleased || mHandler == null || !mThread.isAlive()) return;
                h = mHandler;
            }
            if (Thread.currentThread() == mThread) {
                drainLatestFrame();
                return;
            }
            final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
            if (h.post(new Runnable() {
                @Override public void run() {
                    try {
                        drainLatestFrame();
                    } finally {
                        latch.countDown();
                    }
                }
            })) {
                try {
                    latch.await(2000, java.util.concurrent.TimeUnit.MILLISECONDS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        @Override public android.graphics.Canvas lockCanvas(android.graphics.Rect dirty) {
            synchronized (mLock) {
                if (!isValid() || mReleased) {
                    throw new IllegalStateException("Surface has already been released.");
                }
                if (mCanvasLocked) {
                    throw new IllegalArgumentException("Surface was already locked");
                }
                int w = mWidth > 0 ? mWidth : 640;
                int h = mHeight > 0 ? mHeight : 480;
                if (mDrawBitmap == null || mDrawBitmap.getWidth() != w || mDrawBitmap.getHeight() != h) {
                    mDrawBitmap = android.graphics.Bitmap.createBitmapWithCanvasBacking(w, h);
                    mDrawCanvas = new android.graphics.Canvas(mDrawBitmap);
                }
                mCanvasLocked = true;
                return mDrawCanvas;
            }
        }

        @Override public android.graphics.Canvas lockHardwareCanvas() {
            return lockCanvas(null);
        }

        @Override public void unlockCanvasAndPost(android.graphics.Canvas canvas) {
            MediaCodec codec;
            android.graphics.Bitmap bm;
            long ptsUs = 0L;
            long tsNs = System.nanoTime();
            synchronized (mLock) {
                if (!isValid() || mReleased) {
                    throw new IllegalStateException("Surface has already been released.");
                }
                if (canvas != mDrawCanvas) {
                    throw new IllegalArgumentException("canvas object must be the same instance "
                            + "that was previously returned by lockCanvas");
                }
                if (!mCanvasLocked) {
                    throw new IllegalStateException("Surface was not locked");
                }
                mCanvasLocked = false;
                bm = mDrawBitmap;
                codec = mCodec;
                if (codec != null && codec.isEncoderStartedAndOpen()) {
                    ptsUs = nextPtsUs(tsNs);
                }
            }
            if (bm == null) return;
            int w = bm.getWidth();
            int h = bm.getHeight();
            int[] pixels = bm.macapkLiveArgbOrNull();
            if (pixels == null) pixels = bm.macapkArgbBuffer();
            if (pixels == null || w <= 0 || h <= 0) return;
            if (codec != null && codec.isEncoderStartedAndOpen()) {
                codec.submitInputSurfacePixels(pixels, w, h, ptsUs);
            } else {
                mSt.macapkQueueProducerFrame(null, pixels.clone(), w, h, w, null, null, false);
            }
        }

        @Override public void release() {
            synchronized (mLock) {
                if (mReleased) return;
                mReleased = true;
                mCodec = null;
            }
            super.release();
            try { mSt.setOnFrameAvailableListener(null, null); } catch (Throwable ignored) {}
            try { mSt.release(); } catch (Throwable ignored) {}
            try { mThread.quitSafely(); } catch (Throwable ignored) {}
        }
    }

    /** API 31, tunneled playback only. Mac.apk has no tunneled path
     *  (AudioDeviceInfo.getEncapsulationModes() reports none), so the listener
     *  is retained and never fires. */
    public void setOnFirstTunnelFrameReadyListener(android.os.Handler handler,
            OnFirstTunnelFrameReadyListener listener) {
        mFirstTunnelFrameReadyListener = listener;
    }

    private OnFirstTunnelFrameReadyListener mFirstTunnelFrameReadyListener;

    /** API 28. Selects a presentation inside a next-gen-audio bitstream. Every
     *  codec here decodes to PCM and carries no presentations, so AOSP's own
     *  answer for that case -- IllegalStateException, "not applicable" -- is
     *  what an app gets. */
    public void setAudioPresentation(AudioPresentation presentation) {
        java.util.Objects.requireNonNull(presentation, "audio presentation must not be null");
        throw new IllegalStateException("Codec does not carry audio presentations");
    }

    // ---- vendor parameters (API 30) ---------------------------------------
    // AOSP: "@return a list of supported vendor parameter names", empty when the
    // codec exposes none. AudioToolbox/VideoToolbox expose no vendor parameters,
    // so the list is genuinely empty -- and getParameterDescriptor() must agree,
    // returning null for every name (AOSP's own answer for a name the codec does
    // not support).

    public java.util.List<String> getSupportedVendorParameters() {
        return java.util.Collections.emptyList();
    }

    /** @Nullable in AOSP: null when the name is not a supported parameter. */
    public ParameterDescriptor getParameterDescriptor(String name) {
        java.util.Objects.requireNonNull(name, "name must not be null");
        return null;
    }

    /** Describes one vendor parameter. AOSP MediaCodec.ParameterDescriptor. */
    public static class ParameterDescriptor {
        private final String mName;
        private final int mType;
        ParameterDescriptor(String name, int type) { mName = name; mType = type; }
        public String getName() { return mName; }
        /** One of the MediaFormat.TYPE_* values. */
        public int getType() { return mType; }
    }

    // ---- block model (API 30) ---------------------------------------------

    /** AOSP throws IllegalStateException unless the codec was configured with
     *  CONFIGURE_FLAG_USE_BLOCK_MODEL; this backend cannot be. */
    public QueueRequest getQueueRequest(int index) {
        throw new IllegalStateException("The codec is not configured for block model");
    }

    /** Same contract as getQueueRequest. */
    public OutputFrame getOutputFrame(int index) {
        throw new IllegalStateException("The codec is not configured for block model");
    }

    /**
     * API 30. Wraps a HardwareBuffer so its planes can be read as ByteBuffers.
     * AOSP returns NULL when the buffer's format or usage flags make it
     * unmappable. This backend never produces a HardwareBuffer-backed output
     * (getOutputFrame above cannot be reached), and there is no mapping path, so
     * null -- AOSP's own "cannot map this" answer -- is correct.
     *
     * STATIC, as in AOSP ("public static @Nullable Image mapHardwareBuffer",
     * frameworks/base android14-release media/java/android/media/
     * MediaCodec.java:3318) and the SDK-34 android.jar. It was an instance
     * method here, so every app call (invokestatic) threw
     * IncompatibleClassChangeError before this body ran. Found by
     * debug/apishape_gate.sh (KNOWN_OPEN, struck 2026-09-26).
     */
    public static Image mapHardwareBuffer(android.hardware.HardwareBuffer hardwareBuffer) {
        java.util.Objects.requireNonNull(hardwareBuffer, "hardwareBuffer must not be null");
        return null;
    }

    /** API 34, block model only. Same terminal answer as getQueueRequest. */
    public void queueInputBuffers(int index, java.util.ArrayDeque<BufferInfo> bufferInfos) {
        throw new IllegalStateException("The codec is not configured for block model");
    }

    /** API 34, block model + DRM. Unreachable twice over: no block model, and
     *  Mac.apk implements no DRM at all (see MediaDrm). */
    public void queueSecureInputBuffers(int index, java.util.ArrayDeque<BufferInfo> bufferInfos,
            java.util.ArrayDeque<CryptoInfo> cryptoInfos) {
        throw new IllegalStateException("The codec is not configured for block model");
    }

    /** One queued input in the block model. Never produced here. */
    public final class QueueRequest {
        QueueRequest() { }
        private IllegalStateException notBlockModel() {
            return new IllegalStateException("The codec is not configured for block model");
        }
        public QueueRequest setLinearBlock(LinearBlock block, int offset, int size) {
            throw notBlockModel();
        }
        public QueueRequest setMultiFrameLinearBlock(LinearBlock block,
                java.util.ArrayDeque<BufferInfo> infos) {
            throw notBlockModel();
        }
        public QueueRequest setEncryptedLinearBlock(LinearBlock block, int offset, int size,
                CryptoInfo cryptoInfo) {
            throw notBlockModel();
        }
        public QueueRequest setMultiFrameEncryptedLinearBlock(LinearBlock block,
                java.util.ArrayDeque<BufferInfo> infos,
                java.util.ArrayDeque<CryptoInfo> cryptoInfos) {
            throw notBlockModel();
        }
        public QueueRequest setHardwareBuffer(android.hardware.HardwareBuffer buffer) {
            throw notBlockModel();
        }
        public QueueRequest setPresentationTimeUs(long presentationTimeUs) {
            throw notBlockModel();
        }
        public QueueRequest setFlags(int flags) { throw notBlockModel(); }
        public QueueRequest setIntegerParameter(String key, int value) { throw notBlockModel(); }
        public QueueRequest setLongParameter(String key, long value) { throw notBlockModel(); }
        public QueueRequest setFloatParameter(String key, float value) { throw notBlockModel(); }
        public QueueRequest setStringParameter(String key, String value) { throw notBlockModel(); }
        public QueueRequest setByteBufferParameter(String key, java.nio.ByteBuffer value) {
            throw notBlockModel();
        }
        public void queue() { throw notBlockModel(); }
    }

    /** One dequeued output in the block model. Never produced here. */
    public static final class OutputFrame {
        OutputFrame() { }
        /** @Nullable in AOSP. */
        public LinearBlock getLinearBlock() { return null; }
        /** @Nullable in AOSP. */
        public android.hardware.HardwareBuffer getHardwareBuffer() { return null; }
        public long getPresentationTimeUs() { return 0L; }
        public int getFlags() { return 0; }
        public MediaFormat getFormat() { return new MediaFormat(); }
        public java.util.Set<String> getChangedKeys() { return java.util.Collections.emptySet(); }
        public java.util.ArrayDeque<BufferInfo> getBufferInfos() {
            return new java.util.ArrayDeque<BufferInfo>();
        }
    }

    /**
     * A shared memory block for the block model. AOSP's obtain() is @Nullable
     * and documented to return null when a block "cannot be obtained", which is
     * permanently the case here -- so an app that checks the return value takes
     * its non-block-model path, which is what we want.
     */
    public static final class LinearBlock {
        LinearBlock() { }

        public static LinearBlock obtain(int capacity, String[] codecNames) {
            java.util.Objects.requireNonNull(codecNames, "codecNames must not be null");
            return null;
        }

        /** AOSP: whether the named codecs can share a block without copying.
         *  Nothing here supports the block model, so false. */
        public static boolean isCodecCopyFreeCompatible(String[] codecNames) {
            java.util.Objects.requireNonNull(codecNames, "codecNames must not be null");
            return false;
        }

        public boolean isMappable() { return false; }

        /** AOSP throws IllegalStateException for a block that is not mappable. */
        public java.nio.ByteBuffer map() {
            throw new IllegalStateException("The linear block is not mappable");
        }

        public void recycle() { }

        @Override protected void finalize() throws Throwable {
            try { recycle(); } finally { super.finalize(); }
        }
    }

    /** Returns bounded observability summary when MACAPK_CODEC_OBS=1, or null when disabled. */
    public String macapkCodecObsSnapshot() {
        return mObs != null ? mObs.snapshot("snapshot") : null;
    }

    /**
     * MACAPK_CODEC_OBS=1: bounded, default-OFF observability recorder for
     * MediaCodec queue/wait/output-poll/image-readback boundaries (OBS-001).
     * Uses System.nanoTime() (monotonic ns), fixed 64-slot ring + dropped counter,
     * and System.err emission so both macapk-runtime and bare JVM work identically.
     */
    private static final class ObsRecorder {
        static final int MAX_OBS_EVENTS = 64;
        private static final int OP_DEQ_IN = 1, OP_QUE_IN = 2, OP_DEQ_OUT = 3, OP_GET_IMG = 4, OP_REL_OUT = 5;
        private static final java.util.concurrent.atomic.AtomicInteger NEXT_ID =
            new java.util.concurrent.atomic.AtomicInteger();

        private final int codecId = NEXT_ID.incrementAndGet();
        private final long[] evDurNs = new long[MAX_OBS_EVENTS];
        private final long[] evPtsUs = new long[MAX_OBS_EVENTS];
        private final int[] evOp = new int[MAX_OBS_EVENTS];
        private final int[] evArg = new int[MAX_OBS_EVENTS];
        private final int[] evBytes = new int[MAX_OBS_EVENTS];

        private String mime = "";
        private boolean surface;
        private boolean emitted;
        private long lastThreadId = -1L;
        private int threadSwitches;
        private int evWriteIdx, evBuffered;
        private long totalEvents, droppedEvents;
        private long inDeq, inTryAgain, inWaitNs, inQueued, inBytes, eosQueued;
        private long outFmt, outDeq, outTryAgain, outWaitNs, outMaxWaitNs, outBytes;
        private long imgCalls, imgBytes, imgNs;
        private long relCount, relRender;

        synchronized void onStart(String m, boolean surf) {
            mime = m != null ? m : "";
            surface = surf;
            emitted = false;
            touchThread();
        }

        private void touchThread() {
            long tid = Thread.currentThread().getId();
            if (lastThreadId != -1L && lastThreadId != tid) threadSwitches++;
            lastThreadId = tid;
        }

        private void pushEvent(int op, int arg, int bytes, long ptsUs, long durNs) {
            totalEvents++;
            if (evBuffered < MAX_OBS_EVENTS) {
                evBuffered++;
            } else {
                droppedEvents++;
            }
            evOp[evWriteIdx] = op;
            evArg[evWriteIdx] = arg;
            evBytes[evWriteIdx] = bytes;
            evPtsUs[evWriteIdx] = ptsUs;
            evDurNs[evWriteIdx] = durNs;
            evWriteIdx = (evWriteIdx + 1) % MAX_OBS_EVENTS;
        }

        synchronized void recordDequeueIn(int ret, long durNs) {
            touchThread();
            if (ret >= 0) inDeq++; else inTryAgain++;
            inWaitNs += durNs;
            pushEvent(OP_DEQ_IN, ret, 0, -1L, durNs);
        }

        synchronized void recordQueueIn(int index, int size, long ptsUs, boolean eos, long durNs) {
            touchThread();
            inQueued++;
            inBytes += Math.max(0, size);
            if (eos) eosQueued++;
            pushEvent(OP_QUE_IN, index, Math.max(0, size), ptsUs, durNs);
        }

        synchronized void recordDequeueOut(int ret, int size, long ptsUs, long durNs) {
            touchThread();
            if (ret >= 0) {
                outDeq++;
                outBytes += Math.max(0, size);
            } else if (ret == INFO_OUTPUT_FORMAT_CHANGED) {
                outFmt++;
            } else {
                outTryAgain++;
            }
            outWaitNs += durNs;
            if (durNs > outMaxWaitNs) outMaxWaitNs = durNs;
            pushEvent(OP_DEQ_OUT, ret, Math.max(0, size), ptsUs, durNs);
        }

        synchronized void recordOutputImage(long durNs, int bytes, long ptsUs) {
            touchThread();
            imgCalls++;
            imgBytes += Math.max(0, bytes);
            imgNs += durNs;
            pushEvent(OP_GET_IMG, 0, Math.max(0, bytes), ptsUs, durNs);
        }

        synchronized void recordReleaseOut(int index, boolean render) {
            touchThread();
            relCount++;
            if (render) relRender++;
            pushEvent(OP_REL_OUT, index, render ? 1 : 0, -1L, 0L);
        }

        synchronized String snapshot(String how) {
            return String.format(java.util.Locale.ROOT,
                "[codec-obs] codec#%d end=%s mime=%s surface=%d clock=System.nanoTime unit=ns "
                + "thr_switches=%d cap=%d retained=%d dropped=%d total_ev=%d "
                + "in_deq=%d in_try_again=%d in_wait_ns=%d in_q=%d in_bytes=%d eos_q=%d "
                + "out_fmt=%d out_deq=%d out_try_again=%d out_wait_ns=%d out_max_wait_ns=%d out_bytes=%d "
                + "img_calls=%d img_bytes=%d img_ns=%d rel=%d rel_render=%d",
                codecId, how, mime.isEmpty() ? "-" : mime, surface ? 1 : 0,
                threadSwitches, MAX_OBS_EVENTS, evBuffered, droppedEvents, totalEvents,
                inDeq, inTryAgain, inWaitNs, inQueued, inBytes, eosQueued,
                outFmt, outDeq, outTryAgain, outWaitNs, outMaxWaitNs, outBytes,
                imgCalls, imgBytes, imgNs, relCount, relRender);
        }

        void emitOnce(String how) {
            final String line;
            synchronized (this) {
                if (emitted) return;
                emitted = true;
                line = snapshot(how);
            }
            System.err.println(line);
        }
    }

    /** AOSP declares finalize() so a dropped codec releases its native decoder
     *  instead of leaking it. release() is idempotent. */
    @Override protected void finalize() throws Throwable {
        try { release(); } finally { super.finalize(); }
    }
}
