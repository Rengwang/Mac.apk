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

int main(int argc, char **argv) {
    @autoreleasepool {
        bool vulkan = argc > 1 && std::strcmp(argv[1], "vulkan") == 0;
        bool privateDisplay = argc > 1 && std::strcmp(argv[1], "metal-private") == 0;
        const bool full = argc > 2 && std::strcmp(argv[2], "full") == 0;
        const int W = full ? 998 : 64, H = full ? 1480 : 64;
        const size_t rowBytes = (size_t(W)*4+255) & ~size_t(255);
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
        if (!pool) {std::fprintf(stderr,"FAIL: create pool EGL=0x%x\n",eglGetError());return 1;}
        const EGLint ca[]={EGL_CONTEXT_CLIENT_VERSION,2,EGL_NONE};
        EGLContext context=eglCreateContext(d,cfg,EGL_NO_CONTEXT,ca);
        id<MTLDevice> device=MTLCreateSystemDefaultDevice(); id<MTLCommandQueue> queue=[device newCommandQueue];
        auto render = [&](int f,uint64_t* token) {
            EGLSurface surface=EGL_NO_SURFACE;
            if (!macapk::SharedGlFramesBegin(pool,token,&surface)) return false;
            if (!eglMakeCurrent(d,surface,surface,context)) return false;
            glClearColor((f%2)?0:1,(f%2)?1:0,0,1);glClear(GL_COLOR_BUFFER_BIT);glFinish();
            if (glGetError()!=GL_NO_ERROR || !eglSwapBuffers(d,surface)) return false;
            return macapk::SharedGlFramesPublish(pool,*token);
        };
        uint64_t tokens[3]={};
        for(int i=0;i<3;++i) if(!render(i,&tokens[i])) return 1;
        uint64_t tmp=0;EGLSurface extra=EGL_NO_SURFACE;
        if(macapk::SharedGlFramesBegin(pool,&tmp,&extra) || macapk::SharedGlFramesDestroy(pool)) {std::fprintf(stderr,"FAIL: ring capacity/lifetime\n");return 1;}
        for(int i=0;i<3;++i) {
            uint64_t t=0;IOSurfaceRef image=nullptr;
            if(!macapk::SharedGlFramesTake(pool,&t,&image) || t!=tokens[i]) {std::fprintf(stderr,"FAIL: FIFO\n");return 1;}
            auto* desc=[MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatBGRA8Unorm width:W height:H mipmapped:NO];
            desc.usage=MTLTextureUsageShaderRead;
            id<MTLTexture> texture=[device newTextureWithDescriptor:desc iosurface:image plane:0];
            id<MTLBuffer> out=[device newBufferWithLength:rowBytes*H options:MTLResourceStorageModeShared];
            id<MTLCommandBuffer> cb=[queue commandBuffer];id<MTLBlitCommandEncoder> blit=[cb blitCommandEncoder];
            [blit copyFromTexture:texture sourceSlice:0 sourceLevel:0 sourceOrigin:MTLOriginMake(0,0,0) sourceSize:MTLSizeMake(W,H,1) toBuffer:out destinationOffset:0 destinationBytesPerRow:rowBytes destinationBytesPerImage:rowBytes*H];
            [blit endEncoding];[cb commit];[cb waitUntilCompleted];
            auto* px=(const unsigned char*)out.contents;
            if(cb.status!=MTLCommandBufferStatusCompleted || !px || px[0]!=0 || px[1]!=(i%2?255:0) || px[2]!=(i%2?0:255) || px[3]!=255) {std::fprintf(stderr,"FAIL: GPU pixels frame=%d\n",i);return 1;}
            if(!macapk::SharedGlFramesRelease(pool,t) || macapk::SharedGlFramesRelease(pool,t)) {std::fprintf(stderr,"FAIL: release token\n");return 1;}
        }
        uint64_t next=0;EGLSurface nextSurface=EGL_NO_SURFACE;
        if(!macapk::SharedGlFramesBegin(pool,&next,&nextSurface) || next==tokens[0] || macapk::SharedGlFramesRelease(pool,tokens[0])) {std::fprintf(stderr,"FAIL: stale token\n");return 1;}
        auto* second=macapk::SharedGlFramesCreate(d,cfg,W,H);
        uint64_t other=0;EGLSurface otherSurface=EGL_NO_SURFACE;
        if(!second || !macapk::SharedGlFramesBegin(second,&other,&otherSurface) || other==next ||
           macapk::SharedGlFramesCancel(second,next) || !macapk::SharedGlFramesCancel(second,other) ||
           !macapk::SharedGlFramesDestroy(second)) {std::fprintf(stderr,"FAIL: cross-pool token isolation\n");return 1;}
        if(!macapk::SharedGlFramesCancel(pool,next)) return 1;

        auto* rotation=macapk::SharedGlFramesCreate(d,cfg,W,H);
        uint64_t first=0;EGLSurface firstSurface=EGL_NO_SURFACE;
        if(!rotation || !macapk::SharedGlFramesBegin(rotation,&first,&firstSurface) ||
           !eglMakeCurrent(d,firstSurface,firstSurface,context)) {
            std::fprintf(stderr,"FAIL: direct-rotation setup\n");return 1;
        }
        glClearColor(0,1,0,1);glClear(GL_COLOR_BUFFER_BIT);glFinish();
        IOSurfaceRef presented=nullptr;int presentedW=0,presentedH=0;
        uint64_t rotated=0;EGLSurface rotatedSurface=EGL_NO_SURFACE;
        if(!macapk::SharedGlFramesPublishAndBegin(rotation,first,&presented,&presentedW,
               &presentedH,&rotated,&rotatedSurface) || !presented || presentedW!=W ||
           presentedH!=H || rotated==first || rotatedSurface==EGL_NO_SURFACE ||
           !macapk::SharedGlFramesOwnsSurface(rotation,firstSurface) ||
           !macapk::SharedGlFramesOwnsSurface(rotation,rotatedSurface) ||
           macapk::SharedGlFramesReleasePresented(rotation,rotated)) {
            std::fprintf(stderr,"FAIL: direct-rotation transition\n");return 1;
        }
        if(!macapk::SharedGlFramesReleasePresented(rotation,first) ||
           !macapk::SharedGlFramesCancel(rotation,rotated) ||
           !macapk::SharedGlFramesDestroy(rotation)) {
            std::fprintf(stderr,"FAIL: direct-rotation lifetime\n");return 1;
        }
        eglMakeCurrent(d,EGL_NO_SURFACE,EGL_NO_SURFACE,EGL_NO_CONTEXT);eglDestroyContext(d,context);
        if(!macapk::SharedGlFramesDestroy(pool)) return 1;
        eglTerminate(d);
        std::puts("PASS: actual shared frame pool, FIFO, bounded capacity, stale tokens, direct rotation, GPU colors, destroy");return 0;
    }
}
