// android.app.MacApkBootClassLoader -- the Android `Class.getClassLoader()`
// answer for a BOOTSTRAP class, which OpenJDK models as null.
//
// WHY THIS CLASS EXISTS (2026-10-27)
// ------------------------------------------------------------------
// Android: `Class.getClassLoader()` returns the singleton `BootClassLoader`
// for a class the boot class loader defined. It is an OBJECT with
// `getParent() == null`; it is never null. libcore's ClassLoader.java and
// ART's `Class::GetClassLoader` both guarantee it, and every Android app is
// free to write:
//
//     Object.class.getClassLoader().loadClass(name)      // BootClassLoader
//     SomeBootClass.class.getClassLoader().getParent()   // null
//
// OpenJDK: the bootstrap loader is REPRESENTED BY null, so the same
// expression evaluates to null and the call throws NullPointerException.
//
// That is not a corner case. MEASURED (Bilibili 9.16.1, 2026-10-27): its
// Tribe module loader, `com.bilibili.lib.tribe.core.internal.loader
// .DefaultClassLoaderService.i(String)`, starts with
//
//     try { return Object.class.getClassLoader().loadClass(name); }
//     catch (ClassNotFoundException e) { return h(name); }
//     catch (Throwable t) { return (Class) c.invoke(); }   // c is null here
//
// On a device the boot loader throws ClassNotFoundException (it cannot see
// app classes) and `h(name)` resolves the module. Here the null receiver
// throws NullPointerException, which lands in the second catch -- whose `c`
// Function0 has not been assigned yet -- and the class dies with
// ExceptionInInitializerError. Downstream: MsaHelper/OAID could not
// initialize, and Bilibili's boot watchdog counted the crashes and
// System.exit(10)ed into SafeModeActivity ("black screen, then restart").
//
// HOW IT IS WIRED
// ------------------------------------------------------------------
// Not by app patching: com.macapk.loader.ClassFileRewriter rewrites each
// `invokevirtual java/lang/Class.getClassLoader()Ljava/lang/ClassLoader;`
// in APP classes at load time into
//
//     invokestatic android/app/MacApkBootClassLoader.get(Ljava/lang/Class;)Ljava/lang/ClassLoader;
//
// which is stack-neutral (the receiver becomes the argument), length-neutral
// and frame-neutral. MACAPK_BOOT_CLASSLOADER_SHIM=0 turns the rewrite off.
//
// The answer for a REAL loader is that loader, untouched. Only the bootstrap
// case changes, and it changes to Android's shape: a non-null loader whose
// getParent() is null. We use the JDK's PLATFORM loader as the stand-in
// because it behaves like Android's BootClassLoader in the two ways callers
// depend on:
//   * it cannot see application classes, so `loadClass(appClass)` throws
//     ClassNotFoundException -- exactly what the device path above relies on
//     to reach its own fallback;
//   * its getParent() is null, like BootClassLoader's.
// (The platform loader reaches java.*/jdk.*/sun.*; the boot loader reaches
// those too. Framework classes on our classpath are NOT visible to either,
// which is the one divergence, and it is in the direction that keeps the
// caller on its own fallback path.)
package android.app;

public final class MacApkBootClassLoader {
    private MacApkBootClassLoader() {}

    /** Never null: the caller's own loader when there is one, else the
     *  closest thing this VM has to Android's BootClassLoader. */
    public static ClassLoader get(Class<?> c) {
        // Deliberately NOT rewritten by the shim: this class is excluded by
        // name in ClassFileRewriter, or it would recurse into itself.
        ClassLoader cl = (c != null) ? c.getClassLoader() : null;
        if (cl != null) return cl;
        return standIn();
    }

    /** ClassLoader.getPlatformClassLoader() is documented never to be null on
     *  a running VM; the extra fallbacks exist so a hostile/exotic setup still
     *  cannot hand an app a null (which is the whole bug). */
    private static ClassLoader standIn() {
        ClassLoader p = null;
        try { p = ClassLoader.getPlatformClassLoader(); } catch (Throwable ignored) { }
        if (p == null) {
            try { p = ClassLoader.getSystemClassLoader(); } catch (Throwable ignored) { }
        }
        if (p == null) p = MacApkBootClassLoader.class.getClassLoader();
        return p != null ? p : new ClassLoader(null) { };
    }
}
