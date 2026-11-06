package com.macapk.loader;

import android.content.ComponentName;
import android.content.ServiceConnection;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Delayed completion cannot resurrect an unbound connection or overwrite a
 * later bind using that same ServiceConnection. No transport natives needed. */
public class RemoteBindCompletionTest {
    static final ComponentName NAME=new ComponentName("test.broker","test.broker.Worker");
    static class Connection implements ServiceConnection {
        int connected,nullBindings,dead;
        public void onServiceConnected(ComponentName n,IBinder b){if(!NAME.equals(n))throw new AssertionError();connected++;}
        public void onServiceDisconnected(ComponentName n){}
        public void onNullBinding(ComponentName n){nullBindings++;}
        public void onBindingDied(ComponentName n){if(!NAME.equals(n))throw new AssertionError("lost bind component on broker death");dead++;}
    }
    static class Service extends Binder {
        final List<IBinder.DeathRecipient> deaths=new ArrayList<>();
        public void linkToDeath(IBinder.DeathRecipient d,int f){deaths.add(d);}
        public boolean unlinkToDeath(IBinder.DeathRecipient d,int f){return deaths.remove(d);}
    }
    static Object[] pending(Executor deliver){return new Object[]{null,null,new AtomicBoolean(),NAME,deliver};}
    static void check(boolean v,String msg){if(!v)throw new AssertionError(msg);}
    @SuppressWarnings("unchecked")
    public static void main(String[] args)throws Exception {
        Field f=SystemClient.class.getDeclaredField("sRemote");f.setAccessible(true);
        Map<ServiceConnection,Object[]> bindings=(Map<ServiceConnection,Object[]>)f.get(null);
        Method result=SystemClient.class.getDeclaredMethod("readBindResult",ServiceConnection.class,Object[].class,Executor.class,Parcel.class);result.setAccessible(true);
        ArrayDeque<Runnable> events=new ArrayDeque<>();Executor delivery=events::add;
        Connection c=new Connection();Service service=new Service();
        java.util.function.Consumer<Object[]> finish=p->{
            Parcel data=Parcel.obtain();
            try{data.writeInt(1);data.writeTypedObject(NAME,0);data.writeStrongBinder(service);data.setDataPosition(0);result.invoke(null,c,p,delivery,data);}
            catch(ReflectiveOperationException e){throw new RuntimeException(e);}finally{data.recycle();}
        };
        Object[] old=pending(delivery);bindings.put(c,old);SystemClient.unbindRemote(c);finish.accept(old);
        check(events.isEmpty()&&!bindings.containsKey(c),"late completion resurrected canceled bind");
        Object[] newer=pending(delivery);bindings.put(c,newer);finish.accept(old);
        check(bindings.get(c)==newer&&events.isEmpty(),"stale completion changed newer generation");
        finish.accept(newer);finish.accept(newer);
        check(events.size()==1&&service.deaths.size()==1,"duplicate completion/death registration");
        SystemClient.unbindRemote(c);events.remove().run();
        check(c.connected==0&&service.deaths.isEmpty(),"queued callback survived unbind");
        Object[] valid=pending(delivery);bindings.put(c,valid);finish.accept(valid);events.remove().run();
        check(c.connected==1,"valid callback missing");
        // Replace generation before the old service dies. Its death must not
        // cancel the replacement or report a disconnect against it.
        IBinder.DeathRecipient staleDeath=service.deaths.get(0);
        Object[] replacement=pending(delivery);bindings.put(c,replacement);staleDeath.binderDied();
        check(bindings.get(c)==replacement&&events.isEmpty(),"stale death removed replacement");
        Parcel data=Parcel.obtain();data.writeInt(1);data.writeTypedObject(NAME,0);data.writeStrongBinder(null);data.setDataPosition(0);
        result.invoke(null,c,replacement,delivery,data);data.recycle();events.remove().run();
        check(c.nullBindings==1,"null binding was not delivered");SystemClient.unbindRemote(c);

        // A broker can die after acknowledging BIND but before completion. The
        // live generation must fail once, clear its cache and permit a fresh
        // lookup instead of leaving the service connection waiting forever.
        Connection queued=new Connection();Object[] brokerPending=pending(delivery);bindings.put(queued,brokerPending);
        Binder broker=new Binder();Field system=SystemClient.class.getDeclaredField("sSystem");system.setAccessible(true);system.set(null,broker);
        Field death=SystemClient.class.getDeclaredField("sSystemDeath");death.setAccessible(true);death.set(null,new Object[]{broker,new Object()});
        Field cached=SystemClient.class.getDeclaredField("sBroker");cached.setAccessible(true);cached.set(null,broker);
        Field looked=SystemClient.class.getDeclaredField("sBrokerLooked");looked.setAccessible(true);looked.setBoolean(null,true);
        Method died=SystemClient.class.getDeclaredMethod("brokerDied",IBinder.class);died.setAccessible(true);died.invoke(null,broker);
        check(queued.dead==0&&!bindings.containsKey(queued),"queued bind was not queued for broker-death failure");
        check(system.get(null)==null&&cached.get(null)==null&&!looked.getBoolean(null),"dead broker cache was not invalidated");
        events.remove().run();check(queued.dead==1,"onBindingDied was not delivered");finish.accept(brokerPending);
        check(events.isEmpty()&&queued.connected==0,"late completion resurrected broker-dead bind");
        service.deaths.clear();bindings.clear();
        System.out.println("PASS: cancel, generation reuse, duplicate result, queued cancellation, binder identity, null binding and broker death");
    }
}
