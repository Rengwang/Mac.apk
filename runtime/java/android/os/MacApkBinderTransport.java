// android.os.MacApkBinderTransport -- the Java half of cross-process Binder
// (native half: runtime/src/binder_mach.cpp, libmacapk-binder.dylib).
//
// Each Mac.apk app runs in its own process; a Binder reaches across them over
// Mach ports (design + measurements: docs/cross-process-binder-over-mach_
// 2026-10-30.md). This class owns the IDENTITY tables -- which port stands for
// which Java object -- because Binder identity is load-bearing: AIDL's
// asInterface, linkToDeath and every "is this the same binder" check compare
// objects, so a binder that crosses and comes back must be the SAME object.
//
//   local Binder  <-> a receive right we own   (sPortOf / sLocalAt)
//   BinderProxy   <-> a send right             (sProxyAt, one proxy per port)
//
// A Parcel crosses as its bytes plus its object table (Parcel.macapkTransport*):
// each IBinder entry becomes a port right, null stays null, and the far side
// rebuilds the table with the same indices. Anything else in the table is a
// live Java reference that cannot leave the process (a Parcelable whose
// writeToParcel is still empty) and the
// transact fails loudly naming the class, rather than delivering a hollow
// object.
//
// In-process behaviour is untouched: a local Binder's transact never comes
// here. Only a BinderProxy (which exists only for a port another process owns)
// does.
//
// File descriptors (Parcel.writeFileDescriptor, ParcelFileDescriptor) cross as
// Mach fileports and arrive as NEW fds for the same open file (phase 2).
//
// PHASE 1 LIMITS (documented, not hidden): an exported local Binder's port is
// kept for the life of the process (no MACH_NOTIFY_NO_SENDERS yet); the
// sender's Android uid travels in the header and is trusted until the phase-3
// broker assigns per-package uids and checks them against the pid it launched.
package android.os;

import java.io.File;
import java.lang.ref.WeakReference;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class MacApkBinderTransport {
    private MacApkBinderTransport() {}

    // AOSP status_t (system/core/libutils/include/utils/Errors.h).
    static final int NO_ERROR = 0;
    static final int UNKNOWN_TRANSACTION = -74;
    static final int DEAD_OBJECT = -32;
    static final int FAILED_TRANSACTION = 0x80000002;
    static final int BAD_VALUE = -22;

    private static final int KIND_NULL = 0;
    private static final int KIND_BINDER = 1;
    /** An fd, sent as a Mach fileport; the receiver gets a NEW fd for the same
     *  open file (BINDER_TYPE_FD semantics). PFD vs raw keeps the type the
     *  reader's readValue / readRawFileDescriptor expects. */
    private static final int KIND_PFD = 2;
    private static final int KIND_FD = 3;
    /** Raw Mach send right (owned until sent or released), used for zero-copy
     *  IOSurface transport between cooperating processes. */
    private static final int KIND_MACH_PORT = 4;

    static final class MachPortRef {
        private int port;
        MachPortRef(int port) { this.port = port; }
        synchronized int take() { int p = port; port = 0; return p; }
        synchronized void release() {
            int p = port;
            port = 0;
            if (p != 0) safeReleasePort(p);
        }
    }

    static Object wrapMachPort(int port) { return port != 0 ? new MachPortRef(port) : null; }
    static int takeMachPort(Object o) { return (o instanceof MachPortRef) ? ((MachPortRef) o).take() : 0; }
    static void releaseUnconsumed(Object o) { if (o instanceof MachPortRef) ((MachPortRef) o).release(); }
    private static void safeReleasePort(int port) {
        try { if (sLoaded && port != 0) nativeReleaseSend(port); }
        catch (UnsatisfiedLinkError ignored) {}
    }

    /** Receive threads on the port set (Mach receive is multi-reader). An app
     *  needs few; a process whose handlers BLOCK on other processes needs more
     *  -- AOSP gives system_server a bigger binder pool than an app (31 vs 15)
     *  for the same reason. Set before the first publish / export. */
    private static int sPoolThreads = 4;
    public static synchronized void setThreadPoolSize(int n) {
        if (!sThreads && n > 0) sPoolThreads = n;
    }

    private static boolean sLoaded;
    private static Throwable sLoadError;
    private static boolean sThreads;

    private static final Map<Binder, Integer> sPortOf = new IdentityHashMap<>();
    private static final ConcurrentHashMap<Integer, Binder> sLocalAt = new ConcurrentHashMap<>();
    private static final Map<Integer, WeakReference<BinderProxy>> sProxyAt = new HashMap<>();

    // Per-target-port FIFO serialization for FLAG_ONEWAY transactions, matching
    // Linux Binder's per-binder_node async_todo queue so multi-reader Mach
    // receive threads (binder:1..N) never execute concurrent oneway dispatches
    // on the same target Binder.
    private static final int MAX_ONEWAY_QUEUE_PER_PORT = 512;
    private static final ConcurrentHashMap<Integer, PortOnewayQueue> sOnewayQueues =
        new ConcurrentHashMap<>();
    private static final AtomicInteger sOnewayThreadSeq = new AtomicInteger();
    private static final ThreadPoolExecutor ONEWAY_EXECUTOR = createOnewayExecutor();

    private static ThreadPoolExecutor createOnewayExecutor() {
        ThreadFactory tf = r -> {
            Thread t = new Thread(r, "binder-oneway:" + sOnewayThreadSeq.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
        ThreadPoolExecutor exec = new ThreadPoolExecutor(
                4, 4, 30L, TimeUnit.SECONDS, new LinkedBlockingQueue<>(), tf);
        exec.allowCoreThreadTimeOut(true);
        return exec;
    }

    private static final class OnewayTask {
        final int code;
        final int flags;
        final byte[] data;
        final int[] offsets;
        final int[] ports;
        final int[] kinds;
        final int callingPid;
        final int callingUid;

        OnewayTask(int code, int flags, byte[] data, int[] offsets,
                   int[] ports, int[] kinds, int callingPid, int callingUid) {
            this.code = code;
            this.flags = flags;
            this.data = data;
            this.offsets = offsets;
            this.ports = ports;
            this.kinds = kinds;
            this.callingPid = callingPid;
            this.callingUid = callingUid;
        }
    }

    private static final class PortOnewayQueue implements Runnable {
        final int port;
        final ArrayDeque<OnewayTask> queue = new ArrayDeque<>();
        boolean running;
        boolean retired;

        PortOnewayQueue(int port) {
            this.port = port;
        }

        @Override
        public void run() {
            for (;;) {
                OnewayTask task;
                synchronized (this) {
                    task = queue.pollFirst();
                    if (task == null) {
                        running = false;
                        if (!sLocalAt.containsKey(port) || sOnewayQueues.size() > 128) {
                            retired = true;
                            sOnewayQueues.remove(port, this);
                        }
                        return;
                    }
                }
                dispatchOne(port, task.code, task.flags, task.data, task.offsets,
                        task.ports, task.kinds, task.callingPid, task.callingUid);
            }
        }
    }

    // ---- loading -------------------------------------------------------------

    /** Load libmacapk-binder.dylib: -Dmacapk.binder.lib / MACAPK_BINDER_LIB,
     *  else beside the classpath jars (build/lib -> build/bin, and a bundle's
     *  Contents/Resources), else java.library.path. */
    static synchronized void ensureLoaded() {
        if (sLoaded) return;
        if (sLoadError != null) throw new IllegalStateException("binder transport unavailable", sLoadError);
        try {
            String explicit = System.getProperty("macapk.binder.lib");
            if (explicit == null || explicit.isEmpty()) explicit = System.getenv("MACAPK_BINDER_LIB");
            File found = null;
            if (explicit != null && !explicit.isEmpty()) {
                found = new File(explicit);
            } else {
                String cp = System.getProperty("java.class.path", "");
                for (String entry : cp.split(File.pathSeparator)) {
                    if (entry.isEmpty()) continue;
                    File dir = new File(entry).getAbsoluteFile().getParentFile();
                    if (dir == null) continue;
                    File[] tries = {
                        new File(dir, "libmacapk-binder.dylib"),
                        new File(dir.getParentFile(), "bin/libmacapk-binder.dylib"),
                        new File(dir.getParentFile(), "Resources/libmacapk-binder.dylib"),
                    };
                    for (File f : tries) if (f.isFile()) { found = f; break; }
                    if (found != null) break;
                }
            }
            if (found != null) System.load(found.getAbsolutePath());
            else System.loadLibrary("macapk-binder");
            if (!nativeInit()) throw new IllegalStateException("nativeInit failed");
            sLoaded = true;
        } catch (Throwable t) {
            sLoadError = t;
            throw new IllegalStateException("binder transport unavailable", t);
        }
    }

    private static synchronized void ensureThreads() {
        ensureLoaded();
        if (!sThreads) {
            // Pre-touch oneway classes so the first async burst never stalls
            // a native receive thread on class loading.
            @SuppressWarnings("unused")
            Class<?> c1 = PortOnewayQueue.class;
            @SuppressWarnings("unused")
            Class<?> c2 = OnewayTask.class;
            nativeStartThreads(sPoolThreads);
            sThreads = true;
        }
    }

    /** Framework-internal: export a local Binder to its Mach receive port name
     *  (starting the native receive pool if needed). */
    public static int macapkExportBinderPort(Binder service) {
        if (service == null) throw new IllegalArgumentException("service == null");
        return exportLocal(service);
    }

    /** Framework-internal: adopt a Mach send right into a BinderProxy (or
     *  resolve back to the local Binder when the port is ours). */
    public static IBinder macapkImportBinderSendRight(int sendPort) {
        ensureLoaded();
        return sendPort == 0 ? null : (IBinder) incoming(sendPort, KIND_BINDER);
    }

    // ---- rendezvous ------------------------------------------------------------

    /** Serve `service` under a launchd-declared name (a LaunchAgent's
     *  MachServices entry). Throws, naming the bootstrap error, if this process
     *  is not the job that declares it. */
    public static void publish(String name, IBinder service) {
        if (!(service instanceof Binder)) {
            throw new IllegalArgumentException("only a local Binder can be published");
        }
        ensureThreads();
        int port = nativeCheckIn(name);
        if (port <= 0) {
            throw new IllegalStateException("bootstrap_check_in(" + name + ") failed: 0x"
                    + Integer.toHexString(-port));
        }
        synchronized (MacApkBinderTransport.class) {
            sPortOf.put((Binder) service, port);
            sLocalAt.put(port, (Binder) service);
        }
    }

    /** A proxy for the binder published under `name`, or null when nothing is. */
    public static IBinder lookUp(String name) {
        ensureLoaded();
        int port = nativeLookUp(name);
        return port == 0 ? null : (IBinder) incoming(port, KIND_BINDER);
    }

    // ---- identity --------------------------------------------------------------

    private static synchronized int exportLocal(Binder b) {
        Integer p = sPortOf.get(b);
        if (p != null) return p;
        ensureThreads();
        int port = nativeCreatePort();
        if (port == 0) throw new IllegalStateException("could not allocate a port for " + b);
        sPortOf.put(b, port);
        sLocalAt.put(port, b);
        return port;
    }

    /** The object a received port right stands for. Each received right is one
     *  user reference: a right to our OWN port (a local binder coming home) or
     *  a second right to a port we already proxy is released, so each proxy
     *  owns exactly one. */
    private static synchronized Object incoming(int port, int kind) {
        if (kind == KIND_MACH_PORT) return port != 0 ? new MachPortRef(port) : null;
        if (kind == KIND_PFD) return port >= 0 ? ParcelFileDescriptor.adoptFd(port) : null;
        if (kind == KIND_FD) return port >= 0 ? ParcelFileDescriptor.adoptFd(port).getFileDescriptor() : null;
        if (kind == KIND_NULL || port == 0) return null;
        Binder local = sLocalAt.get(port);
        if (local != null) {
            nativeReleaseSend(port);
            return local;
        }
        WeakReference<BinderProxy> ref = sProxyAt.get(port);
        BinderProxy bp = ref != null ? ref.get() : null;
        if (bp != null) {
            nativeReleaseSend(port);
            return bp;
        }
        bp = new BinderProxy(port);
        sProxyAt.put(port, new WeakReference<>(bp));
        return bp;
    }

    /** A proxy was collected: release its right (BinderProxy's Cleaner). */
    static synchronized void proxyGone(int port) {
        WeakReference<BinderProxy> ref = sProxyAt.get(port);
        if (ref != null && ref.get() == null) sProxyAt.remove(port);
        nativeReleaseSend(port);
    }

    // ---- flattening -------------------------------------------------------------

    private static final class Flat {
        byte[] data; int[] offsets; int[] ports; int[] kinds;
    }

    private static Flat flatten(Parcel p) {
        Flat f = new Flat();
        if (p == null) {
            f.data = new byte[0]; f.offsets = new int[0]; f.ports = new int[0]; f.kinds = new int[0];
            return f;
        }
        Object[] objs = p.macapkTransportObjects();
        f.data = p.macapkTransportBytes();
        f.offsets = p.macapkTransportOffsets();
        f.ports = new int[objs.length];
        f.kinds = new int[objs.length];
        for (int i = 0; i < objs.length; i++) {
            Object o = objs[i];
            if (o == null) continue;
            if (o instanceof BinderProxy) {
                f.ports[i] = ((BinderProxy) o).port();
                f.kinds[i] = KIND_BINDER;
            } else if (o instanceof Binder) {
                f.ports[i] = exportLocal((Binder) o);
                f.kinds[i] = KIND_BINDER;
            } else if (o instanceof MachPortRef) {
                int port = ((MachPortRef) o).take();
                f.ports[i] = port;
                f.kinds[i] = port != 0 ? KIND_MACH_PORT : KIND_NULL;
            } else if (o instanceof ParcelFileDescriptor || o instanceof java.io.FileDescriptor) {
                boolean pfd = o instanceof ParcelFileDescriptor;
                int fd = pfd ? ((ParcelFileDescriptor) o).getFd()
                             : ParcelFileDescriptor.macapkFdInt((java.io.FileDescriptor) o);
                if (fd < 0) throw new BadParcelableException("cannot send a closed or invalid fd");
                f.ports[i] = fd;
                f.kinds[i] = pfd ? KIND_PFD : KIND_FD;
            } else {
                throw new BadParcelableException("cannot send " + o.getClass().getName()
                        + " to another process: it is a live in-process reference (its"
                        + " writeToParcel writes nothing yet)");
            }
        }
        return f;
    }

    private static void releaseRawIncomingObjects(int[] ports, int[] kinds) {
        if (ports == null || kinds == null) return;
        int n = Math.min(ports.length, kinds.length);
        for (int i = 0; i < n; i++) {
            int p = ports[i], k = kinds[i];
            if ((k == KIND_BINDER || k == KIND_MACH_PORT) && p != 0) {
                safeReleasePort(p);
            } else if ((k == KIND_PFD || k == KIND_FD) && p >= 0) {
                try { ParcelFileDescriptor.adoptFd(p).close(); } catch (Throwable ignored) {}
            }
        }
    }

    private static void unflatten(Parcel into, byte[] data, int[] offsets, int[] ports, int[] kinds) {
        int len = ports == null ? 0 : ports.length;
        Object[] objs = new Object[len];
        int converted = 0;
        try {
            for (; converted < len; converted++) {
                objs[converted] = incoming(ports[converted], kinds[converted]);
            }
            into.macapkTransportLoad(data != null ? data : new byte[0], objs, offsets);
        } catch (Throwable t) {
            for (int j = 0; j < converted; j++) {
                Object o = objs[j];
                if (o instanceof MachPortRef) ((MachPortRef) o).release();
                else if (o instanceof ParcelFileDescriptor) {
                    try { ((ParcelFileDescriptor) o).close(); } catch (Throwable ignored) {}
                }
            }
            for (int j = converted; j < len && kinds != null && j < kinds.length; j++) {
                int p = ports[j], k = kinds[j];
                if ((k == KIND_BINDER || k == KIND_MACH_PORT) && p != 0) safeReleasePort(p);
                else if ((k == KIND_PFD || k == KIND_FD) && p >= 0) {
                    try { ParcelFileDescriptor.adoptFd(p).close(); } catch (Throwable ignored) {}
                }
            }
            throw t;
        }
    }

    // ---- outgoing ---------------------------------------------------------------

    /** BinderProxy.transact. Returns the AOSP status_t. */
    static int transact(int port, int code, Parcel data, Parcel reply, int flags) {
        ensureLoaded();
        Flat f = flatten(data);
        Object[] r = nativeTransact(port, code, flags, Process.myUid(),
                f.data, f.offsets, f.ports, f.kinds);
        int status = ((int[]) r[0])[0];
        if (status == NO_ERROR && reply != null && r[1] != null) {
            unflatten(reply, (byte[]) r[1], (int[]) r[2], (int[]) r[3], (int[]) r[4]);
        } else if (r[3] != null) {
            // A discarded reply's rights and fds still need releasing.
            int[] ps = (int[]) r[3], ks = (int[]) r[4];
            for (int i = 0; i < ps.length; i++) {
                if ((ks[i] == KIND_BINDER || ks[i] == KIND_MACH_PORT) && ps[i] != 0) nativeReleaseSend(ps[i]);
                else if ((ks[i] == KIND_PFD || ks[i] == KIND_FD) && ps[i] >= 0) {
                    try { ParcelFileDescriptor.adoptFd(ps[i]).close(); } catch (Exception ignored) {}
                }
            }
        }
        return status;
    }

    static boolean requestDeath(int port) { ensureLoaded(); return nativeRequestDeath(port); }
    static boolean isDead(int port) { ensureLoaded(); return nativeIsDead(port); }

    // ---- incoming (called from the native thread pool) ----------------------------

    /** The app's class loader, for the native binder threads (they attach
     *  with the JDK's system loader). On a device a binder thread runs with
     *  the app's loader, and code there loads app classes through it -- e.g.
     *  ServiceRegistry resolving a bind Google Play services makes to its own
     *  service while handling another app's call. */
    private static volatile ClassLoader sAppLoader;
    public static void setAppClassLoader(ClassLoader cl) { sAppLoader = cl; }

    private static final java.util.Set<String> sLoggedThrowSites =
        java.util.concurrent.ConcurrentHashMap.newKeySet();

    private static void enqueueOneway(int port, int code, int flags, byte[] data, int[] offsets,
                                      int[] ports, int[] kinds, int callingPid, int callingUid) {
        if (!sLocalAt.containsKey(port)) {
            releaseRawIncomingObjects(ports, kinds);
            return;
        }
        OnewayTask task = new OnewayTask(code, flags, data, offsets, ports, kinds, callingPid, callingUid);
        OnewayTask evicted = null;
        PortOnewayQueue chosen;
        boolean startDrain = false;
        for (;;) {
            PortOnewayQueue q = sOnewayQueues.computeIfAbsent(port, PortOnewayQueue::new);
            synchronized (q) {
                if (q.retired) continue;
                if (q.queue.size() >= MAX_ONEWAY_QUEUE_PER_PORT) {
                    evicted = q.queue.pollFirst();
                }
                q.queue.addLast(task);
                if (!q.running) {
                    q.running = true;
                    startDrain = true;
                }
                chosen = q;
                break;
            }
        }
        if (evicted != null) {
            releaseRawIncomingObjects(evicted.ports, evicted.kinds);
        }
        if (startDrain) {
            try {
                ONEWAY_EXECUTOR.execute(chosen);
            } catch (Throwable t) {
                ArrayDeque<OnewayTask> leaked = new ArrayDeque<>();
                synchronized (chosen) {
                    chosen.running = false;
                    chosen.retired = true;
                    sOnewayQueues.remove(port, chosen);
                    leaked.addAll(chosen.queue);
                    chosen.queue.clear();
                }
                for (OnewayTask drop : leaked) {
                    releaseRawIncomingObjects(drop.ports, drop.kinds);
                }
            }
        }
    }

    /** A transaction for one of our ports. Mirrors AOSP Binder.execTransact:
     *  the caller's identity is set for the duration, an exception thrown by
     *  onTransact is written into the reply (writeException) rather than
     *  escaping, and the reply goes back with its own object table.
     *  FLAG_ONEWAY transactions enqueue onto a per-port serial FIFO queue so
     *  multi-reader Mach receive threads never execute concurrent oneway calls
     *  against the same Binder node. */
    static Object[] dispatch(int port, int code, int flags, byte[] data, int[] offsets,
                             int[] ports, int[] kinds, int callingPid, int callingUid) {
        if ((flags & IBinder.FLAG_ONEWAY) != 0) {
            enqueueOneway(port, code, flags, data, offsets, ports, kinds, callingPid, callingUid);
            return null;
        }
        return dispatchOne(port, code, flags, data, offsets, ports, kinds, callingPid, callingUid);
    }

    private static Object[] dispatchOne(int port, int code, int flags, byte[] data, int[] offsets,
                                        int[] ports, int[] kinds, int callingPid, int callingUid) {
        Binder target = sLocalAt.get(port);
        ClassLoader app = sAppLoader;
        if (app != null && Thread.currentThread().getContextClassLoader() != app) {
            Thread.currentThread().setContextClassLoader(app);
        }
        boolean oneway = (flags & IBinder.FLAG_ONEWAY) != 0;
        Parcel in = Parcel.obtain();
        Parcel out = oneway ? null : Parcel.obtain();
        int status;
        long[] prev = Binder.sCallingIdentity.get();
        Binder.sCallingIdentity.set(new long[] { callingPid, callingUid });
        int prevTx = Binder.sTransactionDepth.get();
        Binder.sTransactionDepth.set(prevTx + 1);
        try {
            unflatten(in, data, offsets, ports, kinds);
            if (target == null) {
                status = DEAD_OBJECT;
            } else {
                boolean ok;
                try {
                    ok = target.transact(code, in, out, flags);
                } catch (RemoteException | RuntimeException e) {
                    // AOSP Binder.execTransactInternal can log this ("Caught a
                    // RuntimeException from the binder stub implementation.");
                    // the caller only gets the exception TYPE and message, so
                    // the serving side's stack is the one place the cause shows.
                    // Once per throw site, so a hot failing call cannot flood.
                    StackTraceElement[] st = e.getStackTrace();
                    String site = e.getClass().getName() + "@" + (st.length > 0 ? st[0] : "?");
                    if (sLoggedThrowSites.add(site)) {
                        System.err.println("[binder] Caught a RuntimeException from the binder stub "
                                + "implementation (" + target.getClass().getName() + " code " + code + "):");
                        e.printStackTrace();
                    }
                    if (oneway) {
                        System.err.println("[binder] oneway " + target + " code=" + code + " threw " + e);
                    } else {
                        out.setDataSize(0);
                        out.setDataPosition(0);
                        out.writeException(e instanceof RemoteException
                                ? new RuntimeException(e) : e);
                    }
                    ok = true;
                }
                status = ok ? NO_ERROR : UNKNOWN_TRANSACTION;
            }
            if (oneway) return null;
            Flat f = flatten(out);
            return new Object[] { new int[] { status }, f.data, f.offsets, f.ports, f.kinds };
        } catch (Throwable t) {
            System.err.println("[binder] dispatch failed on port " + port + ": " + t);
            return oneway ? null : new Object[] { new int[] { FAILED_TRANSACTION }, null, null, null, null };
        } finally {
            if (prevTx == 0) Binder.sTransactionDepth.remove();
            else Binder.sTransactionDepth.set(prevTx);
            if (prev != null) Binder.sCallingIdentity.set(prev);
            else Binder.sCallingIdentity.remove();
            in.recycle();
            if (out != null) out.recycle();
        }
    }

    /** The kernel reported a send right's port dead. */
    static void onDeadName(int port) {
        BinderProxy bp;
        synchronized (MacApkBinderTransport.class) {
            WeakReference<BinderProxy> ref = sProxyAt.get(port);
            bp = ref != null ? ref.get() : null;
        }
        if (bp != null) bp.deliverDeath();
    }

    // ---- natives (binder_mach.cpp) ------------------------------------------------
    private static native boolean nativeInit();
    private static native int nativeCreatePort();
    private static native int nativeCheckIn(String name);
    private static native int nativeLookUp(String name);
    private static native void nativeStartThreads(int n);
    private static native Object[] nativeTransact(int dest, int code, int flags, int senderUid,
            byte[] data, int[] offsets, int[] ports, int[] kinds);
    private static native boolean nativeRequestDeath(int port);
    private static native boolean nativeIsDead(int port);
    private static native void nativeReleaseSend(int port);
}
