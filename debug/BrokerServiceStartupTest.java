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
        testOpenAppTeardownAndMainRespawnGuard(fixture);
    }

    private static void testOpenAppTeardownAndMainRespawnGuard(Path fixture) throws Exception {
        Path xml = fixture.resolve("quit-manifest.xml");
        Files.writeString(xml, """
            <manifest xmlns:android="http://schemas.android.com/apk/res/android" package="test.quit">
              <application>
                <activity android:name="test.quit.MainActivity" android:exported="true">
                  <intent-filter>
                    <action android:name="android.intent.action.MAIN"/>
                    <category android:name="android.intent.category.LAUNCHER"/>
                  </intent-filter>
                </activity>
                <provider android:name="test.quit.MainProvider" android:authorities="test.quit.auth" android:exported="false"/>
                <service android:name="test.quit.MainService" android:exported="false"/>
                <service android:name="test.quit.WorkerService" android:process=":worker" android:exported="false"/>
              </application>
            </manifest>
            """);
        Manifest manifest = Manifest.parse(xml.toFile());
        SystemServer server = new SystemServer();
        Map<String, SystemServer.PackageRecord> packages = field(server, "packages");
        packages.put("test.quit", new SystemServer.PackageRecord("test.quit", "", manifest));
        Map<Long, Process> spawned = field(server, "spawned");
        Map<Long, String> spawnedPackages = field(server, "spawnedPackages");
        Map<String, Object> running = field(server, "running");
        Map<String, Object> starting = field(server, "starting");
        Map<String, Long> tearingDown = field(server, "tearingDownPackages");
        java.util.Set<Long> victimPids = field(server, "tearingDownVictimPids");
        Binder stub = server.new Stub();

        // 1. Same-package secondary process must not cold-spawn launchable app's main process.
        Parcel pIn = request(), pOut = Parcel.obtain();
        try {
            pIn.writeString("test.quit");
            pIn.writeString("test.quit.auth");
            stub.transact(SystemServer.TX_GET_PROVIDER, pIn, pOut, 0);
            pOut.readException();
            if (pOut.readStrongBinder() != null) throw new AssertionError("expected null provider on dead main process");
        } finally { pIn.recycle(); pOut.recycle(); }
        Parcel bIn = request(), bOut = Parcel.obtain();
        try {
            bIn.writeString("test.quit");
            bIn.writeTypedObject(new Intent().setComponent(new ComponentName("test.quit", "test.quit.MainService")), 0);
            stub.transact(SystemServer.TX_BIND, bIn, bOut, 0);
            bOut.readException();
            if (bOut.readInt() != 0) throw new AssertionError("expected bind failure on dead main process");
        } finally { bIn.recycle(); bOut.recycle(); }
        if (!starting.isEmpty() || !spawned.isEmpty()) {
            throw new AssertionError("same-package caller triggered unexpected main-process launch");
        }

        // 2. Deterministic race check: late TX_REGISTER from a spawned child mid-teardown
        // must still see wasSpawned=true, must not attach as [open app], and must not clear tearingDownPackages.
        Process lateChild = new ProcessBuilder("/bin/sleep", "30").start();
        try {
            long latePid = lateChild.pid();
            spawned.put(latePid, lateChild);
            spawnedPackages.put(latePid, "test.quit");
            victimPids.add(latePid);
            tearingDown.put("test.quit", 99L);

            server.register("test.quit", "test.quit:worker", new Binder(), (int) latePid);
            if (running.containsKey("test.quit:worker")) {
                throw new AssertionError("late spawned child attached during teardown");
            }
            if (!Long.valueOf(99L).equals(tearingDown.get("test.quit"))) {
                throw new AssertionError("late spawned child cleared tearingDownPackages");
            }
            long killDeadline = System.currentTimeMillis() + 3000L;
            while (lateChild.isAlive() && System.currentTimeMillis() < killDeadline) Thread.sleep(20);
            if (lateChild.isAlive()) throw new AssertionError("late spawned child was not force-killed");
        } finally {
            if (lateChild.isAlive()) lateChild.destroyForcibly();
            spawned.clear();
            spawnedPackages.clear();
            victimPids.clear();
            tearingDown.clear();
        }

        // 3. Full linkToDeath teardown flow kills spawned sibling processes and cleans state.
        Process sibling = new ProcessBuilder("/bin/sleep", "30").start();
        try {
            long sibPid = sibling.pid();
            spawned.put(sibPid, sibling);
            spawnedPackages.put(sibPid, "test.quit");

            final IBinder.DeathRecipient[] deathHolder = new IBinder.DeathRecipient[1];
            Binder openApp = new Binder() {
                @Override public void linkToDeath(DeathRecipient recipient, int flags) {
                    deathHolder[0] = recipient;
                }
            };
            server.register("test.quit", "test.quit", openApp, 999998);
            if (deathHolder[0] == null) throw new AssertionError("linkToDeath not registered");
            deathHolder[0].binderDied();

            long deadline = System.currentTimeMillis() + 5000L;
            while ((sibling.isAlive() || !tearingDown.isEmpty()) && System.currentTimeMillis() < deadline) {
                Thread.sleep(20);
            }
            if (sibling.isAlive()) throw new AssertionError("sibling process survived open app death");
            if (!spawned.isEmpty() || !spawnedPackages.isEmpty() || !victimPids.isEmpty() || !tearingDown.isEmpty()) {
                throw new AssertionError("teardown left stale broker tracking entries");
            }
        } finally {
            if (sibling.isAlive()) sibling.destroyForcibly();
            try { ((ExecutorService) field(server, "serviceRequests")).shutdownNow(); }
            catch (NoSuchFieldException ignored) {}
        }
        System.out.println("PASS: same-package main respawn refused; late-register race and sibling teardown verified");
    }
}
