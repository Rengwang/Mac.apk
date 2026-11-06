// Tag interface for objects the native side can drive per-frame.
// android.opengl.GLSurfaceView implements this so MTKView's display
// link can call into Java via JNI without depending on the
// android.opengl package from the C++ side.
package com.macapk.loader;

public interface MetalSurfaceCallbacks {
    /** Fired once when the drawable is first available, and again on resize. */
    void macapkSurfaceChanged(int widthPx, int heightPx);
    /** Fired every vsync from the MTKView display link. */
    void macapkDrawFrame();
    /** Whether the host must keep invoking macapkDrawFrame while the guest has
     *  no pending traversal or Surface frame. GL and native-EGL renderers are
     *  continuous; a pure View/Canvas renderer can opt into request-driven
     *  ticks and rely on ViewRootImpl plus the low-frequency host heartbeat. */
    default boolean macapkNeedsContinuousDisplay() { return true; }
}
