package android.graphics;

import java.awt.PaintContext;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.lang.management.ManagementFactory;
import java.util.Random;

/** Pixel and allocation contract for the software BitmapShader sampler.
 * Run with -XX:-DoEscapeAnalysis so the allocation gate also catches temporary
 * arrays that one particular JIT might otherwise remove.
 */
public final class BitmapShaderSamplingTest {
    private static volatile int sink;

    private static int index(int i, int n, Shader.TileMode mode) {
        if (mode == Shader.TileMode.DECAL && (i < 0 || i >= n)) return -1;
        if (mode == Shader.TileMode.REPEAT) return Math.floorMod(i, n);
        if (mode == Shader.TileMode.MIRROR) {
            int p = Math.floorMod(i, 2 * n);
            return p < n ? p : 2 * n - p - 1;
        }
        return Math.max(0, Math.min(n - 1, i));
    }

    private static int pixel(BufferedImage img, int x, int y,
                             Shader.TileMode tx, Shader.TileMode ty) {
        x = index(x, img.getWidth(), tx); y = index(y, img.getHeight(), ty);
        return x < 0 || y < 0 ? 0 : img.getRGB(x, y);
    }

    // Frozen pre-optimization interpolation, with independent tile addressing.
    private static int reference(BufferedImage img, double u, double v,
                                  Shader.TileMode tx, Shader.TileMode ty, boolean linear) {
        if (!linear) return pixel(img, (int) Math.floor(u), (int) Math.floor(v), tx, ty);
        double fu = u - .5, fv = v - .5;
        int x = (int) Math.floor(fu), y = (int) Math.floor(fv);
        double ax = fu - x, ay = fv - y;
        int[] colors = {pixel(img, x, y, tx, ty), pixel(img, x + 1, y, tx, ty),
                        pixel(img, x, y + 1, tx, ty), pixel(img, x + 1, y + 1, tx, ty)};
        double[] weights = {(1 - ax) * (1 - ay), ax * (1 - ay), (1 - ax) * ay, ax * ay};
        double a = 0, r = 0, g = 0, b = 0;
        for (int k = 0; k < 4; k++) {
            int c = colors[k]; double ca = (c >>> 24) * weights[k];
            a += ca; r += ((c >>> 16) & 255) * ca;
            g += ((c >>> 8) & 255) * ca; b += (c & 255) * ca;
        }
        if (a <= 0) return 0;
        return ((int) Math.round(a) << 24) | ((int) Math.round(r / a) << 16)
            | ((int) Math.round(g / a) << 8) | (int) Math.round(b / a);
    }

    private static void equal(int want, int got, String label) {
        if (want != got) throw new AssertionError(label + ": expected "
            + Integer.toHexString(want) + ", got " + Integer.toHexString(got));
    }

    public static void main(String[] args) throws Exception {
        BufferedImage img = new BufferedImage(13, 11, BufferedImage.TYPE_INT_ARGB);
        Random random = new Random(0xB17A);
        for (int y = 0; y < img.getHeight(); y++) for (int x = 0; x < img.getWidth(); x++) {
            int c = random.nextInt();
            if ((x + y) % 3 == 0) c &= 0xffffff;
            else if ((x + y) % 3 == 1) c |= 0xff000000;
            img.setRGB(x, y, c);
        }
        long checked = 0;
        for (Shader.TileMode tx : Shader.TileMode.values()) {
            for (Shader.TileMode ty : Shader.TileMode.values()) for (boolean linear : new boolean[]{false, true}) {
                MacApkBitmapShaderPaint paint = new MacApkBitmapShaderPaint(img, null, tx, ty, linear);
                for (int i = 0; i < 25000; i++) {
                    double u = i < 1000 ? (i % 50 - 20) * .5 : random.nextDouble() * 100 - 50;
                    double v = i < 1000 ? (i / 50 - 10) * .5 : random.nextDouble() * 100 - 50;
                    equal(reference(img, u, v, tx, ty, linear), paint.sample(u, v), "sample");
                    checked++;
                }
                AffineTransform local = new AffineTransform(2, 0, 0, .5, 4, -2);
                AffineTransform canvas = new AffineTransform(0, 1, -1, 0, 8, 4);
                paint = new MacApkBitmapShaderPaint(img, local, tx, ty, linear);
                AffineTransform inverse = new AffineTransform(canvas);
                inverse.concatenate(local); inverse.invert();
                PaintContext ctx = paint.createContext(img.getColorModel(), new Rectangle(-10, -10, 40, 40),
                    new Rectangle(-10, -10, 40, 40), canvas, new RenderingHints(null));
                var raster = ctx.getRaster(-10, -10, 40, 40);
                for (int y = 0; y < 40; y++) for (int x = 0; x < 40; x++) {
                    double[] pt = {x - 9.5, y - 9.5}; inverse.transform(pt, 0, pt, 0, 1);
                    equal(reference(img, pt[0], pt[1], tx, ty, linear),
                        ctx.getColorModel().getRGB(raster.getDataElements(x, y, null)), "transformed raster");
                    checked++;
                }
                ctx.dispose();
                // An axis-aligned inverse exercises the fixed-source-row path
                // for every tile mode, including negative DECAL coordinates.
                AffineTransform axis = new AffineTransform(2, 0, 0, .5, 4, -2);
                paint = new MacApkBitmapShaderPaint(img, null, tx, ty, linear);
                ctx = paint.createContext(img.getColorModel(), new Rectangle(-20, -20, 80, 80),
                    new Rectangle(-20, -20, 80, 80), axis, new RenderingHints(null));
                raster = ctx.getRaster(-20, -20, 80, 80);
                inverse = axis.createInverse();
                for (int y = 0; y < 80; y++) for (int x = 0; x < 80; x++) {
                    double[] pt = {x - 19.5, y - 19.5}; inverse.transform(pt, 0, pt, 0, 1);
                    equal(reference(img, pt[0], pt[1], tx, ty, linear),
                        ctx.getColorModel().getRGB(raster.getDataElements(x, y, null)), "axis raster");
                    checked++;
                }
                ctx.dispose();
                // Fractional and reflected scales need the exact same
                // incremental x arithmetic as the uncached sampler.
                for (AffineTransform fractional : new AffineTransform[] {
                        new AffineTransform(1.7, 0, 0, .61, 2.13, -3.37),
                        new AffineTransform(-.73, 0, 0, 1.31, -8.2, 4.27)}) {
                    paint = new MacApkBitmapShaderPaint(img, null, tx, ty, linear);
                    ctx = paint.createContext(img.getColorModel(), new Rectangle(-40, -40, 70, 70),
                        new Rectangle(-40, -40, 70, 70), fractional, new RenderingHints(null));
                    inverse = fractional.createInverse();
                    raster = ctx.getRaster(-37, -27, 65, 57);
                    for (int y = 0; y < 57; y++) {
                        double[] pt = {-36.5, y - 26.5}; inverse.transform(pt, 0, pt, 0, 1);
                        double u = pt[0], v = pt[1];
                        for (int x = 0; x < 65; x++) {
                            equal(paint.sample(u, v), ctx.getColorModel().getRGB(
                                raster.getDataElements(x, y, null)), "fractional axis raster");
                            u += inverse.getScaleX();
                            v += inverse.getShearY();
                            checked++;
                        }
                    }
                    ctx.dispose();
                }
            }
        }
        MacApkBitmapShaderPaint paint = new MacApkBitmapShaderPaint(img, null,
            Shader.TileMode.CLAMP, Shader.TileMode.CLAMP, true);
        // Source pixels remain live; the optimization must not cache colors.
        img.setRGB(2, 3, 0xff1257ad);
        equal(0xff1257ad, paint.sample(2.5, 3.5), "source mutation");
        var ctx = paint.createContext(img.getColorModel(), new Rectangle(4, 4), new Rectangle(4, 4),
            AffineTransform.getScaleInstance(0, 0), new RenderingHints(null));
        var raster = ctx.getRaster(0, 0, 4, 4);
        equal(0, ctx.getColorModel().getRGB(raster.getDataElements(1, 1, null)), "singular matrix");
        ctx.dispose();
        ctx = paint.createContext(img.getColorModel(), new Rectangle(100, 100), new Rectangle(100, 100),
            new AffineTransform(), new RenderingHints(null));
        int[][] requests = {{0, 0, 64, 64}, {-3, 9, 3, 7}, {15, -12, 80, 2}, {0, 0, 4, 90}};
        for (int[] request : requests) {
            img.setRGB(2, 3, img.getRGB(2, 3) ^ 0xff507cab);
            raster = ctx.getRaster(request[0], request[1], request[2], request[3]);
            for (int y = 0; y < request[3]; y++) for (int x = 0; x < request[2]; x++) {
                equal(reference(img, request[0] + x + .5, request[1] + y + .5,
                        Shader.TileMode.CLAMP, Shader.TileMode.CLAMP, true),
                    ctx.getColorModel().getRGB(raster.getDataElements(x, y, null)), "reused raster");
                checked++;
            }
        }
        ctx.getRaster(0, 0, 32, 32);
        var allocationBean = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        allocationBean.setThreadAllocatedMemoryEnabled(true);
        long thread = Thread.currentThread().threadId();
        long tileBefore = allocationBean.getThreadAllocatedBytes(thread);
        for (int i = 0; i < 10000; i++) ctx.getRaster(i % 13, i % 11, 32, 32);
        long tileBytes = allocationBean.getThreadAllocatedBytes(thread) - tileBefore;
        ctx.dispose();
        if (tileBytes > 1048576) throw new AssertionError("per-tile allocation: " + tileBytes + " bytes");
        System.out.println("raster: 10000 reused tiles allocated " + tileBytes + " bytes");
        for (int i = 0; i < 100000; i++) sink ^= paint.sample(i % 37 * .31, i % 41 * .23);
        var bean = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        bean.setThreadAllocatedMemoryEnabled(true);
        long tid = Thread.currentThread().threadId(), before = bean.getThreadAllocatedBytes(tid);
        long started = System.nanoTime();
        int hash = 0;
        for (int i = 0; i < 500000; i++) hash ^= paint.sample(i % 37 * .31, i % 41 * .23);
        long nanos = System.nanoTime() - started;
        long allocated = bean.getThreadAllocatedBytes(tid) - before;
        sink = hash;
        System.out.println("sampling: 500000 pixels, " + allocated + " allocated bytes, "
            + (nanos / 1000000.0) + " ms");
        if (allocated > 1048576) throw new AssertionError("per-pixel allocation: " + allocated + " bytes");
        System.out.println("PASS: " + checked + " exact pixel comparisons; 500000 samples allocated " + allocated + " bytes");
    }
}
