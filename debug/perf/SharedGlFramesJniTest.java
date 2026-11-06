import com.macapk.loader.SharedGlFrameBridge;

/** Runs against a test JNI library linked to the actual pool and bridge. */
public class SharedGlFramesJniTest {
    private static native long[] setup();
    private static native boolean paint(long surface, int color);
    private static native boolean paintPattern(long surface);
    private static native boolean pixelIs(long surface, int color);
    private static native void setPresentAccepted(boolean accepted);
    private static native boolean completePresent();
    private static native void teardown();
    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
    public static void main(String[] args) {
        System.load(args[0]);
        long[] config=setup();
        check(config != null && config.length==2,"EGL setup");
        long pool=SharedGlFrameBridge.create(config[0],config[1],64,64);
        check(pool>0,"create pool");
        check(!SharedGlFrameBridge.begin(pool,null),"null output");
        check(!SharedGlFrameBridge.begin(pool,new long[1]),"short output");
        check(!SharedGlFrameBridge.begin(-1,new long[2]),"invalid pool");
        long[] ids=new long[3];
        for(int i=0;i<3;i++) {
            long[] out=new long[2];
            check(SharedGlFrameBridge.begin(pool,out),"begin");
            ids[i]=out[0];check(paint(out[1],i),"render");
            check(SharedGlFrameBridge.publish(pool,ids[i]),"publish");
        }
        check(!SharedGlFrameBridge.begin(pool,new long[2]),"bounded ring");
        check(!SharedGlFrameBridge.destroy(pool),"busy destroy");
        for(int i=0;i<3;i++) {
            long[] out=new long[2];
            check(SharedGlFrameBridge.take(pool,out) && out[0]==ids[i],"FIFO");
            check(pixelIs(out[1],i),"surface pixels");
            check(SharedGlFrameBridge.release(pool,out[0]),"release");
            check(!SharedGlFrameBridge.release(pool,out[0]),"double release");
        }
        long[] pattern=new long[2];
        check(SharedGlFrameBridge.begin(pool,pattern),"pattern begin");
        check(!SharedGlFrameBridge.copyArgb(pool,pattern[0],new int[4096]),"copy before take rejected");
        check(paintPattern(pattern[1]),"pattern render");
        int[] drawingPixels=new int[4096];
        check(SharedGlFrameBridge.copyDrawingArgb(pool,pattern[0],drawingPixels),"drawing copy");
        check(drawingPixels[0]==0xff0000ff && drawingPixels[63*64]==0x80800000,"drawing orientation and alpha");
        check(SharedGlFrameBridge.publish(pool,pattern[0]),"pattern publish");
        long[] acquired=new long[2];
        check(SharedGlFrameBridge.take(pool,acquired) && acquired[0]==pattern[0],"pattern take");
        check(!SharedGlFrameBridge.copyDrawingArgb(pool,acquired[0],drawingPixels),"drawing copy after publish rejected");
        check(!SharedGlFrameBridge.copyArgb(pool,acquired[0],null),"copy null rejected");
        check(!SharedGlFrameBridge.copyArgb(pool,acquired[0],new int[4095]),"copy short rejected");
        int[] pixels=new int[4096];
        check(SharedGlFrameBridge.copyArgb(pool,acquired[0],pixels),"copy pattern");
        check(pixels[0]==0xff0000ff && pixels[32]==0,"top row blue and transparent");
        check(pixels[63*64]==0x80800000 && pixels[63*64+63]==0xff00ff00,"bottom red alpha and green");
        check(SharedGlFrameBridge.release(pool,acquired[0]),"pattern release");
        check(!SharedGlFrameBridge.copyArgb(pool,acquired[0],pixels),"copy after release rejected");
        long pool2=SharedGlFrameBridge.create(config[0],config[1],64,64);
        check(pool2>0 && pool2!=pool,"distinct pools");
        long[] other=new long[2];
        check(SharedGlFrameBridge.begin(pool2,other),"second pool begin");
        check(!SharedGlFrameBridge.cancel(pool,other[0]),"cross-pool token rejected");
        check(SharedGlFrameBridge.cancel(pool2,other[0]),"second pool cancel");
        check(SharedGlFrameBridge.destroy(pool2),"second pool destroy");
        check(!SharedGlFrameBridge.begin(pool2,new long[2]),"closed pool rejected");
        long[] next=new long[2];
        check(SharedGlFrameBridge.begin(pool,next),"reuse");
        check(next[0]!=ids[0] && !SharedGlFrameBridge.cancel(pool,ids[0]),"stale token");
        check(SharedGlFrameBridge.cancel(pool,next[0]),"cancel");
        long deferred=SharedGlFrameBridge.create(config[0],config[1],64,64);
        long[] deferredSlot=new long[2];
        check(deferred>0 && SharedGlFrameBridge.begin(deferred,deferredSlot),"deferred begin");
        check(SharedGlFrameBridge.cancel(deferred,deferredSlot[0]),"deferred cancel");
        check(SharedGlFrameBridge.destroyDeferred(deferred),"deferred close");
        check(!SharedGlFrameBridge.begin(deferred,new long[2]),"deferred pool closed");
        check(!SharedGlFrameBridge.destroyDeferred(deferred),"deferred close idempotent");

        long fallback=SharedGlFrameBridge.create(config[0],config[1],64,64);
        long[] fallbackSlot=new long[2];
        check(fallback>0 && SharedGlFrameBridge.begin(fallback,fallbackSlot),"direct fallback begin");
        setPresentAccepted(false);
        check(!SharedGlFrameBridge.presentLayer(fallback,fallbackSlot[0],7,new long[2]),
            "rejected compositor restores producer");
        setPresentAccepted(true);
        check(SharedGlFrameBridge.cancel(fallback,fallbackSlot[0]),"direct fallback cancel");
        check(SharedGlFrameBridge.destroy(fallback),"direct fallback destroy");

        long direct=SharedGlFrameBridge.create(config[0],config[1],64,64);
        long[] directSlot=new long[2],directNext=new long[2];
        check(direct>0 && SharedGlFrameBridge.begin(direct,directSlot),"direct begin");
        check(SharedGlFrameBridge.presentLayer(direct,directSlot[0],7,directNext),
            "direct compositor accepted");
        check(SharedGlFrameBridge.cancel(direct,directNext[0]),"direct next cancel");
        check(!SharedGlFrameBridge.destroy(direct),"published frame waits for compositor");
        check(SharedGlFrameBridge.destroyDeferred(direct),"direct deferred close");
        check(!SharedGlFrameBridge.begin(direct,new long[2]),"direct deferred handle closed");
        check(completePresent(),"compositor completion releases frame");
        check(!completePresent(),"compositor completion fires once");
        teardown(); // unbind context while surfaces still exist
        check(SharedGlFrameBridge.destroy(pool),"destroy");
        check(!SharedGlFrameBridge.destroy(pool),"double destroy");
        System.out.println("PASS: JNI pool boundaries, EGL pixels, FIFO and lifetime");
    }
}
