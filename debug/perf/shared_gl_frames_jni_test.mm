#include <jni.h>
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <EGL/eglext_angle.h>
#include <GLES2/gl2.h>
#include <IOSurface/IOSurface.h>
#include <cstdint>

namespace macapk {
// This JNI harness isolates pool semantics and has no Cocoa run loop. The
// direct-layer entry is captured so the Java test can exercise both immediate
// rejection and an asynchronous compositor completion.
static bool acceptPresent=true;
static void (*pendingRelease)(void*)=nullptr;
static void* pendingContext=nullptr;
bool CocoaPresentSurfaceIOSurfaceFrom(int, int, int, IOSurfaceRef,
                                      void (*release)(void*), void* context) {
    if(!acceptPresent || !release || pendingRelease) return false;
    pendingRelease=release;
    pendingContext=context;
    return true;
}
}

static EGLDisplay display=EGL_NO_DISPLAY;
static EGLContext context=EGL_NO_CONTEXT;
extern "C" JNIEXPORT jlongArray JNICALL Java_SharedGlFramesJniTest_setup(JNIEnv* env,jclass) {
    auto getPlatform=(PFNEGLGETPLATFORMDISPLAYEXTPROC)eglGetProcAddress("eglGetPlatformDisplayEXT");
    const EGLint attrs[]={EGL_PLATFORM_ANGLE_TYPE_ANGLE,EGL_PLATFORM_ANGLE_TYPE_METAL_ANGLE,EGL_NONE};
    display=getPlatform(EGL_PLATFORM_ANGLE_ANGLE,reinterpret_cast<void*>(0x4A453130),attrs);
    if(display==EGL_NO_DISPLAY || !eglInitialize(display,nullptr,nullptr)) return nullptr;
    const EGLint ca[]={EGL_SURFACE_TYPE,EGL_PBUFFER_BIT,EGL_RENDERABLE_TYPE,EGL_OPENGL_ES2_BIT,EGL_RED_SIZE,8,EGL_GREEN_SIZE,8,EGL_BLUE_SIZE,8,EGL_ALPHA_SIZE,8,EGL_NONE};
    EGLConfig cfg=nullptr;EGLint n=0;
    if(!eglChooseConfig(display,ca,&cfg,1,&n)||n!=1) return nullptr;
    const EGLint cc[]={EGL_CONTEXT_CLIENT_VERSION,2,EGL_NONE};context=eglCreateContext(display,cfg,EGL_NO_CONTEXT,cc);
    if(context==EGL_NO_CONTEXT) return nullptr;
    jlong v[]={static_cast<jlong>(reinterpret_cast<intptr_t>(display)),static_cast<jlong>(reinterpret_cast<intptr_t>(cfg))};
    jlongArray result=env->NewLongArray(2);if(result)env->SetLongArrayRegion(result,0,2,v);return result;
}
extern "C" JNIEXPORT jboolean JNICALL Java_SharedGlFramesJniTest_paint(JNIEnv*,jclass,jlong handle,jint color) {
    auto surface=reinterpret_cast<EGLSurface>(static_cast<intptr_t>(handle));
    if(!eglMakeCurrent(display,surface,surface,context))return JNI_FALSE;
    glClearColor(color==0?1:0,color==1?1:0,color==2?1:0,1);glClear(GL_COLOR_BUFFER_BIT);glFinish();
    return glGetError()==GL_NO_ERROR && eglSwapBuffers(display,surface);
}
extern "C" JNIEXPORT jboolean JNICALL Java_SharedGlFramesJniTest_paintPattern(JNIEnv*,jclass,jlong handle) {
    auto surface=reinterpret_cast<EGLSurface>(static_cast<intptr_t>(handle));
    if(!eglMakeCurrent(display,surface,surface,context))return JNI_FALSE;
    glDisable(GL_SCISSOR_TEST);glClearColor(0,0,1,1);glClear(GL_COLOR_BUFFER_BIT);
    glEnable(GL_SCISSOR_TEST);glScissor(0,0,32,32);glClearColor(.25,0,0,.5);glClear(GL_COLOR_BUFFER_BIT);
    glScissor(32,0,32,32);glClearColor(0,1,0,1);glClear(GL_COLOR_BUFFER_BIT);
    glScissor(32,32,32,32);glClearColor(1,0,0,0);glClear(GL_COLOR_BUFFER_BIT);
    glDisable(GL_SCISSOR_TEST);glFinish();
    return glGetError()==GL_NO_ERROR && eglSwapBuffers(display,surface);
}
extern "C" JNIEXPORT jboolean JNICALL Java_SharedGlFramesJniTest_pixelIs(JNIEnv*,jclass,jlong handle,jint color) {
    auto io=reinterpret_cast<IOSurfaceRef>(static_cast<intptr_t>(handle));
    if(!io || IOSurfaceLock(io,kIOSurfaceLockReadOnly,nullptr)!=kIOReturnSuccess)return JNI_FALSE;
    const auto* p=static_cast<const unsigned char*>(IOSurfaceGetBaseAddress(io));
    bool ok=p && p[0]==(color==2?255:0) && p[1]==(color==1?255:0) && p[2]==(color==0?255:0) && p[3]==255;
    IOSurfaceUnlock(io,kIOSurfaceLockReadOnly,nullptr);return ok;
}
extern "C" JNIEXPORT void JNICALL Java_SharedGlFramesJniTest_teardown(JNIEnv*,jclass) {
    eglMakeCurrent(display,EGL_NO_SURFACE,EGL_NO_SURFACE,EGL_NO_CONTEXT);eglDestroyContext(display,context);
    // Pool still owns EGL surfaces. The process exits after pool.destroy().
}
extern "C" JNIEXPORT void JNICALL Java_SharedGlFramesJniTest_setPresentAccepted(
    JNIEnv*,jclass,jboolean accepted) {
    macapk::acceptPresent=accepted;
}
extern "C" JNIEXPORT jboolean JNICALL Java_SharedGlFramesJniTest_completePresent(
    JNIEnv*,jclass) {
    void (*release)(void*)=macapk::pendingRelease;
    void* context=macapk::pendingContext;
    macapk::pendingRelease=nullptr;
    macapk::pendingContext=nullptr;
    if(!release) return JNI_FALSE;
    release(context);
    return JNI_TRUE;
}
