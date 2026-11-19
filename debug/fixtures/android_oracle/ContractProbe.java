import android.content.ComponentName;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Bundle;
import android.os.Parcel;
import android.os.PatternMatcher;

public final class ContractProbe {
    public static void main(String[] args) throws Exception { run(); }

    public static void run() throws Exception {
        Bundle src = new Bundle();
        src.putString("text", "ok");
        src.putParcelable("component", new ComponentName("com.example", "Entry"));
        Parcel parcel = Parcel.obtain();
        parcel.writeBundle(src);
        parcel.setDataPosition(0);
        Bundle decoded = parcel.readBundle();
        ComponentName got = decoded.getParcelable("component", ComponentName.class);
        boolean wrongType = decoded.getParcelable("text", ComponentName.class) == null;
        System.out.println("ORACLE bundle=" + decoded.getString("text") + "," + got.getClassName() + "," + wrongType);
        parcel.recycle();

        Intent filled = Intent.parseUri("android-app://com.example/https/example.org/path",
            Intent.URI_ANDROID_APP_SCHEME);
        System.out.println("ORACLE intent=" + filled.getAction() + "," + filled.getPackage() + "," + filled.getData());

        IntentFilter filter = new IntentFilter("view", "text/*");
        System.out.println("ORACLE filter=" + filter.match("view", "text/plain", null, null, null, "oracle")
            + "," + filter.match("view", "text/plain", "http", Uri.parse("http://example.org/"), null, "oracle"));

        PatternMatcher matcher = new PatternMatcher("[a-z]{2,3}[0-9]+", PatternMatcher.PATTERN_ADVANCED_GLOB);
        System.out.println("ORACLE pattern=" + matcher.match("ab12") + "," + matcher.match("abcd1") + ","
            + matcher.match("ab") + "," + matcher.match("xy123"));
    }
}
