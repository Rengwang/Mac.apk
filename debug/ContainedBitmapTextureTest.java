package android.graphics;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.TexturePaint;
import java.awt.geom.AffineTransform;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.Arrays;
import java.util.Random;

/** Exact gate for the contained, pixel-aligned BitmapShader texture fast path. */
public final class ContainedBitmapTextureTest {
    private static final int W = 47, H = 31;

    public static void main(String[] args) {
        BufferedImage source = source();
        int cases = 0;
        if ("0".equals(System.getenv("MACAPK_BITMAPSHADER_CONTAINED_TEXTURE"))) {
            for (Object[] c : supported(source)) {
                check(Canvas.macapkContainedBitmapTexture(
                    source, (AffineTransform) c[0], ((RoundRectangle2D) c[1]).getBounds2D(),
                    (AffineTransform) c[2]) == null, "disabled gate");
                cases++;
            }
            System.out.println("PASS: disabled gate rejected " + cases + " cases");
            return;
        }

        for (Object[] c : supported(source)) {
            AffineTransform local = (AffineTransform) c[0];
            RoundRectangle2D shape = (RoundRectangle2D) c[1];
            AffineTransform ctm = (AffineTransform) c[2];
            Shader.TileMode tx = (Shader.TileMode) c[3];
            Shader.TileMode ty = (Shader.TileMode) c[4];
            boolean bilinear = (Boolean) c[5];
            boolean aa = (Boolean) c[6];
            TexturePaint fast = Canvas.macapkContainedBitmapTexture(
                source, local, shape.getBounds2D(), ctm);
            check(fast != null, "supported gate rejected");
            BufferedImage custom = render(source, local, shape, ctm, tx, ty, bilinear, aa, false);
            BufferedImage texture = render(source, local, shape, ctm, tx, ty, bilinear, aa, true);
            int[] a = pixels(custom), b = pixels(texture);
            check(Arrays.equals(a, b), "pixel mismatch " + tx + "/" + ty
                + " bilinear=" + bilinear + " aa=" + aa);
            cases++;
        }

        BufferedImage scaledSource = scaledSource();
        Bitmap bitmap = Bitmap.createBitmap(scaledSource.getWidth(), scaledSource.getHeight(),
            Bitmap.Config.ARGB_8888);
        int[] scaledPixels = pixels(scaledSource);
        bitmap.setPixels(scaledPixels, 0, scaledSource.getWidth(), 0, 0,
            scaledSource.getWidth(), scaledSource.getHeight());
        BitmapShader bitmapShader = new BitmapShader(
            bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        AffineTransform scale = new AffineTransform(
            0.6962962567168504, 0, 0, 0.6962962567168504, 0, 0);
        RoundRectangle2D scaledShape = new RoundRectangle2D.Double(0, 0, 334, 188, 14, 14);
        MacApkBitmapShaderCache.resetStats();
        for (boolean linear : new boolean[] {false, true}) {
            for (int y : new int[] {121, 625}) {
                AffineTransform ctm = AffineTransform.getTranslateInstance(24, y);
                TexturePaint fast = Canvas.macapkScaledBitmapTexture(
                    bitmapShader, scaledSource, scale, scaledShape.getBounds2D(), ctm, linear);
                check(fast != null, "scaled gate rejected");
                BufferedImage custom = renderPaint(scaledSource, scale, scaledShape, ctm,
                    bitmapShader, linear, false);
                BufferedImage texture = renderPaint(scaledSource, scale, scaledShape, ctm,
                    bitmapShader, linear, true);
                check(Arrays.equals(pixels(custom), pixels(texture)),
                    "scaled cache mismatch linear=" + linear + " y=" + y);
                cases++;
            }
        }
        long[] cache = MacApkBitmapShaderCache.stats();
        check(cache[0] == 6 && cache[1] == 2,
            "scaled cache expected 6 hits/2 misses, got " + cache[0] + "/" + cache[1]);

        if (Canvas.BITMAPSHADER_CROPPED_CACHE)
            cases += croppedShapes(scaledSource, bitmapShader, scale);

        MacApkBitmapShaderCache.resetStats();
        for (boolean aa : new boolean[] {true}) {
            for (int y : new int[] {121, 625}) {
                AffineTransform ctm = AffineTransform.getTranslateInstance(24, y);
                BufferedImage baseline = renderPaint(scaledSource, scale, scaledShape, ctm,
                    bitmapShader, true, false);
                Canvas.CachedBitmapMapping mapping = Canvas.macapkScaledBitmapMapping(
                    bitmapShader, scaledSource, scale, scaledShape.getBounds2D(), ctm, true);
                check(mapping != null, "shape mapping rejected");
                MacApkBitmapShaderCache.ShapeTexture baked = MacApkBitmapShaderCache.shape(
                    bitmapShader, scaledSource, scale, ctm, scaledShape,
                    mapping.x(), mapping.y(), mapping.width(), mapping.height(),
                    mapping.scaleX(), mapping.scaleY(), true, aa);
                check(baked != null, "shape cache rejected");
                BufferedImage blit = new BufferedImage(400, 850, BufferedImage.TYPE_INT_ARGB);
                Graphics2D g = blit.createGraphics();
                g.drawImage(baked.image(), baked.deviceX(), baked.deviceY(), null);
                g.dispose();
                int[] before = pixels(baseline), after = pixels(blit);
                check(Arrays.equals(before, after),
                    "shape cache pixel mismatch aa=" + aa + " y=" + y
                        + " " + describeDiff(before, after));
                cases++;
            }
        }
        cache = MacApkBitmapShaderCache.stats();
        check(cache[0] == 1 && cache[1] == 1,
            "shape cache expected 1 hit/1 miss, got " + cache[0] + "/" + cache[1]);
        long[] shapeCache = MacApkBitmapShaderCache.shapeStats();
        check(shapeCache[0] == 1 && shapeCache[1] == 1,
            "shape-only cache expected 1 hit/1 miss, got " + shapeCache[0] + "/" + shapeCache[1]);

        // AOSP generation changes are the cache's source-pixel invalidation
        // contract. The old entry may remain until LRU, but the next draw must
        // miss and use the new generation.
        MacApkBitmapShaderCache.resetStats();
        Canvas.CachedBitmapMapping generationMapping = Canvas.macapkScaledBitmapMapping(
            bitmapShader, scaledSource, scale, scaledShape.getBounds2D(),
            AffineTransform.getTranslateInstance(24, 121), true);
        check(generationMapping != null, "generation mapping rejected");
        MacApkBitmapShaderCache.ShapeTexture firstGeneration = MacApkBitmapShaderCache.shape(
            bitmapShader, scaledSource, scale, AffineTransform.getTranslateInstance(24, 121),
            scaledShape, generationMapping.x(), generationMapping.y(),
            generationMapping.width(), generationMapping.height(),
            generationMapping.scaleX(), generationMapping.scaleY(), true, true);
        check(firstGeneration != null, "first generation bake rejected");
        bitmap.setPixel(0, 0, 0x7f123456);
        MacApkBitmapShaderCache.ShapeTexture secondGeneration = MacApkBitmapShaderCache.shape(
            bitmapShader, scaledSource, scale, AffineTransform.getTranslateInstance(24, 121),
            scaledShape, generationMapping.x(), generationMapping.y(),
            generationMapping.width(), generationMapping.height(),
            generationMapping.scaleX(), generationMapping.scaleY(), true, true);
        check(secondGeneration != null, "second generation bake rejected");
        cache = MacApkBitmapShaderCache.stats();
        check(cache[0] == 0 && cache[1] == 2,
            "generation change should miss twice, got " + cache[0] + "/" + cache[1]);

        MacApkBitmapShaderCache.discard(bitmap);
        cache = MacApkBitmapShaderCache.stats();
        check(cache[2] == 0, "recycle discard retained " + cache[2] + " bytes");
        bitmap.recycle();
        check(MacApkBitmapShaderCache.shape(bitmapShader, scaledSource, scale,
            AffineTransform.getTranslateInstance(24, 121), scaledShape,
            generationMapping.x(), generationMapping.y(), generationMapping.width(),
            generationMapping.height(), generationMapping.scaleX(), generationMapping.scaleY(),
            true, true) == null, "recycled bitmap returned to cache");
        MacApkBitmapShaderCache.trimMemory();
        cache = MacApkBitmapShaderCache.stats();
        check(cache[2] == 0, "trim-memory retained " + cache[2] + " bytes");
        cases += 3;

        // The lifecycle checks above deliberately recycle the first source.
        // Use a fresh source for the remaining generic and Canvas-level tests.
        bitmap = Bitmap.createBitmap(scaledSource.getWidth(), scaledSource.getHeight(),
            Bitmap.Config.ARGB_8888);
        scaledPixels = pixels(scaledSource);
        bitmap.setPixels(scaledPixels, 0, scaledSource.getWidth(), 0, 0,
            scaledSource.getWidth(), scaledSource.getHeight());
        bitmapShader = new BitmapShader(
            bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);

        Path2D.Double genericShape = new Path2D.Double();
        genericShape.append(scaledShape, false);
        AffineTransform genericCtm = AffineTransform.getTranslateInstance(24, 121);
        Canvas.CachedBitmapMapping genericMapping = Canvas.macapkScaledBitmapMapping(
            bitmapShader, scaledSource, scale, genericShape.getBounds2D(), genericCtm, true);
        MacApkBitmapShaderCache.ShapeTexture genericBaked = MacApkBitmapShaderCache.shape(
            bitmapShader, scaledSource, scale, genericCtm, genericShape,
            genericMapping.x(), genericMapping.y(), genericMapping.width(), genericMapping.height(),
            genericMapping.scaleX(), genericMapping.scaleY(), true, true);
        BufferedImage genericBlit = new BufferedImage(400, 850, BufferedImage.TYPE_INT_ARGB);
        Graphics2D gg = genericBlit.createGraphics();
        gg.drawImage(genericBaked.image(), genericBaked.deviceX(), genericBaked.deviceY(), null);
        gg.dispose();
        BufferedImage genericBaseline = renderPaint(scaledSource, scale, genericShape,
            genericCtm, bitmapShader, true, false);
        check(Arrays.equals(pixels(genericBaseline), pixels(genericBlit)),
            "generic path bake mismatch "
                + describeDiff(pixels(genericBaseline), pixels(genericBlit)));

        MacApkBitmapShaderCache.resetStats();
        Bitmap targetBitmap = Bitmap.createBitmap(400, 850, Bitmap.Config.ARGB_8888);
        Canvas targetCanvas = new Canvas(targetBitmap);
        Paint bitmapPaint = new Paint();
        bitmapPaint.setAntiAlias(true);
        bitmapPaint.setFilterBitmap(true);
        bitmapPaint.setShader(bitmapShader);
        Path roundedPath = new Path();
        roundedPath.addRoundRect(new RectF(0, 0, 334, 188), 7, 7, Path.Direction.CW);
        Matrix shaderMatrix = new Matrix();
        shaderMatrix.setScale((float) scale.getScaleX(), (float) scale.getScaleY());
        bitmapShader.setLocalMatrix(shaderMatrix);
        AffineTransform actualLocal = bitmapShader.macapkLocalAffine();
        for (int y : new int[] {121, 625}) {
            targetCanvas.save();
            targetCanvas.translate(24, y);
            targetCanvas.drawPath(roundedPath, bitmapPaint);
            targetCanvas.restore();
        }
        BufferedImage directBaseline = renderPaint(scaledSource, actualLocal, scaledShape,
            AffineTransform.getTranslateInstance(24, 625), bitmapShader, true, false);
        int[] actual = pixels(targetBitmap.macapkAwtImage());
        // The target also contains the item at y=121, so compare its bottom region
        // against a baseline that includes the identical first draw.
        BufferedImage stackedBaseline = new BufferedImage(400, 850, BufferedImage.TYPE_INT_ARGB);
        Graphics2D sg = stackedBaseline.createGraphics();
        sg.drawImage(renderPaint(scaledSource, actualLocal, scaledShape,
            AffineTransform.getTranslateInstance(24, 121), bitmapShader, true, false), 0, 0, null);
        sg.drawImage(directBaseline, 0, 0, null);
        sg.dispose();
        long[] directShape = MacApkBitmapShaderCache.shapeStats();
        check(Arrays.equals(pixels(stackedBaseline), actual),
            "Canvas.drawPath fast path mismatch " + describeDiff(pixels(stackedBaseline), actual)
                + " shapeStats=" + Arrays.toString(directShape)
                + " shapeClass=" + roundedPath.macapkDrawShape().getClass().getName()
                + " shapeBounds=" + roundedPath.macapkDrawShape().getBounds2D()
                + " expectedBox=" + alphaBox(pixels(stackedBaseline), 400)
                + " actualBox=" + alphaBox(actual, 400));
        shapeCache = MacApkBitmapShaderCache.shapeStats();
        check(shapeCache[0] == 1 && shapeCache[1] == 1,
            "Canvas.drawPath shape cache expected 1 hit/1 miss, got "
                + shapeCache[0] + "/" + shapeCache[1]);

        for (Object[] c : unsupported(source)) {
            AffineTransform local = (AffineTransform) c[0];
            RoundRectangle2D shape = (RoundRectangle2D) c[1];
            AffineTransform ctm = (AffineTransform) c[2];
            check(Canvas.macapkContainedBitmapTexture(
                source, local, shape.getBounds2D(), ctm) == null, (String) c[3]);
            cases++;
        }
        cacheBound(bitmapShader, scaledSource);
        cases++;
        System.out.println("PASS: " + cases + " exact-gate checks");
    }

    /** Nine near-one-megapixel results exceed the 32 MiB bound. The newest
     *  entries must survive, while total retained pixels remain bounded. */
    private static void cacheBound(BitmapShader shader, BufferedImage source) {
        MacApkBitmapShaderCache.resetStats();
        int[][] sizes = {
            {1000, 1000}, {999, 1001}, {998, 1002},
            {997, 1003}, {996, 1004}, {995, 1005},
            {994, 1006}, {993, 1007}, {992, 1008}
        };
        for (int[] size : sizes) {
            TexturePaint paint = MacApkBitmapShaderCache.texture(
                shader, source, new AffineTransform(),
                new java.awt.geom.Rectangle2D.Double(0, 0, size[0], size[1]),
                new AffineTransform(), 0, 0, size[0], size[1], 1, 1, true);
            check(paint != null, "cache-bound render rejected " + size[0] + "x" + size[1]);
        }
        long bytes = MacApkBitmapShaderCache.stats()[2];
        check(bytes > 24L * 1024 * 1024 && bytes <= 32L * 1024 * 1024,
            "cache byte bound expected (24MiB,32MiB], got " + bytes);
    }

    /** A contained fill need not reach the source's right/bottom edges.
     *  Cover both the texture and baked-shape paths against the independent
     *  sampler, with transparent source pixels, integer scrolling and clips. */
    private static int croppedShapes(BufferedImage source, BitmapShader shader,
            AffineTransform local) {
        int cases = 0;
        for (int[] size : new int[][] {{334, 185}, {320, 188}, {280, 150}}) {
            for (boolean linear : new boolean[] {false, true}) {
                for (boolean rounded : new boolean[] {false, true}) {
                    Shape shape = rounded
                        ? new RoundRectangle2D.Double(0, 0, size[0], size[1], 14, 14)
                        : new java.awt.geom.Rectangle2D.Double(0, 0, size[0], size[1]);
                    MacApkBitmapShaderCache.resetStats();
                    for (int y : new int[] {121, 625}) {
                        AffineTransform ctm = AffineTransform.getTranslateInstance(24, y);
                        Canvas.CachedBitmapMapping m = Canvas.macapkScaledBitmapMapping(
                            shader, source, local, shape.getBounds2D(), ctm, linear);
                        check(m != null, "contained crop rejected " + Arrays.toString(size));
                        BufferedImage expected = renderPaint(source, local, shape, ctm,
                            shader, linear, false);
                        BufferedImage texture = renderPaint(source, local, shape, ctm,
                            shader, linear, true);
                        check(Arrays.equals(pixels(expected), pixels(texture)),
                            "cropped texture mismatch " + describeDiff(pixels(expected), pixels(texture)));
                        MacApkBitmapShaderCache.ShapeTexture baked = MacApkBitmapShaderCache.shape(
                            shader, source, local, ctm, shape, m.x(), m.y(), m.width(), m.height(),
                            m.scaleX(), m.scaleY(), linear, true);
                        check(baked != null, "cropped shape rejected");
                        BufferedImage actual = new BufferedImage(400, 850, BufferedImage.TYPE_INT_ARGB);
                        Graphics2D g = actual.createGraphics();
                        g.drawImage(baked.image(), baked.deviceX(), baked.deviceY(), null);
                        g.dispose();
                        check(Arrays.equals(pixels(expected), pixels(actual)),
                            "cropped baked mismatch " + describeDiff(pixels(expected), pixels(actual)));
                        cases++;
                    }
                    long[] shapes = MacApkBitmapShaderCache.shapeStats();
                    check(shapes[0] == 1 && shapes[1] == 1,
                        "cropped shape must reuse across integer scroll");
                }
            }
        }
        return cases;
    }

    private static Object[][] supported(BufferedImage source) {
        java.util.ArrayList<Object[]> out = new java.util.ArrayList<>();
        RoundRectangle2D identityShape = new RoundRectangle2D.Double(5, 7, W, H, 14, 14);
        for (Shader.TileMode tx : Shader.TileMode.values()) {
            for (Shader.TileMode ty : Shader.TileMode.values()) {
                for (boolean linear : new boolean[] {false, true}) {
                    for (boolean aa : new boolean[] {false, true}) {
                        out.add(new Object[] {new AffineTransform(1, 0, 0, 1, 5, 7),
                            identityShape, new AffineTransform(), tx, ty, linear, aa});
                    }
                }
            }
        }
        AffineTransform local = new AffineTransform(.5, 0, 0, .5, 3, 4);
        AffineTransform ctm = new AffineTransform(2, 0, 0, 2, 0, 0);
        RoundRectangle2D scaled = new RoundRectangle2D.Double(
            3, 4, W * .5, H * .5, 7, 7);
        out.add(new Object[] {local, scaled, ctm, Shader.TileMode.CLAMP,
            Shader.TileMode.CLAMP, true, true});
        return out.toArray(new Object[0][]);
    }

    private static Object[][] unsupported(BufferedImage source) {
        return new Object[][] {
            {new AffineTransform(1.1, 0, 0, 1.1, 4, 6),
                new RoundRectangle2D.Double(50, 60, 40, 40, 8, 8),
                new AffineTransform(1.1, 0, 0, 1.1, -51, -60), "non-unit mapping"},
            {new AffineTransform(1, 0, 0, 1, 4.25, 6),
                new RoundRectangle2D.Double(5, 6, 20, 20, 8, 8),
                new AffineTransform(), "fractional device translation"},
            {new AffineTransform(0, 1, -1, 0, 50, 4),
                new RoundRectangle2D.Double(10, 10, 20, 20, 8, 8),
                new AffineTransform(), "rotation"},
            {new AffineTransform(1, 0, 0, 1, 4, 6),
                new RoundRectangle2D.Double(55, 6, 20, 20, 8, 8),
                new AffineTransform(), "shape outside image"},
            {new AffineTransform(1, 0, 0, 1, 4, 6),
                new RoundRectangle2D.Double(5, 6, 20, 20, 8, 8),
                new AffineTransform(1, .1, 0, 1, 0, 0), "canvas shear"}
        };
    }

    private static BufferedImage render(BufferedImage source, AffineTransform local,
            RoundRectangle2D shape, AffineTransform ctm, Shader.TileMode tx, Shader.TileMode ty,
            boolean bilinear, boolean aa, boolean fast) {
        BufferedImage dst = new BufferedImage(112, 72, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = dst.createGraphics();
        g.setTransform(ctm);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
            aa ? RenderingHints.VALUE_ANTIALIAS_ON : RenderingHints.VALUE_ANTIALIAS_OFF);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
            bilinear ? RenderingHints.VALUE_INTERPOLATION_BILINEAR
                     : RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        if (fast) {
            g.setPaint(Canvas.macapkContainedBitmapTexture(source, local, shape.getBounds2D(), ctm));
        } else {
            g.setPaint(new MacApkBitmapShaderPaint(
                source, local, tx, ty, bilinear));
        }
        g.fill(shape);
        g.dispose();
        return dst;
    }

    private static BufferedImage renderPaint(BufferedImage source, AffineTransform local,
            Shape shape, AffineTransform ctm, BitmapShader shader,
            boolean bilinear, boolean texture) {
        BufferedImage dst = new BufferedImage(400, 850, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = dst.createGraphics();
        g.setTransform(ctm);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
            RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
            bilinear ? RenderingHints.VALUE_INTERPOLATION_BILINEAR
                     : RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        if (texture) {
            g.setPaint(Canvas.macapkScaledBitmapTexture(
                shader, source, local, shape.getBounds2D(), ctm, bilinear));
        } else {
            g.setPaint(new MacApkBitmapShaderPaint(
                source, local, shader.tileX, shader.tileY, bilinear));
        }
        g.fill(shape);
        g.dispose();
        return dst;
    }

    private static BufferedImage source() {
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB);
        Random random = new Random(0xC0A7);
        for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) {
            int c = random.nextInt();
            if ((x + y) % 4 == 0) c &= 0x00ffffff;
            else if ((x + y) % 4 == 1) c |= 0xff000000;
            img.setRGB(x, y, c);
        }
        return img;
    }

    private static BufferedImage scaledSource() {
        BufferedImage img = new BufferedImage(481, 270, BufferedImage.TYPE_INT_ARGB);
        Random random = new Random(0x696296);
        for (int y = 0; y < img.getHeight(); y++) for (int x = 0; x < img.getWidth(); x++) {
            int c = random.nextInt();
            if ((x + y) % 5 == 0) c &= 0x00ffffff;
            img.setRGB(x, y, c);
        }
        return img;
    }

    private static int[] pixels(BufferedImage img) {
        return ((DataBufferInt) img.getRaster().getDataBuffer()).getData();
    }

    private static String describeDiff(int[] a, int[] b) {
        int count = 0, max = 0, first = -1;
        for (int i = 0; i < a.length; i++) {
            int d = Math.max(Math.abs(((a[i] >>> 24) & 255) - ((b[i] >>> 24) & 255)),
                Math.max(Math.abs(((a[i] >>> 16) & 255) - ((b[i] >>> 16) & 255)),
                    Math.max(Math.abs(((a[i] >>> 8) & 255) - ((b[i] >>> 8) & 255)),
                        Math.abs((a[i] & 255) - (b[i] & 255)))));
            if (d != 0) { count++; if (first < 0) first = i; max = Math.max(max, d); }
        }
        return "diffPixels=" + count + " max=" + max
            + (first < 0 ? "" : " first=" + first
                + " expected=" + Integer.toHexString(a[first])
                + " actual=" + Integer.toHexString(b[first]));
    }

    private static String alphaBox(int[] pixels, int width) {
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, maxX = -1, maxY = -1;
        for (int i = 0; i < pixels.length; i++) {
            if ((pixels[i] >>> 24) == 0) continue;
            int x = i % width, y = i / width;
            minX = Math.min(minX, x); minY = Math.min(minY, y);
            maxX = Math.max(maxX, x); maxY = Math.max(maxY, y);
        }
        return minX + "," + minY + ".." + maxX + "," + maxY;
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
