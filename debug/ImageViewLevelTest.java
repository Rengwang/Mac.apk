import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LevelListDrawable;
import android.widget.ImageView;

/** Regression for level-backed ImageView icons (play/pause, toggles, etc.). */
public final class ImageViewLevelTest {
    private static final class ProbeDrawable extends Drawable {
        int lastLevel;

        @Override public void draw(Canvas canvas) {}
        @Override public void setAlpha(int alpha) {}
        @Override public void setColorFilter(ColorFilter filter) {}
        @Override public int getOpacity() { return PixelFormat.TRANSPARENT; }
        @Override public boolean onLevelChange(int level) {
            lastLevel = level;
            return true;
        }
    }

    private static final class CountingImageView extends ImageView {
        int invalidations;

        CountingImageView() { super(null); }

        @Override public void invalidate() { invalidations++; }
    }

    private static LevelListDrawable levels(ProbeDrawable zero, ProbeDrawable one) {
        LevelListDrawable result = new LevelListDrawable();
        result.addLevel(0, 0, zero);
        result.addLevel(1, 1, one);
        return result;
    }

    private static void assertCurrent(LevelListDrawable levels, Drawable want, String what) {
        if (levels.getCurrent() != want) throw new AssertionError(what);
    }

    public static void main(String[] args) {
        ProbeDrawable zero = new ProbeDrawable();
        ProbeDrawable one = new ProbeDrawable();
        LevelListDrawable drawable = levels(zero, one);
        CountingImageView view = new CountingImageView();
        view.setImageDrawable(drawable);
        assertCurrent(drawable, zero, "level 0 should select the first child");

        view.invalidations = 0;
        view.setImageLevel(1);
        assertCurrent(drawable, one, "ImageView.setImageLevel(1) did not reach the drawable");
        if (one.lastLevel != 1) throw new AssertionError("selected child did not receive level 1");
        if (view.invalidations == 0) throw new AssertionError("level change did not invalidate the view");

        view.setImageLevel(0);
        assertCurrent(drawable, zero, "ImageView.setImageLevel(0) did not restore the first child");

        // Widgets refresh themed resources after applying their logical state.
        // The newly installed drawable must start at the same level.
        ProbeDrawable replacementZero = new ProbeDrawable();
        ProbeDrawable replacementOne = new ProbeDrawable();
        LevelListDrawable replacement = levels(replacementZero, replacementOne);
        view.setImageLevel(1);
        view.setImageDrawable(replacement);
        assertCurrent(replacement, replacementOne,
            "replacing the drawable lost the previously requested level");

        // Setting a level before a drawable exists is part of AOSP ImageView's
        // stored state; later resource installation must apply it.
        ProbeDrawable lateZero = new ProbeDrawable();
        ProbeDrawable lateOne = new ProbeDrawable();
        LevelListDrawable late = levels(lateZero, lateOne);
        CountingImageView lateView = new CountingImageView();
        lateView.setImageLevel(1);
        lateView.setImageDrawable(late);
        assertCurrent(late, lateOne, "a level set before setImageDrawable was discarded");

        System.out.println("PASS: ImageView levels select, invalidate, and survive drawable replacement");
    }
}
