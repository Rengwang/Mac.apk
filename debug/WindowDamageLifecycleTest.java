import android.view.*;
import android.widget.FrameLayout;
import android.graphics.*;
import java.lang.reflect.*;

/** Regressions: ancestor transforms, old transform damage, retained video streams. */
public class WindowDamageLifecycleTest {
    private static void clear(ViewRootImpl vr) {
        vr.consumeLayoutRequested(); vr.macapkConsumeDamage(new int[4]); vr.consumeTraversalScheduled();
    }
    public static void main(String[] args) throws Exception {
        FrameLayout root = new FrameLayout(null), parent = new FrameLayout(null);
        View child = new View(null);
        root.layout(0, 0, 600, 600); parent.layout(0, 0, 300, 300); child.layout(10, 20, 110, 120);
        parent.setParent(root); child.setParent(parent);
        parent.setPivotX(0); parent.setPivotY(0); parent.setScaleX(2); parent.setScaleY(2);
        Field attached = View.class.getDeclaredField("macapkAttached"); attached.setAccessible(true);
        for (View v : new View[] {root, parent, child}) attached.setBoolean(v, true);
        ViewRootImpl vr = ViewRootImpl.getInstance(); clear(vr);
        child.macapkReportDamage(0, 0, 100, 100);
        int[] d = new int[4]; int kind = vr.macapkConsumeDamage(d);
        if (kind != ViewRootImpl.DAMAGE_PARTIAL || d[2] - d[0] < 200 || d[3] - d[1] < 200)
            throw new AssertionError("ancestor transform omitted: " + java.util.Arrays.toString(d));
        clear(vr); child.setTranslationX(150);
        if (vr.macapkConsumeDamage(d) != ViewRootImpl.DAMAGE_FULL)
            throw new AssertionError("moving a view must also erase its OLD bounds");
        TextureView tv = new TextureView(null); tv.layout(0, 0, 100, 100);
        tv.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) {}
            public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) {}
            public boolean onSurfaceTextureDestroyed(SurfaceTexture st) { return false; }
            public void onSurfaceTextureUpdated(SurfaceTexture st) {}
        });
        tv.onAttachedToWindow(); Surface before = tv.getSurface(); SurfaceTexture retained = tv.getSurfaceTexture();
        tv.onDetachedFromWindow(); tv.onAttachedToWindow();
        if (tv.getSurfaceTexture() != retained || retained.isReleased() || before.isValid()
                || !tv.getSurface().isValid() || before == tv.getSurface())
            throw new AssertionError("retained consumer must receive a new, valid producer Surface");
        System.out.println("PASS: complete transformed damage, old-bound refresh, retained texture reattach");
    }
}
