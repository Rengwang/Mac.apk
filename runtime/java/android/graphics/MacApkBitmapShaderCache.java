package android.graphics;

import java.awt.TexturePaint;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.PathIterator;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.lang.ref.WeakReference;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/** Translation-independent scaled BitmapShader images for contained fills.
 *  Pixel scrolling changes only the shader's integer device origin, so a scaled
 *  image can be reused across all those draws. The key still includes source
 *  generation, scale, filter mode, and output size. */
final class MacApkBitmapShaderCache {
    /** The result depends on Bitmap.getGenerationId() to notice source-pixel
     *  changes. Bitmap.GENID=0 deliberately makes that API constant for
     *  diagnostics; in that mode no immutable cache of those pixels is valid. */
    static final boolean ENABLED = Bitmap.GENID
        && !"0".equals(System.getenv("MACAPK_BITMAPSHADER_SCALE_CACHE"));
    private static final long MAX_BYTES = 32L * 1024 * 1024;
    private static final int MAX_OUTPUT_PIXELS = 4_000_000;
    private static final boolean TRACE =
        "1".equals(System.getenv("MACAPK_BITMAPSHADER_SCALE_CACHE_TRACE"));
    private static final LinkedHashMap<Key, Entry> CACHE =
        new LinkedHashMap<>(16, .75f, true);
    private static long sBytes;
    private static long sHits, sMisses, sShapeHits, sShapeMisses;

    static synchronized void resetStats() {
        CACHE.clear(); sBytes = 0; sHits = sMisses = sShapeHits = sShapeMisses = 0;
    }
    static synchronized long[] stats() { return new long[] {sHits, sMisses, sBytes}; }
    static synchronized long[] shapeStats() { return new long[] {sShapeHits, sShapeMisses}; }

    /** Drop every baked result that belongs to this source immediately on
     *  recycle, without waiting for LRU eviction. The bound already limits a
     *  leak, but an app that recycles thumbnails should get that memory back at
     *  the call that released the source pixels. */
    static synchronized void discard(Bitmap bitmap) {
        if (bitmap == null) return;
        Iterator<Map.Entry<Key, Entry>> it = CACHE.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Key, Entry> e = it.next();
            if (e.getKey().source.get() != bitmap) continue;
            sBytes -= e.getValue().bytes;
            it.remove();
        }
    }

    /** Release all framework raster caches when the runtime asks the process
     *  to trim memory. The Canvas path rebuilds entries lazily on demand. */
    static synchronized void trimMemory() {
        CACHE.clear();
        sBytes = 0;
    }

    record ShapeTexture(BufferedImage image, int deviceX, int deviceY) { }

    /** Immutable, translation-independent path identity. A canvas translation
     *  is not part of the path and therefore not part of this key. */
    private static final class ShapeGeometry {
        private static final int MAX_SEGMENTS = 64;
        final int windingRule;
        final int[] types;
        final double[] coords;

        private ShapeGeometry(int windingRule, int[] types, double[] coords) {
            this.windingRule = windingRule;
            this.types = types;
            this.coords = coords;
        }

        static ShapeGeometry of(Shape shape) {
            if (shape == null) return null;
            PathIterator it = shape.getPathIterator(null);
            int[] types = new int[MAX_SEGMENTS];
            double[] coords = new double[MAX_SEGMENTS * 6];
            double[] p = new double[6];
            int n = 0, c = 0;
            while (!it.isDone()) {
                if (n >= MAX_SEGMENTS) return null;
                int type = it.currentSegment(p);
                types[n++] = type;
                int count = switch (type) {
                    case PathIterator.SEG_MOVETO, PathIterator.SEG_LINETO -> 2;
                    case PathIterator.SEG_QUADTO -> 4;
                    case PathIterator.SEG_CUBICTO -> 6;
                    case PathIterator.SEG_CLOSE -> 0;
                    default -> -1;
                };
                if (count < 0 || c + count > coords.length) return null;
                for (int i = 0; i < count; i++) {
                    if (!Double.isFinite(p[i])) return null;
                    coords[c++] = p[i];
                }
                it.next();
            }
            return n == 0 ? null : new ShapeGeometry(
                it.getWindingRule(), Arrays.copyOf(types, n), Arrays.copyOf(coords, c));
        }

        @Override public int hashCode() {
            return 31 * (31 * windingRule + Arrays.hashCode(types)) + Arrays.hashCode(coords);
        }

        @Override public boolean equals(Object other) {
            return other instanceof ShapeGeometry s
                && windingRule == s.windingRule
                && Arrays.equals(types, s.types) && Arrays.equals(coords, s.coords);
        }
    }

    private static final class Key {
        final WeakReference<Bitmap> source;
        final int sourceIdentity, generation, outputWidth, outputHeight;
        final double scaleX, scaleY;
        final boolean bilinear;
        final ShapeGeometry shape;
        final boolean antiAlias;

        Key(Bitmap bitmap, int generation, int outputWidth, int outputHeight,
                double scaleX, double scaleY, boolean bilinear,
                ShapeGeometry shape, boolean antiAlias) {
            this.source = new WeakReference<>(bitmap);
            this.sourceIdentity = System.identityHashCode(bitmap);
            this.generation = generation;
            this.outputWidth = outputWidth;
            this.outputHeight = outputHeight;
            this.scaleX = scaleX;
            this.scaleY = scaleY;
            this.bilinear = bilinear;
            this.shape = shape;
            this.antiAlias = antiAlias;
        }

        @Override public int hashCode() {
            int h = sourceIdentity;
            h = 31 * h + generation;
            h = 31 * h + outputWidth;
            h = 31 * h + outputHeight;
            long sx = Double.doubleToLongBits(scaleX), sy = Double.doubleToLongBits(scaleY);
            h = 31 * h + (int) (sx ^ (sx >>> 32));
            h = 31 * h + (int) (sy ^ (sy >>> 32));
            h = 31 * h + (bilinear ? 1 : 0);
            h = 31 * h + java.util.Objects.hashCode(shape);
            return 31 * h + (antiAlias ? 1 : 0);
        }

        @Override public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof Key k)) return false;
            Bitmap a = source.get();
            Bitmap b = k.source.get();
            return a != null && a == b
                && generation == k.generation
                && outputWidth == k.outputWidth && outputHeight == k.outputHeight
                && Double.doubleToLongBits(scaleX) == Double.doubleToLongBits(k.scaleX)
                && Double.doubleToLongBits(scaleY) == Double.doubleToLongBits(k.scaleY)
                && bilinear == k.bilinear
                && java.util.Objects.equals(shape, k.shape)
                && antiAlias == k.antiAlias;
        }
    }
    private record Entry(BufferedImage image, long bytes) { }

    private MacApkBitmapShaderCache() { }

    static synchronized TexturePaint texture(BitmapShader shader, BufferedImage source,
            AffineTransform local, Rectangle2D userBounds, AffineTransform ctm,
            int originX, int originY, int outputWidth, int outputHeight,
            double scaleX, double scaleY, boolean bilinear) {
        if (!ENABLED || shader == null || shader.bitmap == null || shader.bitmap.isRecycled()
                || source == null
                || outputWidth <= 0 || outputHeight <= 0
                || (long) outputWidth * outputHeight > MAX_OUTPUT_PIXELS)
            return null;
        int generation;
        try { generation = shader.bitmap.getGenerationId(); }
        catch (Throwable recycled) { return null; }
        Key key = new Key(shader.bitmap, generation,
            outputWidth, outputHeight, scaleX, scaleY, bilinear, null, false);
        Entry hit = CACHE.get(key);
        BufferedImage scaled = hit != null ? hit.image : null;
        if (scaled == null) {
            sMisses++;
            scaled = render(source, local, ctm, originX, originY,
                outputWidth, outputHeight, bilinear);
            if (scaled == null) return null;
            long bytes = (long) outputWidth * outputHeight * 4L;
            Entry prior = CACHE.put(key, new Entry(scaled, bytes));
            if (prior != null) sBytes -= prior.bytes;
            sBytes += bytes;
            evict();
        } else sHits++;
        if (TRACE && (sHits + sMisses) % 256 == 0)
            com.macapk.loader.NativeLog.i("Mac.apk.BitmapShaderCache",
                "hits=" + sHits + " misses=" + sMisses + " bytes=" + sBytes);
        return new TexturePaint(scaled, (Rectangle2D) userBounds.clone());
    }

    /** Cache the final anti-aliased rounded image. The scaled source and its
     *  path are immutable while scrolling; only the integer device origin
     *  changes, so replaying them through a TexturePaint every frame is wasted
     *  work. The baked ARGB image contains exactly the same filled pixels and
     *  can be blitted at 1:1. */
    static synchronized ShapeTexture shape(BitmapShader shader, BufferedImage source,
            AffineTransform local, AffineTransform ctm, Shape shape,
            int originX, int originY, int outputWidth, int outputHeight,
            double scaleX, double scaleY, boolean bilinear, boolean antiAlias) {
        ShapeGeometry geometry = ShapeGeometry.of(shape);
        if (!ENABLED || shader == null || shader.bitmap == null || shader.bitmap.isRecycled()
                || source == null
                || geometry == null || outputWidth <= 0 || outputHeight <= 0
                || (long) outputWidth * outputHeight > MAX_OUTPUT_PIXELS)
            return null;
        int generation;
        try { generation = shader.bitmap.getGenerationId(); }
        catch (Throwable recycled) { return null; }
        Key key = new Key(shader.bitmap, generation, outputWidth, outputHeight,
            scaleX, scaleY, bilinear, geometry, antiAlias);
        Entry hit = CACHE.get(key);
        BufferedImage composed = hit != null ? hit.image : null;
        if (composed == null) {
            sMisses++; sShapeMisses++;
            BufferedImage scaled = render(source, local, ctm, originX, originY,
                outputWidth, outputHeight, bilinear);
            if (scaled == null) return null;
            composed = compose(scaled, ctm, shape, originX, originY, antiAlias);
            if (composed == null) return null;
            long bytes = (long) outputWidth * outputHeight * 4L;
            Entry prior = CACHE.put(key, new Entry(composed, bytes));
            if (prior != null) sBytes -= prior.bytes;
            sBytes += bytes;
            evict();
        } else { sHits++; sShapeHits++; }
        if (TRACE && (sHits + sMisses) % 256 == 0)
            com.macapk.loader.NativeLog.i("Mac.apk.BitmapShaderCache",
                "hits=" + sHits + " misses=" + sMisses + " shapeHits=" + sShapeHits
                    + " shapeMisses=" + sShapeMisses + " bytes=" + sBytes);
        return new ShapeTexture(composed, originX, originY);
    }

    private static BufferedImage render(BufferedImage source, AffineTransform local,
            AffineTransform ctm, int originX, int originY,
            int outputWidth, int outputHeight, boolean bilinear) {
        try {
            AffineTransform total = new AffineTransform(ctm);
            if (local != null) total.concatenate(local);
            AffineTransform inverse = total.createInverse();
            BufferedImage out = new BufferedImage(
                outputWidth, outputHeight, BufferedImage.TYPE_INT_ARGB);
            int[] pixels = ((DataBufferInt) out.getRaster().getDataBuffer()).getData();
            MacApkBitmapShaderPaint sampler = new MacApkBitmapShaderPaint(
                source, local, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP, bilinear);
            int at = 0;
            for (int y = 0; y < outputHeight; y++)
                for (int x = 0; x < outputWidth; x++)
                    pixels[at++] = sampler.sampleDevice(inverse, originX + x, originY + y);
            return out;
        } catch (Throwable unavailable) {
            return null;
        }
    }

    private static BufferedImage compose(BufferedImage scaled, AffineTransform ctm,
            Shape shape, int originX, int originY,
            boolean antiAlias) {
        try {
            BufferedImage out = new BufferedImage(scaled.getWidth(), scaled.getHeight(),
                BufferedImage.TYPE_INT_ARGB_PRE);
            java.awt.Graphics2D g = out.createGraphics();
            try {
                g.translate(-originX, -originY);
                g.transform(ctm);
                g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING,
                    antiAlias ? java.awt.RenderingHints.VALUE_ANTIALIAS_ON
                              : java.awt.RenderingHints.VALUE_ANTIALIAS_OFF);
                g.setPaint(new TexturePaint(scaled, shape.getBounds2D()));
                g.fill(shape);
            } finally { g.dispose(); }
            return out;
        } catch (Throwable unavailable) {
            return null;
        }
    }

    private static void evict() {
        Iterator<Map.Entry<Key, Entry>> it = CACHE.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Key, Entry> e = it.next();
            if (sBytes <= MAX_BYTES && e.getKey().source.get() != null) continue;
            sBytes -= e.getValue().bytes;
            it.remove();
        }
    }
}
