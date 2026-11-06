import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.util.AttributeSet;
import java.lang.reflect.Proxy;

/** AOSP contract for absent and explicit-null color-state-list attributes. */
public final class TypedArrayColorStateListTest {
    private static final int ANDROID_TINT = 0x01010121;

    private static TypedArray array(String value) {
        AttributeSet attrs = (AttributeSet) Proxy.newProxyInstance(
            AttributeSet.class.getClassLoader(), new Class<?>[]{AttributeSet.class},
            (proxy, method, args) -> {
                if (method.getName().equals("getAttributeValue")) return value;
                if (method.getReturnType() == int.class) return 0;
                if (method.getReturnType() == boolean.class) return false;
                if (method.getReturnType() == float.class) return 0f;
                return null;
            });
        return new TypedArray(null, new int[]{ANDROID_TINT}, attrs);
    }

    private static void assertNull(String raw, String label) {
        ColorStateList got = array(raw).getColorStateList(0);
        if (got != null) {
            throw new AssertionError(label + " returned #"
                + Integer.toHexString(got.getDefaultColor()));
        }
    }

    public static void main(String[] args) {
        Resources.macapkSetResourceIndex(null);
        assertNull(null, "absent tint");
        assertNull("@null", "explicit null tint");

        ColorStateList literal = array("#ff123456").getColorStateList(0);
        if (literal == null || literal.getDefaultColor() != 0xff123456) {
            throw new AssertionError("literal tint was not preserved");
        }
        System.out.println("PASS: absent/@null tint is null; literal tint is preserved");
    }
}
