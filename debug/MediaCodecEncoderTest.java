package debug;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.SurfaceTexture;
import android.media.Image;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.os.Bundle;
import android.view.Surface;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * End-to-end hardware & AudioToolbox MediaCodec encoder verification harness:
 *   1. H.264 ByteBuffer (I420 + NV12 + BGRA + getInputImage) encode -> decode round-trip
 *   2. H.264 createInputSurface (Canvas lockCanvas/unlockCanvasAndPost + ProducerQueue/GL
 *      + createPersistentInputSurface/setInputSurface + signalEndOfInputStream) round-trip
 *   3. Async setCallback video encode lifecycle + requestSyncFrame
 *   4. HEVC ByteBuffer encode -> decode round-trip (when host supports HEVC encode)
 *   5. AAC PCM encode -> decode round-trip (AudioToolbox AudioConverter + 2-byte ASC csd-0)
 *   6. MediaCodecList encoder discovery + AOSP negative contracts
 */
public final class MediaCodecEncoderTest {

    private static final class Packet {
        final byte[] data;
        final long ptsUs;
        final int flags;
        Packet(byte[] data, long ptsUs, int flags) {
            this.data = data;
            this.ptsUs = ptsUs;
            this.flags = flags;
        }
    }

    private static void check(boolean cond, String msg) {
        if (!cond) throw new AssertionError("FAILED: " + msg);
    }

    private static byte[] toBytes(ByteBuffer b) {
        if (b == null) return new byte[0];
        ByteBuffer dup = b.duplicate();
        byte[] out = new byte[dup.remaining()];
        dup.get(out);
        return out;
    }

    private static boolean startsWithAnnexB(byte[] b) {
        return b != null && b.length >= 4
                && b[0] == 0 && b[1] == 0 && b[2] == 0 && b[3] == 1;
    }

    private static int firstAnnexBNalTypeH264(byte[] b) {
        if (!startsWithAnnexB(b) || b.length < 5) return -1;
        return b[4] & 0x1F;
    }

    private static byte[] makeI420Frame(int w, int h, int frameIdx) {
        int ySize = w * h;
        int cw = w / 2;
        int ch = h / 2;
        int uvSize = cw * ch;
        byte[] out = new byte[ySize + 2 * uvSize];
        int barX = (frameIdx * 24) % w;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                boolean inBar = Math.abs(x - barX) < 32;
                int luma = inBar ? 220 : (40 + (x * 160 / w));
                out[y * w + x] = (byte) Math.max(16, Math.min(235, luma));
            }
        }
        int uVal = (frameIdx % 2 == 0) ? 90 : 180;
        int vVal = (frameIdx % 2 == 0) ? 200 : 80;
        for (int i = 0; i < uvSize; i++) {
            out[ySize + i] = (byte) uVal;
            out[ySize + uvSize + i] = (byte) vVal;
        }
        return out;
    }

    private static byte[] makeNv12Frame(int w, int h, int frameIdx) {
        int ySize = w * h;
        int cw = w / 2;
        int ch = h / 2;
        byte[] out = new byte[ySize + w * ch];
        int barX = (frameIdx * 28) % w;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int luma = (Math.abs(x - barX) < 30) ? 215 : (35 + (y * 150 / h));
                out[y * w + x] = (byte) Math.max(16, Math.min(235, luma));
            }
        }
        int uVal = 170;
        int vVal = 70;
        for (int y = 0; y < ch; y++) {
            for (int x = 0; x < cw; x++) {
                int off = ySize + y * w + x * 2;
                out[off] = (byte) uVal;
                out[off + 1] = (byte) vVal;
            }
        }
        return out;
    }

    private static byte[] makeBgraFrame(int w, int h, int frameIdx) {
        byte[] out = new byte[w * h * 4];
        int barX = (frameIdx * 32) % w;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                boolean bar = Math.abs(x - barX) < 36;
                int b = bar ? 240 : (x * 200 / w);
                int g = bar ? 200 : (y * 200 / h);
                int r = bar ? 40 : ((frameIdx * 40) % 255);
                int off = (y * w + x) * 4;
                out[off] = (byte) b;
                out[off + 1] = (byte) g;
                out[off + 2] = (byte) r;
                out[off + 3] = (byte) 255;
            }
        }
        return out;
    }

    private static List<int[]> decodeVideoAndCollectYStats(
            String mime, int w, int h, byte[] csd0, byte[] csd1, List<Packet> packets) throws Exception {
        MediaCodec dec = MediaCodec.createDecoderByType(mime);
        MediaFormat fmt = MediaFormat.createVideoFormat(mime, w, h);
        if (csd0 != null && csd0.length > 0) fmt.setByteBuffer("csd-0", ByteBuffer.wrap(csd0));
        if (csd1 != null && csd1.length > 0) fmt.setByteBuffer("csd-1", ByteBuffer.wrap(csd1));
        dec.configure(fmt, null, null, 0);
        dec.start();

        List<Packet> vclAndEos = new ArrayList<Packet>();
        for (Packet p : packets) {
            if ((p.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                continue;
            }
            vclAndEos.add(p);
        }

        List<int[]> stats = new ArrayList<int[]>();
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        boolean inputDone = false;
        boolean outputDone = false;
        int nextIn = 0;
        long deadline = System.currentTimeMillis() + 10_000L;

        while (!outputDone && System.currentTimeMillis() < deadline) {
            if (!inputDone && nextIn < vclAndEos.size()) {
                int inIdx = dec.dequeueInputBuffer(5_000);
                if (inIdx >= 0) {
                    Packet p = vclAndEos.get(nextIn++);
                    ByteBuffer ib = dec.getInputBuffer(inIdx);
                    ib.clear();
                    if (p.data.length > 0) ib.put(p.data);
                    dec.queueInputBuffer(inIdx, 0, p.data.length, p.ptsUs, p.flags);
                    if ((p.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) inputDone = true;
                }
            } else if (!inputDone) {
                int inIdx = dec.dequeueInputBuffer(5_000);
                if (inIdx >= 0) {
                    dec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                    inputDone = true;
                }
            }

            int outIdx = dec.dequeueOutputBuffer(info, 20_000);
            if (outIdx >= 0) {
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    outputDone = true;
                }
                if (info.size > 0 && (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) == 0) {
                    Image img = dec.getOutputImage(outIdx);
                    if (img != null) {
                        check(img.getWidth() == w && img.getHeight() == h,
                                "decoded dims mismatch: " + img.getWidth() + "x" + img.getHeight());
                        check(img.getFormat() == 35, "decoded image format expected YUV_420_888(35)");
                        Image.Plane yPlane = img.getPlanes()[0];
                        ByteBuffer yb = yPlane.getBuffer();
                        int rowStride = yPlane.getRowStride();
                        int minY = 255, maxY = 0;
                        long sumY = 0;
                        int count = 0;
                        for (int row = 0; row < h; row += 8) {
                            for (int col = 0; col < w; col += 8) {
                                int v = yb.get(row * rowStride + col) & 0xFF;
                                if (v < minY) minY = v;
                                if (v > maxY) maxY = v;
                                sumY += v;
                                count++;
                            }
                        }
                        stats.add(new int[] { minY, maxY, (int) (sumY / Math.max(1, count)) });
                        img.close();
                    }
                }
                dec.releaseOutputBuffer(outIdx, false);
            }
        }
        dec.stop();
        dec.release();
        check(outputDone, "video decoder did not reach EOS for " + mime);
        return stats;
    }

    private static void testH264ByteBufferRoundTrip(int colorFormat, String label) throws Exception {
        final int w = 320, h = 240, numFrames = 8;
        MediaCodec enc = MediaCodec.createEncoderByType("video/avc");
        MediaFormat fmt = MediaFormat.createVideoFormat("video/avc", w, h);
        fmt.setInteger(MediaFormat.KEY_COLOR_FORMAT, colorFormat);
        fmt.setInteger(MediaFormat.KEY_BIT_RATE, 1_000_000);
        fmt.setInteger(MediaFormat.KEY_FRAME_RATE, 30);
        fmt.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
        enc.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        enc.start();

        List<Packet> encoded = new ArrayList<Packet>();
        byte[] csd0 = null, csd1 = null;
        boolean sawFormatChange = false;
        boolean sawCodecConfig = false;
        boolean sawKeyFrame = false;
        boolean outputDone = false;

        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        int sent = 0;
        boolean sentEos = false;
        long deadline = System.currentTimeMillis() + 10_000L;

        while (!outputDone && System.currentTimeMillis() < deadline) {
            if (!sentEos) {
                int inIdx = enc.dequeueInputBuffer(5_000);
                if (inIdx >= 0) {
                    if (sent < numFrames) {
                        long ptsUs = sent * 33_333L;
                        if (colorFormat == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible) {
                            Image img = enc.getInputImage(inIdx);
                            check(img != null, "getInputImage returned null for YUV420Flexible");
                            byte[] raw = makeI420Frame(w, h, sent);
                            int yLen = w * h;
                            int uvLen = (w / 2) * (h / 2);
                            Image.Plane[] planes = img.getPlanes();
                            planes[0].getBuffer().put(raw, 0, yLen);
                            planes[1].getBuffer().put(raw, yLen, uvLen);
                            planes[2].getBuffer().put(raw, yLen + uvLen, uvLen);
                            img.close();
                            if (sent == 4) {
                                Bundle req = new Bundle();
                                req.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 1);
                                enc.setParameters(req);
                            }
                            enc.queueInputBuffer(inIdx, 0, raw.length, ptsUs, 0);
                        } else {
                            byte[] raw = (colorFormat == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar)
                                    ? makeNv12Frame(w, h, sent)
                                    : (colorFormat == MediaCodecInfo.CodecCapabilities.COLOR_Format32bitBGRA8888)
                                    ? makeBgraFrame(w, h, sent)
                                    : makeI420Frame(w, h, sent);
                            ByteBuffer ib = enc.getInputBuffer(inIdx);
                            ib.clear();
                            ib.put(raw);
                            enc.queueInputBuffer(inIdx, 0, raw.length, ptsUs, 0);
                        }
                        sent++;
                    } else {
                        enc.queueInputBuffer(inIdx, 0, 0, sent * 33_333L, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        sentEos = true;
                    }
                }
            }

            int outIdx = enc.dequeueOutputBuffer(info, 20_000);
            if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                MediaFormat of = enc.getOutputFormat();
                check("video/avc".equals(of.getString(MediaFormat.KEY_MIME)), "out mime mismatch");
                csd0 = toBytes(of.getByteBuffer("csd-0"));
                csd1 = toBytes(of.getByteBuffer("csd-1"));
                check(startsWithAnnexB(csd0), label + " csd-0 must start with Annex-B 00 00 00 01");
                check(firstAnnexBNalTypeH264(csd0) == 7, label + " csd-0 must carry H.264 SPS (7)");
                check(startsWithAnnexB(csd1), label + " csd-1 must start with Annex-B 00 00 00 01");
                check(firstAnnexBNalTypeH264(csd1) == 8, label + " csd-1 must carry H.264 PPS (8)");
                sawFormatChange = true;
            } else if (outIdx >= 0) {
                ByteBuffer ob = enc.getOutputBuffer(outIdx);
                check(ob != null, label + " getOutputBuffer must be non-null for video encoder");
                byte[] copy = new byte[info.size];
                if (info.size > 0) {
                    ByteBuffer dup = ob.duplicate();
                    dup.position(info.offset);
                    dup.limit(info.offset + info.size);
                    dup.get(copy);
                    check(startsWithAnnexB(copy), label + " output packet missing Annex-B start code");
                }
                if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) sawCodecConfig = true;
                if ((info.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0) sawKeyFrame = true;
                encoded.add(new Packet(copy, info.presentationTimeUs, info.flags));
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputDone = true;
                enc.releaseOutputBuffer(outIdx, false);
            }
        }
        enc.stop();
        enc.release();

        check(sawFormatChange, label + " missing INFO_OUTPUT_FORMAT_CHANGED");
        check(sawCodecConfig, label + " missing BUFFER_FLAG_CODEC_CONFIG");
        check(sawKeyFrame, label + " missing BUFFER_FLAG_KEY_FRAME");
        check(outputDone, label + " missing EOS output buffer");

        List<int[]> yStats = decodeVideoAndCollectYStats("video/avc", w, h, csd0, csd1, encoded);
        check(yStats.size() == numFrames,
                label + " decoded frame count=" + yStats.size() + " expected=" + numFrames);
        for (int i = 0; i < yStats.size(); i++) {
            int[] st = yStats.get(i);
            check(st[1] - st[0] >= 40,
                    label + " decoded frame " + i + " looks flat (min=" + st[0] + " max=" + st[1] + ")");
        }
        System.out.println("[PASS] H.264 ByteBuffer round-trip (" + label + "): frames="
                + yStats.size() + " csd0=" + csd0.length + "B csd1=" + csd1.length + "B");
    }

    private static void testH264InputSurfaceCanvasAndQueue() throws Exception {
        final int w = 320, h = 240, numFrames = 6;
        MediaCodec enc = MediaCodec.createEncoderByType("video/avc");
        MediaFormat fmt = MediaFormat.createVideoFormat("video/avc", w, h);
        fmt.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        fmt.setInteger(MediaFormat.KEY_BIT_RATE, 1_200_000);
        fmt.setInteger(MediaFormat.KEY_FRAME_RATE, 30);
        fmt.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
        enc.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);

        Surface inputSurface = enc.createInputSurface();
        check(inputSurface != null && inputSurface.isValid(), "createInputSurface must return valid Surface");
        check(inputSurface.macapkGetSurfaceTexture() != null, "input Surface must carry SurfaceTexture");

        enc.start();

        // Calling dequeueInputBuffer/queueInputBuffer in Surface mode must throw IllegalStateException.
        boolean threwDeq = false;
        try {
            enc.dequeueInputBuffer(0);
        } catch (IllegalStateException expected) {
            threwDeq = true;
        }
        check(threwDeq, "dequeueInputBuffer with input Surface must throw IllegalStateException");

        // Half via Canvas lockCanvas/unlockCanvasAndPost, half via SurfaceTexture BufferQueue producer path
        // (simulating GL eglSwapBuffers).
        Paint paint = new Paint();
        for (int i = 0; i < 3; i++) {
            Canvas c = inputSurface.lockCanvas(null);
            check(c != null, "lockCanvas returned null");
            c.drawColor(Color.rgb(30, 40 + i * 50, 180));
            paint.setColor(Color.rgb(255, 220, 40));
            c.drawRect(30 + i * 40, 40, 110 + i * 40, 200, paint);
            inputSurface.unlockCanvasAndPost(c);
        }

        SurfaceTexture st = inputSurface.macapkGetSurfaceTexture();
        for (int i = 3; i < numFrames; i++) {
            int[] px = new int[w * h];
            int bg = Color.rgb(180, 40 + i * 25, 40);
            int fg = Color.rgb(40, 240, 240);
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    px[y * w + x] = (x >= 40 * (i - 2) && x < 40 * (i - 2) + 80) ? fg : bg;
                }
            }
            st.macapkQueueProducerFrame(null, px, w, h, w, null, null, false);
            Thread.sleep(15);
        }

        enc.signalEndOfInputStream();

        List<Packet> encoded = new ArrayList<Packet>();
        byte[] csd0 = null, csd1 = null;
        boolean sawFmt = false, sawKf = false, outputDone = false;
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        long deadline = System.currentTimeMillis() + 10_000L;
        while (!outputDone && System.currentTimeMillis() < deadline) {
            int outIdx = enc.dequeueOutputBuffer(info, 20_000);
            if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                MediaFormat of = enc.getOutputFormat();
                csd0 = toBytes(of.getByteBuffer("csd-0"));
                csd1 = toBytes(of.getByteBuffer("csd-1"));
                check(startsWithAnnexB(csd0) && startsWithAnnexB(csd1), "surface csd must be Annex-B");
                sawFmt = true;
            } else if (outIdx >= 0) {
                ByteBuffer ob = enc.getOutputBuffer(outIdx);
                byte[] copy = new byte[info.size];
                if (info.size > 0) {
                    ByteBuffer dup = ob.duplicate();
                    dup.position(info.offset);
                    dup.limit(info.offset + info.size);
                    dup.get(copy);
                }
                if ((info.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0) sawKf = true;
                encoded.add(new Packet(copy, info.presentationTimeUs, info.flags));
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputDone = true;
                enc.releaseOutputBuffer(outIdx, false);
            }
        }
        enc.stop();
        enc.release();

        check(sawFmt && sawKf && outputDone, "Surface encode missing fmt/kf/eos");
        List<int[]> yStats = decodeVideoAndCollectYStats("video/avc", w, h, csd0, csd1, encoded);
        check(yStats.size() == numFrames,
                "Surface decoded frame count=" + yStats.size() + " expected=" + numFrames);
        for (int i = 0; i < yStats.size(); i++) {
            int[] s = yStats.get(i);
            check(s[1] - s[0] >= 35, "Surface decoded frame " + i + " flat: " + s[0] + ".." + s[1]);
        }
        System.out.println("[PASS] H.264 InputSurface (Canvas + ProducerQueue + signalEndOfInputStream): frames="
                + yStats.size());
    }

    private static void testAsyncSurfaceAndPersistentInputSurface() throws Exception {
        final int w = 256, h = 192, numFrames = 4;
        Surface persistent = MediaCodec.createPersistentInputSurface();
        check(persistent != null && persistent.isValid(), "createPersistentInputSurface invalid");

        MediaCodec enc = MediaCodec.createByCodecName("OMX.macapk.enc.video/avc");
        MediaFormat fmt = MediaFormat.createVideoFormat("video/avc", w, h);
        fmt.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        fmt.setInteger(MediaFormat.KEY_BIT_RATE, 800_000);
        fmt.setInteger(MediaFormat.KEY_FRAME_RATE, 30);
        fmt.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
        enc.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        enc.setInputSurface(persistent);

        final AtomicInteger inputCbCount = new AtomicInteger(0);
        final AtomicBoolean sawFmt = new AtomicBoolean(false);
        final AtomicBoolean sawEos = new AtomicBoolean(false);
        final AtomicInteger vclCount = new AtomicInteger(0);
        final CountDownLatch eosLatch = new CountDownLatch(1);
        final byte[][] csds = new byte[2][];

        enc.setCallback(new MediaCodec.Callback() {
            @Override public void onInputBufferAvailable(MediaCodec codec, int index) {
                inputCbCount.incrementAndGet();
            }
            @Override public void onOutputFormatChanged(MediaCodec codec, MediaFormat format) {
                csds[0] = toBytes(format.getByteBuffer("csd-0"));
                csds[1] = toBytes(format.getByteBuffer("csd-1"));
                sawFmt.set(true);
            }
            @Override public void onOutputBufferAvailable(MediaCodec codec, int index, MediaCodec.BufferInfo info) {
                if ((info.flags & (MediaCodec.BUFFER_FLAG_CODEC_CONFIG | MediaCodec.BUFFER_FLAG_END_OF_STREAM)) == 0
                        && info.size > 0) {
                    vclCount.incrementAndGet();
                }
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    sawEos.set(true);
                    eosLatch.countDown();
                }
                codec.releaseOutputBuffer(index, false);
            }
            @Override public void onError(MediaCodec codec, MediaCodec.CodecException e) {
                e.printStackTrace();
            }
        });

        enc.start();
        Paint paint = new Paint();
        for (int i = 0; i < numFrames; i++) {
            Canvas c = persistent.lockCanvas(null);
            c.drawColor(Color.rgb(20 + i * 20, 40, 120));
            paint.setColor(Color.WHITE);
            c.drawRect(20 + i * 20, 20, 90 + i * 20, 120, paint);
            persistent.unlockCanvasAndPost(c);
        }
        enc.signalEndOfInputStream();
        check(eosLatch.await(8, TimeUnit.SECONDS), "Async encoder did not reach EOS");
        enc.stop();
        enc.release();
        persistent.release();

        check(inputCbCount.get() == 0, "Async encoder with input Surface must NOT fire onInputBufferAvailable");
        check(sawFmt.get() && startsWithAnnexB(csds[0]) && startsWithAnnexB(csds[1]),
                "Async encoder did not deliver Annex-B csd-0/csd-1 on format change");
        check(sawEos.get() && vclCount.get() == numFrames,
                "Async VCL count=" + vclCount.get() + " expected=" + numFrames);
        System.out.println("[PASS] Async setCallback + PersistentInputSurface: frames=" + vclCount.get());
    }

    private static void testHevcRoundTripIfSupported() throws Exception {
        MediaCodecList list = new MediaCodecList(MediaCodecList.REGULAR_CODECS);
        MediaFormat query = MediaFormat.createVideoFormat("video/hevc", 320, 240);
        if (list.findEncoderForFormat(query) == null) {
            System.out.println("[SKIP] video/hevc encoder not present on host");
            return;
        }
        final int w = 320, h = 240, numFrames = 5;
        MediaCodec enc = MediaCodec.createEncoderByType("video/hevc");
        MediaFormat fmt = MediaFormat.createVideoFormat("video/hevc", w, h);
        fmt.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar);
        fmt.setInteger(MediaFormat.KEY_BIT_RATE, 1_000_000);
        fmt.setInteger(MediaFormat.KEY_FRAME_RATE, 30);
        fmt.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
        enc.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        enc.start();

        List<Packet> encoded = new ArrayList<Packet>();
        byte[] csd0 = null;
        boolean outputDone = false;
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        int sent = 0;
        boolean sentEos = false;
        long deadline = System.currentTimeMillis() + 10_000L;
        while (!outputDone && System.currentTimeMillis() < deadline) {
            if (!sentEos) {
                int inIdx = enc.dequeueInputBuffer(5_000);
                if (inIdx >= 0) {
                    if (sent < numFrames) {
                        byte[] raw = makeI420Frame(w, h, sent);
                        ByteBuffer ib = enc.getInputBuffer(inIdx);
                        ib.clear();
                        ib.put(raw);
                        enc.queueInputBuffer(inIdx, 0, raw.length, sent * 33_333L, 0);
                        sent++;
                    } else {
                        enc.queueInputBuffer(inIdx, 0, 0, sent * 33_333L, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        sentEos = true;
                    }
                }
            }
            int outIdx = enc.dequeueOutputBuffer(info, 20_000);
            if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                csd0 = toBytes(enc.getOutputFormat().getByteBuffer("csd-0"));
                check(startsWithAnnexB(csd0), "HEVC csd-0 must be Annex-B");
            } else if (outIdx >= 0) {
                ByteBuffer ob = enc.getOutputBuffer(outIdx);
                byte[] copy = new byte[info.size];
                if (info.size > 0) {
                    ByteBuffer dup = ob.duplicate();
                    dup.position(info.offset);
                    dup.limit(info.offset + info.size);
                    dup.get(copy);
                }
                encoded.add(new Packet(copy, info.presentationTimeUs, info.flags));
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputDone = true;
                enc.releaseOutputBuffer(outIdx, false);
            }
        }
        enc.stop();
        enc.release();
        check(outputDone && csd0 != null, "HEVC encode failed");
        List<int[]> yStats = decodeVideoAndCollectYStats("video/hevc", w, h, csd0, null, encoded);
        check(yStats.size() == numFrames, "HEVC decoded count mismatch: " + yStats.size());
        System.out.println("[PASS] HEVC ByteBuffer round-trip: frames=" + yStats.size() + " csd0=" + csd0.length + "B");
    }

    private static void testAsyncAacEncoder() throws Exception {
        final int sr = 48000, ch = 1;
        final int totalFrames = 1024 * 6;
        final byte[] pcm = new byte[totalFrames * ch * 2];
        ByteBuffer view = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < totalFrames; i++) {
            view.putShort((short) (Math.sin(2.0 * Math.PI * 523.25 * i / sr) * 14000.0));
        }

        final AtomicInteger byteOff = new AtomicInteger(0);
        final AtomicBoolean sentEos = new AtomicBoolean(false);
        final AtomicBoolean sawFmt = new AtomicBoolean(false);
        final AtomicInteger auCount = new AtomicInteger(0);
        final CountDownLatch eosLatch = new CountDownLatch(1);
        final byte[][] csd0 = new byte[1][];

        MediaCodec enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
        MediaFormat fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sr, ch);
        fmt.setInteger(MediaFormat.KEY_BIT_RATE, 64_000);
        enc.setCallback(new MediaCodec.Callback() {
            @Override public void onInputBufferAvailable(MediaCodec codec, int index) {
                if (sentEos.get()) return;
                int off = byteOff.get();
                if (off < pcm.length) {
                    int n = Math.min(2048, pcm.length - off);
                    ByteBuffer ib = codec.getInputBuffer(index);
                    ib.clear();
                    ib.put(pcm, off, n);
                    byteOff.set(off + n);
                    long ptsUs = (off / (ch * 2)) * 1_000_000L / sr;
                    codec.queueInputBuffer(index, 0, n, ptsUs, 0);
                } else if (sentEos.compareAndSet(false, true)) {
                    long ptsUs = (long) totalFrames * 1_000_000L / sr;
                    codec.queueInputBuffer(index, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                }
            }
            @Override public void onOutputFormatChanged(MediaCodec codec, MediaFormat format) {
                csd0[0] = toBytes(format.getByteBuffer("csd-0"));
                sawFmt.set(true);
            }
            @Override public void onOutputBufferAvailable(MediaCodec codec, int index, MediaCodec.BufferInfo info) {
                if ((info.flags & (MediaCodec.BUFFER_FLAG_CODEC_CONFIG | MediaCodec.BUFFER_FLAG_END_OF_STREAM)) == 0
                        && info.size > 0) {
                    auCount.incrementAndGet();
                }
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) eosLatch.countDown();
                codec.releaseOutputBuffer(index, false);
            }
            @Override public void onError(MediaCodec codec, MediaCodec.CodecException e) { e.printStackTrace(); }
        });
        enc.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        enc.start();
        check(eosLatch.await(8, TimeUnit.SECONDS), "Async AAC encoder did not reach EOS");
        enc.stop();
        enc.release();

        check(sawFmt.get() && csd0[0] != null && csd0[0].length == 2, "Async AAC missing 2-byte csd-0");
        check(auCount.get() >= 6, "Async AAC produced too few packets: " + auCount.get());
        System.out.println("[PASS] Async AAC setCallback: packets=" + auCount.get());
    }

    private static void testVideoEncoderFlush() throws Exception {
        final int w = 320, h = 240;
        MediaCodec enc = MediaCodec.createEncoderByType("video/avc");
        MediaFormat fmt = MediaFormat.createVideoFormat("video/avc", w, h);
        fmt.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar);
        fmt.setInteger(MediaFormat.KEY_BIT_RATE, 900_000);
        fmt.setInteger(MediaFormat.KEY_FRAME_RATE, 30);
        fmt.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
        enc.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        enc.start();

        for (int i = 0; i < 2; i++) {
            int inIdx = enc.dequeueInputBuffer(50_000);
            check(inIdx >= 0, "pre-flush dequeueInputBuffer failed");
            byte[] raw = makeI420Frame(w, h, i);
            ByteBuffer ib = enc.getInputBuffer(inIdx);
            ib.clear(); ib.put(raw);
            enc.queueInputBuffer(inIdx, 0, raw.length, i * 33_333L, 0);
        }
        enc.flush();

        int postFrames = 3;
        List<Packet> encoded = new ArrayList<Packet>();
        byte[] csd0 = null, csd1 = null;
        boolean eos = false;
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        int sent = 0;
        boolean sentEos = false;
        long deadline = System.currentTimeMillis() + 8_000L;
        while (!eos && System.currentTimeMillis() < deadline) {
            if (!sentEos) {
                int inIdx = enc.dequeueInputBuffer(5_000);
                if (inIdx >= 0) {
                    if (sent < postFrames) {
                        byte[] raw = makeI420Frame(w, h, 10 + sent);
                        ByteBuffer ib = enc.getInputBuffer(inIdx);
                        ib.clear(); ib.put(raw);
                        enc.queueInputBuffer(inIdx, 0, raw.length, sent * 33_333L, 0);
                        sent++;
                    } else {
                        enc.queueInputBuffer(inIdx, 0, 0, sent * 33_333L, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        sentEos = true;
                    }
                }
            }
            int outIdx = enc.dequeueOutputBuffer(info, 20_000);
            if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                MediaFormat of = enc.getOutputFormat();
                csd0 = toBytes(of.getByteBuffer("csd-0"));
                csd1 = toBytes(of.getByteBuffer("csd-1"));
            } else if (outIdx >= 0) {
                ByteBuffer ob = enc.getOutputBuffer(outIdx);
                byte[] copy = new byte[info.size];
                if (info.size > 0) {
                    ByteBuffer dup = ob.duplicate();
                    dup.position(info.offset); dup.limit(info.offset + info.size); dup.get(copy);
                }
                encoded.add(new Packet(copy, info.presentationTimeUs, info.flags));
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) eos = true;
                enc.releaseOutputBuffer(outIdx, false);
            }
        }
        enc.stop();
        enc.release();
        check(eos && csd0 != null && csd1 != null, "Post-flush encode did not complete");
        List<int[]> yStats = decodeVideoAndCollectYStats("video/avc", w, h, csd0, csd1, encoded);
        check(yStats.size() == postFrames, "Post-flush decoded frames=" + yStats.size());
        System.out.println("[PASS] H.264 encoder flush() recovery: postFlushFrames=" + yStats.size());
    }

    private static void testAacAudioRoundTrip() throws Exception {
        final int sr = 44100, ch = 2;
        final int totalFrames = 1024 * 10; // ~232ms stereo 440 Hz sine
        byte[] pcmIn = new byte[totalFrames * ch * 2];
        ByteBuffer pcmView = ByteBuffer.wrap(pcmIn).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < totalFrames; i++) {
            short s = (short) (Math.sin(2.0 * Math.PI * 440.0 * i / sr) * 18000.0);
            pcmView.putShort(s);
            pcmView.putShort(s);
        }

        MediaCodec enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
        MediaFormat fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sr, ch);
        fmt.setInteger(MediaFormat.KEY_BIT_RATE, 128_000);
        fmt.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
        enc.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        enc.start();

        MediaFormat outFmt = null;
        byte[] asc = null;
        boolean sawFmt = false, sawCodecCfg = false, sawEos = false;
        List<Packet> aacPackets = new ArrayList<Packet>();
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();

        // Feed in uneven chunks (e.g. 512 frames = 2048 bytes) to exercise buffering.
        int byteOff = 0;
        final int chunkBytes = 512 * ch * 2;
        boolean sentEos = false;
        long deadline = System.currentTimeMillis() + 10_000L;
        while (!sawEos && System.currentTimeMillis() < deadline) {
            if (!sentEos) {
                int inIdx = enc.dequeueInputBuffer(5_000);
                if (inIdx >= 0) {
                    if (byteOff < pcmIn.length) {
                        int n = Math.min(chunkBytes, pcmIn.length - byteOff);
                        ByteBuffer ib = enc.getInputBuffer(inIdx);
                        ib.clear();
                        ib.put(pcmIn, byteOff, n);
                        long ptsUs = (byteOff / (ch * 2)) * 1_000_000L / sr;
                        enc.queueInputBuffer(inIdx, 0, n, ptsUs, 0);
                        byteOff += n;
                    } else {
                        long ptsUs = (long) totalFrames * 1_000_000L / sr;
                        enc.queueInputBuffer(inIdx, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        sentEos = true;
                    }
                }
            }

            int outIdx = enc.dequeueOutputBuffer(info, 20_000);
            if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                outFmt = enc.getOutputFormat();
                asc = toBytes(outFmt.getByteBuffer("csd-0"));
                check(asc.length == 2, "AAC ASC csd-0 expected 2 bytes, got " + asc.length);
                sawFmt = true;
            } else if (outIdx >= 0) {
                ByteBuffer ob = enc.getOutputBuffer(outIdx);
                byte[] copy = new byte[info.size];
                if (info.size > 0) {
                    ByteBuffer dup = ob.duplicate();
                    dup.position(info.offset);
                    dup.limit(info.offset + info.size);
                    dup.get(copy);
                }
                if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) sawCodecCfg = true;
                aacPackets.add(new Packet(copy, info.presentationTimeUs, info.flags));
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) sawEos = true;
                enc.releaseOutputBuffer(outIdx, false);
            }
        }
        enc.stop();
        enc.release();

        check(sawFmt && sawCodecCfg && sawEos, "AAC encode missing fmt/codecConfig/eos");

        // Now decode encoded AAC packets back to PCM using MediaCodec AAC decoder!
        MediaCodec dec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
        dec.configure(outFmt, null, null, 0);
        dec.start();

        ByteArrayOutputStream pcmOut = new ByteArrayOutputStream();
        boolean decInputDone = false, decOutDone = false;
        int pktIdx = 0;
        deadline = System.currentTimeMillis() + 10_000L;
        while (!decOutDone && System.currentTimeMillis() < deadline) {
            if (!decInputDone && pktIdx < aacPackets.size()) {
                int inIdx = dec.dequeueInputBuffer(5_000);
                if (inIdx >= 0) {
                    Packet p = aacPackets.get(pktIdx++);
                    ByteBuffer ib = dec.getInputBuffer(inIdx);
                    ib.clear();
                    if (p.data.length > 0) ib.put(p.data);
                    dec.queueInputBuffer(inIdx, 0, p.data.length, p.ptsUs, p.flags);
                    if ((p.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) decInputDone = true;
                }
            } else if (!decInputDone) {
                int inIdx = dec.dequeueInputBuffer(5_000);
                if (inIdx >= 0) {
                    dec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                    decInputDone = true;
                }
            }

            int outIdx = dec.dequeueOutputBuffer(info, 20_000);
            if (outIdx >= 0) {
                ByteBuffer ob = dec.getOutputBuffer(outIdx);
                if (info.size > 0 && ob != null) {
                    byte[] tmp = new byte[info.size];
                    ByteBuffer dup = ob.duplicate();
                    dup.position(info.offset);
                    dup.limit(info.offset + info.size);
                    dup.get(tmp);
                    pcmOut.write(tmp);
                }
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) decOutDone = true;
                dec.releaseOutputBuffer(outIdx, false);
            }
        }
        dec.stop();
        dec.release();

        byte[] decodedBytes = pcmOut.toByteArray();
        check(decOutDone, "AAC decoder did not reach EOS");
        check(decodedBytes.length >= totalFrames * ch, "decoded PCM unexpectedly short: " + decodedBytes.length);

        ByteBuffer db = ByteBuffer.wrap(decodedBytes).order(ByteOrder.LITTLE_ENDIAN);
        int samples = decodedBytes.length / 2;
        double sumSq = 0;
        int maxAbs = 0;
        for (int i = 0; i < samples; i++) {
            int s = db.getShort();
            int a = Math.abs(s);
            if (a > maxAbs) maxAbs = a;
            sumSq += (double) s * s;
        }
        double rms = Math.sqrt(sumSq / Math.max(1, samples));
        check(maxAbs > 4000 && rms > 2000.0,
                "AAC decoded PCM signal too weak: maxAbs=" + maxAbs + " rms=" + rms);
        System.out.println("[PASS] AAC encode->decode round-trip: asc="
                + String.format("%02x%02x", asc[0], asc[1])
                + " decodedBytes=" + decodedBytes.length + " maxAbs=" + maxAbs + " rms=" + (int) rms);
    }

    private static void testDiscoveryAndNegativeContracts() throws Exception {
        MediaCodecList list = new MediaCodecList(MediaCodecList.REGULAR_CODECS);
        boolean foundAacEnc = false, foundAvcEnc = false, foundAvcDec = false;
        for (MediaCodecInfo info : list.getCodecInfos()) {
            if ("OMX.macapk.enc.audio/mp4a-latm".equals(info.getName())) {
                check(info.isEncoder(), "AAC encoder must report isEncoder=true");
                MediaCodecInfo.CodecCapabilities caps =
                        info.getCapabilitiesForType(MediaFormat.MIMETYPE_AUDIO_AAC);
                check(caps.getEncoderCapabilities() != null, "AAC encoder caps must expose EncoderCapabilities");
                check(caps.getEncoderCapabilities().isBitrateModeSupported(
                        MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR), "CBR supported");
                foundAacEnc = true;
            }
            if ("OMX.macapk.enc.video/avc".equals(info.getName())) {
                check(info.isEncoder(), "AVC encoder must report isEncoder=true");
                MediaCodecInfo.CodecCapabilities caps =
                        info.getCapabilitiesForType("video/avc");
                check(caps.colorFormats.length >= 4, "AVC encoder must list colorFormats");
                check(caps.getEncoderCapabilities() != null, "AVC encoder caps must expose EncoderCapabilities");
                foundAvcEnc = true;
            }
            if ("OMX.macapk.dec.video/avc".equals(info.getName())) {
                check(!info.isEncoder(), "AVC decoder must report isEncoder=false");
                foundAvcDec = true;
            }
        }
        check(foundAacEnc && foundAvcEnc && foundAvcDec,
                "MediaCodecList missing expected codecs: aacEnc=" + foundAacEnc
                + " avcEnc=" + foundAvcEnc + " avcDec=" + foundAvcDec);

        MediaFormat avcFmt = MediaFormat.createVideoFormat("video/avc", 640, 480);
        avcFmt.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        check("OMX.macapk.enc.video/avc".equals(list.findEncoderForFormat(avcFmt)),
                "findEncoderForFormat should find OMX.macapk.enc.video/avc");
        check("OMX.macapk.dec.video/avc".equals(list.findDecoderForFormat(
                MediaFormat.createVideoFormat("video/avc", 640, 480))),
                "findDecoderForFormat should still find decoder");

        // Negative contracts
        boolean npe = false;
        try { MediaCodec.createEncoderByType(null); } catch (NullPointerException e) { npe = true; }
        check(npe, "createEncoderByType(null) must throw NPE");

        boolean ioe = false;
        try { MediaCodec.createEncoderByType("video/unknown"); } catch (IOException e) { ioe = true; }
        check(ioe, "createEncoderByType(\"video/unknown\") must throw IOException");

        // createInputSurface before configure
        MediaCodec rawEnc = MediaCodec.createEncoderByType("video/avc");
        boolean iseBeforeCfg = false;
        try { rawEnc.createInputSurface(); } catch (IllegalStateException e) { iseBeforeCfg = true; }
        check(iseBeforeCfg, "createInputSurface before configure must throw IllegalStateException");
        rawEnc.release();

        // createInputSurface on decoder
        MediaCodec dec = MediaCodec.createDecoderByType("video/avc");
        dec.configure(MediaFormat.createVideoFormat("video/avc", 320, 240), null, null, 0);
        boolean iseDec = false;
        try { dec.createInputSurface(); } catch (IllegalStateException e) { iseDec = true; }
        check(iseDec, "createInputSurface on decoder must throw IllegalStateException");
        dec.release();

        // createInputSurface on audio encoder
        MediaCodec aenc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
        aenc.configure(MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, 44100, 2),
                null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        boolean iseAudio = false;
        try { aenc.createInputSurface(); } catch (IllegalStateException e) { iseAudio = true; }
        check(iseAudio, "createInputSurface on audio encoder must throw IllegalStateException");
        aenc.release();

        // createInputSurface after start() & called twice & signalEndOfInputStream before start
        MediaCodec venc = MediaCodec.createEncoderByType("video/avc");
        MediaFormat vfmt = MediaFormat.createVideoFormat("video/avc", 320, 240);
        vfmt.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        venc.configure(vfmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        boolean iseSigEarly = false;
        try { venc.signalEndOfInputStream(); } catch (IllegalStateException e) { iseSigEarly = true; }
        check(iseSigEarly, "signalEndOfInputStream before start() must throw IllegalStateException");
        Surface s1 = venc.createInputSurface();
        check(s1 != null, "first createInputSurface failed");
        boolean iseTwice = false;
        try { venc.createInputSurface(); } catch (IllegalStateException e) { iseTwice = true; }
        check(iseTwice, "second createInputSurface must throw IllegalStateException");
        venc.release();

        MediaCodec vencStarted = MediaCodec.createEncoderByType("video/avc");
        MediaFormat vfmt2 = MediaFormat.createVideoFormat("video/avc", 320, 240);
        vfmt2.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar);
        vencStarted.configure(vfmt2, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        vencStarted.start();
        boolean iseAfterStart = false;
        try { vencStarted.createInputSurface(); } catch (IllegalStateException e) { iseAfterStart = true; }
        check(iseAfterStart, "createInputSurface after start() must throw IllegalStateException");
        boolean iseSigNoSurf = false;
        try { vencStarted.signalEndOfInputStream(); } catch (IllegalStateException e) { iseSigNoSurf = true; }
        check(iseSigNoSurf, "signalEndOfInputStream without input Surface must throw IllegalStateException");
        vencStarted.stop();
        vencStarted.release();

        System.out.println("[PASS] MediaCodecList discovery & negative contracts verified");
    }

    public static void main(String[] args) throws Exception {
        String jniLib = (args != null && args.length > 0 && !args[0].isEmpty())
                ? args[0]
                : System.getProperty("macapk.media.jni.lib", "");
        if (!jniLib.isEmpty()) {
            System.load(jniLib);
        }

        testDiscoveryAndNegativeContracts();
        testAacAudioRoundTrip();
        testH264ByteBufferRoundTrip(MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar, "I420");
        testH264ByteBufferRoundTrip(MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar, "NV12");
        testH264ByteBufferRoundTrip(MediaCodecInfo.CodecCapabilities.COLOR_Format32bitBGRA8888, "BGRA32");
        testH264ByteBufferRoundTrip(MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible, "YUV420Flexible/getInputImage");
        testH264InputSurfaceCanvasAndQueue();
        testAsyncSurfaceAndPersistentInputSurface();
        testAsyncAacEncoder();
        testVideoEncoderFlush();
        testHevcRoundTripIfSupported();
        System.out.println("ALL MediaCodecEncoderTest CHECKS PASSED");
    }
}
