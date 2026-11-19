import android.content.ComponentName;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.IInterface;
import android.os.MacapkParcelWire;
import android.os.Parcel;
import android.os.PatternMatcher;
import android.os.RemoteException;
import android.os.ServiceManager;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;

/**
 * Offline Android-contract regression suite (CONTRACT-001).
 * Outputs one JSON line per case prefixed with "CONTRACT_CASE " so the
 * baseline runner can compare PASS vs pinned XFAIL (GAP-*) regressions
 * without auto-updating expectations.
 */
public class ContractSuiteTest {
    private static String jsonStr(String s) {
        if (s == null) return "null";
        StringBuilder sb = new StringBuilder(s.length() + 8);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' || c == '\\') sb.append('\\').append(c);
            else if (c == '\n') sb.append("\\n");
            else if (c == '\r') sb.append("\\r");
            else if (c == '\t') sb.append("\\t");
            else sb.append(c);
        }
        sb.append('"');
        return sb.toString();
    }

    private static void emit(String id, String status, String detail) {
        System.out.println("CONTRACT_CASE {\"id\":" + jsonStr(id)
                + ",\"status\":" + jsonStr(status)
                + ",\"detail\":" + jsonStr(detail) + "}");
    }

    @FunctionalInterface
    private interface Check {
        String run() throws Exception;
    }

    private static void runCase(String id, Check check) {
        try {
            String detail = check.run();
            emit(id, "PASS", detail != null ? detail : "ok");
        } catch (Throwable t) {
            String msg = t.getClass().getSimpleName() + ": " + (t.getMessage() != null ? t.getMessage() : "");
            emit(id, "FAIL", msg);
        }
    }

    // ---- PASS contracts ----------------------------------------------------

    private static String checkParcelWire(boolean injectRegression) throws Exception {
        if (injectRegression) {
            throw new AssertionError("injected regression for harness self-test");
        }
        Binder binder = new Binder();
        Parcel.MacapkBinderHandles codec = new Parcel.MacapkBinderHandles() {
            public long exportBinder(IBinder b) { return b == binder ? 1L : 0L; }
            public IBinder importBinder(long h) { return h == 1L ? binder : null; }
        };
        Parcel in = Parcel.obtain();
        in.writeInt(42);
        in.writeString("macapk");
        in.writeStrongBinder(binder);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        MacapkParcelWire.write(new DataOutputStream(baos), in, codec);
        Parcel out = Parcel.obtain();
        MacapkParcelWire.read(new DataInputStream(new ByteArrayInputStream(baos.toByteArray())), out, codec);
        if (out.readInt() != 42 || !"macapk".equals(out.readString()) || out.readStrongBinder() != binder) {
            throw new AssertionError("round-trip mismatch");
        }
        byte[] truncated = Arrays.copyOf(baos.toByteArray(), baos.size() - 1);
        try {
            MacapkParcelWire.read(new DataInputStream(new ByteArrayInputStream(truncated)), out, codec);
            throw new AssertionError("accepted truncated frame");
        } catch (EOFException expected) {}
        ByteArrayOutputStream bad = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(bad);
        dos.writeInt(0x4d504331);
        dos.writeInt(Integer.MAX_VALUE);
        dos.writeInt(0);
        try {
            MacapkParcelWire.read(new DataInputStream(new ByteArrayInputStream(bad.toByteArray())), out, codec);
            throw new AssertionError("accepted oversized frame");
        } catch (IOException expected) {}
        in.recycle();
        out.recycle();
        return "round-trip, truncated EOF, oversized IOException ok";
    }

    private static String checkBinderLocalAndIdentity() throws Exception {
        final String desc = "org.macapk.contract.ITest";
        final IInterface owner = new IInterface() {
            @Override public IBinder asBinder() { return null; }
        };
        Binder b = new Binder() {
            @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
                int v = data.readInt();
                reply.writeInt(v + 7);
                return code == 1;
            }
        };
        b.attachInterface(owner, desc);
        if (b.queryLocalInterface(desc) != owner) throw new AssertionError("queryLocalInterface hit failed");
        if (b.queryLocalInterface("other") != null) throw new AssertionError("queryLocalInterface mismatch non-null");
        Parcel req = Parcel.obtain();
        Parcel rep = Parcel.obtain();
        req.writeInt(35);
        boolean ok = b.transact(1, req, rep, 0);
        if (!ok || rep.readInt() != 42) throw new AssertionError("transact data/reply position contract broken");
        req.recycle();
        rep.recycle();
        long tok = Binder.clearCallingIdentity();
        Binder.restoreCallingIdentity(tok);
        return "local interface fast-path, transact position reset, identity restore ok";
    }

    private static String checkBundleBasics() {
        try {
            Bundle.EMPTY.putString("k", "v");
            throw new AssertionError("Bundle.EMPTY allowed putString");
        } catch (UnsupportedOperationException expected) {}
        Bundle.EMPTY.remove("k"); // AOSP allows no-op remove on EMPTY
        Bundle src = new Bundle();
        src.putString("a", "1");
        src.putInt("b", 2);
        src.putString("mismatch", "not_an_intent");
        Parcel p = Parcel.obtain();
        p.writeBundle(src);
        p.setDataPosition(0);
        Bundle dst = p.readBundle();
        p.recycle();
        if (!"1".equals(dst.getString("a")) || dst.getInt("b") != 2) {
            throw new AssertionError("Bundle Parcel round-trip failed");
        }
        if (dst.getParcelable("mismatch", Intent.class) != null) {
            throw new AssertionError("Bundle.getParcelable(key, Class) returned wrong type");
        }
        return "Bundle.EMPTY immutability, Parcel round-trip, single getParcelable type check ok";
    }

    private static String checkIntentBasics() throws Exception {
        Intent a = new Intent("test.ACT", Uri.parse("https://example.org/x")).putExtra("e", 1)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        Intent b = new Intent("test.ACT", Uri.parse("https://example.org/x")).putExtra("e", 2);
        if (!a.filterEquals(b)) throw new AssertionError("filterEquals considered extras/flags");
        Intent base = new Intent();
        Intent src = new Intent("test.FILL", Uri.parse("https://example.org/y"));
        int changed = base.fillIn(src, Intent.FILL_IN_ACTION);
        if ((changed & Intent.FILL_IN_ACTION) == 0 || !"test.FILL".equals(base.getAction())) {
            throw new AssertionError("fillIn action failed");
        }
        Intent parsed = Intent.parseUri(
                "intent:#Intent;action=test.ACT;launchFlags=0xc3;end",
                Intent.URI_INTENT_SCHEME);
        if ((parsed.getFlags() & 0x3) != 0) {
            throw new AssertionError("parseUri retained unsafe URI grant flags");
        }
        Intent typed = new Intent();
        ArrayList<ComponentName> list = new ArrayList<>();
        list.add(new ComponentName("pkg", "Cls"));
        typed.putParcelableArrayListExtra("comps", list);
        ArrayList<ComponentName> got = typed.getParcelableArrayListExtra("comps", ComponentName.class);
        if (got == null || got.size() != 1) throw new AssertionError("getParcelableArrayListExtra failed");
        return "filterEquals, fillIn, grant-flag strip, typed list extra ok";
    }

    private static String checkIntentFilterMatrix() throws Exception {
        IntentFilter typeOnly = new IntentFilter("android.intent.action.VIEW", "text/*");
        if (typeOnly.match("android.intent.action.VIEW", "text/plain", null, null, null, "t") < 0) {
            throw new AssertionError("type-only filter rejected null scheme");
        }
        if (typeOnly.match("android.intent.action.VIEW", "text/plain", "content", null, null, "t") < 0) {
            throw new AssertionError("type-only filter rejected content scheme");
        }
        if (typeOnly.match("android.intent.action.VIEW", "text/plain", "http",
                Uri.parse("http://example.org/a.txt"), null, "t") != IntentFilter.NO_MATCH_DATA) {
            throw new AssertionError("type-only filter accepted http scheme");
        }
        IntentFilter empty = new IntentFilter();
        if (empty.actionsIterator() != null) throw new AssertionError("empty filter actionsIterator != null");
        try {
            empty.addDataScheme(null);
            throw new AssertionError("addDataScheme(null) did not throw NPE");
        } catch (NullPointerException expected) {}
        try {
            new IntentFilter("a", "badmime");
            throw new AssertionError("invalid MIME did not throw MalformedMimeTypeException");
        } catch (IntentFilter.MalformedMimeTypeException expected) {}
        try {
            IntentFilter.create("a", "badmime");
            throw new AssertionError("IntentFilter.create did not throw RuntimeException");
        } catch (RuntimeException expected) {}
        return "scheme/type match matrix, empty iterator, NPE, MalformedMimeTypeException ok";
    }

    private static String checkPatternMatcherSimple() {
        PatternMatcher lit = new PatternMatcher("abc", PatternMatcher.PATTERN_LITERAL);
        PatternMatcher pre = new PatternMatcher("pre", PatternMatcher.PATTERN_PREFIX);
        PatternMatcher suf = new PatternMatcher("suf", PatternMatcher.PATTERN_SUFFIX);
        PatternMatcher glob = new PatternMatcher("a.*z", PatternMatcher.PATTERN_SIMPLE_GLOB);
        if (!lit.match("abc") || lit.match("abcd")) throw new AssertionError("PATTERN_LITERAL failed");
        if (!pre.match("prefix") || !suf.match("xsuf")) throw new AssertionError("PREFIX/SUFFIX failed");
        if (!glob.match("az") || !glob.match("a123z") || glob.match(null)) {
            throw new AssertionError("PATTERN_SIMPLE_GLOB failed");
        }
        return "LITERAL, PREFIX, SUFFIX, SIMPLE_GLOB ok";
    }

    private static String checkAudioTrackValidMinBuf() {
        int minBuf = AudioTrack.getMinBufferSize(48000, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT);
        if (minBuf < 4096) throw new AssertionError("unexpected minBuf=" + minBuf);
        if (AudioTrack.getNativeOutputSampleRate(AudioManager.STREAM_MUSIC) <= 0) {
            throw new AssertionError("invalid native output sample rate");
        }
        if (AudioTrack.getMinVolume() != 0.0f || AudioTrack.getMaxVolume() != 1.0f) {
            throw new AssertionError("volume range mismatch");
        }
        return "minBuf=" + minBuf + ", sampleRate, volume bounds ok";
    }

    // ---- Batch 1 fixed contracts (PILOT-001 -> PASS) & remaining XFAILs ----

    private static String checkServiceManagerOrThrow() throws Exception {
        try {
            IBinder b = ServiceManager.getServiceOrThrow("missing_contract_svc");
            throw new AssertionError("getServiceOrThrow returned " + b + " instead of throwing");
        } catch (ServiceManager.ServiceNotFoundException expected) {
            return "ServiceNotFoundException thrown ok";
        }
    }

    private static String checkBinderCallingUidOrThrow() throws Exception {
        try {
            int uid = Binder.getCallingUidOrThrow();
            throw new AssertionError("getCallingUidOrThrow returned " + uid + " outside transaction");
        } catch (IllegalStateException expected) {
            // Expected: not in transaction and no explicit identity.
        }

        // Verify that with clearCallingIdentity(), getCallingUidOrThrow succeeds
        long token = Binder.clearCallingIdentity();
        try {
            int uid = Binder.getCallingUidOrThrow();
            if (uid != android.os.Process.myUid()) {
                throw new AssertionError("getCallingUidOrThrow with cleared identity returned " + uid
                        + " != " + android.os.Process.myUid());
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }

        // Verify that after restoreCallingIdentity outside transaction, it throws again
        try {
            int uid = Binder.getCallingUidOrThrow();
            throw new AssertionError("getCallingUidOrThrow returned " + uid + " after restoreCallingIdentity outside transaction");
        } catch (IllegalStateException expected) {}

        // Verify that inside transact() / onTransact, getCallingUidOrThrow succeeds
        final int[] txUid = new int[1];
        Binder b = new Binder() {
            @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
                txUid[0] = Binder.getCallingUidOrThrow();
                return true;
            }
        };
        b.transact(1, null, null, 0);
        if (txUid[0] != android.os.Process.myUid()) {
            throw new AssertionError("getCallingUidOrThrow inside onTransact returned " + txUid[0]);
        }

        return "getCallingUidOrThrow throws outside transaction, succeeds in transaction or with explicit identity ok";
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static String checkBundleApi33TypedLists() {
        Bundle b = new Bundle();
        ArrayList raw = new ArrayList();
        raw.add("wrong_element_type");
        b.putParcelableArrayList("arr", raw);
        ArrayList<ComponentName> out = b.getParcelableArrayList("arr", ComponentName.class);
        if (out != null) {
            throw new AssertionError("getParcelableArrayList(k,Class) returned non-null for mismatched element");
        }
        b.putSerializable("ser", "wrong_serializable");
        Integer iv = b.getSerializable("ser", Integer.class);
        if (iv != null) {
            throw new AssertionError("getSerializable(k,Integer) returned " + iv);
        }
        b.putSerializable("ser_ok", Integer.valueOf(77));
        if (!Integer.valueOf(77).equals(b.getSerializable("ser_ok", Integer.class))) {
            throw new AssertionError("getSerializable(k,Integer) rejected valid Integer");
        }
        return "API 33 Bundle typed getters ok";
    }

    private static String checkIntentParseAndroidAppUri() throws Exception {
        Intent i = Intent.parseUri("android-app://com.example/https/example.org/item",
                Intent.URI_ANDROID_APP_SCHEME);
        if (!"com.example".equals(i.getPackage()) || !"https://example.org/item".equals(i.getDataString())) {
            throw new AssertionError("android-app URI degraded pkg=" + i.getPackage() + " data=" + i.getDataString());
        }
        Intent mainOnly = Intent.parseUri("android-app://com.example", Intent.URI_ANDROID_APP_SCHEME);
        if (!"com.example".equals(mainOnly.getPackage())
                || !Intent.ACTION_MAIN.equals(mainOnly.getAction())
                || mainOnly.getData() != null) {
            throw new AssertionError("android-app main URI mismatch: " + mainOnly);
        }
        return "android-app URI parse & round-trip ok";
    }

    private static String checkPatternMatcherAdvancedGlob() {
        PatternMatcher adv = new PatternMatcher("[0-9]+", PatternMatcher.PATTERN_ADVANCED_GLOB);
        if (!adv.match("42") || adv.match("") || adv.match("42a")) {
            throw new AssertionError("PATTERN_ADVANCED_GLOB '[0-9]+' failed");
        }
        PatternMatcher neg = new PatternMatcher("/items/[^/]+/edit", PatternMatcher.PATTERN_ADVANCED_GLOB);
        if (!neg.match("/items/123/edit") || neg.match("/items/a/b/edit")) {
            throw new AssertionError("PATTERN_ADVANCED_GLOB negated set failed");
        }
        PatternMatcher range = new PatternMatcher("[a-f]{2,4}", PatternMatcher.PATTERN_ADVANCED_GLOB);
        if (!range.match("cafe") || range.match("a") || range.match("cafebabe")) {
            throw new AssertionError("PATTERN_ADVANCED_GLOB quantifier range failed");
        }
        PatternMatcher esc = new PatternMatcher("v1\\.0", PatternMatcher.PATTERN_SIMPLE_GLOB);
        if (!esc.match("v1.0") || esc.match("v1X0")) {
            throw new AssertionError("escaped dot in SIMPLE_GLOB matched non-dot");
        }
        Parcel p = Parcel.obtain();
        adv.writeToParcel(p, 0);
        p.setDataPosition(0);
        PatternMatcher fromParcel = PatternMatcher.CREATOR.createFromParcel(p);
        p.recycle();
        if (!fromParcel.match("99") || !"[0-9]+".equals(fromParcel.getPattern())) {
            throw new AssertionError("PatternMatcher Parcel round-trip or getPattern() failed");
        }
        try {
            new PatternMatcher("[0-9", PatternMatcher.PATTERN_ADVANCED_GLOB);
            throw new AssertionError("malformed ADVANCED_GLOB accepted");
        } catch (IllegalArgumentException expected) {}
        return "PATTERN_ADVANCED_GLOB, escaped dot, Parcel & getPattern() ok";
    }

    private static String checkAudioTrackInvalidMinBuf() {
        int badSr = AudioTrack.getMinBufferSize(-1, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT);
        int badFmt = AudioTrack.getMinBufferSize(48000, AudioFormat.CHANNEL_OUT_STEREO, 999);
        int badCh = AudioTrack.getMinBufferSize(48000, -1, AudioFormat.ENCODING_PCM_16BIT);
        if (badSr != AudioTrack.ERROR_BAD_VALUE
                || badFmt != AudioTrack.ERROR_BAD_VALUE
                || badCh != AudioTrack.ERROR_BAD_VALUE) {
            throw new AssertionError("invalid params returned badSr=" + badSr + " badFmt=" + badFmt
                    + " badCh=" + badCh + " instead of ERROR_BAD_VALUE(-2)");
        }
        return "invalid sampleRate/channelConfig/audioFormat -> ERROR_BAD_VALUE(-2) ok";
    }

    private static String checkMediaExtractorContract() throws Exception {
        MediaExtractor ex = new MediaExtractor();
        try {
            ex.setDataSource((String) null);
            throw new AssertionError("setDataSource((String) null) did not throw");
        } catch (IllegalArgumentException expected) {}
        try {
            ex.setDataSource(new ContextWrapper(null), (Uri) null, null);
            throw new AssertionError("setDataSource(null URI) did not throw");
        } catch (IllegalArgumentException expected) {}
        try {
            ex.setDataSource(new ContextWrapper(null), Uri.parse("https://example.invalid/video.mp4"), null);
            throw new AssertionError("setDataSource(https) silently returned tracks=" + ex.getTrackCount());
        } catch (IOException expected) {}
        try {
            ex.setDataSource(new ContextWrapper(null), Uri.parse("ftp://example.invalid/audio.mp3"), null);
            throw new AssertionError("setDataSource(ftp) did not throw IOException");
        } catch (IOException expected) {}
        try {
            ex.setDataSource(new ContextWrapper(null), Uri.parse("rtsp://example.invalid/live"), null);
            throw new AssertionError("setDataSource(rtsp) did not throw IOException");
        } catch (IOException expected) {}
        try {
            MediaFormat f = ex.getTrackFormat(0);
            throw new AssertionError("getTrackFormat(0) on uninitialized extractor returned " + f);
        } catch (IllegalStateException expected) {}
        try {
            MediaFormat f = ex.getTrackFormat(99);
            throw new AssertionError("getTrackFormat(99) on uninitialized extractor returned " + f);
        } catch (IllegalStateException expected) {}
        try {
            ex.getTrackFormat(-1);
            throw new AssertionError("getTrackFormat(-1) on uninitialized extractor did not throw");
        } catch (IllegalStateException expected) {}
        try {
            ex.selectTrack(0);
            throw new AssertionError("selectTrack(0) on empty extractor did not throw");
        } catch (IllegalArgumentException expected) {}
        try {
            ex.selectTrack(99);
            throw new AssertionError("selectTrack(99) on empty extractor did not throw");
        } catch (IllegalArgumentException expected) {}
        try {
            ex.selectTrack(-1);
            throw new AssertionError("selectTrack(-1) did not throw");
        } catch (IllegalArgumentException expected) {}
        try {
            ex.unselectTrack(0);
            throw new AssertionError("unselectTrack(0) on empty extractor did not throw");
        } catch (IllegalArgumentException expected) {}
        try {
            ex.unselectTrack(99);
            throw new AssertionError("unselectTrack(99) on empty extractor did not throw");
        } catch (IllegalArgumentException expected) {}
        try {
            ex.unselectTrack(-1);
            throw new AssertionError("unselectTrack(-1) did not throw");
        } catch (IllegalArgumentException expected) {}
        ex.release();
        return "MediaExtractor rejects invalid scheme and out-of-range track indices ok";
    }

    public static void main(String[] args) {
        boolean injectRegression = false;
        boolean corruptOutput = false;
        for (String a : args) {
            if ("--inject-regression".equals(a)) injectRegression = true;
            else if ("--corrupt-output".equals(a)) corruptOutput = true;
        }
        if (corruptOutput) {
            System.out.println("CONTRACT_CASE {broken_json");
            return;
        }
        final boolean inj = injectRegression;
        runCase("CTR-PARCEL-001", () -> checkParcelWire(inj));
        runCase("CTR-BINDER-001", ContractSuiteTest::checkBinderLocalAndIdentity);
        runCase("CTR-BUNDLE-001", ContractSuiteTest::checkBundleBasics);
        runCase("CTR-INTENT-001", ContractSuiteTest::checkIntentBasics);
        runCase("CTR-FILTER-001", ContractSuiteTest::checkIntentFilterMatrix);
        runCase("CTR-PM-MATCH-001", ContractSuiteTest::checkPatternMatcherSimple);
        runCase("CTR-AUDIO-001", ContractSuiteTest::checkAudioTrackValidMinBuf);

        runCase("CTR-IPC-XFAIL-001", ContractSuiteTest::checkServiceManagerOrThrow);
        runCase("CTR-IPC-XFAIL-002", ContractSuiteTest::checkBinderCallingUidOrThrow);
        runCase("CTR-BUNDLE-XFAIL-001", ContractSuiteTest::checkBundleApi33TypedLists);
        runCase("CTR-INTENT-XFAIL-001", ContractSuiteTest::checkIntentParseAndroidAppUri);
        runCase("CTR-PM-XFAIL-001", ContractSuiteTest::checkPatternMatcherAdvancedGlob);
        runCase("CTR-AUDIO-XFAIL-001", ContractSuiteTest::checkAudioTrackInvalidMinBuf);
        runCase("CTR-MEDIA-XFAIL-001", ContractSuiteTest::checkMediaExtractorContract);
    }
}
