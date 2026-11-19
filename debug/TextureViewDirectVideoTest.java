import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.view.Surface;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;

/**
 * Verifies TextureView direct GPU CAMetalLayer routing invariants:
 * - Axis-aligned attached TextureView opts into direct video layer and punches a Canvas hole.
 * - Transient invisibility hides the direct rect (-1L) without dropping direct mode.
 * - Rotation/skew transform, external OnFrameAvailableListener, or release falls back cleanly.
 */
public class TextureViewDirectVideoTest {
    private static void check(boolean cond, String msg) {
        if (!cond) throw new AssertionError(msg);
    }

    public static void main(String[] args) {
        Context ctx = new ContextWrapper(null);
        TextureView tv = new TextureView(ctx);
        tv.macapkDispatchAttachedToWindow();
        tv.layout(20, 40, 220, 240);

        // Obtaining Surface connects a Surface(surfaceTexture) producer target.
        Surface surface = tv.getSurface();
        SurfaceTexture st = tv.getSurfaceTexture();
        check(surface != null && surface.isValid(), "Surface should be valid");
        check(st != null, "SurfaceTexture should exist after layout/getSurface");

        // MediaCodec.configure / SurfaceSinkBinder registers active video target on bind.
        st.macapkRegisterVideoTarget();

        check(SurfaceTexture.macapkTryDirectVideoLayer(st),
                "Attached axis-aligned TextureView should enter direct video layer mode");
        check(SurfaceTexture.macapkTryDirectVideoLayer(null),
                "Null explicit target should resolve via active target");

        int[] winOrigin = new int[2];
        tv.macapkWindowOrigin(winOrigin);
        long packed = SurfaceView.macapkActiveVideoRectPacked();
        long expected = View.macapkPackVideoRect(winOrigin[0], winOrigin[1], 200, 200);
        check(packed == expected,
                "Expected direct rect " + expected + " (" + winOrigin[0] + "," + winOrigin[1]
                        + ",200,200), got " + packed);

        // Verify Canvas.punchHole clears alpha inside TextureView bounds while preserving surround.
        Bitmap out = Bitmap.createBitmap(260, 280, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        canvas.drawColor(0xFF112233);
        canvas.save();
        canvas.translate(20, 40);
        tv.draw(canvas);
        canvas.restore();
        check((out.getPixel(120, 140) >>> 24) == 0,
                "TextureView direct draw must punch transparent hole at center");
        check(out.getPixel(10, 10) == 0xFF112233,
                "Pixels outside TextureView bounds must stay untouched");

        // Transiently invisible view hides direct layer (-1L) without exiting direct mode.
        tv.setVisibility(View.INVISIBLE);
        check(SurfaceTexture.macapkTryDirectVideoLayer(st),
                "Invisibility should keep direct mode armed for quick resume");
        check(SurfaceView.macapkActiveVideoRectPacked() == -1L,
                "Invisible TextureView must report -1L to hide video layer");
        tv.setVisibility(View.VISIBLE);
        check(SurfaceView.macapkActiveVideoRectPacked() == expected,
                "Restoring VISIBLE must restore packed rect");

        // Skew/rotation transform exits direct mode and falls back safely.
        Matrix rot = new Matrix();
        rot.setRotate(45f);
        tv.setTransform(rot);
        check(!SurfaceTexture.macapkTryDirectVideoLayer(st),
                "Rotated/skewed transform matrix must disable direct video layer");
        check(SurfaceView.macapkActiveVideoRectPacked() == 0L,
                "Disabled direct mode must clear packed video rect");
        tv.setTransform(null);
        check(SurfaceTexture.macapkTryDirectVideoLayer(st),
                "Resetting transform to identity must re-enable direct mode");

        // View rotation must also disable direct mode.
        tv.setRotation(15f);
        check(!SurfaceTexture.macapkTryDirectVideoLayer(st),
                "Non-zero view rotation must disable direct video layer");
        tv.setRotation(0f);
        check(SurfaceTexture.macapkTryDirectVideoLayer(st),
                "Clearing view rotation must re-enable direct mode");

        // External OnFrameAvailableListener must fall back to consumer latch path.
        st.setOnFrameAvailableListener(new SurfaceTexture.OnFrameAvailableListener() {
            @Override public void onFrameAvailable(SurfaceTexture surfaceTexture) {}
        });
        check(!SurfaceTexture.macapkTryDirectVideoLayer(st),
                "External OnFrameAvailableListener must disable direct video layer");

        // Adopting a clean SurfaceTexture restores direct mode; releasing clears it.
        SurfaceTexture st2 = new SurfaceTexture(false);
        tv.setSurfaceTexture(st2);
        st2.macapkRegisterVideoTarget();
        check(SurfaceTexture.macapkTryDirectVideoLayer(st2),
                "Adopting fresh SurfaceTexture must re-arm direct mode");
        st2.release();
        surface.release();
        check(!SurfaceTexture.macapkTryDirectVideoLayer(st2),
                "Released SurfaceTexture must disable direct video layer");
        check(SurfaceView.macapkActiveVideoRectPacked() == 0L,
                "Released SurfaceTexture must report 0L rect");

        // Multi-card RecyclerView case: explicitTarget succeeds when sActiveVideoTarget == null,
        // and sDirectVideoLayerTarget tracks the active card's rect.
        SurfaceTexture.macapkUnregisterVideoTarget(null);
        check(!SurfaceTexture.macapkHasActiveVideoTarget(),
                "Expected no active video target after explicit unregister");
        TextureView tvCard1 = new TextureView(ctx);
        tvCard1.macapkDispatchAttachedToWindow();
        tvCard1.layout(15, 25, 315, 225);
        Surface card1Surface = tvCard1.getSurface();
        SurfaceTexture stCard1 = tvCard1.getSurfaceTexture();
        check(card1Surface != null && stCard1 != null, "Card 1 surface/texture should exist");
        check(SurfaceTexture.macapkTryDirectVideoLayer(stCard1),
                "Explicit target must enter direct video layer mode even when sActiveVideoTarget == null");
        int[] card1Origin = new int[2];
        tvCard1.macapkWindowOrigin(card1Origin);
        long expectedCard1 = View.macapkPackVideoRect(card1Origin[0], card1Origin[1], 300, 200);
        check(SurfaceTexture.macapkActiveVideoRectPacked() == expectedCard1,
                "Expected SurfaceTexture.macapkActiveVideoRectPacked()=" + expectedCard1
                        + " with explicitTarget and null sActiveVideoTarget, got "
                        + SurfaceTexture.macapkActiveVideoRectPacked());
        check(SurfaceView.macapkActiveVideoRectPacked() == expectedCard1,
                "SurfaceView.macapkActiveVideoRectPacked() should propagate card 1 rect");

        TextureView tvCard2 = new TextureView(ctx);
        tvCard2.macapkDispatchAttachedToWindow();
        tvCard2.layout(30, 50, 230, 150);
        Surface card2Surface = tvCard2.getSurface();
        SurfaceTexture stCard2 = tvCard2.getSurfaceTexture();
        check(SurfaceTexture.macapkTryDirectVideoLayer(stCard2),
                "Card 2 explicit target must take over direct video layer tracking");
        int[] card2Origin = new int[2];
        tvCard2.macapkWindowOrigin(card2Origin);
        long expectedCard2 = View.macapkPackVideoRect(card2Origin[0], card2Origin[1], 200, 100);
        check(SurfaceTexture.macapkActiveVideoRectPacked() == expectedCard2,
                "Expected card 2 rect " + expectedCard2 + ", got "
                        + SurfaceTexture.macapkActiveVideoRectPacked());
        stCard2.macapkUnregisterVideoTargetIfSelf();
        check(SurfaceTexture.macapkActiveVideoRectPacked() == 0L,
                "Clearing direct video state on active card must reset rect to 0L");
        stCard1.release();
        stCard2.release();
        card1Surface.release();
        card2Surface.release();

        // TextureView.setSurfaceTexture must initialize mSurfaceWidth/mSurfaceHeight and
        // fire onSurfaceTextureAvailable when view already has non-zero bounds.
        final int[] availCount = {0};
        final int[] availDims = {0, 0};
        final SurfaceTexture[] availSurface = {null};
        TextureView.SurfaceTextureListener availListener = new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(SurfaceTexture s, int width, int height) {
                availCount[0]++;
                availSurface[0] = s;
                availDims[0] = width;
                availDims[1] = height;
            }
            @Override public void onSurfaceTextureSizeChanged(SurfaceTexture s, int width, int height) {}
            @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture s) { return true; }
            @Override public void onSurfaceTextureUpdated(SurfaceTexture s) {}
        };
        TextureView tvAdopt = new TextureView(ctx);
        tvAdopt.setSurfaceTextureListener(availListener);
        tvAdopt.macapkDispatchAttachedToWindow();
        tvAdopt.layout(10, 20, 410, 260);
        check(availCount[0] == 1 && availDims[0] == 400 && availDims[1] == 240,
                "Initial layout should fire onSurfaceTextureAvailable(400x240)");

        availCount[0] = 0;
        availSurface[0] = null;
        availDims[0] = 0;
        availDims[1] = 0;
        SurfaceTexture adopted = new SurfaceTexture(false);
        tvAdopt.setSurfaceTexture(adopted);
        check(tvAdopt.isAvailable(), "TextureView must be available after setSurfaceTexture");
        check(availCount[0] == 1,
                "setSurfaceTexture on laid-out TextureView must fire onSurfaceTextureAvailable once, got "
                        + availCount[0]);
        check(availSurface[0] == adopted,
                "onSurfaceTextureAvailable must receive the newly adopted SurfaceTexture");
        check(availDims[0] == 400 && availDims[1] == 240,
                "onSurfaceTextureAvailable must report 400x240, got "
                        + availDims[0] + "x" + availDims[1]);
        check(adopted.macapkGetDefaultWidth() == 400 && adopted.macapkGetDefaultHeight() == 240,
                "Adopted SurfaceTexture default buffer size must be initialized to 400x240");

        // Re-check setSurfaceTexture when mAvailable was false (after detach + reattach).
        tvAdopt.onDetachedFromWindow();
        check(!tvAdopt.isAvailable(), "Detaching must clear mAvailable");
        tvAdopt.macapkDispatchAttachedToWindow();
        availCount[0] = 0;
        availSurface[0] = null;
        availDims[0] = 0;
        availDims[1] = 0;
        SurfaceTexture adopted2 = new SurfaceTexture(false);
        tvAdopt.setSurfaceTexture(adopted2);
        check(tvAdopt.isAvailable() && availCount[0] == 1 && availSurface[0] == adopted2
                        && availDims[0] == 400 && availDims[1] == 240,
                "setSurfaceTexture after detach/reattach must publish 400x240 availability");
        adopted2.release();

        // Pre-layout setSurfaceTexture must wait until non-zero layout bounds arrive.
        availCount[0] = 0;
        availSurface[0] = null;
        availDims[0] = 0;
        availDims[1] = 0;
        TextureView tvPre = new TextureView(ctx);
        tvPre.setSurfaceTextureListener(availListener);
        SurfaceTexture preSt = new SurfaceTexture(false);
        tvPre.setSurfaceTexture(preSt);
        check(!tvPre.isAvailable() && availCount[0] == 0,
                "Pre-layout setSurfaceTexture with 0x0 size must defer availability");
        tvPre.macapkDispatchAttachedToWindow();
        tvPre.layout(0, 0, 320, 180);
        check(tvPre.isAvailable() && availCount[0] == 1 && availSurface[0] == preSt
                        && availDims[0] == 320 && availDims[1] == 180,
                "Layout after pre-layout setSurfaceTexture must fire onSurfaceTextureAvailable(320x180)");
        preSt.release();

        System.out.println("PASS: TextureViewDirectVideoTest");
    }
}
