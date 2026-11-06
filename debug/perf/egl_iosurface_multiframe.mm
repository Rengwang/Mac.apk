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

        EGLint target=0;
        if (!eglGetConfigAttrib(d,cfg,EGL_BIND_TO_TEXTURE_TARGET_ANGLE,&target)) {std::fprintf(stderr,"FAIL: target 0x%x\n",eglGetError());return 1;}
        const EGLint attrs[]={EGL_WIDTH,W,EGL_HEIGHT,H,EGL_IOSURFACE_PLANE_ANGLE,0,
             EGL_TEXTURE_TARGET,target,EGL_TEXTURE_INTERNAL_FORMAT_ANGLE,0x80E1,
             EGL_TEXTURE_FORMAT,EGL_TEXTURE_RGBA,EGL_TEXTURE_TYPE_ANGLE,GL_UNSIGNED_BYTE,EGL_NONE};
        const EGLint ca[]={EGL_CONTEXT_CLIENT_VERSION,2,EGL_NONE};
        EGLContext context=eglCreateContext(d,cfg,EGL_NO_CONTEXT,ca);
        if (context==EGL_NO_CONTEXT) return 1;
        id<MTLDevice> device = MTLCreateSystemDefaultDevice();
        id<MTLCommandQueue> queue = [device newCommandQueue];
        IOSurfaceRef ios[3]={}; EGLSurface surfaces[3]={};
        id<MTLTexture> textures[3]={}; id<MTLBuffer> outputs[3]={};
        id<MTLCommandBuffer> inflight[3]={}; int pending[3]={-1,-1,-1};
        const unsigned char colors[][4] = {{255,0,0,255},{0,255,0,255},{0,0,255,255},{64,128,192,128}};
        for (int i=0;i<3;++i) {
            NSDictionary *props=@{(id)kIOSurfaceWidth:@(W),(id)kIOSurfaceHeight:@(H),
                 (id)kIOSurfaceBytesPerElement:@4,(id)kIOSurfacePixelFormat:@(0x42475241)};
            ios[i]=IOSurfaceCreate((__bridge CFDictionaryRef)props);
            surfaces[i]=eglCreatePbufferFromClientBuffer(d,EGL_IOSURFACE_ANGLE,(EGLClientBuffer)ios[i],cfg,attrs);
            MTLTextureDescriptor *desc=[MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatBGRA8Unorm width:W height:H mipmapped:NO];
            desc.usage=MTLTextureUsageShaderRead;
            textures[i]=[device newTextureWithDescriptor:desc iosurface:ios[i] plane:0];
            outputs[i]=[device newBufferWithLength:rowBytes*H options:MTLResourceStorageModeShared];
            if (!ios[i] || surfaces[i]==EGL_NO_SURFACE || !textures[i] || !outputs[i] || !queue) {std::fprintf(stderr,"FAIL: slot=%d resources EGL=0x%x\n",i,eglGetError());return 1;}
        }
        auto retire = [&](int i) -> bool {
            if (pending[i] < 0) return true;
            [inflight[i] waitUntilCompleted];
            if (inflight[i].status != MTLCommandBufferStatusCompleted) return false;
            const unsigned char* c=colors[pending[i]%4];
            const unsigned char* px=(const unsigned char*)outputs[i].contents;
            auto matches = [&](size_t off) { return px && px[off]==c[2] && px[off+1]==c[1] && px[off+2]==c[0] && px[off+3]==c[3]; };
            if (!matches(0) || !matches((H/2)*rowBytes+(W/2)*4) || !matches((H-1)*rowBytes+(W-1)*4)) {
                std::fprintf(stderr,"FAIL: frame=%d expected BGRA=%u,%u,%u,%u got=%u,%u,%u,%u\n",pending[i],c[2],c[1],c[0],c[3],px?px[0]:0,px?px[1]:0,px?px[2]:0,px?px[3]:0);return false;
            }
            pending[i]=-1; inflight[i]=nil; return true;
        };
        long long finishNanos = 0;
        const auto start=std::chrono::steady_clock::now();
        for (int frame=0;frame<60;++frame) {
            int slot=frame%3;
            if (!retire(slot)) return 1; // never overwrite an in-flight surface
            if (!eglMakeCurrent(d,surfaces[slot],surfaces[slot],context)) {std::fprintf(stderr,"FAIL: make current 0x%x\n",eglGetError());return 1;}
            const unsigned char* c=colors[frame%4];
            glClearColor(c[0]/255.f,c[1]/255.f,c[2]/255.f,c[3]/255.f);
            glClear(GL_COLOR_BUFFER_BIT);
            const auto finishStart=std::chrono::steady_clock::now();
            glFinish(); // conservative producer completion
            finishNanos += std::chrono::duration_cast<std::chrono::nanoseconds>(std::chrono::steady_clock::now()-finishStart).count();
            if (glGetError()!=GL_NO_ERROR || !eglSwapBuffers(d,surfaces[slot])) {std::fprintf(stderr,"FAIL: swap frame %d\n",frame);return 1;}
            id<MTLCommandBuffer> cb=[queue commandBuffer];
            id<MTLBlitCommandEncoder> blit=[cb blitCommandEncoder];
            [blit copyFromTexture:textures[slot] sourceSlice:0 sourceLevel:0 sourceOrigin:MTLOriginMake(0,0,0)
                    sourceSize:MTLSizeMake(W,H,1) toBuffer:outputs[slot] destinationOffset:0
                    destinationBytesPerRow:rowBytes destinationBytesPerImage:rowBytes*H];
            [blit endEncoding];[cb commit];inflight[slot]=cb;pending[slot]=frame;
        }
        for (int i=0;i<3;++i) if (!retire(i)) return 1;
        const auto elapsed=std::chrono::duration_cast<std::chrono::nanoseconds>(std::chrono::steady_clock::now()-start).count();
        std::printf("PASS backend=%s size=%dx%d frames=60 glFinish_mean_ms=%.3f end_to_end_ms=%.3f (correctness probe, not app benchmark)\n",vulkan?"vulkan":(privateDisplay?"metal-private":"metal"),W,H,finishNanos/60000000.0,elapsed/60000000.0);
        eglMakeCurrent(d,EGL_NO_SURFACE,EGL_NO_SURFACE,EGL_NO_CONTEXT);
        eglDestroyContext(d,context);
        for (int i=0;i<3;++i) {eglDestroySurface(d,surfaces[i]);CFRelease(ios[i]);}
        eglTerminate(d);return 0;
    }
}
