import android.view.*;
public class InsetsControllabilityTest {
    public static void main(String[] args) {
        InsetsController controller = new InsetsController();
        int[] calls = {0};
        WindowInsetsController.OnControllableInsetsChangedListener listener = (c, types) -> {
            if (++calls[0] > 1) throw new AssertionError("visibility incorrectly notified as ownership");
            c.hide(WindowInsets.Type.systemBars());
        };
        controller.addOnControllableInsetsChangedListener(listener);
        if (calls[0] != 1) throw new AssertionError("listener reentered through hide: " + calls[0]);
        for (int i=0;i<100;i++) { controller.show(WindowInsets.Type.systemBars()); controller.hide(WindowInsets.Type.systemBars()); }
        if (calls[0] != 1 || controller.isRequestedVisible(WindowInsets.Type.statusBars()))
            throw new AssertionError("repeated visibility update created callbacks or lost state");
        controller.removeOnControllableInsetsChangedListener(listener);
        System.out.println("PASS: immersive hide listener is not reentered by visibility changes");
    }
}
