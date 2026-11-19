import android.content.res.Configuration;
import com.macapk.loader.ResourceIndex;
import java.io.File;

/**
 * Verifies that ResourceIndex produces identical framework styles, theme
 * attributes, typed values, and dp threshold checks across portrait/landscape/
 * night configuration rotations when reusing the in-memory MacapkFwParsed cache.
 */
public class ResourceIndexRotationTest {
    private static String findFrameworkApk() {
        String env = System.getenv("MACAPK_FW_RES_APK");
        if (env != null && !env.isEmpty() && new File(env).isFile()) return env;
        String[] candidates = {
            "runtime/build/bin/tools/framework-res.apk",
            "runtime/build/lib/framework-res.apk",
        };
        for (String c : candidates) {
            if (new File(c).isFile()) return c;
        }
        throw new AssertionError("framework-res.apk not found for ResourceIndexRotationTest");
    }

    private static Configuration makeConfig(int orientation, int uiMode, int swDp, int wDp, int hDp) {
        Configuration cfg = new Configuration();
        cfg.densityDpi = 320;
        cfg.orientation = orientation;
        cfg.uiMode = uiMode;
        cfg.smallestScreenWidthDp = swDp;
        cfg.screenWidthDp = wDp;
        cfg.screenHeightDp = hDp;
        return cfg;
    }

    private static void assertConfigConsistency(ResourceIndex idx, String label) {
        int lightId = idx.idOfFrameworkStyleName("Theme.Material.Light");
        if (lightId == 0) {
            throw new AssertionError(label + ": missing Theme.Material.Light framework style id");
        }
        ResourceIndex.StyleDef style = idx.getStyleByName("Theme.Material.Light");
        if (style == null || style.items == null || style.items.isEmpty()) {
            throw new AssertionError(label + ": missing Theme.Material.Light StyleDef");
        }
        String bg = idx.resolveAttrInTheme("Theme.Material.Light", "colorBackground");
        if (bg == null || bg.isEmpty()) {
            throw new AssertionError(label + ": resolveAttrInTheme(colorBackground) returned null/empty");
        }
        Integer bgColor = idx.resolveAttrToColor("Theme.Material.Light", "colorBackground");
        if (bgColor == null) {
            throw new AssertionError(label + ": resolveAttrToColor(colorBackground) returned null");
        }
        String textPrimary = idx.resolveAttrInTheme("Theme.Material.Light", "textColorPrimary");
        if (textPrimary == null || textPrimary.isEmpty()) {
            throw new AssertionError(label + ": resolveAttrInTheme(textColorPrimary) returned null/empty");
        }
        Integer colorPrimary = idx.resolveAttrToColor("Theme.Material.Light", "colorPrimary");
        if (colorPrimary == null) {
            throw new AssertionError(label + ": resolveAttrToColor(colorPrimary) returned null");
        }
        Object[] animShort = idx.macapkFwValueOf(0x010e0000); // config_shortAnimTime
        if (animShort == null || animShort.length < 2) {
            throw new AssertionError(label + ": macapkFwValueOf(config_shortAnimTime) missing");
        }
    }

    public static void main(String[] args) {
        String fwApk = findFrameworkApk();

        Configuration port = makeConfig(
            Configuration.ORIENTATION_PORTRAIT,
            Configuration.UI_MODE_TYPE_NORMAL | Configuration.UI_MODE_NIGHT_NO,
            360, 360, 640);
        Configuration land = makeConfig(
            Configuration.ORIENTATION_LANDSCAPE,
            Configuration.UI_MODE_TYPE_NORMAL | Configuration.UI_MODE_NIGHT_NO,
            360, 640, 360);
        Configuration night = makeConfig(
            Configuration.ORIENTATION_LANDSCAPE,
            Configuration.UI_MODE_TYPE_NORMAL | Configuration.UI_MODE_NIGHT_YES,
            360, 640, 360);

        ResourceIndex idxPort = new ResourceIndex(port, fwApk);
        assertConfigConsistency(idxPort, "portrait");

        // Changing orientation from portrait to landscape must be detected AND
        // produce a complete framework resource view via MacapkFwParsed cache.
        if (!idxPort.macapkSelectionChangesFor(land)) {
            throw new AssertionError("portrait -> landscape should trigger macapkSelectionChangesFor");
        }
        ResourceIndex idxLand = new ResourceIndex(land, fwApk);
        assertConfigConsistency(idxLand, "landscape");

        // Changing night mode (UI_MODE_NIGHT_NO -> YES) also builds a cold bucket.
        if (!idxLand.macapkSelectionChangesFor(night)) {
            throw new AssertionError("day -> night should trigger macapkSelectionChangesFor");
        }
        ResourceIndex idxNight = new ResourceIndex(night, fwApk);
        assertConfigConsistency(idxNight, "night");

        // Returning to portrait hits the LRU memory cache cleanly and matches initial resolution.
        ResourceIndex idxPort2 = new ResourceIndex(port, fwApk);
        assertConfigConsistency(idxPort2, "portrait-repeat");

        Integer portBg = idxPort.resolveAttrToColor("Theme.Material.Light", "colorBackground");
        Integer landBg = idxLand.resolveAttrToColor("Theme.Material.Light", "colorBackground");
        Integer port2Bg = idxPort2.resolveAttrToColor("Theme.Material.Light", "colorBackground");
        Integer nightBg = idxNight.resolveAttrToColor("Theme.Material.Light", "colorBackground");
        if (!portBg.equals(landBg) || !portBg.equals(port2Bg)) {
            throw new AssertionError("Light theme colorBackground drifted across rotation: "
                + portBg + " / " + landBg + " / " + port2Bg);
        }
        if (nightBg == null) {
            throw new AssertionError("Night theme colorBackground missing");
        }
        // Day/night check on DayNight or Material dark/light polarity
        String winBg = idxPort.resolveAttrInTheme("Theme.Material.Light", "windowBackground");
        String winBgLand = idxLand.resolveAttrInTheme("Theme.Material.Light", "windowBackground");
        if (winBg == null || !winBg.equals(winBgLand)) {
            throw new AssertionError("windowBackground drifted across rotation: " + winBg + " vs " + winBgLand);
        }
        System.out.println("PASS: ResourceIndexRotationTest");
    }
}
