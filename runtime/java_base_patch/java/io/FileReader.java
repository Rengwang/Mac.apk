/*
 * Mac.apk java.base overlay (--patch-module java.base). This REPLACES the JDK's
 * java.io.FileReader. It is byte-for-byte the JDK 21 public API (five
 * constructors delegating to a FileInputStream + the inherited InputStreamReader
 * behaviour) with ONE addition: it redirects a read of the Linux procfs paths
 * /proc/self/smaps and /proc/self/maps -- which do NOT exist on macOS -- to an
 * in-memory synthesized body.
 *
 * WHY: macOS has no /proc, and our guest /proc synthesis lives in the Bionic
 * open() shim, which the GUEST's native code routes through -- but a Java caller
 * opens through libjava's own open(), which bypasses that shim entirely. There
 * is no process-wide interpose (dyld does not apply a main-executable __interpose
 * to a later-dlopen'd libjava) and no DYLD_INSERT entitlement, so the JVM read
 * hit the absent real /proc. Instagram's page-size probe (X.0te) does
 * `new FileReader("/proc/self/smaps")` and scans for a `KernelPageSize:` line; on
 * failure it throws IllegalStateException("Could not get page size") ->
 * ExceptionInInitializerError -> System.exit(10), which blocked the logged-in
 * feed. This overlay makes that read succeed with the HOST page size (Apple
 * Silicon = 16 kB, what the guest's native memory is actually laid out on). Only
 * these two exact /proc/self paths are rewritten; every other file open is
 * untouched, so ordinary Java file I/O is unchanged. Native /proc readers keep
 * using the full live census through the Bionic shim.
 *
 * NO TEMP FILE (2026-09-24). The body used to be written to
 * File.createTempFile("macapk_jvm_smaps", ".txt") with deleteOnExit(), and that
 * hook never runs here: window close is _exit, harnesses SIGKILL, and HotSpot runs
 * with -Xrs, under which JDK 21's Terminator says shutdown hooks run only through
 * System.exit. So every JVM that read it leaked one file into the per-user TMPDIR
 * (28 seen). A FileReader exposes no fd and no path, so serving the same ASCII
 * bytes from a ByteArrayInputStream is invisible to every caller.
 * See docs/procfs-synth-tmp-lifetime_2026-09-24.md.
 */
package java.io;

import java.nio.charset.Charset;

public class FileReader extends InputStreamReader {

    public FileReader(String fileName) throws FileNotFoundException {
        super(macapkOpen(fileName));
    }

    public FileReader(File file) throws FileNotFoundException {
        super(macapkOpen(file));
    }

    public FileReader(FileDescriptor fd) {
        super(new FileInputStream(fd));
    }

    public FileReader(String fileName, Charset charset) throws IOException {
        super(macapkOpen(fileName), charset);
    }

    public FileReader(File file, Charset charset) throws IOException {
        super(macapkOpen(file), charset);
    }

    // ---- Mac.apk /proc redirect (see the class comment) --------------------

    // A minimal but well-formed smaps carrying exactly the fields a page-size
    // probe scans. One synthetic mapping is enough: the probe reads the first
    // KernelPageSize it finds. Pure ASCII, so the byte[] is built without going
    // through a Charset.
    private static final byte[] PROC_BODY = macapkAscii(
          "0000000000000000-0000000000010000 r--p 00000000 00:00 0 \n"
        + "Size:                 64 kB\n"
        + "KernelPageSize:       16 kB\n"   // Apple Silicon host page size
        + "MMUPageSize:          16 kB\n"
        + "Rss:                   0 kB\n"
        + "Pss:                   0 kB\n"
        + "VmFlags: rd mr mw me \n");

    private static byte[] macapkAscii(String s) {
        byte[] b = new byte[s.length()];
        for (int i = 0; i < b.length; i++) b[i] = (byte) s.charAt(i);
        return b;
    }

    private static boolean macapkIsProc(String name) {
        // Constant-first equals: a null name is NOT a /proc path, so it falls
        // through to FileInputStream and throws the JDK's own NullPointerException.
        return "/proc/self/smaps".equals(name) || "/proc/self/maps".equals(name);
    }

    // ---- Mac.apk /proc/<pid>/cmdline (added 2026-10-27) ---------------------
    // Linux gives every process a /proc/<pid>/cmdline holding its argv,
    // NUL-separated: for an Android app process that is exactly its process
    // name. It is the portable pre-API-28 way to ask "which process am I?"
    // and it is what modern apps fall back to whenever
    // android.app.Application.getProcessName() is unavailable:
    //
    //   MEASURED (Bilibili 9.16.1): com.bilibili.droid.ProcessUtils.processName()
    //   is `return processName(Process.myPid())`, which opens
    //   new FileReader("/proc/" + pid + "/cmdline"). macOS has no /proc, so the
    //   open throws, the method returns null, and its caller
    //   (r10.w.execute: `StringsKt.endsWith(processName(), "safemode")`) dies:
    //
    //     FATAL EXCEPTION: gripper-root-single-1
    //     java.lang.NullPointerException: Cannot invoke "String.endsWith(String)"
    //         because "<local4>" is null
    //         at kotlin.text.StringsKt__StringsJVMKt.endsWith(BL:3)
    //         at r10.w.execute(BL:68)
    //
    //   Bilibili's own startup watchdog counts that as a failed boot and
    //   System.exit(10)s into SafeModeActivity, which reads to the user as
    //   "black screen, then it restarts". The same idiom is standard in WeChat,
    //   Douyin and every push SDK, so this is a framework gap, not an app bug.
    //
    // The name comes from the macapk.process.name system property, which Boot
    // sets from the parsed manifest at the same point it records
    // Boot.sProcessName (the value AOSP's RuntimeInit would put in cmdline).
    //
    // WHICH PIDS: self, thread-self, and OUR OWN pid only. A device can read any
    // process's cmdline; we cannot (there is no guest process table here -- every
    // manifest process of an app is hosted in this one runtime process), and
    // inventing an answer for a stranger's pid would be worse than the honest
    // FileNotFoundException a device never gives: an app that asks "is that other
    // process mine?" would be told "yes". android.os.Process.myPid() is
    // ProcessHandle.current().pid(), so the own-pid test is the same number the
    // app passes in.
    private static boolean macapkIsCmdline(String name) {
        if (name == null || !name.startsWith("/proc/") || !name.endsWith("/cmdline")) return false;
        final int end = name.length() - 8;              // "/cmdline".length()
        if (end <= 6) return false;                     // "/proc/" + "/cmdline" with no pid
        final String mid = name.substring(6, end);      // the <pid> field
        if ("self".equals(mid) || "thread-self".equals(mid)) return true;
        for (int i = 0; i < mid.length(); i++) {
            final char c = mid.charAt(i);
            if (c < '0' || c > '9') return false;
        }
        try {
            return Long.parseLong(mid) == ProcessHandle.current().pid();
        } catch (Throwable notOursOrTooBig) {
            return false;
        }
    }

    /** The process name, as Boot recorded it; a neutral fallback keeps the file
     *  readable (never null, never empty) for a read that races process setup. */
    private static String macapkProcessName() {
        final String p = System.getProperty("macapk.process.name");
        return (p == null || p.isEmpty()) ? "android.process" : p;
    }

    /** argv as the kernel lays it out: UTF-8 name followed by one NUL. Java
     *  callers do `readLine()` + `trim()`, and String.trim() drops the NUL, so
     *  the same body serves both the correct Linux shape and the trimmed form
     *  an app compares against (Bilibili's own processName() trims). */
    private static byte[] macapkProcCmdline() {
        final byte[] raw = macapkProcessName().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        final byte[] body = new byte[raw.length + 1];
        System.arraycopy(raw, 0, body, 0, raw.length);
        body[raw.length] = 0;
        return body;
    }

    private static InputStream macapkOpen(String fileName) throws FileNotFoundException {
        if (macapkIsCmdline(fileName)) return new ByteArrayInputStream(macapkProcCmdline());
        if (macapkIsProc(fileName)) return new ByteArrayInputStream(PROC_BODY);
        return new FileInputStream(fileName);
    }

    private static InputStream macapkOpen(File file) throws FileNotFoundException {
        if (file != null) {
            final String p = file.getPath();
            if (macapkIsCmdline(p)) return new ByteArrayInputStream(macapkProcCmdline());
            if (macapkIsProc(p)) return new ByteArrayInputStream(PROC_BODY);
        }
        return new FileInputStream(file);
    }
}
