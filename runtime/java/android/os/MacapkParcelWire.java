package android.os;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

/** Bounded framing for Parcel data on a private Binder transport connection.
 * This does not provide a connection, Binder routing, or file descriptor transfer. */
public final class MacapkParcelWire {
    private static final int MAGIC = 0x4d504331;
    private static final int MAX_BYTES = 1024 * 1024;
    private static final int MAX_OBJECTS = 65536;
    private MacapkParcelWire() {}

    public static void write(DataOutput out, Parcel parcel, Parcel.MacapkBinderHandles codec)
            throws IOException {
        Parcel.MacapkTransportData data = parcel.macapkExportForBinder(codec);
        if (data.bytes.length > MAX_BYTES || data.offsets.length > MAX_OBJECTS)
            throw new IOException("Parcel exceeds transport limits");
        out.writeInt(MAGIC);
        out.writeInt(data.bytes.length);
        out.writeInt(data.offsets.length);
        out.write(data.bytes);
        for (int i = 0; i < data.offsets.length; i++) {
            out.writeInt(data.offsets[i]);
            out.writeLong(data.handles[i]);
        }
    }

    public static void read(DataInput in, Parcel parcel, Parcel.MacapkBinderHandles codec)
            throws IOException {
        if (in.readInt() != MAGIC) throw new IOException("invalid Parcel frame");
        int size = in.readInt(), count = in.readInt();
        if (size < 0 || size > MAX_BYTES || count < 0 || count > MAX_OBJECTS
                || count > size / 4)
            throw new IOException("invalid Parcel frame size");
        byte[] bytes = new byte[size];
        int[] offsets = new int[count];
        long[] handles = new long[count];
        in.readFully(bytes);
        for (int i = 0; i < count; i++) {
            offsets[i] = in.readInt();
            handles[i] = in.readLong();
        }
        try { parcel.macapkImportFromBinder(bytes, offsets, handles, codec); }
        catch (IllegalArgumentException e) { throw new IOException("invalid Binder object", e); }
    }
}
