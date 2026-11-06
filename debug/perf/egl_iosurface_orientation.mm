// Headless feasibility probe; does not change Mac.apk or launch an APK.
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
#include <vector>

int main(int argc, char **argv) {
    @autoreleasepool {
        bool vulkan = argc > 1 && std::strcmp(argv[1], "vulkan") == 0;
        bool privateDisplay = argc > 1 && std::strcmp(argv[1], "metal-private") == 0;
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
        NSDictionary *props=@{(id)kIOSurfaceWidth:@64,(id)kIOSurfaceHeight:@64,
                              (id)kIOSurfaceBytesPerElement:@4,(id)kIOSurfacePixelFormat:@(0x42475241)};
        IOSurfaceRef io=IOSurfaceCreate((__bridge CFDictionaryRef)props);
        if (!io) {std::fprintf(stderr,"FAIL: IOSurfaceCreate\n");return 1;}
        EGLint target=0;
        if (!eglGetConfigAttrib(d,cfg,EGL_BIND_TO_TEXTURE_TARGET_ANGLE,&target)) {std::fprintf(stderr,"FAIL: target 0x%x\n",eglGetError());return 1;}
        const EGLint attrs[]={EGL_WIDTH,64,EGL_HEIGHT,64,EGL_IOSURFACE_PLANE_ANGLE,0,
             EGL_TEXTURE_TARGET,target,EGL_TEXTURE_INTERNAL_FORMAT_ANGLE,0x80E1,
             EGL_TEXTURE_FORMAT,EGL_TEXTURE_RGBA,EGL_TEXTURE_TYPE_ANGLE,GL_UNSIGNED_BYTE,EGL_NONE};
        EGLSurface surface=eglCreatePbufferFromClientBuffer(d,EGL_IOSURFACE_ANGLE,(EGLClientBuffer)io,cfg,attrs);
        if (surface==EGL_NO_SURFACE) {std::fprintf(stderr,"FAIL: client pbuffer error=0x%x target=0x%x\n",eglGetError(),target);return 1;}
        const EGLint ca[]={EGL_CONTEXT_CLIENT_VERSION,2,EGL_NONE}; EGLContext context=eglCreateContext(d,cfg,EGL_NO_CONTEXT,ca);
        if (context==EGL_NO_CONTEXT || !eglMakeCurrent(d,surface,surface,context)) {std::fprintf(stderr,"FAIL: make current 0x%x\n",eglGetError());return 1;}

        // Asymmetric colors, including alpha, discriminate row orientation and channel order.
        glDisable(GL_SCISSOR_TEST);glClearColor(0,0,1,1);glClear(GL_COLOR_BUFFER_BIT);
        glEnable(GL_SCISSOR_TEST);
        glScissor(0,0,32,32);glClearColor(1,0,0,0.5);glClear(GL_COLOR_BUFFER_BIT);
        glScissor(32,0,32,32);glClearColor(0,1,0,1);glClear(GL_COLOR_BUFFER_BIT);
        glScissor(0,32,32,32);glClearColor(0.25,0.5,0.75,0.5);glClear(GL_COLOR_BUFFER_BIT);
        glDisable(GL_SCISSOR_TEST);
        glFinish();
        std::vector<unsigned char> rgba(64*64*4);
        glReadPixels(0,0,64,64,GL_RGBA,GL_UNSIGNED_BYTE,rgba.data());
        if(glGetError()!=GL_NO_ERROR) return 1;
        if(IOSurfaceLock(io,kIOSurfaceLockReadOnly,nullptr)!=kIOReturnSuccess)return 1;
        const auto* src=(const unsigned char*)IOSurfaceGetBaseAddress(io);size_t stride=IOSurfaceGetBytesPerRow(io);
        size_t same=0,flipped=0;
        for(int y=0;y<64;++y)for(int x=0;x<64;++x){
            const auto* gl=rgba.data()+(y*64+x)*4;
            const auto* a=src+y*stride+x*4;const auto* b=src+(63-y)*stride+x*4;
            same+=(gl[0]==a[2]&&gl[1]==a[1]&&gl[2]==a[0]&&gl[3]==a[3]);
            flipped+=(gl[0]==b[2]&&gl[1]==b[1]&&gl[2]==b[0]&&gl[3]==b[3]);
        }
        IOSurfaceUnlock(io,kIOSurfaceLockReadOnly,nullptr);
        std::printf("backend=%s exact_same=%zu exact_flipped=%zu total=4096\n",vulkan?"vulkan":(privateDisplay?"metal-private":"metal"),same,flipped);
        eglMakeCurrent(d,EGL_NO_SURFACE,EGL_NO_SURFACE,EGL_NO_CONTEXT);eglDestroyContext(d,context);eglDestroySurface(d,surface);CFRelease(io);eglTerminate(d);
        return (same==4096 || flipped==4096)?0:1;
    }
}
