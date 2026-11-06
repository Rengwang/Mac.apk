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

int main(int argc, char **argv) {
    @autoreleasepool {
        bool noRead = argc > 2 && std::strcmp(argv[2], "no-readback") == 0;
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
        glClearColor(1,0,0,1);glClear(GL_COLOR_BUFFER_BIT);glFinish();
        if (!noRead) {
        unsigned char rgba[4] = {};
        glReadPixels(0,0,1,1,GL_RGBA,GL_UNSIGNED_BYTE,rgba);
        GLenum glError = glGetError();
        std::printf("GL_RGBA=%u,%u,%u,%u glError=0x%x\n", rgba[0],rgba[1],rgba[2],rgba[3],glError);
        if (glError != GL_NO_ERROR || rgba[0] != 255 || rgba[3] != 255) {std::fprintf(stderr,"FAIL: GL render verification\n");return 1;}
        }
        if (!eglSwapBuffers(d,surface)) {std::fprintf(stderr,"FAIL: swap 0x%x\n",eglGetError());return 1;}
        if (!eglMakeCurrent(d,EGL_NO_SURFACE,EGL_NO_SURFACE,EGL_NO_CONTEXT)) {std::fprintf(stderr,"FAIL: unbind 0x%x\n",eglGetError());return 1;}
        id<MTLDevice> device = MTLCreateSystemDefaultDevice();
        MTLTextureDescriptor *desc = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatBGRA8Unorm width:64 height:64 mipmapped:NO];
        desc.usage = MTLTextureUsageShaderRead;
        id<MTLTexture> texture = [device newTextureWithDescriptor:desc iosurface:io plane:0];
        unsigned char gpu[4] = {};
        if (texture) [texture getBytes:gpu bytesPerRow:4 fromRegion:MTLRegionMake2D(0,0,1,1) mipmapLevel:0];
        std::printf("Metal_texture=%s BGRA=%u,%u,%u,%u\n", texture ? "yes" : "no",gpu[0],gpu[1],gpu[2],gpu[3]);
        if (IOSurfaceLock(io,kIOSurfaceLockReadOnly,nullptr)!=kIOReturnSuccess) return 1;
        const unsigned char* px=(const unsigned char*)IOSurfaceGetBaseAddress(io);
        if (!px) {std::fprintf(stderr,"FAIL: IOSurface base address\n");IOSurfaceUnlock(io,kIOSurfaceLockReadOnly,nullptr);return 1;}
        bool ok= px[0]==0 && px[1]==0 && px[2]==255 && px[3]==255;
        std::printf("%s backend=%s pixel(BGRA)=%u,%u,%u,%u bytesPerRow=%zu\n",ok?"PASS":"FAIL",vulkan?"vulkan":(privateDisplay?"metal-private":"metal"),px[0],px[1],px[2],px[3],IOSurfaceGetBytesPerRow(io));
        IOSurfaceUnlock(io,kIOSurfaceLockReadOnly,nullptr);
        eglMakeCurrent(d,EGL_NO_SURFACE,EGL_NO_SURFACE,EGL_NO_CONTEXT);eglDestroyContext(d,context);eglDestroySurface(d,surface);CFRelease(io);eglTerminate(d);
        return ok?0:1;
    }
}
