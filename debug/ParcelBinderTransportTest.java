import android.os.*;
import java.util.*;
public final class ParcelBinderTransportTest {
  public static void main(String[] args) {
    Binder a=new Binder(), b=new Binder();
    Map<Long,IBinder> table=new HashMap<>(); table.put(11L,a); table.put(22L,b);
    Parcel.MacapkBinderHandles codec=new Parcel.MacapkBinderHandles() {
      public long exportBinder(IBinder x) { return x==a ? 11 : x==b ? 22 : 0; }
      public IBinder importBinder(long h) { return table.get(h); }
    };
    Parcel p=Parcel.obtain(); p.writeInt(37); p.writeStrongBinder(a); p.writeStrongBinder(b); p.writeString("ok");
    Parcel.MacapkTransportData wire=p.macapkExportForBinder(codec);
    Parcel q=Parcel.obtain(); q.macapkImportFromBinder(wire.bytes,wire.offsets,wire.handles,codec);
    if(q.readInt()!=37 || q.readStrongBinder()!=a || q.readStrongBinder()!=b || !"ok".equals(q.readString())) throw new AssertionError("transport round trip");
    try { q.macapkImportFromBinder(wire.bytes,new int[]{wire.offsets[0],wire.offsets[0]},new long[]{11,22},codec); throw new AssertionError("duplicate accepted"); }
    catch(IllegalArgumentException expected) {}
    q.setDataPosition(0); if(q.readInt()!=37 || q.readStrongBinder()!=a) throw new AssertionError("failed import changed parcel");
    try { q.macapkImportFromBinder(wire.bytes,new int[]{wire.offsets[0]},new long[]{99},codec); throw new AssertionError("unknown handle accepted"); }
    catch(IllegalArgumentException expected) {}
    Parcel overwritten=Parcel.obtain(); overwritten.writeStrongBinder(a); overwritten.setDataPosition(0); overwritten.writeInt(123);
    if(overwritten.macapkExportForBinder(codec).offsets.length != 0) throw new AssertionError("overwritten Binder retained");
    Parcel replaced=Parcel.obtain(); replaced.writeStrongBinder(a); replaced.setDataPosition(0); replaced.writeStrongBinder(b);
    Parcel.MacapkTransportData replacement=replaced.macapkExportForBinder(codec);
    if(replacement.offsets.length != 1 || replacement.handles[0] != 22) throw new AssertionError("Binder replacement invalid");
    Parcel self=Parcel.obtain(); self.writeStrongBinder(a); self.writeInt(0);
    self.setDataPosition(0); self.appendFrom(self,0,4);
    Parcel.MacapkTransportData copied=self.macapkExportForBinder(codec);
    if(copied.offsets.length != 1 || copied.handles[0] != 11) throw new AssertionError("self-copy lost Binder");
    replaced.setDataSize(2); replaced.setDataSize(4);
    if(replaced.macapkExportForBinder(codec).offsets.length != 0) throw new AssertionError("truncated Binder retained");
    System.out.println("PASS: Binder transport round trip, malformed messages, overwrite and truncation");
  }
}
