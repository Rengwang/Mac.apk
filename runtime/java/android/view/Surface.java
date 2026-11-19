// android.view.Surface -- historically an empty stub (GLSurfaceView talks to
// the Cocoa MTKView directly through JNI, so most rendering never comes
// through here). lockCanvas/lockHardwareCanvas/unlockCanvasAndPost ARE real,
// though: AOSP's contract is that lockCanvas never returns null (failure is
// OutOfResourcesException/IllegalArgumentException/IllegalStateException
// instead), so a bare `new Surface()` handed out by SurfaceHolder.getSurface()
// or TextureView's (Mac.apk-internal convenience) getSurface() must work on
// its own -- apps that cache that Surface and lock/unlock it directly
// (bypassing the holder every frame) are a common idiom and must not NPE on
// the very next statement. We mirror the same bitmap-backed Canvas +
// NativeCocoa.presentBitmap path that SurfaceView's SurfaceHolderImpl
// already uses for holder.lockCanvas() (SurfaceView.java), so this Surface's
// own lockCanvas() paints and presents on its own instead of returning null.
//
// THE VIDEO-DECODE HAND-OFF POINT: `new Surface(surfaceTexture)` is the one
// and only place a producer-facing Surface for a TextureView's content
// stream comes from on real Android (frameworks/base core/java/android/view/
// TextureView.java has no getSurface() at all -- a real app instead does
// exactly this, most often from inside onSurfaceTextureAvailable, using the
// SurfaceTexture that callback hands it, or the one getSurfaceTexture()
// returns). MediaCodec.configure(format, surface, ...) receiving a Surface
// built this way is indistinguishable, on the wire, from one built from a
// SurfaceView's SurfaceHolder -- so THIS constructor is where
// SurfaceTexture.macapkRegisterVideoTarget() is called, and it is the only
// correct place: it is reached both by a real app's own construction and by
// this framework's internal TextureView.ensureSurfaceTexture(), so neither
// path can miss it. See SurfaceTexture.macapkOfferDecodedFrame's doc for the
// full routing story (cocoa_bridge.mm's CocoaPresentVideoFrame is what
// eventually asks whether a target is registered).
package android.view;

public class Surface implements android.os.Parcelable {
    public static final int ROTATION_0   = 0;
    public static final int ROTATION_90  = 1;
    public static final int ROTATION_180 = 2;
    public static final int ROTATION_270 = 3;

    // setFrameRate(float, int[, int]) compatibility / strategy values.
    // AOSP core/java/android/view/Surface.java FRAME_RATE_COMPATIBILITY_* /
    // CHANGE_FRAME_RATE_* (public surface only; the *_EXACT / *_NO_VOTE /
    // *_MIN compatibility values are @hide internals, not part of the SDK).
    public static final int FRAME_RATE_COMPATIBILITY_DEFAULT      = 0;
    public static final int FRAME_RATE_COMPATIBILITY_FIXED_SOURCE = 1;
    public static final int FRAME_RATE_COMPATIBILITY_GTE          = 2;
    /** AOSP renamed FRAME_RATE_COMPATIBILITY_GTE to
     *  FRAME_RATE_COMPATIBILITY_AT_LEAST for API 36; only the new name is in
     *  core/api/current.txt. Same value, both kept -- an app built against an
     *  older SDK inlined GTE's 2, and one built against 36 names AT_LEAST. */
    public static final int FRAME_RATE_COMPATIBILITY_AT_LEAST = 2;
    public static final int CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS    = 0;
    public static final int CHANGE_FRAME_RATE_ALWAYS              = 1;

    // The producer-side SurfaceTexture this Surface feeds, when constructed from
    // a TextureView's texture (AOSP Surface(SurfaceTexture)). null for the plain
    // SurfaceView-backed case.
    private android.graphics.SurfaceTexture mSurfaceTexture;
    private volatile boolean mReleased;
    private boolean mOwnsEglStream;

    private static final java.util.concurrent.atomic.AtomicLong sNextSurfaceId =
            new java.util.concurrent.atomic.AtomicLong();
    private static final long PROCESS_SURFACE_PREFIX =
            (ProcessHandle.current().pid() & 0x7fffffffL) << 32;
    private final long mSurfaceId =
            PROCESS_SURFACE_PREFIX | (sNextSurfaceId.incrementAndGet() & 0xffffffffL);
    private final java.util.concurrent.atomic.AtomicLong mGeneration =
            new java.util.concurrent.atomic.AtomicLong(1L);
    private volatile long mRemoteTargetGeneration;
    private final java.util.concurrent.atomic.AtomicLong mPresentSeq =
            new java.util.concurrent.atomic.AtomicLong(0L);

    // Zero-overhead producer & cross-process consumer telemetry counters.
    public static final int STAT_DEQUEUED_OUT = 0;
    public static final int STAT_RELEASE_TRUE = 1;
    public static final int STAT_RELEASE_FALSE = 2;
    public static final int STAT_RELEASE_TIMED = 3;
    public static final int STAT_TIMED_SUPERSEDED = 4;
    public static final int STAT_TIMED_OVERFLOW = 5;
    public static final int STAT_IPC_EXPORT_FAIL = 6;
    public static final int STAT_IPC_SENT = 7;
    public static final int PRODUCER_STAT_COUNT = 8;

    private volatile java.util.concurrent.atomic.AtomicLongArray mProducerStats =
            new java.util.concurrent.atomic.AtomicLongArray(PRODUCER_STAT_COUNT);
    private static final java.util.concurrent.atomic.AtomicLongArray sLocalProducerStats =
            new java.util.concurrent.atomic.AtomicLongArray(PRODUCER_STAT_COUNT);
    private static volatile long[] sLocalProducerBaselines = new long[PRODUCER_STAT_COUNT];
    private static final java.util.concurrent.ConcurrentHashMap<String, long[]>
            sRemoteProducerSnapshots = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.concurrent.ConcurrentHashMap<String, long[]>
            sRemoteProducerBaselines = new java.util.concurrent.ConcurrentHashMap<>();

    private static final java.util.concurrent.atomic.AtomicLong sStatIpcRecv =
            new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong sStatIpcSeqOutOfOrder =
            new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong sStatIpcDroppedInvalid =
            new java.util.concurrent.atomic.AtomicLong();

    // The SurfaceView whose SurfaceHolder handed this Surface out, when there
    // is one (SurfaceHolderImpl sets it at construction). On real Android the
    // association is structural -- the Surface IS a handle onto the
    // SurfaceView's SurfaceControl buffer queue, and SurfaceFlinger scales the
    // queued buffers to the view's on-screen rect (SurfaceView.
    // onSetSurfacePositionAndScale: transaction.setPosition(surface,
    // positionLeft, positionTop) + setMatrix(surface, screenRect.width() /
    // (float) mSurfaceWidth, ...), aosp-mirror/master core/java/android/view/
    // SurfaceView.java, fetched 2026-08-17). Our compositor needs the same
    // association to place decoded video at the view's rect instead of over
    // the whole window -- see SurfaceView.macapkActiveVideoRectPacked.
    private SurfaceView mOwnerSurfaceView;

    // lockCanvas()/unlockCanvasAndPost() state. AOSP keeps one persistent
    // Canvas (mCanvas) reused across locks and a native mLockedObject handle
    // that is nonzero while locked; we have no native buffer, so
    // mCanvasLocked stands in for "mLockedObject != 0" and
    // mDrawBitmap/mDrawCanvas stand in for the persistent software backing.
    private android.graphics.Bitmap mDrawBitmap;
    private android.graphics.Canvas mDrawCanvas;
    private boolean mCanvasLocked;

    // setFrameRate is a pure scheduling HINT on real Android ("does not
    // throttle the frame rate or affect frame production"); macOS's
    // CAMetalLayer has no per-Surface variable-refresh-rate hook to honor it
    // against, so we only validate + store for diagnostics, matching how a
    // fixed-refresh-rate Android device is documented to behave.
    private float mFrameRate;

    public Surface() {}
    public Surface(android.graphics.SurfaceTexture surfaceTexture) {
        mSurfaceTexture = surfaceTexture;
    }
    public boolean isValid() { return !mReleased; }
    public long macapkGetSurfaceId() { return mSurfaceId; }
    public long macapkGetGeneration() { return mGeneration.get(); }

    public void release() {
        android.graphics.SurfaceTexture stream;
        boolean ownsStream;
        synchronized (this) {
            if (mReleased) return;
            mReleased = true;
            mGeneration.incrementAndGet();
            mPresentSeq.set(0L);
            stream = mSurfaceTexture;
            ownsStream = mOwnsEglStream;
        }
        if (stream != null) {
            stream.macapkUnregisterVideoTargetIfSelf();
            // A SurfaceHolder's private EGL stream ends with this Surface.
            // A Surface(SurfaceTexture) only borrows the caller's consumer.
            if (ownsStream) stream.release();
        }
    }

    /** The SurfaceTexture this Surface produces into, or null when it was not
     *  constructed from one. Mac.apk framework-internal (macapk prefix, same
     *  convention as ImageReader.macapkReaderForSurface): it is how the camera
     *  capture session recognises a SurfaceTexture-backed output -- on real
     *  Android that association is the BufferQueue behind the Surface. */
    public android.graphics.SurfaceTexture macapkGetSurfaceTexture() {
        return mSurfaceTexture;
    }

    /** EGL10 software-backed window stream for a SurfaceHolder surface. */
    public synchronized android.graphics.SurfaceTexture macapkEnsureEglStream() {
        checkNotReleased();
        if (mSurfaceTexture == null && mOwnerSurfaceView != null) {
            mSurfaceTexture = new android.graphics.SurfaceTexture(false);
            mOwnsEglStream = true;
            mSurfaceTexture.macapkSetOwnerSurfaceView(mOwnerSurfaceView);
            int w = mOwnerSurfaceView.getWidth(), h = mOwnerSurfaceView.getHeight();
            if (w > 0 && h > 0) mSurfaceTexture.setDefaultBufferSize(w, h);
        }
        return mSurfaceTexture;
    }

    /** Mac.apk framework-internal (same convention as macapkGetSurfaceTexture
     *  above): the SurfaceView this Surface belongs to, or null when it is not
     *  a SurfaceHolder-vended Surface. Set once by SurfaceHolderImpl's
     *  constructor; read by MediaCodec.configure/setOutputSurface to register
     *  the view as the active video sink. */
    public void macapkSetOwnerSurfaceView(SurfaceView owner) {
        mOwnerSurfaceView = owner;
        if (mSurfaceTexture != null && owner != null) {
            mSurfaceTexture.macapkSetOwnerSurfaceView(owner);
            int w = owner.getWidth(), h = owner.getHeight();
            if (w > 0 && h > 0) {
                mSurfaceTexture.setDefaultBufferSize(w, h);
            }
        }
    }
    public SurfaceView macapkGetOwnerSurfaceView() {
        return mOwnerSurfaceView;
    }

    /** Framework-internal stable key used by the native ANativeWindow bridge. */
    public int macapkGetNativeWindowSurfaceKey() {
        return mOwnerSurfaceView != null
                ? mOwnerSurfaceView.macapkNativeWindowSurfaceKey() : 0;
    }

    /** Framework-internal: the owning SurfaceView's OWN size in physical
     *  pixels, packed as (w << 32) | h; 0 when there is no owner. This is the
     *  size a native producer must treat as its window's default size -- on
     *  Android a SurfaceView's BufferQueue defaults to the VIEW's size. */
    public long macapkGetNativeWindowOwnSizePacked() {
        if (macapkIsRemote()) {
            return macapkQueryRemoteOwnSizePacked();
        }
        if (mOwnerSurfaceView == null) {
            android.graphics.SurfaceTexture st = mSurfaceTexture;
            if (st == null) return 0L;
            int w = st.macapkGetDefaultWidth(), h = st.macapkGetDefaultHeight();
            return w > 0 && h > 0 ? ((long) w << 32) | (h & 0xffffffffL) : 0L;
        }
        int key = mOwnerSurfaceView.macapkNativeWindowSurfaceKey();
        if (key <= 0) return 0L;
        return android.view.SurfaceView.macapkSurfaceOwnSizePacked(key);
    }

    /** Mac.apk framework-internal, called by the Bionic shim's
     *  ANativeWindow_fromSurface (bionic_shim.cpp) on the Surface a native
     *  producer is taking: on a device that is android_view_Surface_getNativeWindow
     *  reading THIS Surface's window, i.e. the owning SurfaceView's surface is now
     *  fed by native code. The owner then composites below its window and punches
     *  its hole (SurfaceView "native window owner"). */
    public void macapkNoteNativeWindowConsumer() {
        SurfaceView.macapkNoteNativeWindowOwner(mOwnerSurfaceView);
    }

    private void checkNotReleased() {
        // AOSP Surface.checkNotReleasedLocked(): IllegalStateException,
        // exact message, once the native object is gone. We track the same
        // lifecycle transition via mReleased.
        if (mReleased) {
            throw new IllegalStateException("Surface has already been released.");
        }
    }

    public android.graphics.Canvas lockCanvas(android.graphics.Rect dirty) {
        checkNotReleased();
        // AOSP throws IllegalArgumentException when the surface is already
        // locked (mLockedObject != 0) as well as for an invalid dirty rect;
        // we don't interpret the dirty rect (SurfaceHolderImpl.lockCanvas(Rect)
        // doesn't either -- there is no partial-repaint buffer to target).
        if (mCanvasLocked) {
            throw new IllegalArgumentException("Surface was already locked");
        }
        int w = defaultWidth();
        int h = defaultHeight();
        if (macapkIsRemote()) {
            long packed = macapkQueryRemoteOwnSizePacked();
            int rw = (int) (packed >> 32);
            int rh = (int) (packed & 0xffffffffL);
            if (rw > 0 && rh > 0) { w = rw; h = rh; }
        }
        if (mDrawBitmap == null || mDrawBitmap.getWidth() != w || mDrawBitmap.getHeight() != h) {
            mDrawBitmap = android.graphics.Bitmap.createBitmapWithCanvasBacking(w, h);
            mDrawCanvas = new android.graphics.Canvas(mDrawBitmap);
        }
        mCanvasLocked = true;
        return mDrawCanvas;
    }
    public android.graphics.Canvas lockHardwareCanvas() {
        // AOSP routes this through a separate GL/HWUI-backed context
        // (HwuiContext). Mac.apk has no such layer, so -- matching
        // SurfaceHolderImpl.lockHardwareCanvas() -- share the same
        // software-canvas path as lockCanvas().
        return lockCanvas(null);
    }
    public void unlockCanvasAndPost(android.graphics.Canvas canvas) {
        checkNotReleased();
        // AOSP unlockSwCanvasAndPost(): canvas-identity check happens BEFORE
        // the locked-state check, exact message on each.
        if (canvas != mDrawCanvas) {
            throw new IllegalArgumentException("canvas object must be the same instance "
                + "that was previously returned by lockCanvas");
        }
        if (!mCanvasLocked) {
            throw new IllegalStateException("Surface was not locked");
        }
        mCanvasLocked = false;
        if (mDrawBitmap == null) return;
        // v1.0.1648: the canvas's own live pixels when available (no 24 MB copy
        // at a full-screen window); presentBitmap copies synchronously on this,
        // the drawing thread, which is the contract macapkLiveArgbOrNull states.
        int[] pixels = mDrawBitmap.macapkLiveArgbOrNull();
        if (pixels == null) pixels = mDrawBitmap.macapkArgbBuffer();
        if (pixels == null) return;
        if (macapkIsRemote()) {
            macapkPresentRemotePixels(pixels, mDrawBitmap.getWidth(), mDrawBitmap.getHeight());
            return;
        }
        try {
            com.macapk.loader.NativeCocoa.presentBitmap(
                pixels, mDrawBitmap.getWidth(), mDrawBitmap.getHeight());
        } catch (UnsatisfiedLinkError e) {
            // Native method not registered (headless build / older runtime).
            // Painting still happens to memory; just no visible output.
            // Shares the warn-once flag SurfaceView's SurfaceHolderImpl uses
            // for the identical fallback so the log only fires once total.
            if (!com.macapk.loader.SurfaceCanvasState.warnedMissingNative) {
                com.macapk.loader.SurfaceCanvasState.warnedMissingNative = true;
                System.err.println(
                    "[Surface] presentBitmap not registered; Canvas paints invisibly: "
                    + e.getMessage());
            }
        }
    }

    // A bare Surface has no native buffer geometry to query for a size, so
    // fall back to the actual configured display size (the common case is a
    // full-screen presenting Surface) and only then to a fixed default,
    // mirroring the "sensible default" SurfaceHolderImpl.lockCanvas() already
    // falls back to when its frame Rect is empty.
    private static int defaultWidth() {
        try {
            int w = com.macapk.loader.NativeCocoa.phoneWidth();
            if (w > 0) return w;
        } catch (Throwable ignored) {}
        return 800;
    }
    private static int defaultHeight() {
        try {
            int h = com.macapk.loader.NativeCocoa.phoneHeight();
            if (h > 0) return h;
        } catch (Throwable ignored) {}
        return 600;
    }

    // AOSP Surface.setFrameRate(float, int): delegates to the 3-arg overload
    // with the seamless-only strategy.
    public void setFrameRate(float frameRate, int compatibility) {
        setFrameRate(frameRate, compatibility, CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS);
    }
    // AOSP Surface.setFrameRate(float, int, int): native returns -EINVAL for
    // bad arguments, which the Java layer turns into IllegalArgumentException;
    // we validate the same argument shapes directly since there is no native
    // call to make. Never throws for a merely-unsupported device -- a fixed-
    // refresh-rate device is documented to accept the call and do nothing.
    public void setFrameRate(float frameRate, int compatibility, int changeFrameRateStrategy) {
        checkNotReleased();
        if (Float.isNaN(frameRate) || frameRate < 0f) {
            throw new IllegalArgumentException("frameRate must be >= 0, got " + frameRate);
        }
        if (compatibility != FRAME_RATE_COMPATIBILITY_DEFAULT
                && compatibility != FRAME_RATE_COMPATIBILITY_FIXED_SOURCE
                && compatibility != FRAME_RATE_COMPATIBILITY_GTE) {
            throw new IllegalArgumentException("invalid compatibility value: " + compatibility);
        }
        if (changeFrameRateStrategy != CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS
                && changeFrameRateStrategy != CHANGE_FRAME_RATE_ALWAYS) {
            throw new IllegalArgumentException(
                "invalid changeFrameRateStrategy value: " + changeFrameRateStrategy);
        }
        mFrameRate = frameRate;
    }

    // AOSP Surface.OutOfResourcesException -- thrown when the system cannot
    // allocate a Surface buffer. We never run out, but the type must exist
    // because callers (custom views, some camera libs) catch it by name.
    public static class OutOfResourcesException extends RuntimeException {
        public OutOfResourcesException() { super(); }
        public OutOfResourcesException(String name) { super(name); }
    }

    // ======================================================================
    //  AOSP API-36 surface completion (Mac.apk API-gap lane, android.view).
    // ======================================================================

    /** AOSP Surface.unlockCanvas(Canvas), deprecated since API 1 and documented
     *  as "this method is not supported, use unlockCanvasAndPost". AOSP's body
     *  is `throw new UnsupportedOperationException()` -- reproduced, because an
     *  app that calls it must see the throw rather than a silent no-post. */
    @Deprecated
    public void unlockCanvas(android.graphics.Canvas canvas) {
        throw new UnsupportedOperationException();
    }

    /** AOSP Surface.clearFrameRate() (API 34): withdraw this surface's
     *  frame-rate vote. The vote is a stored hint here (see setFrameRate), so
     *  clearing it resets the same field. */
    public void clearFrameRate() {
        checkNotReleased();
        mFrameRate = 0f;
    }

    /** AOSP Surface.setFrameRate(FrameRateParams) (API 36): the RANGE form of
     *  the hint. Same validation and same storage as the scalar overloads. */
    public void setFrameRate(FrameRateParams params) {
        checkNotReleased();
        if (params == null) {
            throw new IllegalArgumentException("params must not be null");
        }
        mFrameRate = params.getDesiredMaxRate();
    }

    /** AOSP Surface.FrameRateParams (API 36): a desired frame-rate RANGE plus
     *  an optional fixed source rate, replacing the single-value hint. IGNORE is
     *  AOSP's "no vote" instance. */
    public static class FrameRateParams {
        /** AOSP Surface.FrameRateParams.IGNORE -- an empty vote. */
        public static final FrameRateParams IGNORE =
                new FrameRateParams(0f, 0f, 0f, CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS);

        private final float mDesiredMinRate;
        private final float mDesiredMaxRate;
        private final float mFixedSourceRate;
        private final int mChangeFrameRateStrategy;

        private FrameRateParams(float desiredMinRate, float desiredMaxRate,
                float fixedSourceRate, int changeFrameRateStrategy) {
            mDesiredMinRate = desiredMinRate;
            mDesiredMaxRate = desiredMaxRate;
            mFixedSourceRate = fixedSourceRate;
            mChangeFrameRateStrategy = changeFrameRateStrategy;
        }

        public float getDesiredMinRate() { return mDesiredMinRate; }
        public float getDesiredMaxRate() { return mDesiredMaxRate; }
        public float getFixedSourceRate() { return mFixedSourceRate; }
        public int getChangeFrameRateStrategy() { return mChangeFrameRateStrategy; }

        public static final class Builder {
            private float mDesiredMinRate;
            private float mDesiredMaxRate;
            private float mFixedSourceRate;
            private int mChangeFrameRateStrategy = CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS;

            public Builder() {}
            public Builder setDesiredRateRange(float desiredMinRate, float desiredMaxRate) {
                if (Float.isNaN(desiredMinRate) || desiredMinRate < 0f
                        || Float.isNaN(desiredMaxRate) || desiredMaxRate < 0f) {
                    throw new IllegalArgumentException("rates must be >= 0");
                }
                mDesiredMinRate = desiredMinRate;
                mDesiredMaxRate = desiredMaxRate;
                return this;
            }
            public Builder setFixedSourceRate(float fixedSourceRate) {
                if (Float.isNaN(fixedSourceRate) || fixedSourceRate < 0f) {
                    throw new IllegalArgumentException("fixedSourceRate must be >= 0");
                }
                mFixedSourceRate = fixedSourceRate;
                return this;
            }
            public Builder setChangeFrameRateStrategy(int changeFrameRateStrategy) {
                if (changeFrameRateStrategy != CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS
                        && changeFrameRateStrategy != CHANGE_FRAME_RATE_ALWAYS) {
                    throw new IllegalArgumentException(
                        "invalid changeFrameRateStrategy value: " + changeFrameRateStrategy);
                }
                mChangeFrameRateStrategy = changeFrameRateStrategy;
                return this;
            }
            public FrameRateParams build() {
                return new FrameRateParams(mDesiredMinRate, mDesiredMaxRate,
                        mFixedSourceRate, mChangeFrameRateStrategy);
            }
        }
    }

    // ---- Parcelable -------------------------------------------------------
    // AOSP's Surface marshals an IGraphicBufferProducer Binder token across
    // processes so the remote producer writes back to the owning Surface's
    // buffer queue. Keeping a local Binder in the owning process and handing
    // out a BinderProxy across MacApkBinderTransport reproduces this
    // contract for both in-process Parcel round trips and cross-process
    // media/rendering workers.
    private volatile android.os.IBinder mSink;
    private static final boolean sRemoteSurfaceOneway =
            !"0".equals(System.getenv("MACAPK_REMOTE_SURFACE_ONEWAY"));

    public boolean macapkIsRemote() {
        return mSink != null && !(mSink instanceof SurfaceSinkBinder);
    }

    public void macapkNoteVideoSinkActive(boolean active) {
        if (!macapkIsRemote()) {
            applyLocalVideoSink(this, active);
            return;
        }
        android.os.IBinder sink = mSink;
        if (sink == null) return;
        android.os.Parcel data = android.os.Parcel.obtain();
        android.os.Parcel reply = android.os.Parcel.obtain();
        try {
            data.writeInterfaceToken(SurfaceSinkBinder.DESCRIPTOR);
            data.writeInt(active ? 1 : 0);
            sink.transact(SurfaceSinkBinder.TX_SET_VIDEO_SINK, data, reply, 0);
            reply.readException();
        } catch (android.os.RemoteException ignored) {
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    public static void macapkRecordProducerStat(Surface s, int slot) {
        if (slot < 0 || slot >= PRODUCER_STAT_COUNT) return;
        if (s != null && s.macapkIsRemote()) {
            s.mProducerStats.incrementAndGet(slot);
        } else {
            sLocalProducerStats.incrementAndGet(slot);
        }
    }

    /** Aggregate producer counters [dequeuedOut, releaseTrue, releaseFalse,
     *  releaseTimed, timedSuperseded, timedOverflow, ipcExportFail, ipcSent]
     *  across local codecs and piggybacked cross-process streams since last reset. */
    public static long[] macapkSnapshotProducerStats() {
        long[] out = new long[PRODUCER_STAT_COUNT];
        long[] localBase = sLocalProducerBaselines;
        for (int i = 0; i < PRODUCER_STAT_COUNT; i++) {
            long v = sLocalProducerStats.get(i) - (localBase != null && i < localBase.length ? localBase[i] : 0L);
            if (v > 0L) out[i] += v;
        }
        for (java.util.Map.Entry<String, long[]> e : sRemoteProducerSnapshots.entrySet()) {
            long[] snap = e.getValue();
            if (snap == null) continue;
            long[] base = sRemoteProducerBaselines.get(e.getKey());
            int n = Math.min(PRODUCER_STAT_COUNT, snap.length);
            for (int i = 0; i < n; i++) {
                long b = (base != null && i < base.length) ? base[i] : 0L;
                long d = snap[i] - b;
                if (d > 0L) out[i] += d;
            }
        }
        return out;
    }

    /** Returns [ipcRecv, ipcSeqOutOfOrder, ipcDroppedInvalid]. */
    public static long[] macapkSnapshotIpcConsumerStats() {
        return new long[] {
            sStatIpcRecv.get(),
            sStatIpcSeqOutOfOrder.get(),
            sStatIpcDroppedInvalid.get(),
        };
    }

    /** Window-aligned reset for both local/remote producer counters and consumer counters. */
    public static void macapkResetVideoStats() {
        long[] localBase = new long[PRODUCER_STAT_COUNT];
        for (int i = 0; i < PRODUCER_STAT_COUNT; i++) {
            localBase[i] = sLocalProducerStats.get(i);
        }
        sLocalProducerBaselines = localBase;
        for (java.util.Map.Entry<String, long[]> e : sRemoteProducerSnapshots.entrySet()) {
            long[] snap = e.getValue();
            if (snap != null) {
                sRemoteProducerBaselines.put(e.getKey(), snap.clone());
            }
        }
        sStatIpcRecv.set(0L);
        sStatIpcSeqOutOfOrder.set(0L);
        sStatIpcDroppedInvalid.set(0L);
        android.graphics.SurfaceTexture.macapkResetConsumerStats();
    }

    public void macapkPresentRemotePixelBuffer(long pixbufHandle, int width, int height) {
        macapkPresentRemotePixelBuffer(pixbufHandle, width, height, System.nanoTime());
    }

    public void macapkPresentRemotePixelBuffer(long pixbufHandle, int width, int height,
                                               long presentationTimeNs) {
        if (!macapkIsRemote() || pixbufHandle == 0 || mReleased) return;
        android.os.IBinder sink = mSink;
        if (sink == null) return;
        int port = com.macapk.loader.NativeMedia.nativeVidExportIOSurfacePort(pixbufHandle);
        if (port == 0) {
            mProducerStats.incrementAndGet(STAT_IPC_EXPORT_FAIL);
            return;
        }
        java.util.concurrent.atomic.AtomicLongArray statsArr = mProducerStats;
        long sentCount = statsArr.get(STAT_IPC_SENT) + 1L;
        long seq = mPresentSeq.incrementAndGet();
        long[] prodSnap = new long[PRODUCER_STAT_COUNT];
        for (int i = 0; i < PRODUCER_STAT_COUNT; i++) {
            prodSnap[i] = (i == STAT_IPC_SENT) ? sentCount : statsArr.get(i);
        }
        android.os.Parcel data = android.os.Parcel.obtain();
        android.os.Parcel reply = sRemoteSurfaceOneway ? null : android.os.Parcel.obtain();
        try {
            data.writeInterfaceToken(SurfaceSinkBinder.DESCRIPTOR);
            data.writeInt(width);
            data.writeInt(height);
            data.writeLong(presentationTimeNs);
            data.writeLong(mSurfaceId);
            data.writeLong(mGeneration.get());
            data.writeLong(mRemoteTargetGeneration);
            data.writeLong(seq);
            data.macapkWriteMachPort(port);
            data.writeLongArray(prodSnap);
            boolean transacted;
            if (sRemoteSurfaceOneway) {
                transacted = sink.transact(SurfaceSinkBinder.TX_PRESENT_IOSURFACE, data, null,
                        android.os.IBinder.FLAG_ONEWAY);
            } else {
                transacted = sink.transact(SurfaceSinkBinder.TX_PRESENT_IOSURFACE, data, reply, 0);
                if (transacted) reply.readException();
            }
            if (transacted) statsArr.incrementAndGet(STAT_IPC_SENT);
        } catch (android.os.RemoteException ignored) {
        } finally {
            if (reply != null) reply.recycle();
            data.recycle();
        }
    }

    private long macapkQueryRemoteOwnSizePacked() {
        android.os.IBinder sink = mSink;
        if (sink == null) return 0L;
        android.os.Parcel data = android.os.Parcel.obtain();
        android.os.Parcel reply = android.os.Parcel.obtain();
        try {
            data.writeInterfaceToken(SurfaceSinkBinder.DESCRIPTOR);
            if (!sink.transact(SurfaceSinkBinder.TX_QUERY_OWN_SIZE, data, reply, 0)) return 0L;
            reply.readException();
            return reply.readLong();
        } catch (android.os.RemoteException e) {
            return 0L;
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    private void macapkPresentRemotePixels(int[] pixels, int width, int height) {
        android.os.IBinder sink = mSink;
        if (sink == null) return;
        android.os.Parcel data = android.os.Parcel.obtain();
        android.os.Parcel reply = android.os.Parcel.obtain();
        try {
            data.writeInterfaceToken(SurfaceSinkBinder.DESCRIPTOR);
            data.writeInt(width);
            data.writeInt(height);
            data.writeIntArray(pixels);
            sink.transact(SurfaceSinkBinder.TX_PRESENT_BITMAP, data, reply, 0);
            reply.readException();
        } catch (android.os.RemoteException ignored) {
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    private static void applyLocalVideoSink(Surface owner, boolean active) {
        if (owner == null) return;
        if (active) {
            SurfaceView.macapkRegisterVideoSink(owner);
        } else {
            SurfaceView.macapkUnregisterVideoSink(owner);
        }
    }

    private static final class SurfaceSinkBinder extends android.os.Binder {
        static final String DESCRIPTOR = "android.view.MacapkSurfaceSink";
        static final int TX_QUERY_OWN_SIZE = FIRST_CALL_TRANSACTION;
        static final int TX_SET_VIDEO_SINK = FIRST_CALL_TRANSACTION + 1;
        static final int TX_PRESENT_IOSURFACE = FIRST_CALL_TRANSACTION + 2;
        static final int TX_PRESENT_BITMAP = FIRST_CALL_TRANSACTION + 3;

        final Surface mOwner;
        private volatile boolean mSinkActive;
        private final java.util.LinkedHashMap<String, Long> mLastSeqByStream =
                new java.util.LinkedHashMap<String, Long>(16, 0.75f, true) {
                    @Override
                    protected boolean removeEldestEntry(java.util.Map.Entry<String, Long> eldest) {
                        return size() > 64;
                    }
                };

        SurfaceSinkBinder(Surface owner) {
            mOwner = owner;
            attachInterface(null, DESCRIPTOR);
        }

        private void applyVideoSink(boolean active) {
            mSinkActive = active;
            applyLocalVideoSink(mOwner, active);
            if (mOwner.macapkGetOwnerSurfaceView() == null) {
                android.graphics.SurfaceTexture st = mOwner.macapkGetSurfaceTexture();
                if (st != null) {
                    if (active && !st.isReleased()) st.macapkRegisterVideoTarget();
                    else if (!active) st.macapkUnregisterVideoTargetIfSelf();
                }
            }
        }

        @Override
        protected boolean onTransact(int code, android.os.Parcel data,
                android.os.Parcel reply, int flags) throws android.os.RemoteException {
            switch (code) {
                case TX_QUERY_OWN_SIZE:
                    data.enforceInterface(DESCRIPTOR);
                    reply.writeNoException();
                    reply.writeLong(mOwner.macapkGetNativeWindowOwnSizePacked());
                    return true;
                case TX_SET_VIDEO_SINK:
                    data.enforceInterface(DESCRIPTOR);
                    applyVideoSink(data.readInt() != 0);
                    reply.writeNoException();
                    return true;
                case TX_PRESENT_IOSURFACE: {
                    data.enforceInterface(DESCRIPTOR);
                    int width = data.readInt();
                    int height = data.readInt();
                    long presentationTimeNs = data.readLong();
                    long prodSurfaceId = data.readLong();
                    long prodGen = data.readLong();
                    long targetGen = data.readLong();
                    long seq = data.readLong();
                    int port = data.macapkTakeMachPort();
                    boolean consumed = false;
                    try {
                        long[] prodSnap = data.createLongArray();

                        sStatIpcRecv.incrementAndGet();
                        String streamKey = prodSurfaceId + ":" + prodGen;
                        if (prodSnap != null && prodSnap.length >= PRODUCER_STAT_COUNT) {
                            if (sRemoteProducerSnapshots.size() >= 64
                                    && !sRemoteProducerSnapshots.containsKey(streamKey)) {
                                java.util.Iterator<String> it = sRemoteProducerSnapshots.keySet().iterator();
                                if (it.hasNext()) {
                                    String oldKey = it.next();
                                    sRemoteProducerSnapshots.remove(oldKey);
                                    sRemoteProducerBaselines.remove(oldKey);
                                }
                            }
                            sRemoteProducerSnapshots.put(streamKey, prodSnap);
                        }
                        synchronized (mLastSeqByStream) {
                            Long prevSeq = mLastSeqByStream.put(streamKey, seq);
                            long expected = (prevSeq == null) ? 1L : (prevSeq + 1L);
                            if (seq != expected) {
                                sStatIpcSeqOutOfOrder.incrementAndGet();
                            }
                        }

                        boolean ownerValid = mOwner.isValid()
                                && (targetGen <= 0L || mOwner.macapkGetGeneration() == targetGen);
                        if (!ownerValid) {
                            sStatIpcDroppedInvalid.incrementAndGet();
                        } else if (port != 0) {
                            if (!mSinkActive) applyVideoSink(true);
                            android.graphics.SurfaceTexture targetSt = mOwner.macapkGetSurfaceTexture();
                            try {
                                com.macapk.loader.NativeMedia.nativeVidPresentIOSurfacePort(
                                        port, width, height, targetSt, presentationTimeNs);
                                consumed = true;
                            } catch (UnsatisfiedLinkError missingNative) {
                                // Native library unavailable (e.g. bare-JVM test); finally releases port.
                            } catch (Throwable nativeThrew) {
                                // PresentIOSurfacePortImpl deallocates port before dispatching to Java.
                                consumed = true;
                                throw nativeThrew;
                            }
                        }
                    } finally {
                        if (!consumed && port != 0) {
                            releaseMachPortSafe(port);
                        }
                    }
                    if (reply != null && (flags & android.os.IBinder.FLAG_ONEWAY) == 0) {
                        reply.writeNoException();
                    }
                    return true;
                }
                case TX_PRESENT_BITMAP: {
                    data.enforceInterface(DESCRIPTOR);
                    int width = data.readInt();
                    int height = data.readInt();
                    int[] pixels = data.createIntArray();
                    if (pixels != null && mOwner.isValid()) {
                        try {
                            com.macapk.loader.NativeCocoa.presentBitmap(pixels, width, height);
                        } catch (UnsatisfiedLinkError ignored) {}
                    }
                    reply.writeNoException();
                    return true;
                }
            }
            return super.onTransact(code, data, reply, flags);
        }

        private static void releaseMachPortSafe(int port) {
            if (port == 0) return;
            android.os.Parcel drop = android.os.Parcel.obtain();
            try {
                drop.macapkWriteMachPort(port);
            } finally {
                drop.recycle();
            }
        }
    }

    @Override public int describeContents() { return 0; }

    @Override
    public void writeToParcel(android.os.Parcel dest, int flags) {
        if (dest == null) {
            throw new IllegalArgumentException("destination must not be null");
        }
        android.os.IBinder sink = mSink;
        if (sink == null && !mReleased) {
            synchronized (this) {
                if (mSink == null && !mReleased) {
                    mSink = new SurfaceSinkBinder(this);
                }
                sink = mSink;
            }
        }
        dest.writeInt(mReleased ? 1 : 0);
        dest.writeLong(mSurfaceId);
        dest.writeLong(mGeneration.get());
        dest.writeStrongBinder(sink);
    }

    /** AOSP Surface.readFromParcel(Parcel): re-point THIS Surface at the one the
     *  parcel describes. */
    public void readFromParcel(android.os.Parcel source) {
        if (source == null) {
            throw new IllegalArgumentException("source must not be null");
        }
        boolean released = source.readInt() != 0;
        source.readLong(); // sender's mSurfaceId (this Surface keeps its own unique mSurfaceId)
        long srcGen = source.readLong();
        android.os.IBinder b = source.readStrongBinder();
        synchronized (this) {
            mGeneration.incrementAndGet();
            mPresentSeq.set(0L);
            mProducerStats = new java.util.concurrent.atomic.AtomicLongArray(PRODUCER_STAT_COUNT);
            mRemoteTargetGeneration = srcGen;
            if (b instanceof SurfaceSinkBinder) {
                Surface other = ((SurfaceSinkBinder) b).mOwner;
                mSurfaceTexture = other.mSurfaceTexture;
                mOwnerSurfaceView = other.mOwnerSurfaceView;
                mOwnsEglStream = false;
                mReleased = released || other.mReleased;
                mSink = b;
            } else {
                mSurfaceTexture = null;
                mOwnerSurfaceView = null;
                mOwnsEglStream = false;
                mSink = b;
                mReleased = released || (b == null);
            }
        }
    }

    public static final android.os.Parcelable.Creator<Surface> CREATOR =
            new android.os.Parcelable.Creator<Surface>() {
        @Override public Surface createFromParcel(android.os.Parcel source) {
            Surface s = new Surface();
            s.readFromParcel(source);
            return s;
        }
        @Override public Surface[] newArray(int size) { return new Surface[size]; }
    };
}
