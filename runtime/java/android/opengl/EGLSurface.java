// android.opengl.EGLSurface -- typed EGL handle wrapper. See EGLObjectHandle.
// Mirrors AOSP android.opengl.EGLSurface (package-private ctor; the native EGL14/15
// bridges construct these via the (long) ctor through JNI). No em dashes.
package android.opengl;

public class EGLSurface extends EGLObjectHandle {
    // Package-private no-arg ctor matches AOSP; the long-handle ctor is what
    // the native bridges actually call to box a real EGL handle.
    EGLSurface(long handle) {
        super(handle);
    }

    /** Internal wrapper for an already-owned EGL surface handle. */
    public static EGLSurface macapkWrapNativeHandle(long handle) {
        return new EGLSurface(handle);
    }

    @Override
    public boolean equals(Object o) {
        if (o == this) return true;
        if (!(o instanceof EGLSurface)) return false;
        return getNativeHandle() == ((EGLSurface) o).getNativeHandle();
    }
}
