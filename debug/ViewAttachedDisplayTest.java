import android.content.Context;
import android.content.ContextWrapper;
import android.hardware.display.DisplayManager;
import android.view.Display;
import android.view.View;

/** Attached renderers must be able to discover their stable window Display. */
public class ViewAttachedDisplayTest {
    private static final class TestContext extends ContextWrapper {
        final DisplayManager manager = new DisplayManager();
        final Display selected;
        final boolean unsupported;
        int displayReads;

        TestContext(Display selected, boolean unsupported) {
            super(null);
            this.selected = selected;
            this.unsupported = unsupported;
        }

        @Override public Display getDisplay() {
            ++displayReads;
            if (unsupported) throw new UnsupportedOperationException("no Context display");
            return selected;
        }

        @Override public Object getSystemService(String name) {
            return Context.DISPLAY_SERVICE.equals(name) ? manager : null;
        }
    }

    private static final class RendererView extends View {
        Display displaySeenOnAttach;
        int attaches;

        RendererView(Context context) { super(context); }

        @Override public void onAttachedToWindow() {
            displaySeenOnAttach = getDisplay();
            ++attaches;
        }
    }

    private static void check(boolean value, String description) {
        if (!value) throw new AssertionError(description);
    }

    private static void checkLifecycle(Context context, Display expected) {
        RendererView view = new RendererView(context);
        check(view.getDisplay() == null, "detached view exposed a display");
        view.macapkDispatchAttachedToWindow();
        check(view.attaches == 1 && view.displaySeenOnAttach == expected,
              "onAttachedToWindow did not see its window display");
        check(view.getDisplay() == expected && view.getDisplay() == expected,
              "attached display identity changed");
        view.macapkDispatchAttachedToWindow();
        check(view.attaches == 1, "idempotent attach fired twice");
        view.macapkDispatchDetachedFromWindow();
        check(view.getDisplay() == null, "detached view retained its old display");
        view.macapkDispatchAttachedToWindow();
        check(view.attaches == 2 && view.displaySeenOnAttach == expected,
              "reattached renderer lost its stable display");
        view.macapkDispatchDetachedFromWindow();
    }

    public static void main(String[] args) {
        Display selected = new Display();
        TestContext direct = new TestContext(selected, false);
        RendererView detached = new RendererView(direct);
        check(detached.getDisplay() == null && direct.displayReads == 0,
              "detached lookup queried a Context display");
        checkLifecycle(direct, selected);

        TestContext unsupported = new TestContext(null, true);
        Display shared = unsupported.manager.getDisplay(Display.DEFAULT_DISPLAY);
        checkLifecycle(unsupported, shared);
        checkLifecycle(new TestContext(null, false), shared);
        checkLifecycle(new ContextWrapper(unsupported), shared);
        checkLifecycle(null, null);
        System.out.println("PASS: attached display lookup, callback order, stable identity, "
            + "detach/reattach, Context fallback and wrapper");
    }
}
