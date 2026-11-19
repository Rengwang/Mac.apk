import android.graphics.SurfaceTexture;
import android.os.Binder;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Parcel;
import android.os.RemoteException;
import android.view.Surface;
import java.io.FileDescriptor;
import java.util.HashMap;
import java.util.Map;

/** Regression test: android.view.Surface must marshal via a Binder sink token
 *  rather than an empty body, preserving local ownership in-process and
 *  forwarding sink state/geometry over IBinder across processes. */
public class SurfaceParcelTransportTest {
    private static void check(boolean cond, String msg) {
        if (!cond) throw new AssertionError(msg);
    }

    /** Wraps a real Binder into a distinct non-Binder IBinder, mirroring how
     *  MacApkBinderTransport produces a BinderProxy in another process. */
    private static final class MockProxyBinder implements IBinder {
        private final IBinder target;
        MockProxyBinder(IBinder target) { this.target = target; }

        @Override public String getInterfaceDescriptor() throws RemoteException { return target.getInterfaceDescriptor(); }
        @Override public boolean pingBinder() { return target.pingBinder(); }
        @Override public boolean isBinderAlive() { return target.isBinderAlive(); }
        @Override public IInterface queryLocalInterface(String descriptor) { return null; }
        @Override public void dump(FileDescriptor fd, String[] args) {}
        @Override public void dumpAsync(FileDescriptor fd, String[] args) {}
        @Override public void linkToDeath(DeathRecipient recipient, int flags) {}
        @Override public boolean unlinkToDeath(DeathRecipient recipient, int flags) { return false; }

        @Override
        public boolean transact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
            byte[] bytes = data.marshall();
            Parcel rx = Parcel.obtain();
            try {
                rx.unmarshall(bytes, 0, bytes.length);
                rx.setDataPosition(0);
                return target.transact(code, rx, reply, flags);
            } finally {
                rx.recycle();
                if (reply != null) reply.setDataPosition(0);
            }
        }
    }

    public static void main(String[] args) {
        SurfaceTexture st = new SurfaceTexture(false);
        st.setDefaultBufferSize(1280, 720);
        Surface orig = new Surface(st);

        // 1. Same-process Parcel round trip preserves owner SurfaceTexture cleanly.
        Parcel sameP = Parcel.obtain();
        sameP.writeParcelable(orig, 0);
        sameP.setDataPosition(0);
        Surface sameProc = sameP.readParcelable(Surface.class.getClassLoader());
        check(sameProc != null, "same-process unparceled Surface is null");
        check(!sameProc.macapkIsRemote(), "same-process Surface marked remote");
        check(sameProc.macapkGetSurfaceTexture() == st, "same-process lost SurfaceTexture");
        check(sameProc.macapkGetNativeWindowOwnSizePacked() == ((1280L << 32) | 720L),
                "same-process lost buffer size");
        sameP.recycle();

        // 2. Cross-process Binder transport simulation via macapkExportForBinder / Import.
        Map<Long, IBinder> exported = new HashMap<>();
        long[] nextHandle = { 100L };
        Parcel.MacapkBinderHandles codec = new Parcel.MacapkBinderHandles() {
            @Override
            public long exportBinder(IBinder b) {
                check(b instanceof Binder, "Surface must write a Binder token, got " + b);
                long h = ++nextHandle[0];
                exported.put(h, new MockProxyBinder(b));
                return h;
            }
            @Override
            public IBinder importBinder(long handle) {
                return exported.get(handle);
            }
        };

        Parcel tx = Parcel.obtain();
        tx.writeParcelable(orig, 0);
        Parcel.MacapkTransportData wire = tx.macapkExportForBinder(codec);
        check(wire.handles.length == 1, "expected 1 exported binder token, got " + wire.handles.length);
        tx.recycle();

        Parcel rx = Parcel.obtain();
        rx.macapkImportFromBinder(wire.bytes, wire.offsets, wire.handles, codec);
        Surface remote = rx.readParcelable(Surface.class.getClassLoader());
        rx.recycle();

        check(remote != null && remote.isValid(), "remote Surface invalid");
        check(remote.macapkIsRemote(), "cross-process Surface must report macapkIsRemote()=true");
        check(remote.macapkGetSurfaceTexture() == null, "remote Surface leaked local SurfaceTexture");
        long remoteSize = remote.macapkGetNativeWindowOwnSizePacked();
        check(remoteSize == ((1280L << 32) | 720L),
                "remote Surface own size mismatch: 0x" + Long.toHexString(remoteSize));

        // 3. Remote sink activation registers the owning SurfaceTexture in the owner process.
        SurfaceTexture.macapkUnregisterVideoTarget(null);
        check(!SurfaceTexture.macapkHasActiveVideoTarget(), "expected no active video target initially");
        remote.macapkNoteVideoSinkActive(true);
        check(SurfaceTexture.macapkHasActiveVideoTarget(),
                "remote video sink activation did not register owner SurfaceTexture");
        remote.macapkNoteVideoSinkActive(false);
        check(!SurfaceTexture.macapkHasActiveVideoTarget(),
                "remote video sink deactivation did not unregister owner SurfaceTexture");

        // 4. Direct writeToParcel + CREATOR + released state.
        orig.release();
        Parcel relTx = Parcel.obtain();
        orig.writeToParcel(relTx, 0);
        Parcel.MacapkTransportData relWire = relTx.macapkExportForBinder(codec);
        relTx.recycle();
        Parcel relRx = Parcel.obtain();
        relRx.macapkImportFromBinder(relWire.bytes, relWire.offsets, relWire.handles, codec);
        Surface relRemote = Surface.CREATOR.createFromParcel(relRx);
        relRx.recycle();
        check(relRemote != null && !relRemote.isValid(), "released Surface did not round-trip as invalid");

        st.release();
        System.out.println("PASS: SurfaceParcelTransportTest");
    }
}
