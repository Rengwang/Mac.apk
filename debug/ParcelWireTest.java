import android.os.*;
import java.io.*;

public class ParcelWireTest {
    public static void main(String[] args) throws Exception {
        Binder binder = new Binder();
        Parcel.MacapkBinderHandles codec = new Parcel.MacapkBinderHandles() {
            public long exportBinder(IBinder b) { return b == binder ? 1 : 0; }
            public IBinder importBinder(long h) { return h == 1 ? binder : null; }
        };
        Parcel input = Parcel.obtain(); input.writeInt(42); input.writeStrongBinder(binder);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        MacapkParcelWire.write(new DataOutputStream(bytes), input, codec);
        Parcel result = Parcel.obtain();
        MacapkParcelWire.read(new DataInputStream(new ByteArrayInputStream(bytes.toByteArray())), result, codec);
        if (result.readInt() != 42 || result.readStrongBinder() != binder) throw new AssertionError();
        byte[] truncated = java.util.Arrays.copyOf(bytes.toByteArray(), bytes.size() - 1);
        try {
            MacapkParcelWire.read(new DataInputStream(new ByteArrayInputStream(truncated)), result, codec);
            throw new AssertionError("accepted truncated frame");
        } catch (EOFException expected) { }
        ByteArrayOutputStream bad = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bad);
        out.writeInt(0x4d504331); out.writeInt(Integer.MAX_VALUE); out.writeInt(0);
        try {
            MacapkParcelWire.read(new DataInputStream(new ByteArrayInputStream(bad.toByteArray())), result, codec);
            throw new AssertionError("accepted oversized frame");
        } catch (IOException expected) { }
        System.out.println("PASS: Parcel frame round trip and malformed input");
    }
}
