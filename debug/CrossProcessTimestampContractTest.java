import android.graphics.SurfaceTexture;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.Binder;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.MacApkBinderTransport;
import android.os.Parcel;
import android.os.RemoteException;
import android.view.Surface;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * End-to-end multi-process Mach Binder regression test verifying:
 * 1. MediaCodec.releaseOutputBuffer(index, true) preserves exact ptsUs * 1000L
 *    across B-frame reordering, staggered delays, cross-process IOSurface Mach
 *    port transfer, and ANGLE Metal GL SurfaceTexture.updateTexImage().
 * 2. MediaCodec.releaseOutputBuffer(index, renderTimestampNs) preserves exact
 *    caller renderTimestampNs (past, +45ms scheduled, >1s future clamped) while
 *    clamping host sleep deadlines to [now, now + 1s].
 * 3. 200-frame FLAG_ONEWAY burst over MacApkBinderTransport maintains strict
 *    per-port FIFO order (ipcSeqOutOfOrder == 0), piggybacks producer stats
 *    into AutomationServer's "video_stats" op, drops flushed MediaCodec
 *    generations cleanly, drops post-release Surface frames (ipcDroppedInvalid),
 *    and resets SurfaceTexture.getTimestamp() == 0 on releaseTexImage().
 */
public class CrossProcessTimestampContractTest {
    private static final String DESCRIPTOR = "org.macapk.test.CrossProcessTimestampHandoff";
    private static final int TX_GET_SURFACE = IBinder.FIRST_CALL_TRANSACTION;
    private static final int TX_AWAIT_LATCHED_COUNT = IBinder.FIRST_CALL_TRANSACTION + 1;
    private static final int TX_DRAIN_ALL_AVAILABLE = IBinder.FIRST_CALL_TRANSACTION + 2;

    private static void check(boolean cond, String msg) {
        if (!cond) throw new AssertionError(msg);
    }

    // JNI helpers compiled into libxproc_ts_contract_jni.dylib.
    private static native boolean nativeInitAngle();
    private static native void nativeTeardownAngle();
    private static native int nativeGenTexture();
    private static native void nativeDeleteTexture(int tex);
    private static native long nativeCreateColorPixbuf(int w, int h, int r, int g, int b);
    private static native int nativeGetRetainCount(long cfHandle);
    private static native void nativeReleaseCf(long cfHandle);
    private static native byte[] nativeRenderAndReadPixels(int tex, float[] mtx16, int w, int h);
    private static native boolean nativeRegisterChildSendRight(int localRecvPort);
    private static native int nativeLookupParentSendRight();

    private static Path findRepoRoot() {
        Path p = Paths.get("").toAbsolutePath().normalize();
        for (int i = 0; i < 6 && p != null; i++) {
            if (Files.isRegularFile(p.resolve("runtime/src/cocoa_bridge.mm"))
                    && Files.isRegularFile(p.resolve("runtime/src/video_toolbox.mm"))
                    && Files.isRegularFile(p.resolve("runtime/src/native_methods.cpp"))) {
                return p;
            }
            p = p.getParent();
        }
        return null;
    }

    private static Path findRuntimeBinDir(Path repoRoot) {
        List<Path> candidates = new ArrayList<>();
        String libPath = System.getProperty("java.library.path", "");
        for (String entry : libPath.split(File.pathSeparator)) {
            if (!entry.isEmpty()) candidates.add(Paths.get(entry));
        }
        if (repoRoot != null) {
            candidates.add(repoRoot.resolve("runtime/build/bin"));
        }
        for (Path dir : candidates) {
            if (Files.isRegularFile(dir.resolve("libEGL.dylib"))
                    && Files.isRegularFile(dir.resolve("libGLESv2.dylib"))
                    && Files.isRegularFile(dir.resolve("libmacapk-binder.dylib"))) {
                return dir.toAbsolutePath().normalize();
            }
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

    private static Path buildJniHarnessDylib(Path tmpDir, Path repoRoot, Path binDir)
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
                + "#include <mach/mach.h>\n"
                + "#include <EGL/egl.h>\n"
                + "#include <EGL/eglext.h>\n"
                + "#include <EGL/eglext_angle.h>\n"
                + "#include <GLES2/gl2.h>\n"
                + "#include <GLES2/gl2ext.h>\n"
                + "#include <jni.h>\n"
                + "#include <atomic>\n"
                + "#include <cstdio>\n"
                + "#include <cstdint>\n"
                + "#include <cstring>\n"
                + "#include <mutex>\n"
                + "#include <vector>\n"
                + "#include \"video_pixels.h\"\n"
                + "\n"
                + "namespace macapk { namespace video { void RegisterNativeVideoMethods(JNIEnv* env); } }\n"
                + "extern \"C\" void* macapk_vd_present_create(void*) { return nullptr; }\n"
                + "extern \"C\" int macapk_vd_present_to_layer(void*, void*, void*) { return 0; }\n"
                + "\n"
                + "static bool MacapkTraceVideoLayerGeometry() { return false; }\n"
                + "namespace macapk {\n"
                + offerSection + "\n"
                + pixbufSection + "\n"
                + jniWrappers + "\n"
                + "}\n"
                + "\n"
                + "static JavaVM* g_test_jvm = nullptr;\n"
                + "struct TestJniScope {\n"
                + "    JNIEnv* env = nullptr;\n"
                + "    bool attached = false;\n"
                + "    TestJniScope() {\n"
                + "        if (!g_test_jvm) return;\n"
                + "        jint st = g_test_jvm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_21);\n"
                + "        if (st == JNI_EDETACHED) {\n"
                + "            if (g_test_jvm->AttachCurrentThreadAsDaemon(reinterpret_cast<void**>(&env), nullptr) == JNI_OK)\n"
                + "                attached = true;\n"
                + "        }\n"
                + "    }\n"
                + "    ~TestJniScope() { if (attached && g_test_jvm) g_test_jvm->DetachCurrentThread(); }\n"
                + "};\n"
                + "\n"
                + "extern \"C\" __attribute__((visibility(\"default\")))\n"
                + "void CocoaPresentVideoFrame(void* pixbuf, int, int) {\n"
                + "    if (!pixbuf) return;\n"
                + "    TestJniScope scope;\n"
                + "    macapk::MacapkOfferDecodedFrameToTextureView(scope.env, pixbuf, nullptr, 0, false);\n"
                + "}\n"
                + "\n"
                + "extern \"C\" __attribute__((visibility(\"default\")))\n"
                + "void CocoaPresentVideoFrameToSurface(void* pixbuf, int, int, jobject target) {\n"
                + "    if (!pixbuf) return;\n"
                + "    TestJniScope scope;\n"
                + "    macapk::MacapkOfferDecodedFrameToTextureView(scope.env, pixbuf, target, 0, false);\n"
                + "}\n"
                + "\n"
                + "extern \"C\" __attribute__((visibility(\"default\")))\n"
                + "void CocoaPresentVideoFrameToSurfaceAtTime(void* pixbuf, int, int, jobject target,\n"
                + "                                           int64_t presentationTimeNs, bool hasExplicitTs) {\n"
                + "    if (!pixbuf) return;\n"
                + "    TestJniScope scope;\n"
                + "    macapk::MacapkOfferDecodedFrameToTextureView(\n"
                + "        scope.env, pixbuf, target, presentationTimeNs, hasExplicitTs);\n"
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
                + "    return s;\n"
                + "}\n"
                + "\n"
                + "extern \"C\" JNIEXPORT jboolean JNICALL\n"
                + "Java_CrossProcessTimestampContractTest_nativeInitAngle(JNIEnv*, jclass) {\n"
                + "    if (g_dpy != EGL_NO_DISPLAY) return JNI_TRUE;\n"
                + "    g_dpy = eglGetDisplay(EGL_DEFAULT_DISPLAY);\n"
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
                + "Java_CrossProcessTimestampContractTest_nativeTeardownAngle(JNIEnv*, jclass) {\n"
                + "    if (g_dpy != EGL_NO_DISPLAY) {\n"
                + "        eglMakeCurrent(g_dpy, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);\n"
                + "        if (g_ctx != EGL_NO_CONTEXT) eglDestroyContext(g_dpy, g_ctx);\n"
                + "        if (g_pb != EGL_NO_SURFACE) eglDestroySurface(g_dpy, g_pb);\n"
                + "        eglTerminate(g_dpy);\n"
                + "    }\n"
                + "    g_dpy = EGL_NO_DISPLAY; g_cfg = nullptr; g_pb = EGL_NO_SURFACE; g_ctx = EGL_NO_CONTEXT;\n"
                + "}\n"
                + "\n"
                + "extern \"C\" JNIEXPORT jint JNICALL\n"
                + "Java_CrossProcessTimestampContractTest_nativeGenTexture(JNIEnv*, jclass) {\n"
                + "    GLuint t = 0; glGenTextures(1, &t); return static_cast<jint>(t);\n"
                + "}\n"
                + "\n"
                + "extern \"C\" JNIEXPORT void JNICALL\n"
                + "Java_CrossProcessTimestampContractTest_nativeDeleteTexture(JNIEnv*, jclass, jint tex) {\n"
                + "    GLuint t = static_cast<GLuint>(tex); glDeleteTextures(1, &t);\n"
                + "}\n"
                + "\n"
                + "extern \"C\" JNIEXPORT jlong JNICALL\n"
                + "Java_CrossProcessTimestampContractTest_nativeCreateColorPixbuf(\n"
                + "        JNIEnv*, jclass, jint w, jint h, jint r, jint g, jint b) {\n"
                + "    CVPixelBufferRef pb = nullptr;\n"
                + "    @autoreleasepool {\n"
                + "        NSDictionary* attrs = @{ (id)kCVPixelBufferMetalCompatibilityKey: @YES,\n"
                + "                                 (id)kCVPixelBufferIOSurfacePropertiesKey: @{} };\n"
                + "        if (CVPixelBufferCreate(kCFAllocatorDefault, w, h, kCVPixelFormatType_32BGRA,\n"
                + "                                (__bridge CFDictionaryRef)attrs, &pb) != kCVReturnSuccess || !pb) {\n"
                + "            return 0;\n"
                + "        }\n"
                + "    }\n"
                + "    if (!CVPixelBufferGetIOSurface(pb)) { CFRelease(pb); return 0; }\n"
                + "    CVPixelBufferLockBaseAddress(pb, 0);\n"
                + "    uint8_t* base = static_cast<uint8_t*>(CVPixelBufferGetBaseAddress(pb));\n"
                + "    size_t bpr = CVPixelBufferGetBytesPerRow(pb);\n"
                + "    for (int y = 0; y < h; ++y) {\n"
                + "        uint8_t* row = base + static_cast<size_t>(y) * bpr;\n"
                + "        for (int x = 0; x < w; ++x) {\n"
                + "            row[x * 4 + 0] = static_cast<uint8_t>(b & 0xff);\n"
                + "            row[x * 4 + 1] = static_cast<uint8_t>(g & 0xff);\n"
                + "            row[x * 4 + 2] = static_cast<uint8_t>(r & 0xff);\n"
                + "            row[x * 4 + 3] = 255;\n"
                + "        }\n"
                + "    }\n"
                + "    CVPixelBufferUnlockBaseAddress(pb, 0);\n"
                + "    return static_cast<jlong>(reinterpret_cast<intptr_t>(pb));\n"
                + "}\n"
                + "\n"
                + "extern \"C\" JNIEXPORT jint JNICALL\n"
                + "Java_CrossProcessTimestampContractTest_nativeGetRetainCount(JNIEnv*, jclass, jlong handle) {\n"
                + "    if (handle == 0) return 0;\n"
                + "    return static_cast<jint>(CFGetRetainCount(reinterpret_cast<CFTypeRef>(static_cast<intptr_t>(handle))));\n"
                + "}\n"
                + "\n"
                + "extern \"C\" JNIEXPORT void JNICALL\n"
                + "Java_CrossProcessTimestampContractTest_nativeReleaseCf(JNIEnv*, jclass, jlong handle) {\n"
                + "    if (handle != 0) CFRelease(reinterpret_cast<CFTypeRef>(static_cast<intptr_t>(handle)));\n"
                + "}\n"
                + "\n"
                + "extern \"C\" JNIEXPORT jbyteArray JNICALL\n"
                + "Java_CrossProcessTimestampContractTest_nativeRenderAndReadPixels(\n"
                + "        JNIEnv* env, jclass, jint tex, jfloatArray mtxArr, jint w, jint h) {\n"
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
                + "    glDisableVertexAttribArray(posLoc);\n"
                + "    glDisableVertexAttribArray(uvLoc);\n"
                + "    glBindFramebuffer(GL_FRAMEBUFFER, 0);\n"
                + "    glDeleteProgram(prog); glDeleteShader(vs); glDeleteShader(fs);\n"
                + "    glDeleteFramebuffers(1, &fbo); glDeleteTextures(1, &rboTex);\n"
                + "    jbyteArray out = env->NewByteArray(static_cast<jsize>(rgba.size()));\n"
                + "    if (out) env->SetByteArrayRegion(out, 0, static_cast<jsize>(rgba.size()),\n"
                + "                                     reinterpret_cast<const jbyte*>(rgba.data()));\n"
                + "    return out;\n"
                + "}\n"
                + "\n"
                + "// Inherit a Mach send right across posix_spawn via mach_ports_register.\n"
                + "extern \"C\" JNIEXPORT jboolean JNICALL\n"
                + "Java_CrossProcessTimestampContractTest_nativeRegisterChildSendRight(\n"
                + "        JNIEnv*, jclass, jint localRecvPort) {\n"
                + "    mach_port_t p = static_cast<mach_port_t>(localRecvPort);\n"
                + "    if (p == MACH_PORT_NULL) return JNI_FALSE;\n"
                + "    if (mach_port_insert_right(mach_task_self(), p, p, MACH_MSG_TYPE_MAKE_SEND) != KERN_SUCCESS) {\n"
                + "        return JNI_FALSE;\n"
                + "    }\n"
                + "    mach_port_t arr[1] = { p };\n"
                + "    kern_return_t kr = mach_ports_register(mach_task_self(), arr, 1);\n"
                + "    mach_port_deallocate(mach_task_self(), p);\n"
                + "    return kr == KERN_SUCCESS ? JNI_TRUE : JNI_FALSE;\n"
                + "}\n"
                + "\n"
                + "extern \"C\" JNIEXPORT jint JNICALL\n"
                + "Java_CrossProcessTimestampContractTest_nativeLookupParentSendRight(JNIEnv*, jclass) {\n"
                + "    mach_port_array_t ports = nullptr;\n"
                + "    mach_msg_type_number_t cnt = 0;\n"
                + "    if (mach_ports_lookup(mach_task_self(), &ports, &cnt) != KERN_SUCCESS || !ports) {\n"
                + "        return 0;\n"
                + "    }\n"
                + "    mach_port_t got = cnt >= 1 ? ports[0] : MACH_PORT_NULL;\n"
                + "    for (mach_msg_type_number_t i = 1; i < cnt; ++i) {\n"
                + "        if (ports[i] != MACH_PORT_NULL) mach_port_deallocate(mach_task_self(), ports[i]);\n"
                + "    }\n"
                + "    vm_deallocate(mach_task_self(), reinterpret_cast<vm_address_t>(ports),\n"
                + "                  cnt * sizeof(mach_port_t));\n"
                + "    return static_cast<jint>(got);\n"
                + "}\n"
                + "\n"
                + "static void test_nativelog_print(JNIEnv*, jclass, jint, jstring, jstring) {}\n"
                + "\n"
                + "extern \"C\" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {\n"
                + "    g_test_jvm = vm;\n"
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
                + "    macapk::video::RegisterNativeVideoMethods(env);\n"
                + "    return JNI_VERSION_21;\n"
                + "}\n";

        Path mmPath = tmpDir.resolve("xproc_ts_contract_jni.mm");
        Path dylibPath = tmpDir.resolve("libxproc_ts_contract_jni.dylib");
        Files.writeString(mmPath, mmSource, StandardCharsets.UTF_8);

        String javaHome = System.getProperty("java.home");
        List<String> cmd = Arrays.asList(
                "clang++",
                "-std=c++17",
                "-O2",
                "-shared",
                "-fPIC",
                "-fobjc-arc",
                "-DMACAPK_HAS_ANGLE=1",
                "-I" + javaHome + "/include",
                "-I" + javaHome + "/include/darwin",
                "-I" + repoRoot.resolve("runtime/src"),
                "-I" + repoRoot.resolve("thirdparty/angle-prebuilt/include"),
                "-L" + binDir,
                "-Wl,-rpath," + binDir,
                "-lEGL",
                "-lGLESv2",
                "-framework", "Foundation",
                "-framework", "VideoToolbox",
                "-framework", "CoreMedia",
                "-framework", "CoreVideo",
                "-framework", "CoreFoundation",
                "-framework", "IOSurface",
                mmPath.toString(),
                repoRoot.resolve("runtime/src/video_toolbox.mm").toString(),
                repoRoot.resolve("runtime/src/video_caps_probe.mm").toString(),
                "-o", dylibPath.toString());
        Process proc = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String output = new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int rc = proc.waitFor();
        if (rc != 0) {
            throw new IllegalStateException("clang++ failed (rc=" + rc + "):\n" + output);
        }
        return dylibPath;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> queryAutomationVideoStats(boolean reset) throws Exception {
        Class<?> srvCls = Class.forName("com.macapk.loader.AutomationServer");
        Method m = srvCls.getDeclaredMethod("handleRawCommand", String.class);
        m.setAccessible(true);
        String cmd = reset
                ? "{\"seq\":1,\"op\":\"video_stats\",\"reset\":true}"
                : "{\"seq\":2,\"op\":\"video_stats\"}";
        Map<String, Object> res = (Map<String, Object>) m.invoke(null, cmd);
        check(Boolean.TRUE.equals(res.get("ok")), "AutomationServer video_stats failed: " + res);
        return res;
    }

    private static long statLong(Map<String, Object> m, String key) {
        Object v = m.get(key);
        check(v instanceof Number, "Missing numeric stat '" + key + "' in " + m);
        return ((Number) v).longValue();
    }

    // ---- Child JVM mode -----------------------------------------------------

    private static final int PHASE_BFRAME_RELEASE_TRUE = 1;
    private static final int PHASE_TIMED_RELEASE = 2;
    private static final int PHASE_BURST_200 = 3;
    private static final int PHASE_FLUSH_GEN_DROP = 4;
    private static final int PHASE_POST_RELEASE_DROP = 5;

    private static IBinder connectHandoffProxy() {
        int port = nativeLookupParentSendRight();
        check(port != 0, "Child failed to look up inherited Mach send right from Parent");
        IBinder proxy = MacApkBinderTransport.macapkImportBinderSendRight(port);
        check(proxy != null, "macapkImportBinderSendRight returned null");
        return proxy;
    }

    private static Surface fetchRemoteSurface(IBinder handoff) throws RemoteException {
        Parcel req = Parcel.obtain();
        Parcel rep = Parcel.obtain();
        try {
            req.writeInterfaceToken(DESCRIPTOR);
            check(handoff.transact(TX_GET_SURFACE, req, rep, 0), "TX_GET_SURFACE failed");
            rep.readException();
            Surface s = Surface.CREATOR.createFromParcel(rep);
            check(s != null && s.isValid(), "Remote Surface invalid");
            check(s.macapkIsRemote(), "Unparcelled cross-process Surface must report macapkIsRemote()=true");
            return s;
        } finally {
            req.recycle();
            rep.recycle();
        }
    }

    private static long[] awaitParentLatched(IBinder handoff, int targetCount, long timeoutMs)
            throws RemoteException {
        Parcel req = Parcel.obtain();
        Parcel rep = Parcel.obtain();
        try {
            req.writeInterfaceToken(DESCRIPTOR);
            req.writeInt(targetCount);
            req.writeLong(timeoutMs);
            check(handoff.transact(TX_AWAIT_LATCHED_COUNT, req, rep, 0), "TX_AWAIT_LATCHED_COUNT failed");
            rep.readException();
            return rep.createLongArray();
        } finally {
            req.recycle();
            rep.recycle();
        }
    }

    private static int runPhaseCommand(IBinder handoff, int op, int arg) throws RemoteException {
        Parcel req = Parcel.obtain();
        Parcel rep = Parcel.obtain();
        try {
            req.writeInterfaceToken(DESCRIPTOR);
            req.writeInt(op);
            req.writeInt(arg);
            check(handoff.transact(TX_DRAIN_ALL_AVAILABLE, req, rep, 0), "TX_DRAIN_ALL_AVAILABLE failed");
            rep.readException();
            return rep.readInt();
        } finally {
            req.recycle();
            rep.recycle();
        }
    }

    /** Configure a decoder MediaCodec bound to remoteSurface for output-buffer release contract tests. */
    private static MediaCodec createConfiguredSurfaceDecoder(Surface remoteSurface, int w, int h)
            throws Exception {
        MediaCodec codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_VP8);
        MediaFormat fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_VP8, w, h);
        codec.configure(fmt, remoteSurface, null, 0);
        // Seed output buffer slots cleanly without needing libvpx bitstream files:
        Field isVideoF = MediaCodec.class.getDeclaredField("mIsVideo");
        Field isEncF = MediaCodec.class.getDeclaredField("mIsEncoder");
        Field targetF = MediaCodec.class.getDeclaredField("mOutputTarget");
        Field outPixF = MediaCodec.class.getDeclaredField("mOutPixbuf");
        Field outPtsF = MediaCodec.class.getDeclaredField("mOutPts");
        Field outFreeF = MediaCodec.class.getDeclaredField("mOutFree");
        isVideoF.setAccessible(true);
        isEncF.setAccessible(true);
        targetF.setAccessible(true);
        outPixF.setAccessible(true);
        outPtsF.setAccessible(true);
        outFreeF.setAccessible(true);
        isVideoF.setBoolean(codec, true);
        isEncF.setBoolean(codec, false);
        Object target = targetF.get(codec);
        Field targetHasSurfF = target.getClass().getDeclaredField("hasSurface");
        targetHasSurfF.setAccessible(true);
        check(targetHasSurfF.getBoolean(target), "configure() must bind OutputTarget.hasSurface=true");
        outPixF.set(codec, new long[24]);
        outPtsF.set(codec, new long[24]);
        @SuppressWarnings("unchecked")
        java.util.ArrayDeque<Integer> outFree = (java.util.ArrayDeque<Integer>) outFreeF.get(codec);
        outFree.clear();
        for (int i = 0; i < 24; i++) outFree.add(i);
        return codec;
    }

    private static int stageDecodedFrame(MediaCodec codec, long pb, long ptsUs) throws Exception {
        Field outPixF = MediaCodec.class.getDeclaredField("mOutPixbuf");
        Field outPtsF = MediaCodec.class.getDeclaredField("mOutPts");
        Field outFreeF = MediaCodec.class.getDeclaredField("mOutFree");
        Field targetF = MediaCodec.class.getDeclaredField("mOutputTarget");
        outPixF.setAccessible(true);
        outPtsF.setAccessible(true);
        outFreeF.setAccessible(true);
        targetF.setAccessible(true);
        @SuppressWarnings("unchecked")
        java.util.ArrayDeque<Integer> outFree = (java.util.ArrayDeque<Integer>) outFreeF.get(codec);
        Integer slot = outFree.pollFirst();
        check(slot != null, "No free MediaCodec output slot");
        ((long[]) outPixF.get(codec))[slot] = pb;
        ((long[]) outPtsF.get(codec))[slot] = ptsUs;
        Object target = targetF.get(codec);
        Field targetSurfF = target.getClass().getDeclaredField("surface");
        targetSurfF.setAccessible(true);
        Surface.macapkRecordProducerStat((Surface) targetSurfF.get(target), Surface.STAT_DEQUEUED_OUT);
        return slot;
    }

    private static void runChildProcess() throws Exception {
        IBinder handoff = connectHandoffProxy();
        Surface remoteSurface = fetchRemoteSurface(handoff);
        MediaCodec codec = createConfiguredSurfaceDecoder(remoteSurface, 16, 16);

        // ---- (1) B-frame-reordered PTS values + staggered sleeps via releaseOutputBuffer(i, true) ----
        // Feed B-frame decode-order PTS (us) through MediaCodec.VideoReorder and verify presentation order.
        Class<?> reorderCls = Class.forName("android.media.MediaCodec$VideoReorder");
        var reorderCtor = reorderCls.getDeclaredConstructor();
        reorderCtor.setAccessible(true);
        Object reorder = reorderCtor.newInstance();
        Method pollMethod = reorderCls.getDeclaredMethod(
                "poll", java.util.function.Supplier.class, java.util.function.LongConsumer.class);
        pollMethod.setAccessible(true);

        // Decode order with B-frames: I(10000), P(76666), B(26666), B(43333), EOS(0)
        long[][] decodeOrder = {
            { nativeCreateColorPixbuf(16, 16, 220, 20, 40),   10_000L, 0L },
            { nativeCreateColorPixbuf(16, 16, 40, 200, 60),   76_666L, 0L },
            { nativeCreateColorPixbuf(16, 16, 30, 80, 230),   26_666L, 0L },
            { nativeCreateColorPixbuf(16, 16, 240, 200, 20),  43_333L, 0L },
            { 0L, 0L, 4L }, // EOS flushes remaining reordered frames
        };
        final int[] feedIdx = { 0 };
        java.util.function.Supplier<long[]> src = () ->
                feedIdx[0] < decodeOrder.length ? decodeOrder[feedIdx[0]++] : null;
        java.util.function.LongConsumer rel = CrossProcessTimestampContractTest::nativeReleaseCf;

        List<long[]> ordered = new ArrayList<>();
        for (;;) {
            long[] out = (long[]) pollMethod.invoke(reorder, src, rel);
            if (out == null || out[0] == 0L) break;
            ordered.add(out);
        }
        check(ordered.size() == 4, "Expected 4 reordered frames, got " + ordered.size());
        long[] expectedPtsUs = { 10_000L, 26_666L, 43_333L, 76_666L };
        long[] staggeredSleepsMs = { 5L, 45L, 2L, 30L };

        runPhaseCommand(handoff, PHASE_BFRAME_RELEASE_TRUE, 0);
        for (int i = 0; i < 4; i++) {
            long[] f = ordered.get(i);
            check(f[1] == expectedPtsUs[i],
                    "VideoReorder PTS mismatch at " + i + ": got " + f[1] + " expected " + expectedPtsUs[i]);
            int slot = stageDecodedFrame(codec, f[0], f[1]);
            Thread.sleep(staggeredSleepsMs[i]);
            codec.releaseOutputBuffer(slot, true);
            long[] latched = awaitParentLatched(handoff, i + 1, 3000L);
            check(latched != null && latched.length == i + 1, "Parent did not latch frame " + i);
            check(latched[i] == expectedPtsUs[i] * 1000L,
                    "Cross-process getTimestamp() mismatch at frame " + i + ": got "
                            + latched[i] + ", expected " + (expectedPtsUs[i] * 1000L));
        }

        // Also record one explicit app-drop releaseOutputBuffer(slot, false) for telemetry alignment.
        long dropPb = nativeCreateColorPixbuf(16, 16, 99, 99, 99);
        int dropSlot = stageDecodedFrame(codec, dropPb, 90_000L);
        codec.releaseOutputBuffer(dropSlot, false);

        // ---- (2) releaseOutputBuffer(index, renderTimestampNs) with past, +45ms, and >1s future ----
        runPhaseCommand(handoff, PHASE_TIMED_RELEASE, 0);
        long nowNs = System.nanoTime();
        long pastTsNs = nowNs - 500_000_000L;
        long future45MsTsNs = nowNs + 45_000_000L;
        long farFuture5sTsNs = nowNs + 5_000_000_000L;

        // 2a. Past timestamp: clamped to immediate due, exact renderTimestampNs preserved!
        long t0 = System.nanoTime();
        int sPast = stageDecodedFrame(codec, nativeCreateColorPixbuf(16, 16, 180, 40, 200), 111_111L);
        codec.releaseOutputBuffer(sPast, pastTsNs);
        long[] latchedPast = awaitParentLatched(handoff, 1, 2000L);
        long elapsedPastMs = (System.nanoTime() - t0) / 1_000_000L;
        check(latchedPast.length == 1 && latchedPast[0] == pastTsNs,
                "Past renderTimestampNs corrupted: got " + Arrays.toString(latchedPast) + " expected " + pastTsNs);
        check(elapsedPastMs < 500L, "Past timestamp took too long: " + elapsedPastMs + "ms");

        // 2b. +45ms future timestamp: waits near 45ms and preserves exact renderTimestampNs!
        long t1 = System.nanoTime();
        long targetFuture45 = t1 + 45_000_000L;
        int sFut45 = stageDecodedFrame(codec, nativeCreateColorPixbuf(16, 16, 40, 180, 200), 222_222L);
        codec.releaseOutputBuffer(sFut45, targetFuture45);
        long[] latchedFut45 = awaitParentLatched(handoff, 2, 2000L);
        long elapsedFut45Ms = (System.nanoTime() - t1) / 1_000_000L;
        check(latchedFut45.length == 2 && latchedFut45[1] == targetFuture45,
                "+45ms renderTimestampNs corrupted: got " + latchedFut45[1] + " expected " + targetFuture45);
        check(elapsedFut45Ms >= 25L && elapsedFut45Ms < 800L,
                "+45ms deadline clamping unexpected elapsed=" + elapsedFut45Ms + "ms");

        // 2c. >1s future timestamp (+5s): host deadline clamped to 'now' (<400ms), exact +5s ts preserved!
        long t2 = System.nanoTime();
        int sFar = stageDecodedFrame(codec, nativeCreateColorPixbuf(16, 16, 200, 180, 40), 333_333L);
        codec.releaseOutputBuffer(sFar, farFuture5sTsNs);
        long[] latchedFar = awaitParentLatched(handoff, 3, 2000L);
        long elapsedFarMs = (System.nanoTime() - t2) / 1_000_000L;
        check(latchedFar.length == 3 && latchedFar[2] == farFuture5sTsNs,
                ">1s future renderTimestampNs corrupted: got " + latchedFar[2] + " expected " + farFuture5sTsNs);
        check(elapsedFarMs < 600L,
                ">1s future timestamp was NOT clamped to immediate host deadline: elapsed=" + elapsedFarMs + "ms");

        // ---- (3) 200-frame tight FLAG_ONEWAY burst ----
        runPhaseCommand(handoff, PHASE_BURST_200, 0);
        long burstPb = nativeCreateColorPixbuf(16, 16, 12, 210, 95);
        check(burstPb != 0L, "Failed to allocate burst IOSurface pixbuf");
        long burstBaseTs = 9_000_000_000L;
        try {
            for (int i = 0; i < 200; i++) {
                remoteSurface.macapkPresentRemotePixelBuffer(
                        burstPb, 16, 16, burstBaseTs + (i * 16_666_667L));
            }
        } finally {
            nativeReleaseCf(burstPb);
        }
        int verifyBurst = runPhaseCommand(handoff, PHASE_BURST_200, 200);
        check(verifyBurst == 200, "Parent burst verification returned " + verifyBurst);

        // ---- (4) flush() drops queued timed frames from old mCodecSurfaceGen ----
        runPhaseCommand(handoff, PHASE_FLUSH_GEN_DROP, 0);
        long flushTestPb = nativeCreateColorPixbuf(16, 16, 250, 10, 250);
        int sFlush = stageDecodedFrame(codec, flushTestPb, 444_444L);
        codec.releaseOutputBuffer(sFlush, System.nanoTime() + 200_000_000L);
        codec.flush();
        Thread.sleep(260L);
        int postFlushCount = runPhaseCommand(handoff, PHASE_FLUSH_GEN_DROP, 1);
        check(postFlushCount == 0, "Flushed old-generation frame leaked to Surface: count=" + postFlushCount);

        // ---- (5) Parent releaseTexImage() -> getTimestamp()==0, Parent Surface.release() -> ipcDroppedInvalid ----
        runPhaseCommand(handoff, PHASE_POST_RELEASE_DROP, 0);
        long afterRelPb = nativeCreateColorPixbuf(16, 16, 70, 70, 70);
        try {
            remoteSurface.macapkPresentRemotePixelBuffer(afterRelPb, 16, 16, 12_345_678_900L);
        } finally {
            nativeReleaseCf(afterRelPb);
        }
        int droppedInvalid = runPhaseCommand(handoff, PHASE_POST_RELEASE_DROP, 1);
        check(droppedInvalid == 1, "Expected ipcDroppedInvalid == 1 after owner Surface.release(), got " + droppedInvalid);

        codec.release();
        remoteSurface.release();
    }

    // ---- Parent JVM mode ----------------------------------------------------

    private static void runParentProcess() throws Exception {
        Path repoRoot = findRepoRoot();
        check(repoRoot != null, "Could not locate repository root");
        Path binDir = findRuntimeBinDir(repoRoot);
        check(binDir != null, "Could not locate runtime/build/bin with ANGLE & libmacapk-binder.dylib");

        Path tmpDir = Files.createTempDirectory("macapk-xproc-ts-test-");
        try {
            Path dylib = buildJniHarnessDylib(tmpDir, repoRoot, binDir);
            System.load(dylib.toAbsolutePath().toString());
            System.setProperty("macapk.binder.lib", binDir.resolve("libmacapk-binder.dylib").toString());

            check(nativeInitAngle(), "Parent failed to initialize ANGLE Metal");
            HandlerThread cbThread = new HandlerThread("xproc-st-listener");
            cbThread.setDaemon(true);
            cbThread.start();

            int tex = nativeGenTexture();
            SurfaceTexture st = new SurfaceTexture(tex);
            Surface ownerSurface = new Surface(st);

            final Object latchMonitor = new Object();
            final List<Long> latchedTimestamps = new ArrayList<>();
            final AtomicInteger availableNotifications = new AtomicInteger();

            // Consumer GL thread owns the ANGLE context and drains SurfaceTexture frames on demand.
            final Queue<Runnable> glTasks = new ConcurrentLinkedQueue<>();
            final CountDownLatch glThreadReady = new CountDownLatch(1);
            final AtomicInteger stopGl = new AtomicInteger(0);
            // Unbind ANGLE on main thread so dedicated GL consumer thread can bind it cleanly.
            // (Re-init inside consumer thread for strict single-thread GL ownership.)
            nativeTeardownAngle();

            Thread glConsumer = new Thread(() -> {
                if (!nativeInitAngle()) throw new IllegalStateException("GL thread initAngle failed");
                int glTex = nativeGenTexture();
                st.detachFromGLContext();
                st.attachToGLContext(glTex);
                glThreadReady.countDown();
                while (stopGl.get() == 0) {
                    Runnable task;
                    while ((task = glTasks.poll()) != null) {
                        task.run();
                    }
                    synchronized (latchMonitor) {
                        while (availableNotifications.get() > 0 && stopGl.get() == 0) {
                            availableNotifications.decrementAndGet();
                            long beforeGen = SurfaceTexture.macapkSnapshotConsumerStats()[2];
                            st.updateTexImage();
                            long afterGen = SurfaceTexture.macapkSnapshotConsumerStats()[2];
                            if (afterGen > beforeGen) {
                                latchedTimestamps.add(st.getTimestamp());
                                latchMonitor.notifyAll();
                            }
                        }
                        if (glTasks.isEmpty() && stopGl.get() == 0) {
                            try { latchMonitor.wait(10L); } catch (InterruptedException ignored) {}
                        }
                    }
                }
                st.release();
                nativeDeleteTexture(glTex);
                nativeTeardownAngle();
            }, "xproc-gl-consumer");
            glConsumer.setDaemon(true);
            glConsumer.start();
            check(glThreadReady.await(5, TimeUnit.SECONDS), "GL consumer thread did not initialize");

            st.setOnFrameAvailableListener(ignored -> {
                synchronized (latchMonitor) {
                    availableNotifications.incrementAndGet();
                    latchMonitor.notifyAll();
                }
            }, new Handler(cbThread.getLooper()));

            final AtomicLong burstLastExpectedTs = new AtomicLong(9_000_000_000L + 199L * 16_666_667L);

            Binder handoffBinder = new Binder() {
                @Override
                protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                        throws RemoteException {
                    switch (code) {
                        case TX_GET_SURFACE: {
                            data.enforceInterface(DESCRIPTOR);
                            reply.writeNoException();
                            ownerSurface.writeToParcel(reply, 0);
                            return true;
                        }
                        case TX_AWAIT_LATCHED_COUNT: {
                            data.enforceInterface(DESCRIPTOR);
                            int want = data.readInt();
                            long timeoutMs = data.readLong();
                            long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
                            long[] snap;
                            synchronized (latchMonitor) {
                                while (latchedTimestamps.size() < want) {
                                    long remNs = deadline - System.nanoTime();
                                    if (remNs <= 0) break;
                                    try {
                                        latchMonitor.wait(remNs / 1_000_000L, (int) (remNs % 1_000_000L));
                                    } catch (InterruptedException ie) {
                                        Thread.currentThread().interrupt();
                                        break;
                                    }
                                }
                                snap = new long[latchedTimestamps.size()];
                                for (int i = 0; i < snap.length; i++) snap[i] = latchedTimestamps.get(i);
                            }
                            reply.writeNoException();
                            reply.writeLongArray(snap);
                            return true;
                        }
                        case TX_DRAIN_ALL_AVAILABLE: {
                            data.enforceInterface(DESCRIPTOR);
                            int phase = data.readInt();
                            int arg = data.readInt();
                            try {
                                int result = handleParentPhase(
                                        phase, arg, st, ownerSurface, latchMonitor,
                                        latchedTimestamps, availableNotifications, glTasks,
                                        burstLastExpectedTs.get());
                                reply.writeNoException();
                                reply.writeInt(result);
                                return true;
                            } catch (Throwable t) {
                                RuntimeException wrap = (t instanceof RuntimeException)
                                        ? (RuntimeException) t : new RuntimeException(t);
                                throw wrap;
                            }
                        }
                    }
                    return super.onTransact(code, data, reply, flags);
                }
            };

            int localRecvPort = MacApkBinderTransport.macapkExportBinderPort(handoffBinder);
            check(localRecvPort != 0, "Failed to export local handoff Binder port");
            check(nativeRegisterChildSendRight(localRecvPort),
                    "mach_ports_register failed in Parent");

            // Reset telemetry before spawning Child.
            queryAutomationVideoStats(true);

            String javaBin = Paths.get(System.getProperty("java.home"), "bin", "java").toString();
            List<String> childCmd = Arrays.asList(
                    javaBin,
                    "-Djava.awt.headless=true",
                    "-Djava.library.path=" + binDir,
                    "-Dmacapk.binder.lib=" + binDir.resolve("libmacapk-binder.dylib"),
                    "-cp", System.getProperty("java.class.path"),
                    "CrossProcessTimestampContractTest",
                    "--child",
                    dylib.toAbsolutePath().toString());
            ProcessBuilder pb = new ProcessBuilder(childCmd);
            pb.environment().put("MACAPK_LOG_LEVEL", "7");
            pb.redirectErrorStream(true);
            Process child = pb.start();
            final java.io.ByteArrayOutputStream childOutBuf = new java.io.ByteArrayOutputStream();
            Thread childDrain = new Thread(() -> {
                try {
                    byte[] buf = new byte[4096];
                    int r;
                    while ((r = child.getInputStream().read(buf)) >= 0) {
                        childOutBuf.write(buf, 0, r);
                    }
                } catch (IOException ignored) {
                }
            }, "xproc-child-drain");
            childDrain.setDaemon(true);
            childDrain.start();
            boolean exited = child.waitFor(45, TimeUnit.SECONDS);
            if (!exited) {
                child.destroyForcibly();
                childDrain.join(1000L);
                String partialOut = new String(childOutBuf.toByteArray(), StandardCharsets.UTF_8);
                throw new AssertionError("Child JVM timed out:\n" + partialOut);
            }
            childDrain.join(2000L);
            String childOut = new String(childOutBuf.toByteArray(), StandardCharsets.UTF_8);
            int childRc = child.exitValue();
            check(childRc == 0, "Child JVM failed (rc=" + childRc + "):\n" + childOut);

            stopGl.set(1);
            synchronized (latchMonitor) { latchMonitor.notifyAll(); }
            glConsumer.join(3000L);
            cbThread.quitSafely();
            System.out.println("PASS: CrossProcessTimestampContractTest (2-JVM Mach Binder + IOSurface + ANGLE Metal)");
        } finally {
            try {
                Files.walk(tmpDir)
                        .sorted((a, b) -> b.compareTo(a))
                        .forEach(p -> {
                            try { Files.deleteIfExists(p); } catch (IOException ignored) {}
                        });
            } catch (IOException ignored) {
            }
        }
    }

    private static void runOnGlSync(Object latchMonitor, Queue<Runnable> glTasks, Runnable r)
            throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        Throwable[] err = new Throwable[1];
        glTasks.add(() -> {
            try { r.run(); } catch (Throwable t) { err[0] = t; } finally { done.countDown(); }
        });
        synchronized (latchMonitor) { latchMonitor.notifyAll(); }
        check(done.await(5, TimeUnit.SECONDS), "Timed out waiting for GL consumer task");
        if (err[0] != null) {
            if (err[0] instanceof Exception) throw (Exception) err[0];
            throw (Error) err[0];
        }
    }

    private static int handleParentPhase(int phase, int arg, SurfaceTexture st, Surface ownerSurface,
                                         Object latchMonitor, List<Long> latchedTimestamps,
                                         AtomicInteger availableNotifications,
                                         Queue<Runnable> glTasks, long burstFinalTs) throws Exception {
        if (phase == PHASE_BFRAME_RELEASE_TRUE) {
            synchronized (latchMonitor) { latchedTimestamps.clear(); }
            queryAutomationVideoStats(true);
            return 0;
        }
        if (phase == PHASE_TIMED_RELEASE) {
            // Verify last latched frame from Phase 1 (PTS 76_666us -> P-frame RGB 40,200,60)
            // rendered real cross-process IOSurface pixels via ANGLE Metal!
            runOnGlSync(latchMonitor, glTasks, () -> {
                float[] mtx = new float[16];
                st.getTransformMatrix(mtx);
                byte[] rgba = nativeRenderAndReadPixels(st.macapkGetTexName(), mtx, 16, 16);
                check(rgba != null && rgba.length == 16 * 16 * 4, "FBO readback failed");
                int r = rgba[0] & 0xff, g = rgba[1] & 0xff, b = rgba[2] & 0xff;
                check(Math.abs(r - 40) <= 2 && Math.abs(g - 200) <= 2 && Math.abs(b - 60) <= 2,
                        "Cross-process IOSurface pixel mismatch: got (" + r + "," + g + "," + b + ")");
            });
            Map<String, Object> s1 = queryAutomationVideoStats(false);
            check(statLong(s1, "dequeuedOut") == 4L, "Phase 1 dequeuedOut mismatch: " + s1);
            check(statLong(s1, "releaseTrue") == 4L, "Phase 1 releaseTrue mismatch: " + s1);
            check(statLong(s1, "ipcSent") == 4L, "Phase 1 ipcSent mismatch: " + s1);
            check(statLong(s1, "ipcRecv") == 4L, "Phase 1 ipcRecv mismatch: " + s1);
            check(statLong(s1, "ipcSeqOutOfOrder") == 0L, "Phase 1 ipcSeqOutOfOrder != 0: " + s1);

            synchronized (latchMonitor) { latchedTimestamps.clear(); }
            return 0;
        }
        if (phase == PHASE_BURST_200 && arg == 0) {
            // Before resetting for the 200-frame burst, verify Phase 1+2 piggybacked stats
            // (including the 1 release(false) drop recorded before Phase 2's 3 timed presents!).
            Map<String, Object> s2 = queryAutomationVideoStats(false);
            check(statLong(s2, "releaseFalse") == 1L, "Expected piggybacked releaseFalse==1: " + s2);
            check(statLong(s2, "releaseTimed") == 3L, "Expected piggybacked releaseTimed==3: " + s2);
            check(statLong(s2, "ipcSeqOutOfOrder") == 0L, "Phase 2 ipcSeqOutOfOrder != 0: " + s2);

            synchronized (latchMonitor) {
                latchedTimestamps.clear();
                availableNotifications.set(0);
            }
            queryAutomationVideoStats(true);
            return 0;
        }
        if (phase == PHASE_BURST_200 && arg == 200) {
            long deadline = System.nanoTime() + 8_000_000_000L;
            for (;;) {
                Map<String, Object> sb = queryAutomationVideoStats(false);
                long recv = statLong(sb, "ipcRecv");
                long lastTs = statLong(sb, "lastLatchedTsNs");
                if (recv >= 200L && lastTs == burstFinalTs) {
                    check(statLong(sb, "ipcSent") == 200L, "Burst ipcSent != 200: " + sb);
                    check(recv == 200L, "Burst ipcRecv != 200: " + sb);
                    check(statLong(sb, "ipcSeqOutOfOrder") == 0L,
                            "200-frame FLAG_ONEWAY burst had out-of-order frames! stats=" + sb);
                    check(statLong(sb, "ipcDroppedInvalid") == 0L, "Unexpected droppedInvalid: " + sb);
                    check(statLong(sb, "ringAccepted") == 200L, "Burst ringAccepted != 200: " + sb);
                    check(statLong(sb, "updateTexLatched") >= 1L, "Burst latched 0 frames: " + sb);
                    @SuppressWarnings("unchecked")
                    Map<String, Object> latMap = (Map<String, Object>) sb.get("latchIntervalMs");
                    check(latMap != null && latMap.containsKey("p50") && latMap.containsKey("p99"),
                            "Missing latchIntervalMs percentiles: " + sb);
                    break;
                }
                if (System.nanoTime() >= deadline) {
                    throw new AssertionError("Timed out waiting for 200-frame burst drain; stats=" + sb);
                }
                synchronized (latchMonitor) {
                    availableNotifications.incrementAndGet();
                    latchMonitor.notifyAll();
                }
                Thread.sleep(10L);
            }
            synchronized (latchMonitor) {
                for (int i = 1; i < latchedTimestamps.size(); i++) {
                    long prev = latchedTimestamps.get(i - 1);
                    long cur = latchedTimestamps.get(i);
                    check(cur > prev, "Non-monotonic latched timestamp at " + i + ": " + prev + " -> " + cur);
                }
                check(!latchedTimestamps.isEmpty()
                        && latchedTimestamps.get(latchedTimestamps.size() - 1) == burstFinalTs,
                        "Final latched timestamp != burstFinalTs");
            }
            return 200;
        }
        if (phase == PHASE_FLUSH_GEN_DROP && arg == 0) {
            synchronized (latchMonitor) { latchedTimestamps.clear(); }
            queryAutomationVideoStats(true);
            return 0;
        }
        if (phase == PHASE_FLUSH_GEN_DROP && arg == 1) {
            Map<String, Object> sf = queryAutomationVideoStats(false);
            return (int) statLong(sf, "ipcRecv");
        }
        if (phase == PHASE_POST_RELEASE_DROP && arg == 0) {
            // Test releaseTexImage() resetting getTimestamp() to 0, then release ownerSurface!
            runOnGlSync(latchMonitor, glTasks, () -> {
                check(st.getTimestamp() != 0L, "Expected nonzero timestamp before releaseTexImage()");
                st.releaseTexImage();
                check(st.getTimestamp() == 0L,
                        "releaseTexImage() must reset getTimestamp() to 0, got " + st.getTimestamp());
            });
            queryAutomationVideoStats(true);
            ownerSurface.release();
            check(!ownerSurface.isValid(), "ownerSurface must be invalid after release()");
            return 0;
        }
        if (phase == PHASE_POST_RELEASE_DROP && arg == 1) {
            long deadline = System.nanoTime() + 3_000_000_000L;
            while (System.nanoTime() < deadline) {
                Map<String, Object> sr = queryAutomationVideoStats(false);
                long dropped = statLong(sr, "ipcDroppedInvalid");
                if (dropped >= 1L) {
                    return (int) dropped;
                }
                Thread.sleep(10L);
            }
            throw new AssertionError("Timed out waiting for post-release frame delivery");
        }
        throw new IllegalArgumentException("Unknown phase=" + phase + " arg=" + arg);
    }

    public static void main(String[] args) throws Exception {
        if (args.length >= 2 && "--child".equals(args[0])) {
            System.load(args[1]);
            runChildProcess();
            System.exit(0);
            return;
        }
        runParentProcess();
    }
}
