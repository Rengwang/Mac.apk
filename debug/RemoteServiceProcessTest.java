import android.app.Service;
import android.app.ServiceRegistry;
import android.content.*;
import android.content.pm.*;
import android.os.*;
import java.util.*;

/** Regression requirement: a manifest-declared worker has process-local statics.
 * Runs without any application-specific classes or downloaded assets.
 */
public class RemoteServiceProcessTest {
    static int processSingleton = 17;
    public static class Worker extends Service {
        public void onCreate() { processSingleton = 42; }
        public IBinder onBind(Intent intent) {
            return new Binder() {
                protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
                    reply.writeInt(ProcessIdentity.pid());
                    reply.writeInt(processSingleton);
                    reply.writeInt(Binder.getCallingPid());
                    return true;
                }
            };
        }
    }
    static class ProcessIdentity { static int pid() { return android.os.Process.myPid(); } }
    static class PM extends PackageManager {
        public PackageInfo getPackageInfo(String n,int f) { return new PackageInfo(); }
        public ApplicationInfo getApplicationInfo(String n,int f) { return new ApplicationInfo(); }
        public List<ResolveInfo> queryIntentActivities(Intent i,int f) { return List.of(); }
        public int checkPermission(String a,String b) { return PERMISSION_GRANTED; }
        public boolean hasSystemFeature(String n) { return false; }
        public String getInstallerPackageName(String n) { return null; }
        public ProviderInfo getProviderInfo(ComponentName c,int f) throws NameNotFoundException { throw new NameNotFoundException(); }
        public ServiceInfo getServiceInfo(ComponentName c,int f) {
            ServiceInfo s = new ServiceInfo(); s.name=c.getClassName();
            s.packageName="test.remote"; s.processName="test.remote:worker"; s.enabled=true;
            return s;
        }
    }
    static class Ctx extends ContextWrapper {
        final PackageManager pm = new PM();
        Ctx() { super(null); }
        public Context getApplicationContext() { return this; }
        public PackageManager getPackageManager() { return pm; }
        public String getPackageName() { return "test.remote"; }
    }
    public static void main(String[] args) throws Exception {
        Ctx ctx=new Ctx(); int clientPid=ProcessIdentity.pid();
        Intent i=new Intent().setComponent(new ComponentName("test.remote",Worker.class.getName()));
        IBinder binder=ServiceRegistry.bind(ctx,i);
        if(binder==null) throw new AssertionError("declared worker did not bind");
        Parcel request=Parcel.obtain(), reply=Parcel.obtain();
        binder.transact(1,request,reply,0);
        int workerPid=reply.readInt(), workerState=reply.readInt(), callerPid=reply.readInt();
        request.recycle(); reply.recycle();
        System.out.println("clientPid="+clientPid+" workerPid="+workerPid+
            " clientState="+processSingleton+" workerState="+workerState+" callerPid="+callerPid);
        if(workerPid==clientPid || processSingleton!=17 || workerState!=42 || callerPid!=clientPid)
            throw new AssertionError("remote service lacks process identity/static isolation");
        System.out.println("PASS: worker process isolation and Binder caller identity");
    }
}
