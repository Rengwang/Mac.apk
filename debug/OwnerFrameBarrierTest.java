package android.os;

/** Regression for the vertical-fullscreen return stall: a private UI frame
 * task must remain owner-only yet cross a Choreographer sync barrier, while
 * ordinary synchronous and asynchronous guest semantics stay unchanged. */
public final class OwnerFrameBarrierTest {
    private static Message message(Handler target, int what) {
        Message m = Message.obtain();
        m.target = target;
        m.what = what;
        return m;
    }

    private static void expect(Message got, int what) {
        if (got == null) throw new AssertionError("expected message " + what);
        if (got.what != what)
            throw new AssertionError("expected " + what + " got " + got.what);
        got.recycleUnchecked();
    }

    public static void main(String[] args) {
        // The queue contract depends only on Handler's private scheduling
        // markers. A null Looper keeps this focused test independent of the
        // native logging/runtime library loaded by Looper.getMainLooper().
        Handler ownerFrame = new Handler((Looper) null);
        ownerFrame.mMacapkOwnerOnly = true;
        ownerFrame.mMacapkBarrierExempt = true;
        Handler ownerLifecycle = new Handler((Looper) null);
        ownerLifecycle.mMacapkOwnerOnly = true;
        Handler asyncGuest = new Handler((Looper) null);
        Handler syncGuest = new Handler((Looper) null);

        MessageQueue q = new MessageQueue();
        q.postSyncBarrier();
        long now = SystemClock.uptimeMillis();

        Message async = message(asyncGuest, 1);
        async.setAsynchronous(true);
        q.enqueueMessage(async, now);
        Message owner = message(ownerFrame, 2);
        q.enqueueMessage(owner, now);
        Message lifecycle = message(ownerLifecycle, 3);
        q.enqueueMessage(lifecycle, now);
        Message ordinary = message(syncGuest, 4);
        q.enqueueMessage(ordinary, now);

        expect(q.macapkPollDueForMainLoop(), 1);
        expect(q.macapkPollDueForMainLoop(), 2);
        if (q.macapkPollDueForMainLoop() != null)
            throw new AssertionError("ordinary work crossed the sync barrier");

        MessageQueue osMainQueue = new MessageQueue();
        osMainQueue.postSyncBarrier();
        osMainQueue.enqueueMessage(message(ownerFrame, 5), now);
        if (osMainQueue.macapkPollDueSkippingOwnerOnly() != null)
            throw new AssertionError("OS-main drain stole an owner frame");

        MessageQueue future = new MessageQueue();
        long futureAt = SystemClock.uptimeMillis() + 60_000;
        future.postSyncBarrier();
        future.enqueueMessage(message(ownerFrame, 6), futureAt);
        int ownerTimeout = future.macapkNextPollTimeoutMillisForMainLoop();
        if (ownerTimeout <= 0 || ownerTimeout > 60_000)
            throw new AssertionError("main loop timeout ignored owner frame: "
                    + ownerTimeout);
        if (future.nextPollTimeoutMillis() != -1)
            throw new AssertionError("generic timeout exposed owner frame");

        System.out.println("PASS: owner frame crosses barrier; sync and lifecycle stay blocked");
    }
}
