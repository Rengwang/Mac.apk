// Headless multiframe EGL -> IOSurface -> Metal GPU blit correctness probe.
// EGL_ANGLE_iosurface_client_buffer: create a renderable IOSurface pbuffer,
// render a known color, and inspect the pixels only after GPU completion.
#import <Foundation/Foundation.h>
#import <IOSurface/IOSurface.h>
#import <Metal/Metal.h>
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <EGL/eglext_angle.h>
#include <GLES2/gl2.h>
#include <cstdio>
#include <cstring>
#include "../../runtime/src/shared_gl_frames.h"
#include <chrono>
#include <vector>
#include <algorithm>

int main(int argc, char **argv) {
    @autoreleasepool {
        bool vulkan = argc > 1 && std::strcmp(argv[1], "vulkan") == 0;
        bool privateDisplay = argc > 1 && std::strcmp(argv[1], "metal-private") == 0;
        const bool full = argc > 2 && std::strcmp(argv[2], "full") == 0;
        const int W = full ? 998 : 64, H = full ? 1480 : 64;
        auto getPlatform = (PFNEGLGETPLATFORMDISPLAYEXTPROC)eglGetProcAddress("eglGetPlatformDisplayEXT");
        const EGLint platformAttrs[] = {EGL_PLATFORM_ANGLE_TYPE_ANGLE,
            vulkan ? EGL_PLATFORM_ANGLE_TYPE_VULKAN_ANGLE : EGL_PLATFORM_ANGLE_TYPE_METAL_ANGLE, EGL_NONE};
        EGLDisplay d = getPlatform ? getPlatform(EGL_PLATFORM_ANGLE_ANGLE, privateDisplay ? reinterpret_cast<void*>(0x4A453130) : nullptr, platformAttrs) : EGL_NO_DISPLAY;
        if (d == EGL_NO_DISPLAY || !eglInitialize(d, nullptr, nullptr)) {
            std::fprintf(stderr,"FAIL: initialize %s EGL error=0x%x\n",vulkan?"vulkan":"metal",eglGetError());return 1;
        }
        const char* exts = eglQueryString(d, EGL_EXTENSIONS);
        if (!exts || !std::strstr(exts,"EGL_ANGLE_iosurface_client_buffer")) {
            std::printf("UNSUPPORTED: %s has no EGL_ANGLE_iosurface_client_buffer\n",vulkan?"vulkan":"metal");eglTerminate(d);return 2;
        }
        const EGLint cfgAttrs[] = {EGL_SURFACE_TYPE,EGL_PBUFFER_BIT,EGL_RENDERABLE_TYPE,EGL_OPENGL_ES2_BIT,
                                  EGL_RED_SIZE,8,EGL_GREEN_SIZE,8,EGL_BLUE_SIZE,8,EGL_ALPHA_SIZE,8,EGL_NONE};
        EGLConfig cfg; EGLint n=0;
        if (!eglChooseConfig(d,cfgAttrs,&cfg,1,&n) || n != 1) {std::fprintf(stderr,"FAIL: config 0x%x\n",eglGetError());return 1;}



        auto* pool=macapk::SharedGlFramesCreate(d,cfg,W,H);
        if(!pool)return 1;
        uint64_t token=0;EGLSurface shared=EGL_NO_SURFACE;
        if(!macapk::SharedGlFramesBegin(pool,&token,&shared))return 1;
        const EGLint sa[]={EGL_WIDTH,W,EGL_HEIGHT,H,EGL_NONE};EGLSurface regular=eglCreatePbufferSurface(d,cfg,sa);
        const EGLint ca[]={EGL_CONTEXT_CLIENT_VERSION,2,EGL_NONE};EGLContext context=eglCreateContext(d,cfg,EGL_NO_CONTEXT,ca);
        if(regular==EGL_NO_SURFACE||context==EGL_NO_CONTEXT)return 1;
        std::vector<uint32_t> raw(size_t(W)*H),oldPixels(raw.size()),sharedPixels(raw.size());
        auto paint=[&]() {
            glDisable(GL_SCISSOR_TEST);glClearColor(0,0,1,1);glClear(GL_COLOR_BUFFER_BIT);
            glEnable(GL_SCISSOR_TEST);glScissor(0,0,W/2,H/2);glClearColor(.25,0,0,.5);glClear(GL_COLOR_BUFFER_BIT);glDisable(GL_SCISSOR_TEST);
        };
        auto oldConvert=[&]() {
            for(int y=0;y<H;++y)for(int x=0;x<W;++x) {
                uint32_t px=raw[size_t(H-1-y)*W+x],a=px>>24,r=px&255,b=(px>>16)&255;
                if(a!=255){if(!a){px=0;r=b=0;}else{uint32_t g=(px>>8)&255;r=(r*255+(a>>1))/a;g=(g*255+(a>>1))/a;b=(b*255+(a>>1))/a;px=(a<<24)|(g<<8);}}
                oldPixels[size_t(y)*W+x]=(px&0xff00ff00u)|(r<<16)|b;
            }
        };
        auto ns=[](){return std::chrono::steady_clock::now();};
        double regularMs=0,sharedMs=0;int measure=20,warmup=5;
        for(int round=0;round<warmup+measure;++round) {
            if(!eglMakeCurrent(d,regular,regular,context))return 1;paint();auto a=ns();glReadPixels(0,0,W,H,GL_RGBA,GL_UNSIGNED_BYTE,raw.data());oldConvert();auto b=ns();
            if(glGetError()!=GL_NO_ERROR)return 1;
            if(!eglMakeCurrent(d,shared,shared,context))return 1;paint();auto c=ns();glFinish();bool ok=macapk::SharedGlFramesCopyDrawingArgb(pool,token,sharedPixels.data(),sharedPixels.size());auto e=ns();
            if(!ok || glGetError()!=GL_NO_ERROR)return 1;
            if(!std::equal(oldPixels.begin(),oldPixels.end(),sharedPixels.begin())) {std::fprintf(stderr,"FAIL: output mismatch round=%d\n",round);return 1;}
            if(round>=warmup){regularMs+=std::chrono::duration<double,std::milli>(b-a).count();sharedMs+=std::chrono::duration<double,std::milli>(e-c).count();}
        }
        std::printf("PASS size=%dx%d rounds=%d full-array equal old_read_convert_ms=%.3f shared_finish_copy_ms=%.3f (synthetic only)\n",W,H,measure,regularMs/measure,sharedMs/measure);
        eglMakeCurrent(d,EGL_NO_SURFACE,EGL_NO_SURFACE,EGL_NO_CONTEXT);eglDestroyContext(d,context);eglDestroySurface(d,regular);
        if(!macapk::SharedGlFramesCancel(pool,token)||!macapk::SharedGlFramesDestroy(pool))return 1;eglTerminate(d);return 0;
    }
}
