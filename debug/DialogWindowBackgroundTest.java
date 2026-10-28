import android.content.*;
import android.content.res.TypedArray;
import android.graphics.drawable.*;
import java.lang.reflect.*;

/** A transparent full-window dialog must not acquire the fallback opaque panel. */
public class DialogWindowBackgroundTest {
    public static void main(String[] args) throws Exception {
        Method m = Class.forName("android.app.Dialog$DialogWindow")
            .getDeclaredMethod("macapkDialogBackground", Context.class);
        m.setAccessible(true);
        Context ctx = new ContextWrapper(null) {
            @Override public TypedArray obtainStyledAttributes(int[] attrs) {
                if (attrs.length != 1 || attrs[0] != android.R.attr.windowBackground)
                    throw new AssertionError("wrong window style lookup");
                return new TypedArray(null, attrs) {
                    @Override public boolean hasValueOrEmpty(int i) { return true; }
                    @Override public Drawable getDrawable(int i) { return new ColorDrawable(0); }
                };
            }
        };
        Drawable bg = (Drawable)m.invoke(null, ctx);
        if (!(bg instanceof ColorDrawable) || ((ColorDrawable)bg).getColor() != 0)
            throw new AssertionError("transparent dialog theme replaced by opaque fallback: " + bg);
        if (!(m.invoke(null, new Object[]{null}) instanceof InsetDrawable))
            throw new AssertionError("unmodelled ordinary dialogs lost their fallback panel");
        android.app.Dialog dialog = new android.app.Dialog(null);
        android.view.View decor = new android.view.View(null);
        Field showing = android.app.Dialog.class.getDeclaredField("mShowing"); showing.setAccessible(true);
        Field added = android.app.Dialog.class.getDeclaredField("mDecorAdded"); added.setAccessible(true);
        showing.setBoolean(dialog, true); added.set(dialog, decor);
        dialog.hide();
        if (dialog.isShowing() || !showing.getBoolean(dialog) || decor.getVisibility() != android.view.View.GONE)
            throw new AssertionError("hide must report hidden while retaining attached state for dismiss");
        dialog.show();
        if (!dialog.isShowing() || decor.getVisibility() != android.view.View.VISIBLE)
            throw new AssertionError("show after hide must reveal existing decor");
        dialog.hide(); dialog.dismiss();
        if (dialog.isShowing() || added.get(dialog) != null)
            throw new AssertionError("hide followed by dismiss left a zombie decor");
        System.out.println("PASS: transparent background, fallback panel, hide/show/dismiss lifecycle");
    }
}
