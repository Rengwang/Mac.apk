import android.content.res.Resources;
import android.content.res.TypedArray;
import android.util.AttributeSet;
import java.lang.reflect.Proxy;

/** Regression: XML visibility enums index {VISIBLE, INVISIBLE, GONE}. */
public class TypedArrayVisibilityTest {
    public static class Index {
        public String entryNameOf(int id) { return "visibility"; }
        public Integer resolveAttrEnumValue(String name, String value) { return null; }
    }
    private static TypedArray array(String value) {
        AttributeSet attrs = (AttributeSet) Proxy.newProxyInstance(
            AttributeSet.class.getClassLoader(), new Class<?>[]{AttributeSet.class},
            (p, m, a) -> {
                if (m.getName().equals("getAttributeValue")) return value;
                if (m.getReturnType() == int.class) return 0;
                if (m.getReturnType() == boolean.class) return false;
                if (m.getReturnType() == float.class) return 0f;
                return null;
            });
        return new TypedArray(null, new int[]{0x010100dc}, attrs);
    }
    public static void main(String[] args) {
        Resources.macapkSetResourceIndex(new Index());
        String[] values = {"visible", "invisible", "gone", "0", "1", "2", "4", "8", "unknown", null};
        int[] expected = {0, 1, 2, 0, 1, 2, 4, 8, -7, -7};
        for (int i = 0; i < values.length; i++) {
            TypedArray a = array(values[i]);
            if (a.getInt(0, -7) != expected[i] || a.getInteger(0, -7) != expected[i])
                throw new AssertionError(values[i] + ": " + a.getInt(0, -7));
            if (i < 3 && new int[]{0, 4, 8}[a.getInt(0, -7)] != i * 4)
                throw new AssertionError("visibility table lookup");
        }
        System.out.println("PASS: XML enums, numeric preservation, defaults, ConstraintSet visibility table");
    }
}
