import android.view.SurfaceView;
import java.lang.reflect.Field;
import sun.misc.Unsafe;

/** Android 14/15 setter order, including transitions from ABOVE to MEDIA.
 * Bare JVM: bypass only the JNI-backed constructor. Does not test attachment,
 * Surface creation, applied transactions or pixels. Run with MACAPK_LOG_LEVEL=6.
 */
public class SurfaceZOrderRequestTest {
    private static SurfaceView view() throws Exception {
        Field field = Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        SurfaceView view = (SurfaceView) ((Unsafe) field.get(null))
            .allocateInstance(SurfaceView.class);
        view.setZOrderOnTop(false); // establish MEDIA; Unsafe skips initializers
        return view;
    }

    private static void expect(SurfaceView view, int expected, String action) {
        int key = view.macapkNativeWindowSurfaceKey();
        int actual = SurfaceView.macapkSurfaceZOrder(key);
        if (actual != expected || view.macapkIsZOrderOnTop() != (expected == 2)
                || SurfaceView.macapkSurfaceZOrderOnTop(key) != (expected == 2)) {
            throw new AssertionError(action + ": order=" + actual + ", expected=" + expected);
        }
    }

    public static void main(String[] args) throws Exception {
        SurfaceView first = view(), second = view();
        int key = first.macapkNativeWindowSurfaceKey();
        if (key == second.macapkNativeWindowSurfaceKey())
            throw new AssertionError("different owners share a key");
        expect(first, 0, "MEDIA");
        first.setZOrderOnTop(true);
        expect(first, 2, "on-top");
        first.setZOrderMediaOverlay(true);
        expect(first, 1, "media-overlay overrides previous on-top request");
        first.setZOrderOnTop(true);
        expect(first, 2, "on-top overrides media-overlay");
        first.setZOrderMediaOverlay(false);
        expect(first, 0, "MEDIA overrides previous on-top request");
        first.setZOrderMediaOverlay(true);
        first.setZOrderOnTop(false);
        expect(first, 0, "on-top false resets media-overlay");
        expect(second, 0, "unrelated owner unchanged");
        if (first.macapkNativeWindowSurfaceKey() != key)
            throw new AssertionError("setter changed owner identity");
        System.out.println("PASS: ordered z-order requests and per-owner native queries");
    }
}
