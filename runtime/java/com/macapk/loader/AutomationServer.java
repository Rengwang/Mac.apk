// AutomationServer -- Headless in-process automation bridge for Mac.apk.
//
// Activated ONLY when MACAPK_AUTOMATION=1 and MACAPK_AUTOMATION_DIR is non-empty
// in the primary app process (zero overhead and no threads when off).
// Exposes an atomic JSON file protocol inside MACAPK_AUTOMATION_DIR:
//   ready.json <- written atomically once the top Activity has laid-out bounds
//   cmd.json   -> polled ~50ms, deleted before execution
//   state.json <- written atomically after every command (never silently swallows errors)
//
// Delivers touch, swipe, key, orientation, and intent commands strictly in-process
// via TouchDispatcher and the main Looper without moving the OS cursor or stealing
// foreground window focus.
package com.macapk.loader;

import android.app.Activity;
import android.content.Intent;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.method.PasswordTransformationMethod;
import android.util.DisplayMetrics;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityNodeProvider;
import android.widget.Checkable;
import android.widget.TextView;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

public final class AutomationServer {
    private static final String TAG = "MacApkAutomation";
    private static final int POLL_INTERVAL_MS = 50;
    private static final int READY_WAIT_POLL_MS = 50;
    private static final long UI_LOOP_TIMEOUT_MS = 5000L;
    private static final int MAX_UI_NODES = 5000;

    private static final AtomicBoolean STARTED = new AtomicBoolean(false);
    private static final AtomicLong SEQ_FALLBACK = new AtomicLong(0L);

    /** Hooks injectable for bare-JVM tests. */
    private static volatile Runnable sExitAction = AutomationServer::defaultExitProcess;

    private static void defaultExitProcess() {
        // Route through macapk_terminate_now(0) (ActivityHost.macapkTerminate +
        // KillAppProcessGroup + _exit) rather than HotSpot System.exit(0), which
        // triggers ApplicationShutdownHooks and libc __cxa_finalize (deadlock risk).
        try {
            NativeCocoa.exitProcess(0, null);
        } catch (Throwable ignored) {}
        Runtime.getRuntime().halt(0);
    }

    public static void setExitActionForTest(Runnable action) {
        sExitAction = action != null ? action : AutomationServer::defaultExitProcess;
    }

    /** Lightweight overlay descriptor so AutomationServer and bare-JVM tests share
     *  the exact same overlay-tree geometry collector without JNI dependencies. */
    public static final class OverlayEntry {
        public final View view;
        public final Rect bounds;
        public OverlayEntry(View view, Rect bounds) {
            this.view = view;
            this.bounds = bounds;
        }
    }

    private AutomationServer() {}

    private static void logInfo(String msg) {
        try { NativeLog.i(TAG, msg); } catch (Throwable ignored) {}
    }

    private static void logWarn(String msg) {
        try { NativeLog.w(TAG, msg); } catch (Throwable ignored) {}
    }

    /** Boot hook called from Boot.java right next to TouchDispatcher's inject driver.
     *  Zero cost when MACAPK_AUTOMATION != "1" or MACAPK_AUTOMATION_DIR is empty. */
    public static void startIfEnabled() {
        startIfEnabled(System.getenv("MACAPK_AUTOMATION"),
                       System.getenv("MACAPK_AUTOMATION_DIR"));
    }

    public static boolean startIfEnabled(String enabledFlag, String dirPath) {
        if (!isEnabledConfig(enabledFlag, dirPath)) return false;
        if (isNonPrimaryProcess()) return false;
        if (!STARTED.compareAndSet(false, true)) return false;
        final Path dir = Paths.get(dirPath.trim());
        Thread worker = new Thread(() -> runLoop(dir), "MacApk-Automation");
        worker.setDaemon(true);
        worker.start();
        logInfo("automation server started; dir=" + dir);
        return true;
    }

    public static boolean isEnabledConfig(String enabledFlag, String dirPath) {
        return "1".equals(enabledFlag) && dirPath != null && !dirPath.trim().isEmpty();
    }

    private static boolean isNonPrimaryProcess() {
        try {
            if (SystemClient.isServiceHost()) return true;
        } catch (Throwable ignored) {}
        if ("1".equals(System.getenv("MACAPK_SECONDARY_PROCESS"))) return true;
        String proc = System.getenv("MACAPK_PROCESS_NAME");
        return proc != null && !proc.isEmpty();
    }

    private static void runLoop(Path dir) {
        try {
            Files.createDirectories(dir);
        } catch (Throwable t) {
            logWarn("cannot create automation dir " + dir + ": " + t);
            return;
        }

        // Wait until the top Activity has laid out a non-zero root view.
        while (!Thread.currentThread().isInterrupted()) {
            try {
                Map<String, Object> ready = probeReadySnapshot();
                if (ready != null) {
                    writeJsonAtomic(dir.resolve("ready.json"), ready);
                    logInfo("wrote ready.json: " + toJson(ready));
                    break;
                }
            } catch (Throwable t) {
                logWarn("ready probe failed: " + t);
            }
            if (!sleepQuiet(READY_WAIT_POLL_MS)) return;
        }

        final Path cmdPath = dir.resolve("cmd.json");
        final Path statePath = dir.resolve("state.json");
        while (!Thread.currentThread().isInterrupted()) {
            try {
                if (Files.exists(cmdPath)) {
                    String raw;
                    try {
                        raw = Files.readString(cmdPath, StandardCharsets.UTF_8);
                    } finally {
                        try { Files.deleteIfExists(cmdPath); } catch (Throwable ignored) {}
                    }
                    Map<String, Object> result = handleRawCommand(raw);
                    writeJsonAtomic(statePath, result);
                    if (Boolean.TRUE.equals(result.get("_quit"))) {
                        performQuitShutdown();
                        return;
                    }
                }
            } catch (Throwable t) {
                logWarn("poll loop error: " + t);
                try {
                    Map<String, Object> err = baseResponse(SEQ_FALLBACK.incrementAndGet(), false, null);
                    err.put("error", describeError(t));
                    writeJsonAtomic(statePath, err);
                } catch (Throwable ignored) {}
            }
            if (!sleepQuiet(POLL_INTERVAL_MS)) return;
        }
    }

    private static boolean sleepQuiet(long ms) {
        try {
            Thread.sleep(ms);
            return true;
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static Map<String, Object> probeReadySnapshot() {
        final Activity a = ActivityHost.peek();
        if (a == null) return null;
        final AtomicReference<Map<String, Object>> holder = new AtomicReference<>();
        Throwable err = runOnUiSync(() -> {
            Activity cur = ActivityHost.peek();
            if (cur == null) return;
            View root = resolveRootView(cur);
            if (root == null || root.getWidth() <= 0 || root.getHeight() <= 0) return;
            int[] surf = resolveSurfaceSize(cur, root);
            if (surf[0] <= 0 || surf[1] <= 0) return;

            Map<String, Object> ready = new LinkedHashMap<>();
            ready.put("pid", ProcessHandle.current().pid());
            ready.put("package", resolvePackageName(cur));
            ready.put("activity", cur.getClass().getName());
            ready.put("surfaceWidth", surf[0]);
            ready.put("surfaceHeight", surf[1]);
            ready.put("orientation", resolveOrientation(cur, surf[0], surf[1]));
            holder.set(ready);
        });
        if (err != null) throw new RuntimeException(err);
        return holder.get();
    }

    /** Process one JSON command string and return the response map (with optional internal _quit flag). */
    static Map<String, Object> handleRawCommand(String raw) {
        long seq = SEQ_FALLBACK.incrementAndGet();
        String op = null;
        try {
            if (raw == null || raw.trim().isEmpty()) {
                throw new IllegalArgumentException("cmd.json is empty");
            }
            Map<String, Object> cmd = parseJsonObject(raw);
            if (cmd.containsKey("seq") && cmd.get("seq") instanceof Number) {
                seq = ((Number) cmd.get("seq")).longValue();
                long finalSeq = seq;
                SEQ_FALLBACK.updateAndGet(cur -> Math.max(cur, finalSeq));
            }
            op = optString(cmd, "op");
            if (op == null || op.isEmpty()) {
                throw new IllegalArgumentException("missing required string field 'op'");
            }
            Map<String, Object> res = baseResponse(seq, true, op);
            switch (op) {
                case "state":
                    execState(res);
                    break;
                case "ui_tree":
                    execUiTree(res);
                    break;
                case "tap":
                    execTap(cmd, res);
                    break;
                case "swipe":
                    execSwipe(cmd, res);
                    break;
                case "key":
                    execKey(cmd, res);
                    break;
                case "set_orientation":
                    execSetOrientation(cmd, res);
                    break;
                case "open_url":
                    execOpenUrl(cmd, res);
                    break;
                case "video_stats":
                    execVideoStats(cmd, res);
                    break;
                case "quit":
                    res.put("_quit", Boolean.TRUE);
                    break;
                default:
                    throw new IllegalArgumentException("unknown op: " + op);
            }
            return res;
        } catch (Throwable t) {
            Map<String, Object> err = baseResponse(seq, false, op);
            err.put("error", describeError(t));
            return err;
        }
    }

    /** Strips internal keys (prefixed by '_') before serializing to state.json. */
    static Map<String, Object> externalizeState(Map<String, Object> state) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : state.entrySet()) {
            if (!e.getKey().startsWith("_")) {
                out.put(e.getKey(), e.getValue());
            }
        }
        return out;
    }

    public static void processCommandFileOnce(Path dir) throws IOException {
        Path cmdPath = dir.resolve("cmd.json");
        Path statePath = dir.resolve("state.json");
        if (!Files.exists(cmdPath)) return;
        String raw;
        try {
            raw = Files.readString(cmdPath, StandardCharsets.UTF_8);
        } finally {
            Files.deleteIfExists(cmdPath);
        }
        Map<String, Object> res = handleRawCommand(raw);
        writeJsonAtomic(statePath, externalizeState(res));
        if (Boolean.TRUE.equals(res.get("_quit"))) {
            performQuitShutdown();
        }
    }

    private static Map<String, Object> baseResponse(long seq, boolean ok, String op) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("seq", seq);
        m.put("ok", ok);
        m.put("ts", System.currentTimeMillis());
        if (op != null) m.put("op", op);
        return m;
    }

    private static void execState(Map<String, Object> out) {
        Throwable err = runOnUiSync(() -> {
            Activity a = ActivityHost.peek();
            View root = a != null ? resolveRootView(a) : null;
            int[] surf = resolveSurfaceSize(a, root);
            int activeCount = countActiveActivities();
            out.put("activity", a != null ? a.getClass().getName() : null);
            out.put("package", resolvePackageName(a));
            out.put("orientation", resolveOrientation(a, surf[0], surf[1]));
            out.put("requestedOrientation", a != null ? safeRequestedOrientation(a) : -1);
            out.put("surfaceWidth", surf[0]);
            out.put("surfaceHeight", surf[1]);
            long packed = safeVideoRectPacked();
            out.put("videoSinkState", decodeVideoSinkState(packed));
            if (isVideoRectVisible(packed)) {
                out.put("videoRect", unpackVideoRect(packed));
            }
            out.put("directTextureViewActive", safeSurfaceTextureVideoRectPacked() != 0L);
            out.put("activeActivities", activeCount);
        });
        if (err != null) throw new RuntimeException(err);
    }

    private static void execUiTree(Map<String, Object> out) {
        Throwable err = runOnUiSync(() -> {
            Activity a = ActivityHost.peek();
            if (a == null) throw new IllegalStateException("no active Activity");
            View root = resolveRootView(a);
            if (root == null) throw new IllegalStateException("active Activity has no decor/content view");
            int[] surf = resolveSurfaceSize(a, root);
            int statusBarH = safeStatusBarHeight(root);
            List<WindowOverlayStack.Layer> overlays = Collections.emptyList();
            try { overlays = WindowOverlayStack.snapshot(); } catch (Throwable ignored) {}
            List<OverlayEntry> entries = new ArrayList<>(overlays.size());
            for (WindowOverlayStack.Layer layer : overlays) {
                if (layer != null && layer.view != null) {
                    entries.add(new OverlayEntry(layer.view, layer.bounds));
                }
            }
            List<Map<String, Object>> nodes = collectUiTree(root, entries, statusBarH, surf[0], surf[1]);
            out.put("activity", a.getClass().getName());
            out.put("surfaceWidth", surf[0]);
            out.put("surfaceHeight", surf[1]);
            out.put("nodeCount", nodes.size());
            out.put("nodes", nodes);
        });
        if (err != null) throw new RuntimeException(err);
    }

    private static void execTap(Map<String, Object> cmd, Map<String, Object> out) {
        int[] surf = resolveCurrentSurfaceSize();
        double[] xy = resolvePoint(cmd, "x", "y", "fx", "fy", surf[0], surf[1]);
        float px = (float) xy[0];
        float py = (float) xy[1];
        long holdMs = Math.max(0L, Math.min(5000L, optLong(cmd, "holdMs", 60L)));
        String mode = optString(cmd, "mode");
        if (!TouchDispatcher.macapkHasInstance()) {
            throw new IllegalStateException("TouchDispatcher not initialized");
        }
        if ("view".equalsIgnoreCase(mode)) {
            TouchDispatcher.macapkInjectTap(px, py);
        } else {
            mode = "host";
            TouchDispatcher.macapkInjectHostTap(px, py, holdMs);
        }
        TouchDispatcher.macapkAwaitLoopIdle(2000L);
        out.put("x", round2(px));
        out.put("y", round2(py));
        if (surf[0] > 0 && surf[1] > 0) {
            out.put("fx", round4(px / surf[0]));
            out.put("fy", round4(py / surf[1]));
        }
        out.put("holdMs", holdMs);
        out.put("mode", mode);
        out.put("surfaceWidth", surf[0]);
        out.put("surfaceHeight", surf[1]);
    }

    private static void execSwipe(Map<String, Object> cmd, Map<String, Object> out) {
        int[] surf = resolveCurrentSurfaceSize();
        double[] p1 = resolvePoint(cmd, "x1", "y1", "fx1", "fy1", surf[0], surf[1]);
        double[] p2 = resolvePoint(cmd, "x2", "y2", "fx2", "fy2", surf[0], surf[1]);
        float x1 = (float) p1[0], y1 = (float) p1[1];
        float x2 = (float) p2[0], y2 = (float) p2[1];
        long durationMs = Math.max(32L, Math.min(2000L, optLong(cmd, "durationMs", 250L)));
        String mode = optString(cmd, "mode");
        if (!TouchDispatcher.macapkHasInstance()) {
            throw new IllegalStateException("TouchDispatcher not initialized");
        }
        if ("view".equalsIgnoreCase(mode)) {
            TouchDispatcher.macapkInjectSwipe(x1, y1, x2, y2, durationMs);
        } else {
            mode = "host";
            TouchDispatcher.macapkInjectHostSwipe(x1, y1, x2, y2, durationMs);
        }
        TouchDispatcher.macapkAwaitLoopIdle(2000L);
        out.put("x1", round2(x1));
        out.put("y1", round2(y1));
        out.put("x2", round2(x2));
        out.put("y2", round2(y2));
        out.put("durationMs", durationMs);
        out.put("mode", mode);
        out.put("surfaceWidth", surf[0]);
        out.put("surfaceHeight", surf[1]);
    }

    private static void execKey(Map<String, Object> cmd, Map<String, Object> out) {
        int keyCode = requireInt(cmd, "keyCode");
        TouchDispatcher.macapkInjectKey(keyCode);
        TouchDispatcher.macapkAwaitLoopIdle(2000L);
        out.put("keyCode", keyCode);
    }

    private static void execSetOrientation(Map<String, Object> cmd, Map<String, Object> out) {
        Object rawOrient = cmd.get("orientation");
        if (rawOrient == null) throw new IllegalArgumentException("missing required field 'orientation'");
        final int orient = parseOrientationArg(rawOrient);
        Throwable err = runOnUiSync(() -> {
            Activity a = ActivityHost.peek();
            if (a == null) throw new IllegalStateException("no active Activity");
            a.setRequestedOrientation(orient);
        });
        if (err != null) throw new RuntimeException(err);
        out.put("requestedOrientation", orient);
    }

    private static void execOpenUrl(Map<String, Object> cmd, Map<String, Object> out) {
        final String url = requireString(cmd, "url");
        Throwable err = runOnUiSync(() -> {
            Activity a = ActivityHost.peek();
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            if (a != null) {
                a.startActivity(intent);
            } else {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ActivityHost.start(intent);
            }
        });
        if (err != null) throw new RuntimeException(err);
        out.put("url", url);
    }

    private static void performQuitShutdown() {
        sleepQuiet(50);
        sExitAction.run();
    }

    /** Exposed for unit tests to verify normalized/pixel coordinate conversion. */
    public static double[] resolvePoint(Map<String, Object> cmd,
                                        String xKey, String yKey,
                                        String fxKey, String fyKey,
                                        int surfW, int surfH) {
        boolean hasPx = cmd.containsKey(xKey) || cmd.containsKey(yKey);
        boolean hasNorm = cmd.containsKey(fxKey) || cmd.containsKey(fyKey);
        if (hasPx) {
            if (!(cmd.get(xKey) instanceof Number) || !(cmd.get(yKey) instanceof Number)) {
                throw new IllegalArgumentException("both '" + xKey + "' and '" + yKey + "' must be numeric");
            }
            double x = ((Number) cmd.get(xKey)).doubleValue();
            double y = ((Number) cmd.get(yKey)).doubleValue();
            if (!Double.isFinite(x) || !Double.isFinite(y)) {
                throw new IllegalArgumentException("non-finite pixel coordinates");
            }
            return new double[]{x, y};
        }
        if (hasNorm) {
            if (!(cmd.get(fxKey) instanceof Number) || !(cmd.get(fyKey) instanceof Number)) {
                throw new IllegalArgumentException("both '" + fxKey + "' and '" + fyKey + "' must be numeric");
            }
            if (surfW <= 0 || surfH <= 0) {
                throw new IllegalStateException("surface size unavailable for normalized coordinates");
            }
            double fx = ((Number) cmd.get(fxKey)).doubleValue();
            double fy = ((Number) cmd.get(fyKey)).doubleValue();
            if (!Double.isFinite(fx) || !Double.isFinite(fy)) {
                throw new IllegalArgumentException("non-finite normalized coordinates");
            }
            return new double[]{fx * surfW, fy * surfH};
        }
        throw new IllegalArgumentException(
                "missing coordinates: provide (" + xKey + "," + yKey + ") or (" + fxKey + "," + fyKey + ")");
    }

    private static void execVideoStats(Map<String, Object> cmd, Map<String, Object> out) {
        boolean reset = Boolean.TRUE.equals(cmd.get("reset"));
        if (reset) {
            android.view.Surface.macapkResetVideoStats();
            out.put("resetApplied", Boolean.TRUE);
        }
        long[] prod = android.view.Surface.macapkSnapshotProducerStats();
        long[] ipc = android.view.Surface.macapkSnapshotIpcConsumerStats();
        long[] cons = android.graphics.SurfaceTexture.macapkSnapshotConsumerStats();
        double[] lat = android.graphics.SurfaceTexture.macapkSnapshotLatchIntervals();

        Map<String, Object> producer = new LinkedHashMap<>();
        producer.put("dequeuedOut", prod[android.view.Surface.STAT_DEQUEUED_OUT]);
        producer.put("releaseTrue", prod[android.view.Surface.STAT_RELEASE_TRUE]);
        producer.put("releaseFalse", prod[android.view.Surface.STAT_RELEASE_FALSE]);
        producer.put("releaseTimed", prod[android.view.Surface.STAT_RELEASE_TIMED]);
        producer.put("timedSuperseded", prod[android.view.Surface.STAT_TIMED_SUPERSEDED]);
        producer.put("timedOverflow", prod[android.view.Surface.STAT_TIMED_OVERFLOW]);
        producer.put("ipcExportFail", prod[android.view.Surface.STAT_IPC_EXPORT_FAIL]);
        producer.put("ipcSent", prod[android.view.Surface.STAT_IPC_SENT]);

        Map<String, Object> consumer = new LinkedHashMap<>();
        consumer.put("ipcRecv", ipc[0]);
        consumer.put("ipcSeqOutOfOrder", ipc[1]);
        consumer.put("ipcDroppedInvalid", ipc[2]);
        consumer.put("ringAccepted", cons[0]);
        consumer.put("ringEvicted", cons[1]);
        consumer.put("updateTexLatched", cons[2]);
        consumer.put("uniqueTimestampLatched", cons[3]);
        consumer.put("firstLatchedTsNs", cons[4]);
        consumer.put("lastLatchedTsNs", cons[5]);
        consumer.put("eglDirectSwaps", cons[6]);
        consumer.put("eglPbufferSwaps", cons[7]);

        Map<String, Object> latchIntervalMs = new LinkedHashMap<>();
        latchIntervalMs.put("count", (long) lat[0]);
        latchIntervalMs.put("p50", lat[1]);
        latchIntervalMs.put("p95", lat[2]);
        latchIntervalMs.put("p99", lat[3]);
        latchIntervalMs.put("max", lat[4]);
        latchIntervalMs.put("gt50ms", (long) lat[5]);
        latchIntervalMs.put("gt80ms", (long) lat[6]);

        out.put("wallNanoTime", System.nanoTime());
        out.put("producer", producer);
        out.put("consumer", consumer);
        out.put("latchIntervalMs", latchIntervalMs);
        // Also populate flat top-level keys for direct lookup convenience.
        out.putAll(producer);
        out.putAll(consumer);
    }

    public static int parseOrientationArg(Object val) {
        if (val instanceof Number) return ((Number) val).intValue();
        if (val instanceof String) {
            String s = ((String) val).trim().toLowerCase(java.util.Locale.ROOT);
            switch (s) {
                case "landscape": return 0;          // ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                case "portrait": return 1;           // ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                case "unspecified": return -1;       // ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                case "sensor_landscape": return 6;   // ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                case "sensor_portrait": return 7;    // ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                case "reverse_landscape": return 8;  // ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
                case "reverse_portrait": return 9;   // ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
                default:
                    try { return Integer.parseInt(s); }
                    catch (NumberFormatException ignored) {}
            }
        }
        throw new IllegalArgumentException("invalid orientation: " + val);
    }

    /** Walk the laid-out View hierarchy and visible overlays.
     *  Follows ViewTextDump's global visible rect calculation. Exposed for unit tests. */
    public static List<Map<String, Object>> collectUiTree(View root,
                                                          List<OverlayEntry> overlays,
                                                          int statusBarH,
                                                          int surfW,
                                                          int surfH) {
        List<Map<String, Object>> nodes = new ArrayList<>();
        if (root == null) return nodes;
        int effW = surfW > 0 ? surfW : Math.max(1, root.getRight());
        int effH = surfH > 0 ? surfH : Math.max(1, root.getBottom());
        Rect rootRect = new Rect(root.getLeft(), root.getTop(), root.getRight(), root.getBottom());
        if (!rootRect.isEmpty()) {
            walkView(root, 0, 0, rootRect, "a", effW, effH, nodes);
        }
        if (overlays != null) {
            int idx = 0;
            for (OverlayEntry layer : overlays) {
                if (layer == null || layer.view == null) continue;
                View v = layer.view;
                if (v.getVisibility() != View.VISIBLE) continue;
                Rect lb = layer.bounds != null
                        ? layer.bounds
                        : new Rect(0, 0, v.getWidth(), v.getHeight());
                int al = lb.left;
                int at = statusBarH + lb.top;
                Rect wr = new Rect(al, at, al + lb.width(), at + lb.height());
                Rect surfaceClip = new Rect(0, 0, effW, effH);
                if (!wr.intersect(surfaceClip) || wr.isEmpty()) continue;
                String win = "o" + (idx++);
                walkView(v, al - v.getLeft(), at - v.getTop(), wr, win, effW, effH, nodes);
            }
        }
        return nodes;
    }

    private static void walkView(View v, int ox, int oy, Rect clip, String win,
                                 int surfW, int surfH, List<Map<String, Object>> out) {
        if (v == null || out.size() >= MAX_UI_NODES) return;
        if (v.getVisibility() != View.VISIBLE) return;
        try {
            if (v.getAlpha() <= 0f || v.getTransitionAlpha() <= 0f) return;
        } catch (Throwable ignored) {}
        int l = ox + v.getLeft() + Math.round(safeTx(v));
        int t = oy + v.getTop() + Math.round(safeTy(v));
        Rect abs = new Rect(l, t, l + v.getWidth(), t + v.getHeight());
        Rect vis = new Rect(abs);
        boolean shown = vis.intersect(clip);
        if (shown && !vis.isEmpty()) {
            out.add(buildNodeMap(v, vis, win, surfW, surfH));
        }
        if (v instanceof ViewGroup && out.size() < MAX_UI_NODES) {
            ViewGroup g = (ViewGroup) v;
            boolean clips = true;
            try { clips = g.getClipChildren(); } catch (Throwable ignored) {}
            Rect childClip;
            if (clips) {
                if (!shown || vis.isEmpty()) return;
                childClip = vis;
            } else {
                childClip = clip;
            }
            int cox = l - v.getScrollX();
            int coy = t - v.getScrollY();
            int n = g.getChildCount();
            for (int i = 0; i < n && out.size() < MAX_UI_NODES; i++) {
                walkView(g.getChildAt(i), cox, coy, childClip, win, surfW, surfH, out);
            }
        }
    }

    private static Map<String, Object> buildNodeMap(View v, Rect vis, String win,
                                                    int surfW, int surfH) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("win", win);
        m.put("cls", resolveClassName(v));
        m.put("idName", resolveIdName(v));

        String text = null;
        if (v instanceof TextView) {
            TextView tv = (TextView) v;
            CharSequence cs = null;
            try { cs = tv.getText(); } catch (Throwable ignored) {}
            if (cs != null && cs.length() > 0) {
                text = cs.toString();
                try {
                    if (tv.getTransformationMethod() instanceof PasswordTransformationMethod) {
                        text = "\u2022".repeat(text.length());
                    }
                } catch (Throwable ignored) {}
            } else {
                CharSequence hint = null;
                try { hint = tv.getHint(); } catch (Throwable ignored) {}
                if (hint != null && hint.length() > 0) text = hint.toString();
            }
        }

        CharSequence descCs = null;
        try { descCs = v.getContentDescription(); } catch (Throwable ignored) {}
        String desc = (descCs != null && descCs.length() > 0) ? descCs.toString() : null;

        AccessibilityNodeInfo info = null;
        try {
            AccessibilityNodeProvider prov = v.getAccessibilityNodeProvider();
            if (prov == null) info = v.createAccessibilityNodeInfo();
        } catch (Throwable ignored) {}
        if (info != null) {
            try {
                CharSequence it = info.getText();
                if (it != null && it.length() > 0) text = it.toString();
                CharSequence id = info.getContentDescription();
                if (desc == null && id != null && id.length() > 0) desc = id.toString();
            } catch (Throwable ignored) {}
        }

        boolean clickable = false;
        try { clickable = v.isClickable() || v.hasOnClickListeners(); } catch (Throwable ignored) {}
        boolean selected = false;
        try { selected = v.isSelected(); } catch (Throwable ignored) {}
        boolean checked = false;
        try {
            if (v instanceof Checkable) checked = ((Checkable) v).isChecked();
            else if (info != null) checked = info.isChecked();
        } catch (Throwable ignored) {}

        m.put("text", text);
        m.put("desc", desc);
        m.put("clickable", clickable);
        m.put("selected", selected);
        m.put("checked", checked);

        List<Integer> boundsPx = new ArrayList<>(4);
        boundsPx.add(vis.left);
        boundsPx.add(vis.top);
        boundsPx.add(vis.right);
        boundsPx.add(vis.bottom);
        m.put("boundsPx", boundsPx);

        double cx = surfW > 0 ? clamp01(((vis.left + vis.right) * 0.5) / surfW) : 0.0;
        double cy = surfH > 0 ? clamp01(((vis.top + vis.bottom) * 0.5) / surfH) : 0.0;
        List<Double> centerNorm = new ArrayList<>(2);
        centerNorm.add(round4(cx));
        centerNorm.add(round4(cy));
        m.put("centerNorm", centerNorm);
        return m;
    }

    private static String resolveClassName(View v) {
        Class<?> c = v.getClass();
        String s = c.getSimpleName();
        if (s != null && !s.isEmpty()) return s;
        String full = c.getName();
        int dot = full.lastIndexOf('.');
        return dot >= 0 && dot + 1 < full.length() ? full.substring(dot + 1) : full;
    }

    private static String resolveIdName(View v) {
        int id;
        try { id = v.getId(); } catch (Throwable t) { return null; }
        if (id == View.NO_ID || id == 0) return null;
        try {
            Resources res = v.getResources();
            if (res == null) return null;
            return res.getResourceEntryName(id);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static float safeTx(View v) {
        try { return v.getTranslationX(); } catch (Throwable ignored) { return 0f; }
    }

    private static float safeTy(View v) {
        try { return v.getTranslationY(); } catch (Throwable ignored) { return 0f; }
    }

    private static View resolveRootView(Activity a) {
        if (a == null) return null;
        View content = null;
        try { content = a.macapkContentView(); } catch (Throwable ignored) {}
        if (content != null) {
            try {
                View rv = content.getRootView();
                if (rv != null && rv.getWidth() > 0 && rv.getHeight() > 0) return rv;
            } catch (Throwable ignored) {}
            if (content.getWidth() > 0 && content.getHeight() > 0) return content;
        }
        try {
            Window w = a.getWindow();
            if (w != null) {
                View decor = w.peekDecorView();
                if (decor != null && decor.getWidth() > 0 && decor.getHeight() > 0) return decor;
                if (content == null) return decor;
            }
        } catch (Throwable ignored) {}
        return content;
    }

    private static int[] resolveCurrentSurfaceSize() {
        final int[] out = new int[]{0, 0};
        Throwable err = runOnUiSync(() -> {
            Activity a = ActivityHost.peek();
            View root = resolveRootView(a);
            int[] sz = resolveSurfaceSize(a, root);
            out[0] = sz[0];
            out[1] = sz[1];
        });
        if (err != null) {
            out[0] = TouchDispatcher.macapkSurfaceWidthPx();
            out[1] = TouchDispatcher.macapkSurfaceHeightPx();
        }
        return out;
    }

    private static int[] resolveSurfaceSize(Activity a, View root) {
        int w = TouchDispatcher.macapkSurfaceWidthPx();
        int h = TouchDispatcher.macapkSurfaceHeightPx();
        if (w > 0 && h > 0) return new int[]{w, h};
        try {
            w = DisplayConfig.realWidthPx();
            h = DisplayConfig.realHeightPx();
            if (w > 0 && h > 0) return new int[]{w, h};
        } catch (Throwable ignored) {}
        if (a != null) {
            try {
                Resources res = a.getResources();
                DisplayMetrics dm = res != null ? res.getDisplayMetrics() : null;
                if (dm != null && dm.widthPixels > 0 && dm.heightPixels > 0) {
                    return new int[]{dm.widthPixels, dm.heightPixels};
                }
            } catch (Throwable ignored) {}
        }
        if (root != null && root.getWidth() > 0 && root.getHeight() > 0) {
            return new int[]{root.getWidth(), root.getHeight()};
        }
        return new int[]{0, 0};
    }

    private static int resolveOrientation(Activity a, int w, int h) {
        if (a != null) {
            try {
                Resources res = a.getResources();
                Configuration cfg = res != null ? res.getConfiguration() : null;
                if (cfg != null && cfg.orientation != Configuration.ORIENTATION_UNDEFINED) {
                    return cfg.orientation;
                }
            } catch (Throwable ignored) {}
        }
        if (w > 0 && h > 0) {
            return w > h ? Configuration.ORIENTATION_LANDSCAPE : Configuration.ORIENTATION_PORTRAIT;
        }
        return Configuration.ORIENTATION_UNDEFINED;
    }

    private static int safeRequestedOrientation(Activity a) {
        try { return a.getRequestedOrientation(); }
        catch (Throwable ignored) { return -1; }
    }

    private static String resolvePackageName(Activity a) {
        try {
            String p = Boot.packageName();
            if (p != null && !p.isEmpty()) return p;
        } catch (Throwable ignored) {}
        if (a != null) {
            try { return a.getPackageName(); } catch (Throwable ignored) {}
        }
        return null;
    }

    private static int countActiveActivities() {
        try {
            Activity[] snap = ActivityHost.macapkTaskActivitySnapshot();
            return snap != null ? snap.length : 0;
        } catch (Throwable ignored) {
            return ActivityHost.peek() != null ? 1 : 0;
        }
    }

    private static int safeStatusBarHeight(View root) {
        try { return StatusBarDriver.heightPx(root); }
        catch (Throwable ignored) { return 0; }
    }

    private static long safeVideoRectPacked() {
        try { return SurfaceView.macapkActiveVideoRectPacked(); }
        catch (Throwable ignored) { return 0L; }
    }

    private static long safeSurfaceTextureVideoRectPacked() {
        try { return android.graphics.SurfaceTexture.macapkActiveVideoRectPacked(); }
        catch (Throwable ignored) { return 0L; }
    }

    public static String decodeVideoSinkState(long packed) {
        if (packed == 0L) return "NONE";
        if (packed == -1L) return "HIDDEN";
        int w = (short) ((packed >>> 16) & 0xFFFF);
        int h = (short) (packed & 0xFFFF);
        return (w > 0 && h > 0) ? "VISIBLE" : "HIDDEN";
    }

    private static boolean isVideoRectVisible(long packed) {
        return "VISIBLE".equals(decodeVideoSinkState(packed));
    }

    private static List<Integer> unpackVideoRect(long packed) {
        int x = (short) ((packed >>> 48) & 0xFFFF);
        int y = (short) ((packed >>> 32) & 0xFFFF);
        int w = (short) ((packed >>> 16) & 0xFFFF);
        int h = (short) (packed & 0xFFFF);
        List<Integer> r = new ArrayList<>(4);
        r.add(x);
        r.add(y);
        r.add(w);
        r.add(h);
        return r;
    }

    /** Executes `task` synchronously on Android's main Looper (or directly inline
     *  in bare-JVM tests where Looper.getMainLooper() is null). */
    private static Throwable runOnUiSync(Runnable task) {
        Looper main = null;
        try { main = Looper.getMainLooper(); } catch (Throwable ignored) {}
        if (main == null) {
            try {
                task.run();
                return null;
            } catch (Throwable t) {
                return t;
            }
        }
        try {
            if (Looper.myLooper() == main) {
                task.run();
                return null;
            }
        } catch (Throwable ignored) {}
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<Throwable> err = new AtomicReference<>();
        new Handler(main).post(() -> {
            try { task.run(); }
            catch (Throwable t) { err.set(t); }
            finally { latch.countDown(); }
        });
        try {
            if (!latch.await(UI_LOOP_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                return new IllegalStateException("timed out waiting for UI Looper after " + UI_LOOP_TIMEOUT_MS + "ms");
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return ie;
        }
        return err.get();
    }

    private static String describeError(Throwable t) {
        if (t == null) return "unknown error";
        Throwable root = t;
        while (root.getCause() != null && root instanceof RuntimeException && root.getMessage() != null
                && root.getMessage().equals(root.getCause().toString())) {
            root = root.getCause();
        }
        String msg = root.getMessage();
        return (msg != null && !msg.isEmpty())
                ? root.getClass().getSimpleName() + ": " + msg
                : root.getClass().getName();
    }

    public static void writeJsonAtomic(Path target, Map<String, Object> data) throws IOException {
        Path parent = target.getParent();
        if (parent != null) Files.createDirectories(parent);
        String json = toJson(externalizeState(data)) + "\n";
        Path tmp = Files.createTempFile(parent != null ? parent : Paths.get("."),
                target.getFileName().toString() + ".", ".tmp");
        try {
            Files.writeString(tmp, json, StandardCharsets.UTF_8);
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            try { Files.deleteIfExists(tmp); } catch (Throwable ignored) {}
        }
    }

    // ---- Pure-Java RFC 8259 JSON parser & serializer (zero external dependencies) ----

    public static String toJson(Object value) {
        StringBuilder sb = new StringBuilder(256);
        appendJson(sb, value);
        return sb.toString();
    }

    private static void appendJson(StringBuilder sb, Object value) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof Boolean) {
            sb.append(((Boolean) value).booleanValue() ? "true" : "false");
        } else if (value instanceof Number) {
            if (value instanceof Double || value instanceof Float) {
                double d = ((Number) value).doubleValue();
                if (!Double.isFinite(d)) sb.append("null");
                else if (d == Math.rint(d) && Math.abs(d) < 1e15) sb.append(Long.toString((long) d));
                else sb.append(Double.toString(d));
            } else {
                sb.append(value.toString());
            }
        } else if (value instanceof CharSequence) {
            appendJsonString(sb, value.toString());
        } else if (value instanceof Map<?, ?>) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : ((Map<?, ?>) value).entrySet()) {
                if (!first) sb.append(',');
                first = false;
                appendJsonString(sb, String.valueOf(e.getKey()));
                sb.append(':');
                appendJson(sb, e.getValue());
            }
            sb.append('}');
        } else if (value instanceof Iterable<?>) {
            sb.append('[');
            boolean first = true;
            for (Object item : (Iterable<?>) value) {
                if (!first) sb.append(',');
                first = false;
                appendJson(sb, item);
            }
            sb.append(']');
        } else if (value instanceof int[]) {
            int[] arr = (int[]) value;
            sb.append('[');
            for (int i = 0; i < arr.length; i++) {
                if (i > 0) sb.append(',');
                sb.append(arr[i]);
            }
            sb.append(']');
        } else if (value instanceof long[]) {
            long[] arr = (long[]) value;
            sb.append('[');
            for (int i = 0; i < arr.length; i++) {
                if (i > 0) sb.append(',');
                sb.append(arr[i]);
            }
            sb.append(']');
        } else if (value instanceof double[]) {
            double[] arr = (double[]) value;
            sb.append('[');
            for (int i = 0; i < arr.length; i++) {
                if (i > 0) sb.append(',');
                appendJson(sb, arr[i]);
            }
            sb.append(']');
        } else if (value instanceof Object[]) {
            Object[] arr = (Object[]) value;
            sb.append('[');
            for (int i = 0; i < arr.length; i++) {
                if (i > 0) sb.append(',');
                appendJson(sb, arr[i]);
            }
            sb.append(']');
        } else {
            appendJsonString(sb, String.valueOf(value));
        }
    }

    static void appendJsonString(StringBuilder sb, String s) {
        sb.append('"');
        final int n = s.length();
        for (int i = 0; i < n; i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20 || Character.isSurrogate(c)) {
                        sb.append(String.format(java.util.Locale.ROOT, "\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                    break;
            }
        }
        sb.append('"');
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseJsonObject(String json) {
        Object v = new JsonParser(json).parseRoot();
        if (!(v instanceof Map<?, ?>)) {
            throw new IllegalArgumentException("expected JSON object at root");
        }
        return (Map<String, Object>) v;
    }

    public static Object parseJsonValue(String json) {
        return new JsonParser(json).parseRoot();
    }

    private static String requireString(Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (!(v instanceof String) || ((String) v).isEmpty()) {
            throw new IllegalArgumentException("missing or non-string field '" + key + "'");
        }
        return (String) v;
    }

    private static String optString(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v instanceof String ? (String) v : null;
    }

    private static int requireInt(Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (!(v instanceof Number)) {
            throw new IllegalArgumentException("missing or non-numeric field '" + key + "'");
        }
        return ((Number) v).intValue();
    }

    private static long optLong(Map<String, Object> m, String key, long def) {
        Object v = m.get(key);
        return v instanceof Number ? ((Number) v).longValue() : def;
    }

    private static double clamp01(double v) {
        return v < 0.0 ? 0.0 : (v > 1.0 ? 1.0 : v);
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private static double round4(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }

    private static final class JsonParser {
        private final String s;
        private int pos;

        JsonParser(String s) {
            if (s == null) throw new IllegalArgumentException("null JSON input");
            this.s = s;
        }

        Object parseRoot() {
            skipWs();
            Object v = parseValue();
            skipWs();
            if (pos != s.length()) {
                throw new IllegalArgumentException("unexpected trailing content at pos " + pos);
            }
            return v;
        }

        private Object parseValue() {
            skipWs();
            if (pos >= s.length()) throw new IllegalArgumentException("unexpected end of JSON");
            char c = s.charAt(pos);
            switch (c) {
                case '{': return parseObject();
                case '[': return parseArray();
                case '"': return parseString();
                case 't': return parseLiteral("true", Boolean.TRUE);
                case 'f': return parseLiteral("false", Boolean.FALSE);
                case 'n': return parseLiteral("null", null);
                default:
                    if (c == '-' || (c >= '0' && c <= '9')) return parseNumber();
                    throw new IllegalArgumentException("unexpected char '" + c + "' at pos " + pos);
            }
        }

        private Map<String, Object> parseObject() {
            expect('{');
            Map<String, Object> map = new LinkedHashMap<>();
            skipWs();
            if (peek('}')) {
                pos++;
                return map;
            }
            while (true) {
                skipWs();
                if (!peek('"')) throw new IllegalArgumentException("expected object key at pos " + pos);
                String key = parseString();
                skipWs();
                expect(':');
                Object val = parseValue();
                map.put(key, val);
                skipWs();
                if (peek(',')) {
                    pos++;
                } else if (peek('}')) {
                    pos++;
                    return map;
                } else {
                    throw new IllegalArgumentException("expected ',' or '}' at pos " + pos);
                }
            }
        }

        private List<Object> parseArray() {
            expect('[');
            List<Object> list = new ArrayList<>();
            skipWs();
            if (peek(']')) {
                pos++;
                return list;
            }
            while (true) {
                list.add(parseValue());
                skipWs();
                if (peek(',')) {
                    pos++;
                } else if (peek(']')) {
                    pos++;
                    return list;
                } else {
                    throw new IllegalArgumentException("expected ',' or ']' at pos " + pos);
                }
            }
        }

        private String parseString() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (pos < s.length()) {
                char c = s.charAt(pos++);
                if (c == '"') return sb.toString();
                if (c < 0x20) throw new IllegalArgumentException("unescaped control char in string at pos " + (pos - 1));
                if (c == '\\') {
                    if (pos >= s.length()) throw new IllegalArgumentException("unterminated escape at end");
                    char esc = s.charAt(pos++);
                    switch (esc) {
                        case '"': sb.append('"'); break;
                        case '\\': sb.append('\\'); break;
                        case '/': sb.append('/'); break;
                        case 'b': sb.append('\b'); break;
                        case 'f': sb.append('\f'); break;
                        case 'n': sb.append('\n'); break;
                        case 'r': sb.append('\r'); break;
                        case 't': sb.append('\t'); break;
                        case 'u':
                            if (pos + 4 > s.length()) {
                                throw new IllegalArgumentException("incomplete \\u escape at pos " + pos);
                            }
                            int code = Integer.parseInt(s.substring(pos, pos + 4), 16);
                            sb.append((char) code);
                            pos += 4;
                            break;
                        default:
                            throw new IllegalArgumentException("invalid escape '\\" + esc + "' at pos " + (pos - 1));
                    }
                } else {
                    sb.append(c);
                }
            }
            throw new IllegalArgumentException("unterminated string");
        }

        private Number parseNumber() {
            int start = pos;
            if (s.charAt(pos) == '-') pos++;
            if (pos >= s.length() || !isDigit(s.charAt(pos))) {
                throw new IllegalArgumentException("invalid number at pos " + start);
            }
            if (s.charAt(pos) == '0') {
                pos++;
            } else {
                while (pos < s.length() && isDigit(s.charAt(pos))) pos++;
            }
            boolean isFloat = false;
            if (pos < s.length() && s.charAt(pos) == '.') {
                isFloat = true;
                pos++;
                if (pos >= s.length() || !isDigit(s.charAt(pos))) {
                    throw new IllegalArgumentException("invalid fraction at pos " + start);
                }
                while (pos < s.length() && isDigit(s.charAt(pos))) pos++;
            }
            if (pos < s.length() && (s.charAt(pos) == 'e' || s.charAt(pos) == 'E')) {
                isFloat = true;
                pos++;
                if (pos < s.length() && (s.charAt(pos) == '+' || s.charAt(pos) == '-')) pos++;
                if (pos >= s.length() || !isDigit(s.charAt(pos))) {
                    throw new IllegalArgumentException("invalid exponent at pos " + start);
                }
                while (pos < s.length() && isDigit(s.charAt(pos))) pos++;
            }
            String numStr = s.substring(start, pos);
            if (!isFloat) {
                try { return Long.parseLong(numStr); }
                catch (NumberFormatException ignored) {}
            }
            return Double.parseDouble(numStr);
        }

        private Object parseLiteral(String lit, Object val) {
            if (!s.startsWith(lit, pos)) {
                throw new IllegalArgumentException("expected '" + lit + "' at pos " + pos);
            }
            pos += lit.length();
            return val;
        }

        private void skipWs() {
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') pos++;
                else break;
            }
        }

        private boolean peek(char c) {
            return pos < s.length() && s.charAt(pos) == c;
        }

        private void expect(char c) {
            if (pos >= s.length() || s.charAt(pos) != c) {
                throw new IllegalArgumentException("expected '" + c + "' at pos " + pos);
            }
            pos++;
        }

        private static boolean isDigit(char c) {
            return c >= '0' && c <= '9';
        }
    }
}
