// android.graphics.PorterDuffColorFilter -- AOSP-shape stub so
// Material widgets (MaterialShapeDrawable, BadgeDrawable, etc.) can
// construct one for tinting math. The actual color-filter compositing
// happens inside our Drawable.draw paths -- we don't implement it,
// but the construction must succeed for the BottomNav tinting chain
// to complete and not abort instantiation.
//
// v1.0.97: surfaced when getColorStateList started returning a real
// instance and the BottomNav <init> chain reached
// MaterialShapeDrawable.calculateTintColorTintFilter.
package android.graphics;

public class PorterDuffColorFilter extends ColorFilter {
    private static int sMacapkTraceBlackCount;
    private final int color;
    private final PorterDuff.Mode mode;

    public PorterDuffColorFilter(int color, PorterDuff.Mode mode) {
        this.color = color;
        this.mode = mode;
        if ("1".equals(System.getenv("MACAPK_TRACE_TINT"))
                && color == 0xff000000 && mode == PorterDuff.Mode.SRC_ATOP) {
            synchronized (PorterDuffColorFilter.class) {
                if (sMacapkTraceBlackCount++ < 24) {
                    StringBuilder frames = new StringBuilder();
                    int emitted = 0;
                    for (StackTraceElement frame : Thread.currentThread().getStackTrace()) {
                        String name = frame.getClassName();
                        if (name.equals("java.lang.Thread") || name.equals(getClass().getName())) {
                            continue;
                        }
                        if (emitted++ > 0) frames.append(" <- ");
                        frames.append(frame.getClassName()).append('.').append(frame.getMethodName());
                        if (emitted >= 24) break;
                    }
                    com.macapk.loader.NativeLog.i("Mac.apk.TintTrace",
                        "black SRC_ATOP stack=" + frames);
                }
            }
        }
    }
    public PorterDuffColorFilter() {
        this.color = 0;
        this.mode = null;
    }

    public int getColor()          { return color; }
    public PorterDuff.Mode getMode() { return mode; }
    public void setColor(int color) { /* AOSP is immutable but accept */ }
    public void setMode(PorterDuff.Mode mode) {}
}
