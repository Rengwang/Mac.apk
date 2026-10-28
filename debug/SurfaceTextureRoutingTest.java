import android.graphics.SurfaceTexture;
import android.view.Surface;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** Creating an unrelated Surface must not change an explicit frame destination. */
public class SurfaceTextureRoutingTest {
    public static void main(String[] args) throws Exception {
        SurfaceTexture codecTarget = new SurfaceTexture(1);
        SurfaceTexture unrelated = new SurfaceTexture(false);
        codecTarget.setDefaultBufferSize(320, 180);
        Surface codecSurface = new Surface(codecTarget);
        long packed = codecSurface.macapkGetNativeWindowOwnSizePacked();
        if ((int)(packed >>> 32) != 320 || (int)packed != 180)
            throw new AssertionError("SurfaceTexture native size not preserved");
        new Surface(unrelated); // Supersedes the legacy global destination.
        Method queue = SurfaceTexture.class.getDeclaredMethod("macapkQueueDecodedFrame",
            int[].class, int.class, int.class, int.class);
        queue.setAccessible(true);
        int[] pixels = {0xff123456, 0xffabcdef};
        queue.invoke(codecTarget, pixels, 2, 1, 2); // Same per-instance entry as JNI.
        Field frame = SurfaceTexture.class.getDeclaredField("mVideoFrameArgb");
        frame.setAccessible(true);
        if (frame.get(codecTarget) != pixels || frame.get(unrelated) != null)
            throw new AssertionError("frame redirected to unrelated stream");
        if (new Surface().macapkEnsureEglStream() != null)
            throw new AssertionError("bare Surface invented an EGL destination");
        System.out.println("PASS: explicit destination survives unrelated Surface construction; bare Surface stays unsupported");
    }
}
