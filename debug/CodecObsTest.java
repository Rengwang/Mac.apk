import android.media.MediaCodec;
import android.media.MediaFormat;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/** Verifies MACAPK_CODEC_OBS=1 bounded queue/wait telemetry and default-OFF invariance. */
public class CodecObsTest {
    private static Map<String, String> parseKv(String line) {
        Map<String, String> out = new HashMap<>();
        for (String tok : line.trim().split("\\s+")) {
            int eq = tok.indexOf('=');
            if (eq > 0) out.put(tok.substring(0, eq), tok.substring(eq + 1));
        }
        return out;
    }

    private static long reqLong(Map<String, String> kv, String key) {
        String v = kv.get(key);
        if (v == null) throw new AssertionError("missing key " + key + " in " + kv);
        return Long.parseLong(v);
    }

    private static void runChildOn() throws Exception {
        if (!"1".equals(System.getenv("MACAPK_CODEC_OBS"))) {
            throw new AssertionError("child expected MACAPK_CODEC_OBS=1");
        }
        MediaCodec c = MediaCodec.createByCodecName("test-obs");
        MediaFormat fmt = MediaFormat.createAudioFormat("audio/x-macapk-obs-test", 48000, 2);
        c.configure(fmt, null, null, 0);
        c.start();

        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        int fmtRet = c.dequeueOutputBuffer(info, 0);
        if (fmtRet != MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
            throw new AssertionError("expected INFO_OUTPUT_FORMAT_CHANGED, got " + fmtRet);
        }
        int tryRet = c.dequeueOutputBuffer(info, 15_000);
        if (tryRet != MediaCodec.INFO_TRY_AGAIN_LATER) {
            throw new AssertionError("expected INFO_TRY_AGAIN_LATER, got " + tryRet);
        }

        // Pump enough cycles (> 64 total events) to exercise the bounded 64-entry ring + droppedEvents.
        final int cycles = 25;
        final int pktSize = 16;
        for (int i = 0; i < cycles; i++) {
            int inIdx = c.dequeueInputBuffer(0);
            if (inIdx < 0) throw new AssertionError("dequeueInputBuffer failed at " + i + ": " + inIdx);
            ByteBuffer inBuf = c.getInputBuffer(inIdx);
            inBuf.clear();
            for (int b = 0; b < pktSize; b++) inBuf.put((byte) (i + b));
            c.queueInputBuffer(inIdx, 0, pktSize, i * 1000L, MediaCodec.BUFFER_FLAG_END_OF_STREAM);

            int outIdx = c.dequeueOutputBuffer(info, 0);
            if (outIdx < 0) throw new AssertionError("dequeueOutputBuffer failed at " + i + ": " + outIdx);
            c.releaseOutputBuffer(outIdx, false);
        }

        String snap = c.macapkCodecObsSnapshot();
        if (snap == null || !snap.startsWith("[codec-obs] ")) {
            throw new AssertionError("expected [codec-obs] snapshot, got " + snap);
        }
        Map<String, String> kv = parseKv(snap);
        long cap = reqLong(kv, "cap");
        long retained = reqLong(kv, "retained");
        long dropped = reqLong(kv, "dropped");
        long totalEv = reqLong(kv, "total_ev");
        if (cap != 64 || retained != 64 || dropped <= 0 || totalEv != retained + dropped) {
            throw new AssertionError("bounded ring invariant broken: " + snap);
        }
        if (reqLong(kv, "in_deq") != cycles || reqLong(kv, "in_q") != cycles
                || reqLong(kv, "in_bytes") != (long) cycles * pktSize
                || reqLong(kv, "eos_q") != cycles
                || reqLong(kv, "out_fmt") != 1
                || reqLong(kv, "out_try_again") < 1
                || reqLong(kv, "out_wait_ns") < 10_000_000L
                || reqLong(kv, "out_deq") != cycles
                || reqLong(kv, "rel") != cycles) {
            throw new AssertionError("unexpected counter values: " + snap);
        }

        // Calling release() twice must emit [codec-obs] once.
        c.release();
        c.release();
    }

    private static void verifyDefaultOffAndSpawnOn() throws Exception {
        if ("1".equals(System.getenv("MACAPK_CODEC_OBS"))) {
            runChildOn();
            return;
        }
        PrintStream origErr = System.err;
        ByteArrayOutputStream capErr = new ByteArrayOutputStream();
        try {
            System.setErr(new PrintStream(capErr, true, StandardCharsets.UTF_8));
            MediaCodec c = MediaCodec.createByCodecName("test-off");
            if (c.macapkCodecObsSnapshot() != null) {
                throw new AssertionError("MACAPK_CODEC_OBS default OFF must yield null snapshot");
            }
            c.configure(MediaFormat.createAudioFormat("audio/x-macapk-obs-test", 48000, 2), null, null, 0);
            c.start();
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            if (c.dequeueOutputBuffer(info, 0) != MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                throw new AssertionError("expected format change in OFF mode");
            }
            int inIdx = c.dequeueInputBuffer(0);
            if (inIdx < 0) throw new AssertionError("dequeueInputBuffer failed in OFF mode");
            c.queueInputBuffer(inIdx, 0, 8, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
            int outIdx = c.dequeueOutputBuffer(info, 0);
            if (outIdx < 0) throw new AssertionError("dequeueOutputBuffer failed in OFF mode");
            c.releaseOutputBuffer(outIdx, false);
            c.stop();
            c.release();
        } finally {
            System.setErr(origErr);
        }
        String offErr = capErr.toString(StandardCharsets.UTF_8);
        if (offErr.contains("[codec-obs]")) {
            throw new AssertionError("default OFF mode emitted [codec-obs]: " + offErr);
        }

        String javaBin = System.getProperty("java.home") + "/bin/java";
        String cp = System.getProperty("java.class.path");
        ProcessBuilder pb = new ProcessBuilder(
                javaBin, "-Djava.awt.headless=true", "-cp", cp, "CodecObsTest", "--child-on");
        pb.environment().put("MACAPK_CODEC_OBS", "1");
        pb.redirectErrorStream(false);
        Process p = pb.start();
        byte[] outBytes = p.getInputStream().readAllBytes();
        byte[] errBytes = p.getErrorStream().readAllBytes();
        int rc = p.waitFor();
        String childErr = new String(errBytes, StandardCharsets.UTF_8);
        String childOut = new String(outBytes, StandardCharsets.UTF_8);
        if (rc != 0) {
            throw new AssertionError("child MACAPK_CODEC_OBS=1 failed rc=" + rc
                    + "\nstdout=" + childOut + "\nstderr=" + childErr);
        }
        int hits = 0;
        String obsLine = null;
        for (String line : childErr.split("\\R")) {
            if (line.startsWith("[codec-obs] ")) {
                hits++;
                obsLine = line;
            }
        }
        if (hits != 1 || obsLine == null || !obsLine.contains("end=release") || !obsLine.contains("cap=64")) {
            throw new AssertionError("expected exactly 1 [codec-obs] release line, got hits=" + hits
                    + " stderr=" + childErr);
        }
        System.out.println("PASS: default-OFF no-op + MACAPK_CODEC_OBS=1 bounded telemetry: " + obsLine);
    }

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && "--child-on".equals(args[0])) {
            runChildOn();
        } else {
            verifyDefaultOffAndSpawnOn();
        }
    }
}
