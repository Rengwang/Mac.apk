import android.graphics.*;

/** A SurfaceView hole must remain clipped when its bitmap node is replayed. */
public class RenderNodePunchClipTest {
    public static void main(String[] args) {
        RenderNode child = new RenderNode("clipped video");
        child.setPosition(0, 0, 10, 10);
        RecordingCanvas rc = child.beginRecording(10, 10);
        rc.clipRect(0, 0, 10, 4);
        rc.punchHole(0, 0, 10, 10, 0, 0, 1);
        child.endRecording();
        Bitmap out = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out); c.drawColor(0xffffffff); c.drawRenderNode(child);
        if ((out.getPixel(5, 2) >>> 24) != 0 || out.getPixel(5, 8) != 0xffffffff)
            throw new AssertionError("hole escaped clip: top=" + Integer.toHexString(out.getPixel(5,2))
                + " bottom=" + Integer.toHexString(out.getPixel(5,8)));
        System.out.println("PASS: RenderNode replay preserves the punched region's clip");
    }
}
