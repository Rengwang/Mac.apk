import android.app.Application;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.AssetFileDescriptor;
import android.content.res.AssetManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.drawable.BitmapDrawable;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.util.DisplayMetrics;
import android.widget.FrameLayout;
import com.macapk.loader.DomXmlResourceParser;
import dalvik.system.BaseDexClassLoader;
import java.io.ByteArrayInputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.xmlpull.v1.XmlPullParser;

/** A shrunk XML attribute retains both its document spelling and resource identity. */
public class UpstreamResourceAliasTest {
    public static void main(String[] args) throws Exception {
        String xml = "<Transition xmlns:app='http://schemas.android.com/apk/res-auto' "
            + "xmlns:macapkAlias='http://macapk/axml-alias' xmlns:macapkAttrId='http://macapk/axml-attrid' "
            + "app:constraintSetStart='@123' macapkAlias:APKTOOL_RENAMED_0x7f040123='@123' "
            + "macapkAttrId:constraintSetStart='2130968867'/>";
        DomXmlResourceParser parser = new DomXmlResourceParser(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        while (parser.next() != XmlPullParser.START_TAG) {}
        if (parser.getAttributeCount() != 1) throw new AssertionError("internal metadata leaked into public attrs: " + parser.getAttributeCount());
        if (!"@123".equals(parser.getAttributeValue(null, "constraintSetStart"))) throw new AssertionError("document name lost");
        if (!"@123".equals(parser.getAttributeValue(null, "APKTOOL_RENAMED_0x7f040123"))) throw new AssertionError("resource alias lost");
        if (parser.getAttributeNameResource(0) != 0x7f040123) throw new AssertionError("resource identity lost: " + parser.getAttributeNameResource(0));
        verifyRawFileDescriptors();
        verifyFrameworkCompatAndStubs();
        System.out.println("PASS: XML aliases, raw descriptors, LoadedApk/AssetManager/StorageManager/stub round-trips");
    }

    private static final int RAW_ID = 0x7f100001;
    private static final int COMPRESSED_ID = 0x7f100002;
    private static final int ALIAS_ID = 0x7f100003;
    private static final int FLOAT_ID = 0x7f100004;

    public static final class FileIndex {
        private final String apk;

        FileIndex(String apk) { this.apk = apk; }

        public Object[] macapkFileValueOf(int id) {
            return switch (id) {
                case RAW_ID, ALIAS_ID -> new Object[] { apk, 1, "res/raw/clip.m4a", RAW_ID };
                case COMPRESSED_ID -> new Object[] { apk, 2, "res/raw/text.txt", COMPRESSED_ID };
                default -> null;
            };
        }

        public String stringForId(int id) {
            return id == FLOAT_ID ? "0.75" : null;
        }
    }

    private static void verifyRawFileDescriptors() throws Exception {
        Path base = Files.createTempFile("macapk-raw-base-", ".apk");
        Path overlay = Files.createTempFile("macapk-raw-overlay-", ".apk");
        byte[] content = { 4, 7, 9 };
        writeFixture(base, content);
        writeFixture(overlay, new byte[] { 1, 2, 3 });
        AssetManager assets = new AssetManager(base.toString());
        try {
            Method addShared = AssetManager.class.getDeclaredMethod("addAssetPathAsSharedLibrary", String.class);
            Method addOverlay = AssetManager.class.getDeclaredMethod("addOverlayPath", String.class);
            int sharedCookie = (Integer) addShared.invoke(assets, overlay.toString());
            int overlayCookie = (Integer) addOverlay.invoke(assets, overlay.toString());
            if (sharedCookie <= 0 || overlayCookie <= 0) {
                throw new AssertionError("addAssetPathAsSharedLibrary/addOverlayPath failed: "
                        + sharedCookie + "/" + overlayCookie);
            }
            Resources.macapkSetResourceIndex(new FileIndex(base.toString()));
            Resources resources = new Resources(assets, new DisplayMetrics(), new Configuration());
            float f = resources.getFloat(FLOAT_ID);
            if (Math.abs(f - 0.75f) > 1e-6f) {
                throw new AssertionError("Resources.getFloat expected 0.75 got " + f);
            }
            try (AssetFileDescriptor afd = resources.openRawResourceFd(ALIAS_ID)) {
                if (afd == null || afd.getStartOffset() <= 0 || afd.getDeclaredLength() != content.length)
                    throw new AssertionError("expected a bounded descriptor into the owning APK");
                try (var stream = afd.createInputStream()) {
                    if (!Arrays.equals(stream.readAllBytes(), content))
                        throw new AssertionError("stream read beyond its slice or from the overlay");
                }
            }
            expectMissing(resources, COMPRESSED_ID);
            expectMissing(resources, 0x7f100005);
        } finally {
            Resources.macapkSetResourceIndex(null);
            assets.close();
            Files.deleteIfExists(overlay);
            Files.deleteIfExists(base);
        }
    }

    private static void verifyFrameworkCompatAndStubs() throws Exception {
        StorageManager sm = new StorageManager();
        Method getVolList = StorageManager.class.getMethod("getVolumeList");
        StorageVolume[] vols = (StorageVolume[]) getVolList.invoke(sm);
        if (vols == null || vols.length == 0 || vols[0] == null) {
            throw new AssertionError("StorageManager.getVolumeList returned empty");
        }

        BaseDexClassLoader cl = new BaseDexClassLoader("", null, null, ClassLoader.getSystemClassLoader());
        Method addNative = BaseDexClassLoader.class.getDeclaredMethod("addNativePath", Collection.class);
        addNative.invoke(cl, Collections.singletonList("/tmp"));

        Application app = new Application();
        Context base = app;
        while (base instanceof ContextWrapper) {
            base = ((ContextWrapper) base).getBaseContext();
        }
        Field pkgField = base.getClass().getDeclaredField("mPackageInfo");
        pkgField.setAccessible(true);
        Object loadedApk = pkgField.get(base);
        if (loadedApk == null) {
            throw new AssertionError("AppBaseContext.mPackageInfo was null");
        }
        Field clField = loadedApk.getClass().getDeclaredField("mClassLoader");
        if (Modifier.isFinal(clField.getModifiers())) {
            throw new AssertionError("LoadedApk.mClassLoader must be non-final");
        }
        clField.setAccessible(true);
        ClassLoader replacement = new ClassLoader(ClassLoader.getSystemClassLoader()) {};
        clField.set(loadedApk, replacement);
        if (app.getClassLoader() != replacement || base.getClassLoader() != replacement) {
            throw new AssertionError("Application/AppBaseContext did not observe LoadedApk.mClassLoader update");
        }

        BitmapDrawable bd = new BitmapDrawable();
        bd.setFilterBitmap(true);
        bd.setAutoMirrored(true);
        if (!bd.isFilterBitmap() || !bd.isAutoMirrored()) {
            throw new AssertionError("BitmapDrawable filter/autoMirrored round-trip failed");
        }
        FrameLayout vg = new FrameLayout(app);
        vg.setTransitionGroup(true);
        if (!vg.isTransitionGroup()) {
            throw new AssertionError("ViewGroup transitionGroup round-trip failed");
        }
    }

    private static void writeFixture(Path apk, byte[] content) throws Exception {
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(apk))) {
            CRC32 crc = new CRC32();
            crc.update(content);
            ZipEntry stored = new ZipEntry("res/raw/clip.m4a");
            stored.setMethod(ZipEntry.STORED);
            stored.setSize(content.length);
            stored.setCrc(crc.getValue());
            zip.putNextEntry(stored);
            zip.write(content);
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("res/raw/text.txt"));
            zip.write(content);
        }
    }

    private static void expectMissing(Resources resources, int id) throws Exception {
        try (AssetFileDescriptor afd = resources.openRawResourceFd(id)) {
            throw new AssertionError("expected NotFoundException for " + Integer.toHexString(id));
        } catch (Resources.NotFoundException expected) {
            // A compressed resource can be read as a stream, but has no fd slice.
        }
    }
}
