import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Rect;
import android.util.DisplayMetrics;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.macapk.loader.AutomationServer;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/** Bare-JVM regression test for AutomationServer:
 *  - JSON parser & RFC 8259 serializer (escaping, surrogates, validation)
 *  - Zero-overhead opt-out when MACAPK_AUTOMATION != "1" or dir missing
 *  - Normalized fx,fy <-> surface pixel resolution
 *  - ui_tree extraction schema, boundsPx/centerNorm, overlays, resource idName
 *  - Atomic ready.json / cmd.json / state.json round-trip & pre-exit quit ack */
public class AutomationServerTest {

    private static final class StubResources extends Resources {
        StubResources() {
            super(null, new DisplayMetrics(), new Configuration());
        }

        @Override public String getResourceEntryName(int resid) {
            if (resid == 0x7f0a0001) return "search_box";
            if (resid == 0x7f0a0002) return "chk_remember";
            if (resid == 0x7f0a0003) return "btn_close_overlay";
            throw new Resources.NotFoundException("0x" + Integer.toHexString(resid));
        }
    }

    private static final class StubContext extends ContextWrapper {
        private final Resources res = new StubResources();

        StubContext() { super(null); }

        @Override public Resources getResources() { return res; }
    }

    private static void check(boolean cond, String msg) {
        if (!cond) throw new AssertionError(msg);
    }

    private static void checkEq(Object expected, Object actual, String msg) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError(msg + ": expected=" + expected + " actual=" + actual);
        }
    }

    private static void testOptOutConfig() throws Exception {
        Path tmp = Files.createTempDirectory("macapk-auto-optout-");
        try {
            check(!AutomationServer.isEnabledConfig(null, tmp.toString()), "null flag must opt out");
            check(!AutomationServer.isEnabledConfig("0", tmp.toString()), "0 flag must opt out");
            check(!AutomationServer.isEnabledConfig("1", null), "null dir must opt out");
            check(!AutomationServer.isEnabledConfig("1", "   "), "blank dir must opt out");
            check(AutomationServer.isEnabledConfig("1", tmp.toString()), "1 + valid dir enables");
            check(!AutomationServer.startIfEnabled("0", tmp.toString()),
                  "startIfEnabled('0', dir) must return false without spawning");
            check(!Files.exists(tmp.resolve("ready.json")), "opt-out must not touch dir");
        } finally {
            deleteTree(tmp);
        }
    }

    private static void testJsonRoundTrip() {
        Map<String, Object> src = new LinkedHashMap<>();
        src.put("seq", 7L);
        src.put("ok", true);
        src.put("text", "Hello \"Bilibili\"\nLine2\t\\slash \u54d4\u54e9 \ud83d");
        src.put("empty", null);
        src.put("nums", new int[]{10, 20, 30, 40});
        src.put("norm", new double[]{0.25, 0.75});

        String json = AutomationServer.toJson(src);
        check(!json.contains("\n"), "JSON must escape newlines cleanly");
        check(json.contains("\\\"Bilibili\\\""), "JSON must escape quotes");
        check(json.contains("\\\\slash"), "JSON must escape backslash");
        check(json.contains("\\ud83d"), "JSON must escape unpaired UTF-16 surrogate");

        Map<String, Object> parsed = AutomationServer.parseJsonObject(json);
        checkEq(7L, parsed.get("seq"), "seq round-trip");
        checkEq(Boolean.TRUE, parsed.get("ok"), "bool round-trip");
        checkEq(src.get("text"), parsed.get("text"), "escaped string round-trip");
        check(parsed.containsKey("empty") && parsed.get("empty") == null, "null key preserved");
        check(parsed.get("nums") instanceof List<?>, "array decoded as List");
        checkEq(40L, ((List<?>) parsed.get("nums")).get(3), "array element");

        assertThrowsIllegalArg(() -> AutomationServer.parseJsonObject(""), "empty JSON");
        assertThrowsIllegalArg(() -> AutomationServer.parseJsonObject("[1,2]"), "non-object root");
        assertThrowsIllegalArg(() -> AutomationServer.parseJsonObject("{\"a\":1} trailing"), "trailing garbage");
        assertThrowsIllegalArg(() -> AutomationServer.parseJsonObject("{\"a\":\"\\q\"}"), "bad escape");
    }

    private static void testCoordinateResolution() {
        Map<String, Object> pxCmd = AutomationServer.parseJsonObject("{\"x\":320,\"y\":640}");
        double[] p1 = AutomationServer.resolvePoint(pxCmd, "x", "y", "fx", "fy", 1080, 1920);
        checkEq(320.0, p1[0], "explicit px x");
        checkEq(640.0, p1[1], "explicit px y");

        Map<String, Object> normCmd = AutomationServer.parseJsonObject("{\"fx\":0.5,\"fy\":0.25}");
        double[] p2 = AutomationServer.resolvePoint(normCmd, "x", "y", "fx", "fy", 1080, 1920);
        checkEq(540.0, p2[0], "normalized fx -> px");
        checkEq(480.0, p2[1], "normalized fy -> px");

        Map<String, Object> badPartial = AutomationServer.parseJsonObject("{\"fx\":0.5}");
        assertThrowsIllegalArg(() -> AutomationServer.resolvePoint(badPartial, "x", "y", "fx", "fy", 1080, 1920),
                               "partial fx without fy");

        assertThrowsIllegalArg(() -> AutomationServer.resolvePoint(normCmd, "x", "y", "fx", "fy", 0, 1920),
                               "zero surface width");

        checkEq(0, AutomationServer.parseOrientationArg("landscape"), "landscape string");
        checkEq(1, AutomationServer.parseOrientationArg("portrait"), "portrait string");
        checkEq("NONE", AutomationServer.decodeVideoSinkState(0L), "sink NONE");
        checkEq("HIDDEN", AutomationServer.decodeVideoSinkState(-1L), "sink HIDDEN");
        long visPacked = View.macapkPackVideoRect(0, 100, 1280, 720);
        checkEq("VISIBLE", AutomationServer.decodeVideoSinkState(visPacked), "sink VISIBLE");
    }

    private static void testUiTreeExtraction() {
        Context ctx = new StubContext();
        FrameLayout root = new FrameLayout(ctx);
        root.layout(0, 40, 1000, 2000);

        TextView search = new TextView(ctx);
        search.setId(0x7f0a0001);
        search.setText("Search Bilibili");
        search.setClickable(true);
        search.layout(100, 100, 500, 200); // surface abs: [100, 140, 500, 240] -> center (300, 190) -> [0.3, 0.095]
        root.addView(search);

        CheckBox remember = new CheckBox(ctx);
        remember.setId(0x7f0a0002);
        remember.setText("Remember");
        remember.setChecked(true);
        remember.setSelected(true);
        remember.layout(500, 100, 700, 200);
        root.addView(remember);

        TextView gone = new TextView(ctx);
        gone.setText("Hidden item");
        gone.setVisibility(View.GONE);
        gone.layout(0, 300, 200, 380);
        root.addView(gone);

        TextView overlayClose = new TextView(ctx);
        overlayClose.setId(0x7f0a0003);
        overlayClose.setContentDescription("Close dialog");
        overlayClose.setClickable(true);
        overlayClose.layout(0, 0, 200, 100);
        AutomationServer.OverlayEntry overlay =
                new AutomationServer.OverlayEntry(overlayClose, new Rect(100, 200, 300, 300));

        List<Map<String, Object>> nodes = AutomationServer.collectUiTree(
                root, Collections.singletonList(overlay), 40, 1000, 2000);

        // Expect: root + search + remember + overlayClose (gone excluded) = 4 nodes
        checkEq(4, nodes.size(), "visible node count");

        Map<String, Object> searchNode = findById(nodes, "search_box");
        check(searchNode != null, "search_box node present");
        checkEq("a", searchNode.get("win"), "activity win tag");
        checkEq("TextView", searchNode.get("cls"), "search_box cls");
        checkEq("Search Bilibili", searchNode.get("text"), "search_box text");
        checkEq(Boolean.TRUE, searchNode.get("clickable"), "search_box clickable");
        checkEq(List.of(100, 140, 500, 240), searchNode.get("boundsPx"), "search_box boundsPx");
        checkEq(List.of(0.3, 0.095), searchNode.get("centerNorm"), "search_box centerNorm");

        Map<String, Object> chkNode = findById(nodes, "chk_remember");
        check(chkNode != null, "chk_remember node present");
        checkEq(Boolean.TRUE, chkNode.get("checked"), "chk_remember checked");
        checkEq(Boolean.TRUE, chkNode.get("selected"), "chk_remember selected");

        Map<String, Object> ovNode = findById(nodes, "btn_close_overlay");
        check(ovNode != null, "overlay node present");
        checkEq("o0", ovNode.get("win"), "overlay win tag");
        checkEq("Close dialog", ovNode.get("desc"), "overlay desc");
        // Overlay content Rect(100,200,300,300) + statusBarH 40 -> [100,240,300,340] -> center (200,290) -> [0.2, 0.145]
        checkEq(List.of(100, 240, 300, 340), ovNode.get("boundsPx"), "overlay boundsPx");
        checkEq(List.of(0.2, 0.145), ovNode.get("centerNorm"), "overlay centerNorm");

        for (Map<String, Object> n : nodes) {
            check(!"Hidden item".equals(n.get("text")), "GONE view must be excluded");
        }
    }

    private static void testAtomicRoundTripAndQuitOrder() throws Exception {
        Path dir = Files.createTempDirectory("macapk-auto-roundtrip-");
        try {
            Map<String, Object> ready = new LinkedHashMap<>();
            ready.put("pid", 12345L);
            ready.put("package", "tv.danmaku.bili");
            ready.put("surfaceWidth", 1080);
            ready.put("surfaceHeight", 1920);
            ready.put("orientation", 1);
            AutomationServer.writeJsonAtomic(dir.resolve("ready.json"), ready);

            String readyRaw = Files.readString(dir.resolve("ready.json"), StandardCharsets.UTF_8);
            Map<String, Object> readyParsed = AutomationServer.parseJsonObject(readyRaw);
            checkEq("tv.danmaku.bili", readyParsed.get("package"), "ready.json package");

            // Bad JSON command -> clears cmd.json and writes ok=false + clear error
            Files.writeString(dir.resolve("cmd.json"), "{broken", StandardCharsets.UTF_8);
            AutomationServer.processCommandFileOnce(dir);
            check(!Files.exists(dir.resolve("cmd.json")), "cmd.json removed on invalid input");
            Map<String, Object> errState = AutomationServer.parseJsonObject(
                    Files.readString(dir.resolve("state.json"), StandardCharsets.UTF_8));
            checkEq(Boolean.FALSE, errState.get("ok"), "invalid cmd produces ok=false");
            check(errState.get("error") instanceof String && !((String) errState.get("error")).isEmpty(),
                  "invalid cmd produces non-empty error");

            // Unknown op -> ok=false + echoes seq and op
            Files.writeString(dir.resolve("cmd.json"), "{\"seq\":11,\"op\":\"nope\"}", StandardCharsets.UTF_8);
            AutomationServer.processCommandFileOnce(dir);
            Map<String, Object> unknownState = AutomationServer.parseJsonObject(
                    Files.readString(dir.resolve("state.json"), StandardCharsets.UTF_8));
            checkEq(11L, unknownState.get("seq"), "unknown op echoes seq");
            checkEq("nope", unknownState.get("op"), "unknown op echoes op");
            checkEq(Boolean.FALSE, unknownState.get("ok"), "unknown op ok=false");

            // Valid state command
            Files.writeString(dir.resolve("cmd.json"), "{\"seq\":42,\"op\":\"state\"}", StandardCharsets.UTF_8);
            AutomationServer.processCommandFileOnce(dir);
            Map<String, Object> okState = AutomationServer.parseJsonObject(
                    Files.readString(dir.resolve("state.json"), StandardCharsets.UTF_8));
            checkEq(42L, okState.get("seq"), "state seq");
            checkEq(Boolean.TRUE, okState.get("ok"), "state ok");
            checkEq("state", okState.get("op"), "state op");
            check(okState.containsKey("videoSinkState"), "state includes videoSinkState");
            check(okState.containsKey("directTextureViewActive"), "state includes directTextureViewActive");
            check(okState.containsKey("activeActivities"), "state includes activeActivities");

            // Quit must write ack state.json BEFORE exit action executes
            AtomicBoolean exitCalled = new AtomicBoolean(false);
            AtomicBoolean ackPresentBeforeExit = new AtomicBoolean(false);
            AutomationServer.setExitActionForTest(() -> {
                exitCalled.set(true);
                try {
                    if (Files.exists(dir.resolve("state.json"))) {
                        Map<String, Object> ack = AutomationServer.parseJsonObject(
                                Files.readString(dir.resolve("state.json"), StandardCharsets.UTF_8));
                        if (Long.valueOf(99L).equals(ack.get("seq"))
                                && Boolean.TRUE.equals(ack.get("ok"))
                                && "quit".equals(ack.get("op"))) {
                            ackPresentBeforeExit.set(true);
                        }
                    }
                } catch (Exception ignored) {}
            });
            try {
                Files.writeString(dir.resolve("cmd.json"), "{\"seq\":99,\"op\":\"quit\"}", StandardCharsets.UTF_8);
                AutomationServer.processCommandFileOnce(dir);
            } finally {
                AutomationServer.setExitActionForTest(null);
            }
            check(exitCalled.get(), "quit invoked exit action");
            check(ackPresentBeforeExit.get(), "quit wrote ack state.json BEFORE invoking exit");
        } finally {
            deleteTree(dir);
        }
    }

    private static Map<String, Object> findById(List<Map<String, Object>> nodes, String idName) {
        for (Map<String, Object> n : nodes) {
            if (idName.equals(n.get("idName"))) return n;
        }
        return null;
    }

    private static void assertThrowsIllegalArg(Runnable r, String label) {
        boolean threw = false;
        try {
            r.run();
        } catch (IllegalArgumentException | IllegalStateException expected) {
            threw = true;
        }
        check(threw, "expected exception for " + label);
    }

    private static void deleteTree(Path root) {
        try (var s = Files.walk(root)) {
            s.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (Exception ignored) {}
            });
        } catch (Exception ignored) {}
    }

    public static void main(String[] args) throws Exception {
        testOptOutConfig();
        testJsonRoundTrip();
        testCoordinateResolution();
        testUiTreeExtraction();
        testAtomicRoundTripAndQuitOrder();
        System.out.println("AutomationServerTest: PASS");
    }
}
