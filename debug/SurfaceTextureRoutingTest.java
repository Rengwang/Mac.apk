import android.graphics.Bitmap;
import android.graphics.SurfaceTexture;
import android.view.Surface;
import android.view.SurfaceView;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicInteger;
import sun.misc.Unsafe;

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
        assertAbandonRecyclesPendingFrame();
        assertReplacedProducerCannotQueue();
        assertSurfaceReleasesOnlyItsOwnStream();
        System.out.println("PASS: explicit routing, producer replacement, resize and release");
    }

    private static void assertAbandonRecyclesPendingFrame() {
        SurfaceTexture stream = new SurfaceTexture(false);
        AtomicInteger returned = new AtomicInteger();
        int[] pending = {0xff123456};
        stream.macapkQueueProducerFrame(null, pending, 1, 1, 1, null, frame -> {
            if (frame != pending) throw new AssertionError("wrong buffer returned on abandon");
            returned.incrementAndGet();
        }, false);
        stream.release();
        stream.release();
        if (returned.get() != 1 || stream.macapkLatchBitmap() != null)
            throw new AssertionError("abandon must return the queued buffer exactly once");
    }

    private static void assertReplacedProducerCannotQueue() {
        SurfaceTexture stream = new SurfaceTexture(false);
        Object retired = new Object();
        Object current = new Object();
        if (!stream.macapkConnectProducer(retired) || stream.macapkConnectProducer(current))
            throw new AssertionError("exactly one producer may connect");
        stream.macapkDisconnectProducer(retired);
        if (!stream.macapkConnectProducer(current))
            throw new AssertionError("replacement producer failed to connect");
        stream.macapkDisconnectProducer(retired); // delayed cleanup from the old connection
        stream.setDefaultBufferSize(2, 1);
        AtomicInteger returned = new AtomicInteger();
        int[] fresh = {0xff123456, 0x80654321};
        int[] late = {0xffabcdef};
        stream.macapkQueueProducerFrame(current, fresh, 2, 1, 2, null,
            unused -> returned.incrementAndGet(), false);
        stream.macapkQueueProducerFrame(retired, late, 1, 1, 1, null, frame -> {
            if (frame != late) throw new AssertionError("wrong stale buffer returned");
            returned.incrementAndGet();
        }, false);
        if (returned.get() != 1) throw new AssertionError("stale frame was accepted");
        Bitmap latched = stream.macapkLatchBitmap();
        if (latched == null || latched.getWidth() != 2
                || latched.getPixel(0, 0) != fresh[0] || (latched.getPixel(1, 0) >>> 24) != 128)
            throw new AssertionError("old producer overwrote replacement pixels");
        stream.release();
        if (returned.get() != 2 || stream.macapkConnectProducer(new Object()))
            throw new AssertionError("release retained or returned a buffer twice");
    }

    private static void assertSurfaceReleasesOnlyItsOwnStream() throws Exception {
        SurfaceTexture borrowed = new SurfaceTexture(false);
        new Surface(borrowed).release();
        if (borrowed.isReleased()) throw new AssertionError("borrowed consumer was abandoned");
        borrowed.release();

        // SurfaceView's constructor opens a native view. This probe only uses
        // the owner attachment and stream lifecycle, as in SurfaceZOrderRequestTest.
        Field field = Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        SurfaceView owner = (SurfaceView) ((Unsafe) field.get(null)).allocateInstance(SurfaceView.class);
        for (int i = 0; i < 3; i++) {
            Surface surface = new Surface();
            surface.macapkSetOwnerSurfaceView(owner);
            SurfaceTexture owned = surface.macapkEnsureEglStream();
            surface.release();
            if (!owned.isReleased() || owned.macapkConnectProducer(new Object()))
                throw new AssertionError("Surface left its private EGL stream live");
        }
    }
}
