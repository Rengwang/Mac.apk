import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.Message;
import android.os.MessageQueue;
import android.os.SystemClock;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Verifies AudioTrack.getMinBufferSize 80 ms floor (>= 2048 frames) and
 * MessageQueue delayed wake / poll timeout ordering under bare JVM.
 */
public class AudioTrackClockMonotonicityTest {
    public static void main(String[] args) throws Exception {
        int min48kStereo = AudioTrack.getMinBufferSize(
                48000, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT);
        int expected48k = ((48000 * 2) / 25) * 2 * 2; // 3840 frames * 4 bytes = 15360 bytes (80 ms)
        if (min48kStereo != expected48k) {
            throw new AssertionError("expected min buffer " + expected48k + " got " + min48kStereo);
        }

        int min16kMono = AudioTrack.getMinBufferSize(
                16000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
        int expected16k = 2048 * 1 * 2; // floored at 2048 frames
        if (min16kMono != expected16k) {
            throw new AssertionError("expected mono 16k min buffer " + expected16k + " got " + min16kMono);
        }

        // Verify MessageQueue wake/timeout invariants when inserting delayed messages
        // strictly behind an earlier head vs inserting a new earlier head.
        Constructor<MessageQueue> mqCtor = MessageQueue.class.getDeclaredConstructor();
        mqCtor.setAccessible(true);
        MessageQueue mq = mqCtor.newInstance();

        AtomicInteger wakerCount = new AtomicInteger(0);
        Method setWaker = MessageQueue.class.getDeclaredMethod("setWaker", Runnable.class);
        setWaker.setAccessible(true);
        setWaker.invoke(mq, (Runnable) wakerCount::incrementAndGet);

        Method enqueue = MessageQueue.class.getDeclaredMethod("enqueueMessage", Message.class, long.class);
        enqueue.setAccessible(true);
        Method pollDue = MessageQueue.class.getDeclaredMethod("macapkPollDue");
        pollDue.setAccessible(true);
        Method nextTimeout = MessageQueue.class.getDeclaredMethod("nextPollTimeoutMillis");
        nextTimeout.setAccessible(true);

        long now = SystemClock.uptimeMillis();
        Runnable noop = () -> {};
        Message m1 = Message.obtain();
        m1.what = 1;
        m1.callback = noop;
        enqueue.invoke(mq, m1, now + 50);
        if (wakerCount.get() != 1) {
            throw new AssertionError("expected waker on empty queue insert, got " + wakerCount.get());
        }

        // Delayed message strictly behind earlier head should NOT cause an extra native wake.
        Message m2 = Message.obtain();
        m2.what = 2;
        m2.callback = noop;
        enqueue.invoke(mq, m2, now + 200);
        if (wakerCount.get() != 1) {
            throw new AssertionError("unexpected native wake for later message behind head: " + wakerCount.get());
        }

        // Earlier message ahead of head MUST wake.
        Message m0 = Message.obtain();
        m0.what = 0;
        m0.callback = noop;
        enqueue.invoke(mq, m0, now);
        if (wakerCount.get() != 2) {
            throw new AssertionError("expected wake for new earlier head, got " + wakerCount.get());
        }

        int to0 = (Integer) nextTimeout.invoke(mq);
        if (to0 != 0) throw new AssertionError("expected immediate timeout=0, got " + to0);
        Message got0 = (Message) pollDue.invoke(mq);
        if (got0 == null || got0.what != 0) throw new AssertionError("expected what=0 first");

        Thread.sleep(60);
        Message got1 = (Message) pollDue.invoke(mq);
        if (got1 == null || got1.what != 1) throw new AssertionError("expected what=1 second");

        int to2 = (Integer) nextTimeout.invoke(mq);
        if (to2 <= 0) throw new AssertionError("expected positive remaining timeout for m2, got " + to2);

        System.out.println("AudioTrackClockMonotonicityTest PASS");
    }
}
