import android.graphics.SurfaceTexture;
import android.opengl.EGLDisplay;
import android.view.Surface;
import android.view.SurfaceView;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import javax.microedition.khronos.egl.EGL10;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.egl.EGLContext;
import javax.microedition.khronos.egl.EGLSurface;
import javax.microedition.khronos.egl.MacApkEGL10;
import sun.misc.Unsafe;

/**
 * Headless contract tests for keyed SurfaceView window routing in MacApkEGL10:
 * - Released Surface raises AOSP not_valid_surface IllegalArgumentException.
 * - Unsupported window objects raise UnsupportedOperationException.
 * - Headless JVM gracefully falls back without throwing when native keyed bridge is unbound.
 * - Direct RealSurface binds its SurfaceView identity cleanly and first-frame notice
 *   marks macapkSurfaceLayerDrawn for hole punching.
 */
public class Egl10KeyedSurfaceViewTest {
    private static void check(boolean cond, String msg) {
        if (!cond) throw new AssertionError(msg);
    }

    private static Unsafe unsafe() throws Exception {
        Field f = Unsafe.class.getDeclaredField("theUnsafe");
        f.setAccessible(true);
        return (Unsafe) f.get(null);
    }

    public static void main(String[] args) throws Exception {
        EGL10 egl = (EGL10) EGLContext.getEGL();
        check(egl != null, "EGL10 instance missing");

        Class<?> realDisplayCls = Class.forName("javax.microedition.khronos.egl.MacApkEGL10$RealDisplay");
        Constructor<?> realDisplayCtor = realDisplayCls.getDeclaredConstructor(EGLDisplay.class);
        realDisplayCtor.setAccessible(true);
        Constructor<EGLDisplay> glDpyCtor = EGLDisplay.class.getDeclaredConstructor(long.class);
        glDpyCtor.setAccessible(true);
        Object realDpy = realDisplayCtor.newInstance(glDpyCtor.newInstance(1L));

        Class<?> glCfgCls = Class.forName("android.opengl.EGLConfig");
        Constructor<?> glCfgCtor = glCfgCls.getDeclaredConstructor(long.class);
        glCfgCtor.setAccessible(true);
        Class<?> realCfgCls = Class.forName("javax.microedition.khronos.egl.MacApkEGL10$RealConfig");
        Constructor<?> realCfgCtor = realCfgCls.getDeclaredConstructor(realDisplayCls, glCfgCls);
        realCfgCtor.setAccessible(true);
        EGLConfig realCfg = (EGLConfig) realCfgCtor.newInstance(realDpy, glCfgCtor.newInstance(2L));

        // 1. Bare Surface (neither SurfaceView owner nor SurfaceTexture) falls back to legacy
        //    EGL_NO_SURFACE + EGL_BAD_NATIVE_WINDOW.
        Surface bare = new Surface();
        EGLSurface bareOut = egl.eglCreateWindowSurface(
            (javax.microedition.khronos.egl.EGLDisplay) realDpy, realCfg, bare, null);
        check(bareOut == EGL10.EGL_NO_SURFACE, "Bare Surface must return EGL_NO_SURFACE");
        check(egl.eglGetError() == EGL10.EGL_BAD_NATIVE_WINDOW,
            "Bare Surface must report EGL_BAD_NATIVE_WINDOW");

        // 2. Released SurfaceView-owned Surface must raise AOSP IllegalArgumentException.
        SurfaceView sv = (SurfaceView) unsafe().allocateInstance(SurfaceView.class);
        Surface svSurface = new Surface();
        svSurface.macapkSetOwnerSurfaceView(sv);
        svSurface.release();
        boolean threwReleased = false;
        try {
            egl.eglCreateWindowSurface(
                (javax.microedition.khronos.egl.EGLDisplay) realDpy, realCfg, svSurface, null);
        } catch (IllegalArgumentException expected) {
            threwReleased = true;
        }
        check(threwReleased, "Released Surface must throw IllegalArgumentException");

        // 3. Unsupported window type still throws UnsupportedOperationException.
        boolean threwUnsupported = false;
        try {
            egl.eglCreateWindowSurface(
                (javax.microedition.khronos.egl.EGLDisplay) realDpy, realCfg, new Object(), null);
        } catch (UnsupportedOperationException expected) {
            threwUnsupported = true;
        }
        check(threwUnsupported, "Unknown window object must throw UnsupportedOperationException");

        // 4. Keyed bridge probe on bare JVM safely reports false (no crash / UnsatisfiedLinkError).
        Method probe = MacApkEGL10.class.getDeclaredMethod("keyedSurfaceNativeBound");
        probe.setAccessible(true);
        check(!((Boolean) probe.invoke(null)),
            "Headless JVM without native runtime must report keyed bridge unbound");

        // 5. Try-create on unbound headless JVM returns null cleanly, while Surface with an
        //    existing SurfaceTexture skips keyed layer attempt and routes through SurfaceTexture.
        Surface liveSvSurface = new Surface();
        liveSvSurface.macapkSetOwnerSurfaceView(sv);
        Method tryKeyed = MacApkEGL10.class.getDeclaredMethod("tryCreateKeyedSurfaceViewWindow",
            realDisplayCls, realCfgCls, Surface.class, Object.class, int[].class);
        tryKeyed.setAccessible(true);
        check(tryKeyed.invoke(null, realDpy, realCfg, liveSvSurface, liveSvSurface, null) == null,
            "Unbound native bridge must return null so caller falls back cleanly");

        // 6. Direct RealSurface first-frame notification marks macapkSurfaceLayerDrawn sticky.
        Class<?> glSurfCls = Class.forName("android.opengl.EGLSurface");
        Constructor<?> glSurfCtor = glSurfCls.getDeclaredConstructor(long.class);
        glSurfCtor.setAccessible(true);
        Class<?> realSurfCls = Class.forName("javax.microedition.khronos.egl.MacApkEGL10$RealSurface");
        Constructor<?> realSurfCtor = realSurfCls.getDeclaredConstructor(
            realDisplayCls, glSurfCls,
            Class.forName("javax.microedition.khronos.egl.MacApkGlWindow"),
            SurfaceView.class, int.class);
        realSurfCtor.setAccessible(true);
        int svKey = sv.macapkNativeWindowSurfaceKey();
        check(svKey > 0, "SurfaceView key must be positive");
        Object directSurf = realSurfCtor.newInstance(
            realDpy, glSurfCtor.newInstance(0xCAFEBABEL), null, sv, svKey);
        Field drawnField = SurfaceView.class.getDeclaredField("macapkSurfaceLayerDrawn");
        drawnField.setAccessible(true);
        check(!drawnField.getBoolean(sv), "macapkSurfaceLayerDrawn starts false before first frame");
        Method noteFirst = MacApkEGL10.class.getDeclaredMethod("noteDirectSurfaceFirstFrame", realSurfCls);
        noteFirst.setAccessible(true);
        noteFirst.invoke(null, directSurf);
        check(drawnField.getBoolean(sv),
            "First direct swap must mark macapkSurfaceLayerDrawn so SurfaceView punches its hole");

        // 7. RealSurface carrying a non-null SurfaceTexture still reports owner null check cleanly.
        Surface stSurface = new Surface(new SurfaceTexture(false));
        check(stSurface.macapkGetSurfaceTexture() != null,
            "Surface(SurfaceTexture) must expose its SurfaceTexture");
        stSurface.release();

        System.out.println("PASS: Egl10KeyedSurfaceViewTest");
    }
}
