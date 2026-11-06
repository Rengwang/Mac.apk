package com.macapk.loader;

/** Internal opt-in EGL/IOSurface bridge used by the EGL10 Metal experiment.
 * Pool handles and frame tokens are opaque, not native pointers. The owner
 * must serialize pool close against EGL use; publication requires completed
 * producer GL work and release requires completed consumer GPU work. */
public final class SharedGlFrameBridge {
    private SharedGlFrameBridge() {}
    public static native long create(long eglDisplay, long eglConfig, int width, int height);
    /** On success, out[0] is a token and out[1] is the EGLSurface handle. */
    public static native boolean begin(long pool, long[] out);
    public static native boolean publish(long pool, long token);
    public static native boolean cancel(long pool, long token);
    /** On success, out[0] is a token and out[1] is a borrowed IOSurfaceRef.
     * It remains valid only until release; never dereference it from Java. */
    public static native boolean take(long pool, long[] out);
    /** Compatibility Canvas consumer, with the same orientation and alpha as
     * the existing GL readback. The token must be acquired via take(). */
    public static native boolean copyArgb(long pool, long token, int[] out);
    /** Producer-only synchronous experiment; call glFinish before copying. */
    public static native boolean copyDrawingArgb(long pool, long token, int[] out);
    /** Publish the current GL frame to one SurfaceView CALayer and acquire the
     * next slot. On success out[0] is the next token and out[1] its EGLSurface.
     * The producer must make that surface current before drawing again. */
    public static native boolean presentLayer(long pool, long token, int surfaceKey, long[] out);
    public static native boolean release(long pool, long token);
    /** Returns false without closing when any slot is outstanding. */
    public static native boolean destroy(long pool);
    /** Close immediately from Java's point of view; the native pool retires
     * after outstanding compositor leases return. */
    public static native boolean destroyDeferred(long pool);
}
