package com.macapk.loader;

import android.content.ComponentName;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/** A burst of cold service binds must leave a broker receiver available for
 * the worker's REGISTER. No downloaded APK, native transport or GUI required. */
public final class BrokerServiceStartupTest {
    @SuppressWarnings("unchecked")
    static <T> T field(Object owner, String name) throws Exception {
        Field f=owner.getClass().getDeclaredField(name); f.setAccessible(true);
        return (T)f.get(owner);
    }
    static Parcel request() {
        Parcel p=Parcel.obtain(); p.writeInterfaceToken(SystemServer.DESCRIPTOR); return p;
    }
    static void sendEmptyStart(Binder stub) throws Exception {
        Parcel in=request(),out=Parcel.obtain();
        try{in.writeString("test.broker");in.writeTypedObject(null,0);stub.transact(SystemServer.TX_START,in,out,0);out.readException();}
        finally{in.recycle();out.recycle();}
    }
    public static void main(String[] args) throws Exception {
        Path fixture=Path.of(args[0]); Files.createDirectories(fixture);
        Path xml=fixture.resolve("manifest.xml");
        Files.writeString(xml,"""
            <manifest xmlns:android="http://schemas.android.com/apk/res/android" package="test.broker">
              <application><service android:name="test.broker.Worker" android:process=":worker" android:exported="false"/></application>
            </manifest>
            """);
        Manifest manifest=Manifest.parse(xml.toFile());
        SystemServer server=new SystemServer();
        Map<String,SystemServer.PackageRecord> packages=field(server,"packages");
        packages.put("test.broker",new SystemServer.PackageRecord("test.broker","",manifest));
        Map<String,CompletableFuture<SystemServer.AppRecord>> starting=field(server,"starting");
        starting.put("test.broker:worker",new CompletableFuture<>());
        Binder stub=server.new Stub(); Binder service=new Binder();
        Binder app=new Binder() {
            @Override protected boolean onTransact(int code,Parcel in,Parcel out,int flags) {
                if(code!=SystemServer.TX_SCHEDULE_BIND) throw new AssertionError("unexpected app code "+code);
                in.enforceInterface(SystemServer.THREAD_DESCRIPTOR);
                Intent i=in.readTypedObject(Intent.CREATOR);
                if(!"test.broker.Worker".equals(i.getComponent().getClassName()))throw new AssertionError("wrong component");
                out.writeNoException();out.writeStrongBinder(service);return true;
            }
        };
        int count=32;
        CountDownLatch accepted=new CountDownLatch(count), connected=new CountDownLatch(count);
        AtomicReference<Throwable> failure=new AtomicReference<>();
        ExecutorService receivers=Executors.newFixedThreadPool(16,r->{Thread t=new Thread(r,"test-binder-receiver");t.setDaemon(true);return t;});
        try {
            for(int n=0;n<count;n++)receivers.execute(()->{
                Parcel in=request(),out=Parcel.obtain();
                Binder callback=new Binder() {
                    @Override protected boolean onTransact(int code,Parcel data,Parcel reply,int flags) {
                        try {
                            data.enforceInterface(SystemServer.DESCRIPTOR+".BindResult");data.readException();
                            if(data.readInt()!=1)throw new AssertionError("bind did not resolve");
                            ComponentName name=data.readTypedObject(ComponentName.CREATOR);
                            if(!"test.broker.Worker".equals(name.getClassName())||data.readStrongBinder()!=service)throw new AssertionError("lost component/binder identity");
                            connected.countDown();reply.writeNoException();return true;
                        }catch(Throwable t){failure.compareAndSet(null,t);return false;}
                    }
                };
                try {
                    in.writeString("test.broker");in.writeTypedObject(new Intent().setComponent(new ComponentName("test.broker","test.broker.Worker")),0);
                    in.writeStrongBinder(callback);
                    stub.transact(SystemServer.TX_BIND,in,out,0);out.readException();accepted.countDown();
                }catch(Throwable t){failure.compareAndSet(null,t);}finally{in.recycle();out.recycle();}
            });
            // Old protocol parks all 16 receivers waiting for attach. REGISTER,
            // even submitted to this same pool, then has no thread to run on.
            Future<?> registration=receivers.submit(()->{
                Parcel in=request(),out=Parcel.obtain();
                try {
                    in.writeString("test.broker");in.writeStrongBinder(app);in.writeString(null);in.writeString("test.broker:worker");
                    stub.transact(SystemServer.TX_REGISTER,in,out,0);out.readException();
                }catch(Exception e){throw new RuntimeException(e);}finally{in.recycle();out.recycle();}
            });
            try{registration.get(3,TimeUnit.SECONDS);}catch(TimeoutException e){throw new AssertionError("service startup exhausted broker receivers; REGISTER could not run",e);}
            if(!accepted.await(3,TimeUnit.SECONDS)||!connected.await(3,TimeUnit.SECONDS))throw new AssertionError("missing bind acknowledgement/completion accepted="+accepted.getCount()+" connected="+connected.getCount(),failure.get());
            if(failure.get()!=null)throw new AssertionError(failure.get());
            // A client without the optional callback still reads the old
            // synchronous result when its worker is already running.
            Parcel legacy=request(), legacyReply=Parcel.obtain();
            try {
                legacy.writeString("test.broker");legacy.writeTypedObject(new Intent().setComponent(new ComponentName("test.broker","test.broker.Worker")),0);
                stub.transact(SystemServer.TX_BIND,legacy,legacyReply,0);legacyReply.readException();
                if(legacyReply.readInt()!=1)throw new AssertionError("legacy bind failed");
                legacyReply.readTypedObject(ComponentName.CREATOR);
                if(legacyReply.readStrongBinder()!=service)throw new AssertionError("legacy binder identity changed");
            }finally{legacy.recycle();legacyReply.recycle();}
            System.out.println("PASS: 32 cold binds, 16 receivers, REGISTER, legacy reply and binder identities preserved");
            ThreadPoolExecutor requests=field(server,"serviceRequests");
            CountDownLatch occupied=new CountDownLatch(4), release=new CountDownLatch(1);
            try {
                for(int n=0;n<4;n++)requests.execute(()->{occupied.countDown();try{release.await();}catch(InterruptedException e){Thread.currentThread().interrupt();}});
                if(!occupied.await(3,TimeUnit.SECONDS))throw new AssertionError("host workers did not start");
                for(int n=0;n<64;n++)sendEmptyStart(stub);
                try{sendEmptyStart(stub);throw new AssertionError("overload was silently admitted");}
                catch(RejectedExecutionException expected){}
                System.out.println("PASS: 4 occupied host workers, 64 queued starts; next request rejected explicitly");
            }finally{release.countDown();}
        } finally {
            receivers.shutdownNow();
            try{((ExecutorService)field(server,"serviceRequests")).shutdownNow();}catch(NoSuchFieldException ignored){}
        }
    }
}
