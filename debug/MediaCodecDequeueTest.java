import android.media.MediaCodec;
import java.lang.reflect.Field;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicInteger;

/** Dequeue timeout must wait without starving a producer or consuming CPU. */
public class MediaCodecDequeueTest {
    static Field field(String n) throws Exception {
        Field f = MediaCodec.class.getDeclaredField(n); f.setAccessible(true); return f;
    }
    static MediaCodec codec() throws Exception {
        MediaCodec c = MediaCodec.createByCodecName("test");
        field("mStarted").setBoolean(c, true);
        field("mFormatReported").setBoolean(c, true);
        return c;
    }
    static void waited(long start, int result) {
        long ms = (System.nanoTime() - start) / 1_000_000;
        if (result != MediaCodec.INFO_TRY_AGAIN_LATER || ms < 25 || ms > 1000)
            throw new AssertionError("timeout result=" + result + " elapsed=" + ms);
    }
    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Exception {
        MediaCodec c = codec();
        long t = System.nanoTime(); waited(t, c.dequeueInputBuffer(40_000));
        t = System.nanoTime(); waited(t, c.dequeueOutputBuffer(new MediaCodec.BufferInfo(), 40_000));
        t = System.nanoTime();
        if (c.dequeueInputBuffer(0) != -1 || (System.nanoTime()-t) > 20_000_000L)
            throw new AssertionError("non-blocking input");
        AtomicInteger answer = new AtomicInteger(-99);
        Thread waiter = new Thread(() -> answer.set(c.dequeueInputBuffer(-1)));
        waiter.start(); Thread.sleep(30);
        Object lock = field("mAsyncLock").get(c);
        synchronized (lock) { ((Queue<Integer>)field("mInFree").get(c)).add(7); }
        waiter.join(1000);
        if (waiter.isAlive() || answer.get() != 7) throw new AssertionError("producer starved");
        Thread stopped = new Thread(() -> answer.set(c.dequeueInputBuffer(-1)));
        stopped.start(); Thread.sleep(30); c.release(); stopped.join(1000);
        if (stopped.isAlive() || answer.get() != -1) throw new AssertionError("release did not cancel");

        // Verify async dispatch with setCallback(cb, handler), condition lock wait and flush
        MediaCodec asyncCodec = MediaCodec.createByCodecName("test-async");
        java.util.concurrent.CountDownLatch formatLatch = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch inputLatch = new java.util.concurrent.CountDownLatch(1);
        asyncCodec.setCallback(new MediaCodec.Callback() {
            @Override public void onInputBufferAvailable(MediaCodec codec, int index) {
                inputLatch.countDown();
            }
            @Override public void onOutputBufferAvailable(MediaCodec codec, int index, MediaCodec.BufferInfo info) {}
            @Override public void onError(MediaCodec codec, MediaCodec.CodecException e) {}
            @Override public void onOutputFormatChanged(MediaCodec codec, android.media.MediaFormat format) {
                formatLatch.countDown();
            }
        }, null);
        field("mStarted").setBoolean(asyncCodec, true);
        field("mAsyncRunning").setBoolean(asyncCodec, true);
        Object asyncLock = field("mAsyncLock").get(asyncCodec);
        synchronized (asyncLock) {
            ((Queue<Integer>) field("mInFree").get(asyncCodec)).add(0);
        }
        Thread asyncThread = new Thread(() -> {
            try {
                java.lang.reflect.Method m = MediaCodec.class.getDeclaredMethod("asyncDispatchLoop");
                m.setAccessible(true);
                m.invoke(asyncCodec);
            } catch (Exception ignored) {}
        }, "test-async-dispatch");
        field("mAsyncThread").set(asyncCodec, asyncThread);
        asyncThread.setDaemon(true);
        asyncThread.start();
        if (!formatLatch.await(1, java.util.concurrent.TimeUnit.SECONDS)) {
            throw new AssertionError("async onOutputFormatChanged timed out");
        }
        if (!inputLatch.await(1, java.util.concurrent.TimeUnit.SECONDS)) {
            throw new AssertionError("async onInputBufferAvailable timed out");
        }
        asyncCodec.flush();
        boolean isPaused = field("mAsyncPaused").getBoolean(asyncCodec);
        if (!isPaused) throw new AssertionError("expected mAsyncPaused=true after flush");
        asyncCodec.release();
        asyncThread.join(1000);
        if (asyncThread.isAlive()) throw new AssertionError("asyncThread did not exit on release");

        System.out.println("PASS: bounded input/output timeout, non-blocking poll, producer progress, release cancellation, async dispatch & flush");
    }
}
