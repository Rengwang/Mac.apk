import android.graphics.SurfaceTexture;
import android.view.Surface;

public class SurfaceVideoTargetIsolationTest {
    public static void main(String[] args) {
        SurfaceTexture video = new SurfaceTexture(false);
        SurfaceTexture overlay = new SurfaceTexture(false);
        video.macapkRegisterVideoTarget();
        Surface unrelated = new Surface(overlay);
        int[] frame = {0xff123456};
        if (!SurfaceTexture.macapkOfferDecodedFrame(frame, 1, 1, 1))
            throw new AssertionError("video target lost");
        if (video.macapkLatchBitmap() == null || overlay.macapkLatchBitmap() != null)
            throw new AssertionError("unrelated Surface stole video frame");
        unrelated.release();
        video.release();
        overlay.release();
        System.out.println("SurfaceVideoTargetIsolationTest passed");
    }
}
