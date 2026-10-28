import android.content.res.*;
import android.graphics.*;
import android.util.TypedValue;
import com.macapk.loader.ResourceIndex;

/** Real-APK file keys and transparent/partial-alpha tint coverage. */
public class DrawableIdentityAndTintTest {
    static void assertTint(int got, int alpha, int rgb, String what) {
        if(((got>>>24)&0xff)!=alpha) throw new AssertionError(what+": alpha="+Integer.toHexString(got));
        for(int shift=16;shift>=0;shift-=8){
            int want=(rgb>>>shift)&0xff, have=(got>>>shift)&0xff;
            if(Math.abs(want-have)>1) throw new AssertionError(what+": "+Integer.toHexString(got)+" vs "+Integer.toHexString(rgb));
        }
    }
    public static void main(String[] args) throws Exception {
        Configuration cfg = new Configuration();
        cfg.densityDpi=320; cfg.screenWidthDp=414; cfg.screenHeightDp=739;
        ResourceIndex index = new ResourceIndex(cfg, args[0]);
        Resources.macapkSetResourceIndex(index);
        AssetManager assets = new AssetManager(args[0]);
        Resources resources = new Resources(assets, null, cfg);
        java.util.Map<Long,String> keys = new java.util.HashMap<>();
        int count=0;
        for (int id : index.macapkFileIdsOfType("drawable")) {
            TypedValue value = new TypedValue();
            resources.getValue(id, value, true);
            if(value.type!=TypedValue.TYPE_STRING)
                throw new AssertionError("missing file metadata: "+Integer.toHexString(id));
            if(value.assetCookie<=0)
                throw new AssertionError("file has no APK cookie: "+Integer.toHexString(id)
                    +" path="+value.string);
            long key=((long)value.assetCookie<<32)|(value.data&0xffffffffL);
            String path=value.string.toString(), old=keys.putIfAbsent(key,path);
            if(old!=null && !old.equals(path)) throw new AssertionError("cache key collision: "+old+" / "+path);
            count++;
        }
        if(count<2 || keys.size()<2) throw new AssertionError("fixture needs distinct drawables");
        Bitmap bitmap=Bitmap.createBitmap(4,4,Bitmap.Config.ARGB_8888);
        Canvas canvas=new Canvas(bitmap);
        Paint paint=new Paint(); paint.setColor(0);
        paint.setColorFilter(new BlendModeColorFilter(0xff61666d,BlendMode.SRC_IN));
        canvas.drawRect(0,0,4,4,paint);
        if(bitmap.getPixel(1,1)!=0) throw new AssertionError("transparent painter became a tinted square");
        // A 50% source keeps 0x80 alpha and takes the filter's RGB. Compare per
        // channel with a 1-unit tolerance: Java2D rasterises through a
        // PREMULTIPLIED buffer, so 0x61 at alpha 0x80 round-trips as 0x62 --
        // exactly what a device's premultiplied N32 surface does too.
        paint.setColor(0x80ffffff); canvas.drawRect(0,0,4,4,paint);
        assertTint(bitmap.getPixel(1,1),0x80,0x61666d,"SRC_IN lost source coverage");
        bitmap.eraseColor(0); paint.setColor(0x80ffffff);
        paint.setColorFilter(new PorterDuffColorFilter(0xff123456,PorterDuff.Mode.SRC_ATOP));
        canvas.drawRect(0,0,4,4,paint);
        assertTint(bitmap.getPixel(1,1),0x80,0x123456,"SRC_ATOP lost source coverage");
        System.out.println("PASS: "+count+" file rows, "+keys.size()+" distinct drawable keys, tint alpha");
    }
}
