import android.graphics.SurfaceTexture;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import com.macapk.loader.NativeCocoa;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Verifies zero-copy native CVPixelBuffer/IOSurface GL SurfaceTexture latching:
 * 1. Bare-JVM state machine & UnsatisfiedLinkError guards when native runtime is absent.
 * 2. Real ANGLE Metal (both EGL_DEFAULT_DISPLAY and MacApkEGL10 0x4A453130):
 *    - MacapkOfferDecodedFrameToTextureView zero-copy routing (no int[] alloc/copy)
 *    - Superseded unlatched CVPixelBuffer release (queue 2 before updateTexImage)
 *    - GL_TEXTURE_BINDING_2D save/restore
 *    - IOSurface -> eglBindTexImage -> FBO shader sample -> glReadPixels exact RGBA & upright row order
 *    - Non-IOSurface CVPixelBuffer C++ NEON fallback upload + immediate CFRelease
 *    - Clean CFRelease / eglReleaseTexImage / eglDestroySurface across releaseTexImage, detachFromGLContext, release
 *    - Clean CFRelease when updateTexImage runs with no current EGL context
 */
public class SurfaceTextureNativePixbufTest {
    private static void check(boolean cond, String msg) {
        if (!cond) throw new AssertionError(msg);
    }

    // JNI helpers exposed by the compiled test harness library (loaded dynamically in native phase).
    private static native boolean nativeInitAngle(boolean privateDisplay);
    private static native void nativeTeardownAngle();
    private static native void nativeUnbindContext();
    private static native void nativeRebindContext();
    private static native int nativeGenTexture();
    private static native void nativeDeleteTexture(int tex);
    private static native void nativeBindTexture2D(int tex);
    private static native int nativeGetBoundTexture2D();
    private static native long nativeCreateTestPixbuf(int width, int height, boolean withIosurface);
    private static native int nativeGetRetainCount(long cfHandle);
    private static native void nativeReleaseCf(long cfHandle);
    private static native boolean nativeOfferPixbuf(long pbHandle, SurfaceTexture explicitTarget);
    private static native byte[] nativeRenderAndReadPixels(int tex, float[] mtx16, int width, int height);

    private static long[] getBoundState(SurfaceTexture st) throws Exception {
        Field f = SurfaceTexture.class.getDeclaredField("mGlBoundIosurfaceState");
        f.setAccessible(true);
        return (long[]) f.get(st);
    }

    private static long[] getPendingNativePixbufRing(SurfaceTexture st) throws Exception {
        Field ringField = SurfaceTexture.class.getDeclaredField("mVideoNativeRing");
        Field headField = SurfaceTexture.class.getDeclaredField("mVideoNativeHead");
        Field countField = SurfaceTexture.class.getDeclaredField("mVideoNativeCount");
        ringField.setAccessible(true);
        headField.setAccessible(true);
        countField.setAccessible(true);
        long[] ring = (long[]) ringField.get(st);
        int head = headField.getInt(st);
        int count = countField.getInt(st);
        long[] out = new long[count];
        for (int i = 0; i < count; i++) {
            out[i] = ring[(head + i) & (ring.length - 1)];
        }
        return out;
    }

    private static Object getVideoFrameArgb(SurfaceTexture st) throws Exception {
        Field f = SurfaceTexture.class.getDeclaredField("mVideoFrameArgb");
        f.setAccessible(true);
        return f.get(st);
    }

    private static void runBareJvmTests() throws Exception {
        boolean gateOn = !"0".equals(System.getenv("MACAPK_SURFACETEXTURE_IOSURFACE_GL"));
        SurfaceTexture detached = new SurfaceTexture(false);
        check(!detached.macapkWantsNativeVideoPixbuf(),
                "Detached SurfaceTexture must not want native video pixbuf");
        check(!detached.macapkQueueNativeVideoPixbuf(0x1001L, 16, 16),
                "Detached SurfaceTexture must reject queued native pixbuf");
        detached.updateTexImage();
        detached.release();

        SurfaceTexture attached = new SurfaceTexture(11);
        check(attached.macapkWantsNativeVideoPixbuf() == gateOn,
                "Attached SurfaceTexture macapkWantsNativeVideoPixbuf mismatch for gate=" + gateOn);
        attached.macapkAttachCameraSource(99L, 16, 16);
        check(!attached.macapkWantsNativeVideoPixbuf(),
                "Camera-attached SurfaceTexture must not want native video pixbuf");
        attached.macapkDetachCameraSource();
        check(attached.macapkWantsNativeVideoPixbuf() == gateOn,
                "Detaching camera must restore native video pixbuf eligibility");

        // Bare-JVM tests may run either under run_tests.py (MACAPK_LOG_LEVEL=7) or directly
        // without env vars; Looper/Handler log via NativeLog.print when level <= INFO unless bound.
        boolean canTouchLooper = false;
        try {
            com.macapk.loader.NativeLog.i("SurfaceTextureNativePixbufTest", "probe");
            canTouchLooper = true;
        } catch (UnsatisfiedLinkError ignored) {
        }
        int[] frameCalls = {0};
        if (canTouchLooper) {
            Handler mainHandler = new Handler(Looper.getMainLooper());
            attached.setOnFrameAvailableListener(st -> frameCalls[0]++, mainHandler);
        }
        if (gateOn) {
            check(attached.macapkQueueNativeVideoPixbuf(0x2001L, 16, 16),
                    "Attached SurfaceTexture should accept native pixbuf");
            if (canTouchLooper) {
                Looper.macapkDrainMainDue();
                Handler.drainGlobal();
                check(frameCalls[0] == 1,
                        "OnFrameAvailableListener must fire on main handler drain, got " + frameCalls[0]);
            }
            // Queue into 4-slot FIFO ring before updateTexImage() and verify overflow eviction.
            check(attached.macapkQueueNativeVideoPixbuf(0x2002L, 16, 16),
                    "Queuing second unlatched pixbuf into 4-slot FIFO ring should succeed");
            check(Arrays.equals(getPendingNativePixbufRing(attached), new long[]{0x2001L, 0x2002L}),
                    "4-slot FIFO ring must preserve [0x2001, 0x2002], got "
                            + Arrays.toString(getPendingNativePixbufRing(attached)));
            check(attached.macapkQueueNativeVideoPixbuf(0x20021L, 16, 16), "slot 3");
            check(attached.macapkQueueNativeVideoPixbuf(0x20022L, 16, 16), "slot 4");
            check(attached.macapkQueueNativeVideoPixbuf(0x20023L, 16, 16),
                    "Overflowing 4-slot FIFO ring must evict oldest under UnsatisfiedLinkError guard");
            check(Arrays.equals(getPendingNativePixbufRing(attached),
                    new long[]{0x2002L, 0x20021L, 0x20022L, 0x20023L}),
                    "4-slot FIFO ring after overflow must hold newest 4 handles, got "
                            + Arrays.toString(getPendingNativePixbufRing(attached)));
            attached.updateTexImage();
            check(Arrays.equals(getPendingNativePixbufRing(attached),
                    new long[]{0x20021L, 0x20022L, 0x20023L}),
                    "updateTexImage must pop head in FIFO order");
            attached.updateTexImage();
            attached.updateTexImage();
            attached.updateTexImage();
            check(getPendingNativePixbufRing(attached).length == 0,
                    "Draining updateTexImage must empty 4-slot FIFO ring");
            attached.releaseTexImage();
            attached.detachFromGLContext();
            attached.attachToGLContext(12);
            check(attached.macapkQueueNativeVideoPixbuf(0x2003L, 16, 16),
                    "Re-attached SurfaceTexture should accept native pixbuf");
            attached.detachFromGLContext();
            check(getPendingNativePixbufRing(attached).length == 0,
                    "detachFromGLContext must clear unlatched native pixbuf ring");
            attached.attachToGLContext(13);
            attached.macapkQueueNativeVideoPixbuf(0x2004L, 16, 16);
        }
        attached.release();
        check(getPendingNativePixbufRing(attached).length == 0,
                "release must clear pending native pixbuf ring");
        check(!attached.macapkWantsNativeVideoPixbuf(),
                "Released SurfaceTexture must return false from macapkWantsNativeVideoPixbuf");
        check(!attached.macapkQueueNativeVideoPixbuf(0x2005L, 16, 16),
                "Released SurfaceTexture must reject queued native pixbuf");
        System.out.println("PASS: bare-JVM SurfaceTexture native pixbuf state & guards");
    }

    private static Path findAngleBinDir() {
        List<Path> candidates = new ArrayList<>();
        String libPath = System.getProperty("java.library.path", "");
        if (!libPath.isEmpty()) {
            for (String entry : libPath.split(java.io.File.pathSeparator)) {
                if (!entry.isEmpty()) candidates.add(Paths.get(entry));
            }
        }
        candidates.add(Paths.get("runtime/build/bin"));
        candidates.add(Paths.get("../runtime/build/bin"));
        for (Path dir : candidates) {
            if (Files.isRegularFile(dir.resolve("libEGL.dylib"))
                    && Files.isRegularFile(dir.resolve("libGLESv2.dylib"))) {
                return dir.toAbsolutePath().normalize();
            }
        }
        return null;
    }

    private static Path findRepoRoot() {
        Path p = Paths.get("").toAbsolutePath().normalize();
        for (int i = 0; i < 6 && p != null; i++) {
            if (Files.isRegularFile(p.resolve("runtime/src/cocoa_bridge.mm"))
                    && Files.isRegularFile(p.resolve("runtime/src/native_methods.cpp"))
                    && Files.isRegularFile(p.resolve("runtime/src/video_pixels.h"))) {
                return p;
            }
            p = p.getParent();
        }
        return null;
    }

    private static String extractBetween(String text, String startMarker, String endMarker) {
        int s = text.indexOf(startMarker);
        if (s < 0) throw new IllegalStateException("Missing start marker: " + startMarker);
        int e = text.indexOf(endMarker, s);
        if (e < 0) throw new IllegalStateException("Missing end marker: " + endMarker);
        return text.substring(s, e);
    }

    private static Path buildJniHarnessDylib(Path tmpDir, Path repoRoot, Path angleBinDir)
            throws IOException, InterruptedException {
        String cocoaBridge = Files.readString(repoRoot.resolve("runtime/src/cocoa_bridge.mm"));
        String nativeMethods = Files.readString(repoRoot.resolve("runtime/src/native_methods.cpp"));

        String offerSection = extractBetween(
                cocoaBridge,
                "struct MacapkStJniCache {",
                "// BEGIN_SURFACETEXTURE_NATIVE_PIXBUF");
        String pixbufSection = extractBetween(
                cocoaBridge,
                "// BEGIN_SURFACETEXTURE_NATIVE_PIXBUF",
                "// END_SURFACETEXTURE_NATIVE_PIXBUF");

        String jniWrappers = extractBetween(
                nativeMethods,
                "void Cocoa_releaseVideoPixelBuffer(",
                "const JNINativeMethod kNativeCocoaMethods[]");

        String mmSource = ""
                + "#import <Foundation/Foundation.h>\n"
                + "#import <CoreVideo/CoreVideo.h>\n"
                + "#import <IOSurface/IOSurface.h>\n"
                + "#include <EGL/egl.h>\n"
                + "#include <EGL/eglext.h>\n"
                + "#include <EGL/eglext_angle.h>\n"
                + "#include <GLES2/gl2.h>\n"
                + "#include <GLES2/gl2ext.h>\n"
                + "#include <jni.h>\n"
                + "#include <atomic>\n"
                + "#include <cstdio>\n"
                + "#include <cstring>\n"
                + "#include <mutex>\n"
                + "#include <vector>\n"
                + "#include \"video_pixels.h\"\n"
                + "\n"
                + "static bool MacapkTraceVideoLayerGeometry() { return false; }\n"
                + "namespace macapk {\n"
                + offerSection + "\n"
                + pixbufSection + "\n"
                + jniWrappers + "\n"
                + "}\n"
                + "\n"
                + "static EGLDisplay g_dpy = EGL_NO_DISPLAY;\n"
                + "static EGLConfig  g_cfg = nullptr;\n"
                + "static EGLSurface g_pb  = EGL_NO_SURFACE;\n"
                + "static EGLContext g_ctx = EGL_NO_CONTEXT;\n"
                + "\n"
                + "static GLuint compileShader(GLenum type, const char* src) {\n"
                + "    GLuint s = glCreateShader(type);\n"
                + "    glShaderSource(s, 1, &src, nullptr);\n"
                + "    glCompileShader(s);\n"
                + "    GLint ok = 0;\n"
                + "    glGetShaderiv(s, GL_COMPILE_STATUS, &ok);\n"
                + "    if (!ok) {\n"
                + "        char log[512];\n"
                + "        glGetShaderInfoLog(s, sizeof(log), nullptr, log);\n"
                + "        std::fprintf(stderr, \"shader compile fail: %s\\n\", log);\n"
                + "    }\n"
                + "    return s;\n"
                + "}\n"
                + "\n"
                + "extern \"C\" JNIEXPORT jboolean JNICALL\n"
                + "Java_SurfaceTextureNativePixbufTest_nativeInitAngle(JNIEnv*, jclass, jboolean privateDisplay) {\n"
                + "    if (g_dpy != EGL_NO_DISPLAY) return JNI_TRUE;\n"
                + "    if (privateDisplay == JNI_TRUE) {\n"
                + "        auto getPlatform = (PFNEGLGETPLATFORMDISPLAYEXTPROC)eglGetProcAddress(\"eglGetPlatformDisplayEXT\");\n"
                + "        if (!getPlatform) return JNI_FALSE;\n"
                + "        const EGLint pAttrs[] = {EGL_PLATFORM_ANGLE_TYPE_ANGLE, EGL_PLATFORM_ANGLE_TYPE_METAL_ANGLE, EGL_NONE};\n"
                + "        g_dpy = getPlatform(EGL_PLATFORM_ANGLE_ANGLE, reinterpret_cast<void*>(0x4A453130), pAttrs);\n"
                + "    } else {\n"
                + "        g_dpy = eglGetDisplay(EGL_DEFAULT_DISPLAY);\n"
                + "    }\n"
                + "    if (g_dpy == EGL_NO_DISPLAY || !eglInitialize(g_dpy, nullptr, nullptr)) return JNI_FALSE;\n"
                + "    const EGLint ca[] = {EGL_SURFACE_TYPE, EGL_PBUFFER_BIT, EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,\n"
                + "                         EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8, EGL_ALPHA_SIZE, 8, EGL_NONE};\n"
                + "    EGLint n = 0;\n"
                + "    if (!eglChooseConfig(g_dpy, ca, &g_cfg, 1, &n) || n != 1) return JNI_FALSE;\n"
                + "    const EGLint pbAttrs[] = {EGL_WIDTH, 32, EGL_HEIGHT, 32, EGL_NONE};\n"
                + "    g_pb = eglCreatePbufferSurface(g_dpy, g_cfg, pbAttrs);\n"
                + "    const EGLint ctxAttrs[] = {EGL_CONTEXT_CLIENT_VERSION, 2, EGL_NONE};\n"
                + "    g_ctx = eglCreateContext(g_dpy, g_cfg, EGL_NO_CONTEXT, ctxAttrs);\n"
                + "    if (g_pb == EGL_NO_SURFACE || g_ctx == EGL_NO_CONTEXT) return JNI_FALSE;\n"
                + "    return eglMakeCurrent(g_dpy, g_pb, g_pb, g_ctx) == EGL_TRUE ? JNI_TRUE : JNI_FALSE;\n"
                + "}\n"
                + "\n"
                + "extern \"C\" JNIEXPORT void JNICALL\n"
                + "Java_SurfaceTextureNativePixbufTest_nativeTeardownAngle(JNIEnv*, jclass) {\n"
                + "    if (g_dpy != EGL_NO_DISPLAY) {\n"
                + "        eglMakeCurrent(g_dpy, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);\n"
                + "        if (g_ctx != EGL_NO_CONTEXT) eglDestroyContext(g_dpy, g_ctx);\n"
                + "        if (g_pb != EGL_NO_SURFACE) eglDestroySurface(g_dpy, g_pb);\n"
                + "        eglTerminate(g_dpy);\n"
                + "    }\n"
                + "    g_dpy = EGL_NO_DISPLAY; g_cfg = nullptr; g_pb = EGL_NO_SURFACE; g_ctx = EGL_NO_CONTEXT;\n"
                + "}\n"
                + "\n"
                + "extern \"C\" JNIEXPORT void JNICALL\n"
                + "Java_SurfaceTextureNativePixbufTest_nativeUnbindContext(JNIEnv*, jclass) {\n"
                + "    if (g_dpy != EGL_NO_DISPLAY) eglMakeCurrent(g_dpy, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);\n"
                + "}\n"
                + "\n"
                + "extern \"C\" JNIEXPORT void JNICALL\n"
                + "Java_SurfaceTextureNativePixbufTest_nativeRebindContext(JNIEnv*, jclass) {\n"
                + "    if (g_dpy != EGL_NO_DISPLAY) eglMakeCurrent(g_dpy, g_pb, g_pb, g_ctx);\n"
                + "}\n"
                + "\n"
                + "extern \"C\" JNIEXPORT jint JNICALL\n"
                + "Java_SurfaceTextureNativePixbufTest_nativeGenTexture(JNIEnv*, jclass) {\n"
                + "    GLuint t = 0; glGenTextures(1, &t); return static_cast<jint>(t);\n"
                + "}\n"
                + "\n"
                + "extern \"C\" JNIEXPORT void JNICALL\n"
                + "Java_SurfaceTextureNativePixbufTest_nativeDeleteTexture(JNIEnv*, jclass, jint tex) {\n"
                + "    GLuint t = static_cast<GLuint>(tex); glDeleteTextures(1, &t);\n"
                + "}\n"
                + "\n"
                + "extern \"C\" JNIEXPORT void JNICALL\n"
                + "Java_SurfaceTextureNativePixbufTest_nativeBindTexture2D(JNIEnv*, jclass, jint tex) {\n"
                + "    glBindTexture(GL_TEXTURE_2D, static_cast<GLuint>(tex));\n"
                + "}\n"
                + "\n"
                + "extern \"C\" JNIEXPORT jint JNICALL\n"
                + "Java_SurfaceTextureNativePixbufTest_nativeGetBoundTexture2D(JNIEnv*, jclass) {\n"
                + "    GLint t = 0; glGetIntegerv(GL_TEXTURE_BINDING_2D, &t); return static_cast<jint>(t);\n"
                + "}\n"
                + "\n"
                + "extern \"C\" JNIEXPORT jlong JNICALL\n"
                + "Java_SurfaceTextureNativePixbufTest_nativeCreateTestPixbuf(JNIEnv*, jclass, jint w, jint h, jboolean withIosurface) {\n"
                + "    CVPixelBufferRef pb = nullptr;\n"
                + "    NSDictionary* attrs = withIosurface == JNI_TRUE\n"
                + "        ? @{ (id)kCVPixelBufferMetalCompatibilityKey: @YES, (id)kCVPixelBufferIOSurfacePropertiesKey: @{} }\n"
                + "        : @{};\n"
                + "    if (CVPixelBufferCreate(kCFAllocatorDefault, w, h, kCVPixelFormatType_32BGRA, (__bridge CFDictionaryRef)attrs, &pb) != kCVReturnSuccess || !pb) {\n"
                + "        return 0;\n"
                + "    }\n"
                + "    if (withIosurface == JNI_TRUE && CVPixelBufferGetIOSurface(pb) == nullptr) {\n"
                + "        CFRelease(pb); return 0;\n"
                + "    }\n"
                + "    if (withIosurface == JNI_FALSE && CVPixelBufferGetIOSurface(pb) != nullptr) {\n"
                + "        CFRelease(pb); return 0;\n"
                + "    }\n"
                + "    CVPixelBufferLockBaseAddress(pb, 0);\n"
                + "    uint8_t* base = static_cast<uint8_t*>(CVPixelBufferGetBaseAddress(pb));\n"
                + "    size_t bpr = CVPixelBufferGetBytesPerRow(pb);\n"
                + "    for (int y = 0; y < h; ++y) {\n"
                + "        uint8_t* row = base + static_cast<size_t>(y) * bpr;\n"
                + "        for (int x = 0; x < w; ++x) {\n"
                + "            uint8_t r = 0, g = 0, b = 0, a = 255;\n"
                + "            if (y < h / 2 && x < w / 2)       { r = 255; g = 0;   b = 0; }   /* top-left: RED */\n"
                + "            else if (y < h / 2 && x >= w / 2) { r = 0;   g = 255; b = 0; }   /* top-right: GREEN */\n"
                + "            else if (y >= h / 2 && x < w / 2) { r = 0;   g = 0;   b = 255; } /* bottom-left: BLUE */\n"
                + "            else                              { r = 255; g = 255; b = 0; }   /* bottom-right: YELLOW */\n"
                + "            row[x * 4 + 0] = b; row[x * 4 + 1] = g; row[x * 4 + 2] = r; row[x * 4 + 3] = a;\n"
                + "        }\n"
                + "    }\n"
                + "    CVPixelBufferUnlockBaseAddress(pb, 0);\n"
                + "    return static_cast<jlong>(reinterpret_cast<intptr_t>(pb));\n"
                + "}\n"
                + "\n"
                + "extern \"C\" JNIEXPORT jint JNICALL\n"
                + "Java_SurfaceTextureNativePixbufTest_nativeGetRetainCount(JNIEnv*, jclass, jlong handle) {\n"
                + "    if (handle == 0) return 0;\n"
                + "    return static_cast<jint>(CFGetRetainCount(reinterpret_cast<CFTypeRef>(static_cast<intptr_t>(handle))));\n"
                + "}\n"
                + "\n"
                + "extern \"C\" JNIEXPORT void JNICALL\n"
                + "Java_SurfaceTextureNativePixbufTest_nativeReleaseCf(JNIEnv*, jclass, jlong handle) {\n"
                + "    if (handle != 0) CFRelease(reinterpret_cast<CFTypeRef>(static_cast<intptr_t>(handle)));\n"
                + "}\n"
                + "\n"
                + "extern \"C\" JNIEXPORT jboolean JNICALL\n"
                + "Java_SurfaceTextureNativePixbufTest_nativeOfferPixbuf(JNIEnv* env, jclass, jlong pbHandle, jobject target) {\n"
                + "    return macapk::MacapkOfferDecodedFrameToTextureView(env, reinterpret_cast<void*>(static_cast<intptr_t>(pbHandle)), target) ? JNI_TRUE : JNI_FALSE;\n"
                + "}\n"
                + "\n"
                + "extern \"C\" JNIEXPORT jbyteArray JNICALL\n"
                + "Java_SurfaceTextureNativePixbufTest_nativeRenderAndReadPixels(JNIEnv* env, jclass, jint tex, jfloatArray mtxArr, jint w, jint h) {\n"
                + "    float mtx[16];\n"
                + "    env->GetFloatArrayRegion(mtxArr, 0, 16, mtx);\n"
                + "    GLuint fbo = 0, rboTex = 0;\n"
                + "    glGenTextures(1, &rboTex);\n"
                + "    glBindTexture(GL_TEXTURE_2D, rboTex);\n"
                + "    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, w, h, 0, GL_RGBA, GL_UNSIGNED_BYTE, nullptr);\n"
                + "    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);\n"
                + "    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);\n"
                + "    glGenFramebuffers(1, &fbo);\n"
                + "    glBindFramebuffer(GL_FRAMEBUFFER, fbo);\n"
                + "    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, rboTex, 0);\n"
                + "    if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) {\n"
                + "        glBindFramebuffer(GL_FRAMEBUFFER, 0);\n"
                + "        glDeleteFramebuffers(1, &fbo); glDeleteTextures(1, &rboTex);\n"
                + "        return nullptr;\n"
                + "    }\n"
                + "    const char* vsSrc =\n"
                + "        \"attribute vec2 aPos;\\n\"\n"
                + "        \"attribute vec2 aTexCoord;\\n\"\n"
                + "        \"uniform mat4 uTexMatrix;\\n\"\n"
                + "        \"varying vec2 vUV;\\n\"\n"
                + "        \"void main() { gl_Position = vec4(aPos, 0.0, 1.0); vUV = (uTexMatrix * vec4(aTexCoord, 0.0, 1.0)).xy; }\\n\";\n"
                + "    const char* fsSrc =\n"
                + "        \"precision mediump float;\\n\"\n"
                + "        \"varying vec2 vUV;\\n\"\n"
                + "        \"uniform sampler2D uTex;\\n\"\n"
                + "        \"void main() { gl_FragColor = texture2D(uTex, vUV); }\\n\";\n"
                + "    GLuint vs = compileShader(GL_VERTEX_SHADER, vsSrc);\n"
                + "    GLuint fs = compileShader(GL_FRAGMENT_SHADER, fsSrc);\n"
                + "    GLuint prog = glCreateProgram();\n"
                + "    glAttachShader(prog, vs); glAttachShader(prog, fs); glLinkProgram(prog);\n"
                + "    glUseProgram(prog);\n"
                + "    glViewport(0, 0, w, h);\n"
                + "    glClearColor(0, 0, 0, 0);\n"
                + "    glClear(GL_COLOR_BUFFER_BIT);\n"
                + "    const float verts[] = {\n"
                + "        -1.f, -1.f, 0.f, 0.f,\n"
                + "         1.f, -1.f, 1.f, 0.f,\n"
                + "        -1.f,  1.f, 0.f, 1.f,\n"
                + "         1.f,  1.f, 1.f, 1.f,\n"
                + "    };\n"
                + "    GLint posLoc = glGetAttribLocation(prog, \"aPos\");\n"
                + "    GLint uvLoc = glGetAttribLocation(prog, \"aTexCoord\");\n"
                + "    glEnableVertexAttribArray(posLoc);\n"
                + "    glEnableVertexAttribArray(uvLoc);\n"
                + "    glVertexAttribPointer(posLoc, 2, GL_FLOAT, GL_FALSE, 16, verts);\n"
                + "    glVertexAttribPointer(uvLoc, 2, GL_FLOAT, GL_FALSE, 16, verts + 2);\n"
                + "    glUniformMatrix4fv(glGetUniformLocation(prog, \"uTexMatrix\"), 1, GL_FALSE, mtx);\n"
                + "    glActiveTexture(GL_TEXTURE0);\n"
                + "    glBindTexture(GL_TEXTURE_2D, static_cast<GLuint>(tex));\n"
                + "    glUniform1i(glGetUniformLocation(prog, \"uTex\"), 0);\n"
                + "    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);\n"
                + "    glFinish();\n"
                + "    std::vector<uint8_t> rgba(static_cast<size_t>(w) * static_cast<size_t>(h) * 4);\n"
                + "    glReadPixels(0, 0, w, h, GL_RGBA, GL_UNSIGNED_BYTE, rgba.data());\n"
                + "    GLenum glErr = glGetError();\n"
                + "    glDisableVertexAttribArray(posLoc);\n"
                + "    glDisableVertexAttribArray(uvLoc);\n"
                + "    glBindFramebuffer(GL_FRAMEBUFFER, 0);\n"
                + "    glDeleteProgram(prog); glDeleteShader(vs); glDeleteShader(fs);\n"
                + "    glDeleteFramebuffers(1, &fbo); glDeleteTextures(1, &rboTex);\n"
                + "    if (glErr != GL_NO_ERROR) return nullptr;\n"
                + "    jbyteArray out = env->NewByteArray(static_cast<jsize>(rgba.size()));\n"
                + "    if (out) env->SetByteArrayRegion(out, 0, static_cast<jsize>(rgba.size()), reinterpret_cast<const jbyte*>(rgba.data()));\n"
                + "    return out;\n"
                + "}\n"
                + "\n"
                + "static void test_nativelog_print(JNIEnv*, jclass, jint, jstring, jstring) {}\n"
                + "\n"
                + "extern \"C\" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {\n"
                + "    JNIEnv* env = nullptr;\n"
                + "    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_21) != JNI_OK) return JNI_ERR;\n"
                + "    jclass nl = env->FindClass(\"com/macapk/loader/NativeLog\");\n"
                + "    if (nl) {\n"
                + "        const JNINativeMethod nlMethods[] = {\n"
                + "            {const_cast<char*>(\"print\"), const_cast<char*>(\"(ILjava/lang/String;Ljava/lang/String;)V\"),\n"
                + "             reinterpret_cast<void*>(test_nativelog_print)},\n"
                + "        };\n"
                + "        if (env->RegisterNatives(nl, nlMethods, 1) != JNI_OK) return JNI_ERR;\n"
                + "        env->DeleteLocalRef(nl);\n"
                + "    }\n"
                + "    jclass nc = env->FindClass(\"com/macapk/loader/NativeCocoa\");\n"
                + "    if (!nc) return JNI_ERR;\n"
                + "    const JNINativeMethod methods[] = {\n"
                + "        {const_cast<char*>(\"releaseVideoPixelBuffer\"), const_cast<char*>(\"(J)V\"),\n"
                + "         reinterpret_cast<void*>(macapk::Cocoa_releaseVideoPixelBuffer)},\n"
                + "        {const_cast<char*>(\"latchVideoPixelBufferToGl\"), const_cast<char*>(\"(JI[J)Z\"),\n"
                + "         reinterpret_cast<void*>(macapk::Cocoa_latchVideoPixelBufferToGl)},\n"
                + "        {const_cast<char*>(\"releaseGlBoundIosurfaceState\"), const_cast<char*>(\"([J)V\"),\n"
                + "         reinterpret_cast<void*>(macapk::Cocoa_releaseGlBoundIosurfaceState)},\n"
                + "    };\n"
                + "    if (env->RegisterNatives(nc, methods, 3) != JNI_OK) return JNI_ERR;\n"
                + "    env->DeleteLocalRef(nc);\n"
                + "    return JNI_VERSION_21;\n"
                + "}\n";

        Path mmPath = tmpDir.resolve("st_native_pixbuf_jni.mm");
        Path dylibPath = tmpDir.resolve("libst_native_pixbuf_jni.dylib");
        Files.writeString(mmPath, mmSource, StandardCharsets.UTF_8);

        String javaHome = System.getProperty("java.home");
        List<String> cmd = Arrays.asList(
                "clang++",
                "-std=c++17",
                "-O2",
                "-shared",
                "-fPIC",
                "-DMACAPK_HAS_ANGLE=1",
                "-I" + javaHome + "/include",
                "-I" + javaHome + "/include/darwin",
                "-I" + repoRoot.resolve("runtime/src"),
                "-I" + repoRoot.resolve("thirdparty/angle-prebuilt/include"),
                "-L" + angleBinDir,
                "-Wl,-rpath," + angleBinDir,
                "-lEGL",
                "-lGLESv2",
                "-framework", "Foundation",
                "-framework", "CoreVideo",
                "-framework", "IOSurface",
                mmPath.toString(),
                "-o", dylibPath.toString());
        Process proc = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String output = new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int rc = proc.waitFor();
        if (rc != 0) {
            throw new IllegalStateException("clang++ failed (rc=" + rc + "):\n" + output);
        }
        return dylibPath;
    }

    private static void assertFboQuadrants(byte[] rgba, int w, int h, String label) {
        check(rgba != null && rgba.length == w * h * 4, label + ": invalid readPixels output");
        // glReadPixels row 0 is the bottom of the FBO (GL bottom-left origin).
        // Top of rendered quad (row h - 3): left = RED (255,0,0,255), right = GREEN (0,255,0,255).
        // Bottom of rendered quad (row 2): left = BLUE (0,0,255,255), right = YELLOW (255,255,0,255).
        assertPixel(rgba, w, 2, h - 3, 255, 0, 0, 255, label + " top-left (RED)");
        assertPixel(rgba, w, w - 3, h - 3, 0, 255, 0, 255, label + " top-right (GREEN)");
        assertPixel(rgba, w, 2, 2, 0, 0, 255, 255, label + " bottom-left (BLUE)");
        assertPixel(rgba, w, w - 3, 2, 255, 255, 0, 255, label + " bottom-right (YELLOW)");
    }

    private static void assertPixel(byte[] rgba, int w, int x, int y,
                                    int er, int eg, int eb, int ea, String label) {
        int idx = (y * w + x) * 4;
        int r = rgba[idx] & 0xFF;
        int g = rgba[idx + 1] & 0xFF;
        int b = rgba[idx + 2] & 0xFF;
        int a = rgba[idx + 3] & 0xFF;
        check(r == er && g == eg && b == eb && a == ea,
                label + " expected (" + er + "," + eg + "," + eb + "," + ea
                        + ") got (" + r + "," + g + "," + b + "," + a + ")");
    }

    private static void verifyAngleDisplay(boolean privateDisplay) throws Exception {
        String label = privateDisplay ? "MacApkEGL10-0x4A453130" : "EGL_DEFAULT_DISPLAY";
        check(nativeInitAngle(privateDisplay), label + ": failed to initialize ANGLE Metal");
        HandlerThread listenerThread = new HandlerThread("st-pixbuf-cb-" + label);
        listenerThread.start();
        try {
            int tex = nativeGenTexture();
            int sentinelTex = nativeGenTexture();
            SurfaceTexture st = new SurfaceTexture(tex);
            CountDownLatch frameAvailLatch = new CountDownLatch(1);
            st.setOnFrameAvailableListener(
                    ignored -> frameAvailLatch.countDown(),
                    new Handler(listenerThread.getLooper()));
            st.macapkRegisterVideoTarget();
            check(st.macapkWantsNativeVideoPixbuf(), label + ": expected wantsNativeVideoPixbuf");

            // 1. Queue two IOSurface-backed CVPixelBuffers before updateTexImage() -> 4-slot FIFO ring keeps both!
            long pb1 = nativeCreateTestPixbuf(16, 16, true);
            long pb2 = nativeCreateTestPixbuf(16, 16, true);
            check(pb1 != 0 && pb2 != 0, label + ": CVPixelBufferCreate failed");
            check(nativeGetRetainCount(pb1) == 1 && nativeGetRetainCount(pb2) == 1,
                    label + ": initial retain count should be 1");

            // Test sActiveVideoTarget resolution (explicitTarget == null).
            check(nativeOfferPixbuf(pb1, null), label + ": offer pb1 failed");
            check(frameAvailLatch.await(3, TimeUnit.SECONDS),
                    label + ": OnFrameAvailableListener must fire off native pixbuf offer");
            check(nativeGetRetainCount(pb1) == 2, label + ": queued pb1 should be retained (rc=2)");
            check(getVideoFrameArgb(st) == null, label + ": zero-copy path must NOT allocate int[]");

            // Offer pb2 via explicit target before updateTexImage() -> queued behind pb1 in 4-slot FIFO ring.
            check(nativeOfferPixbuf(pb2, st), label + ": offer pb2 failed");
            check(nativeGetRetainCount(pb1) == 2 && nativeGetRetainCount(pb2) == 2,
                    label + ": 4-slot FIFO ring must retain both pb1 and pb2 (got rc1="
                            + nativeGetRetainCount(pb1) + ", rc2=" + nativeGetRetainCount(pb2) + ")");
            check(getVideoFrameArgb(st) == null, label + ": zero-copy path must NOT allocate int[]");

            // 2. Latch in FIFO order (pb1 then pb2), save/restore GL_TEXTURE_BINDING_2D, sample latched IOSurface!
            nativeBindTexture2D(sentinelTex);
            st.updateTexImage();
            check(nativeGetBoundTexture2D() == sentinelTex,
                    label + ": updateTexImage must preserve caller GL_TEXTURE_BINDING_2D");
            long[] state = getBoundState(st);
            check(state[0] != 0L && state[1] != 0L && state[2] == pb1,
                    label + ": first updateTexImage must latch FIFO head pb1, got "
                            + Arrays.toString(state));
            check(nativeGetRetainCount(pb1) == 2 && nativeGetRetainCount(pb2) == 2,
                    label + ": latched pb1 and queued pb2 must stay retained");
            st.updateTexImage();
            check(nativeGetBoundTexture2D() == sentinelTex,
                    label + ": second updateTexImage must preserve caller GL_TEXTURE_BINDING_2D");
            check(state[0] != 0L && state[1] != 0L && state[2] == pb2,
                    label + ": expected active zero-copy IOSurface bound state for pb2, got "
                            + Arrays.toString(state));
            check(nativeGetRetainCount(pb1) == 1,
                    label + ": previous latched pb1 must be CFReleased on pb2 latch");
            check(nativeGetRetainCount(pb2) == 2,
                    label + ": latched pb2 must stay retained while bound to texture");
            check(st.getTimestamp() > 0L, label + ": getTimestamp must be populated");

            float[] mtx = new float[16];
            st.getTransformMatrix(mtx);
            byte[] pixels = nativeRenderAndReadPixels(tex, mtx, 16, 16);
            assertFboQuadrants(pixels, 16, 16, label + " IOSurface zero-copy");

            // 3. Latch a resized IOSurface frame (24x20) -> pb2 released, pb3 retained & upright!
            long pb3 = nativeCreateTestPixbuf(24, 20, true);
            check(nativeOfferPixbuf(pb3, st), label + ": offer pb3 failed");
            st.updateTexImage();
            check(nativeGetRetainCount(pb2) == 1,
                    label + ": previous latched pb2 must be CFReleased on next latch");
            check(nativeGetRetainCount(pb3) == 2, label + ": pb3 must be retained");
            st.getTransformMatrix(mtx);
            byte[] pixelsResized = nativeRenderAndReadPixels(tex, mtx, 24, 20);
            assertFboQuadrants(pixelsResized, 24, 20, label + " IOSurface resized 24x20");

            // 4. Clean release via releaseTexImage() (both bound state and queued unlatched pixbuf).
            st.releaseTexImage();
            check(nativeGetRetainCount(pb3) == 1,
                    label + ": releaseTexImage must release bound pb3");
            check(state[0] == 0L && state[1] == 0L && state[2] == 0L,
                    label + ": releaseTexImage must clear boundState");
            long pbUnlatchedRel = nativeCreateTestPixbuf(16, 16, true);
            check(nativeOfferPixbuf(pbUnlatchedRel, st), label + ": offer pbUnlatchedRel");
            check(nativeGetRetainCount(pbUnlatchedRel) == 2, label + ": pbUnlatchedRel rc=2");
            st.releaseTexImage();
            check(nativeGetRetainCount(pbUnlatchedRel) == 1,
                    label + ": releaseTexImage must also release unlatched queued pixbuf");

            // 4b. 4-slot FIFO ring overflow -> 5th offer evicts & CFReleases oldest unlatched buffer.
            long[] ov = new long[5];
            for (int i = 0; i < 5; i++) {
                ov[i] = nativeCreateTestPixbuf(16, 16, true);
                check(ov[i] != 0 && nativeGetRetainCount(ov[i]) == 1, label + ": ov[" + i + "] create");
                check(nativeOfferPixbuf(ov[i], st), label + ": offer ov[" + i + "]");
            }
            check(nativeGetRetainCount(ov[0]) == 1,
                    label + ": 4-slot FIFO ring overflow must CFRelease oldest ov[0], got rc="
                            + nativeGetRetainCount(ov[0]));
            for (int i = 1; i < 5; i++) {
                check(nativeGetRetainCount(ov[i]) == 2,
                        label + ": ov[" + i + "] must remain retained in 4-slot FIFO ring");
            }
            st.releaseTexImage();
            for (int i = 0; i < 5; i++) {
                check(nativeGetRetainCount(ov[i]) == 1,
                        label + ": releaseTexImage must drain ov[" + i + "]");
                nativeReleaseCf(ov[i]);
            }

            // 5. Non-IOSurface CVPixelBuffer fallback (io == nullptr): C++ NEON upload + immediate CFRelease.
            long pbFallback = nativeCreateTestPixbuf(20, 16, false);
            check(pbFallback != 0, label + ": non-IOSurface CVPixelBufferCreate failed");
            check(nativeOfferPixbuf(pbFallback, st), label + ": offer fallback pixbuf");
            check(nativeGetRetainCount(pbFallback) == 2, label + ": queued fallback rc=2");
            nativeBindTexture2D(sentinelTex);
            st.updateTexImage();
            check(nativeGetBoundTexture2D() == sentinelTex,
                    label + ": fallback updateTexImage must preserve GL_TEXTURE_BINDING_2D");
            check(nativeGetRetainCount(pbFallback) == 1,
                    label + ": fallback must CFRelease CVPixelBuffer immediately after upload");
            check(state[0] == 0L && state[1] == 0L && state[2] == 0L,
                    label + ": fallback must leave boundState empty");
            st.getTransformMatrix(mtx);
            byte[] pixelsFallback = nativeRenderAndReadPixels(tex, mtx, 20, 16);
            assertFboQuadrants(pixelsFallback, 20, 16, label + " non-IOSurface C++ NEON fallback");

            // 6. Clean release via detachFromGLContext() (bound + unlatched).
            long pbDetachBound = nativeCreateTestPixbuf(16, 16, true);
            long pbDetachQueued = nativeCreateTestPixbuf(16, 16, true);
            check(nativeOfferPixbuf(pbDetachBound, st), label + ": offer pbDetachBound");
            st.updateTexImage();
            check(nativeOfferPixbuf(pbDetachQueued, st), label + ": offer pbDetachQueued");
            st.detachFromGLContext();
            check(nativeGetRetainCount(pbDetachBound) == 1 && nativeGetRetainCount(pbDetachQueued) == 1,
                    label + ": detachFromGLContext must release both bound and queued pixbufs");
            check(state[0] == 0L && state[1] == 0L && state[2] == 0L,
                    label + ": detachFromGLContext must clear boundState");

            // 7. No current EGL context during updateTexImage() -> clean CFRelease without leaking.
            st.attachToGLContext(tex);
            long pbNoCtx = nativeCreateTestPixbuf(16, 16, true);
            check(nativeOfferPixbuf(pbNoCtx, st), label + ": offer pbNoCtx");
            nativeUnbindContext();
            st.updateTexImage();
            nativeRebindContext();
            check(nativeGetRetainCount(pbNoCtx) == 1,
                    label + ": updateTexImage without current EGL context must CFRelease without leaking");
            check(state[0] == 0L && state[1] == 0L && state[2] == 0L,
                    label + ": boundState must remain zero when context absent");

            // 8. Clean release via release() (bound + unlatched).
            long pbRelBound = nativeCreateTestPixbuf(16, 16, true);
            long pbRelQueued = nativeCreateTestPixbuf(16, 16, true);
            check(nativeOfferPixbuf(pbRelBound, st), label + ": offer pbRelBound");
            st.updateTexImage();
            check(nativeOfferPixbuf(pbRelQueued, st), label + ": offer pbRelQueued");
            st.release();
            check(nativeGetRetainCount(pbRelBound) == 1 && nativeGetRetainCount(pbRelQueued) == 1,
                    label + ": release() must release both bound and queued pixbufs");
            check(state[0] == 0L && state[1] == 0L && state[2] == 0L,
                    label + ": release() must clear boundState");

            for (long h : new long[]{pb1, pb2, pb3, pbUnlatchedRel, pbFallback,
                    pbDetachBound, pbDetachQueued, pbNoCtx, pbRelBound, pbRelQueued}) {
                nativeReleaseCf(h);
            }
            nativeDeleteTexture(tex);
            nativeDeleteTexture(sentinelTex);
        } finally {
            listenerThread.quitSafely();
            nativeTeardownAngle();
        }
        System.out.println("PASS: ANGLE Metal zero-copy & orientation verified (" + label + ")");
    }

    private static void deleteTree(Path dir) {
        if (dir == null || !Files.exists(dir)) return;
        try (var stream = Files.walk(dir)) {
            stream.sorted((a, b) -> b.compareTo(a)).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (IOException ignored) {}
            });
        } catch (IOException ignored) {}
    }

    public static void main(String[] args) throws Exception {
        System.setProperty("java.awt.headless", "true");
        boolean bareOnly = false;
        String explicitDylib = null;
        for (String a : args) {
            if ("--bare-only".equals(a)) bareOnly = true;
            else if (!a.startsWith("-")) explicitDylib = a;
        }

        runBareJvmTests();
        if (bareOnly) return;

        Path tmpDir = null;
        try {
            if (explicitDylib != null) {
                System.load(Paths.get(explicitDylib).toAbsolutePath().toString());
            } else {
                Path angleBinDir = findAngleBinDir();
                Path repoRoot = findRepoRoot();
                if (angleBinDir == null || repoRoot == null) {
                    throw new IllegalStateException(
                            "Missing built ANGLE libraries in java.library.path / runtime/build/bin "
                                    + "(angleBinDir=" + angleBinDir + ", repoRoot=" + repoRoot + ")");
                }
                tmpDir = Files.createTempDirectory("macapk_st_pixbuf_test_");
                Path dylib = buildJniHarnessDylib(tmpDir, repoRoot, angleBinDir);
                System.load(dylib.toAbsolutePath().toString());
            }
            verifyAngleDisplay(false);
            verifyAngleDisplay(true);
            System.out.println("PASS: SurfaceTextureNativePixbufTest");
        } finally {
            deleteTree(tmpDir);
        }
    }
}
