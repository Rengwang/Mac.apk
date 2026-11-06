#include "shared_gl_frames.h"
#include <EGL/eglext.h>
#include <EGL/eglext_angle.h>
#include <GLES2/gl2.h>
#import <Foundation/Foundation.h>
#include <array>
#include <atomic>
#include <limits>
#include <mutex>
#include <new>

namespace macapk {
namespace {
enum class State { Free, Drawing, Published, Acquired };
std::atomic<uint64_t> nextToken{1};
struct Slot { IOSurfaceRef image=nullptr; EGLSurface surface=EGL_NO_SURFACE; uint64_t token=0; State state=State::Free; };
}
class SharedGlFrames {
public:
    EGLDisplay display=EGL_NO_DISPLAY;
    std::array<Slot,3> slots{};
    std::mutex mu;
    bool closing=false;
};

SharedGlFrames* SharedGlFramesCreate(EGLDisplay d, EGLConfig config, int w, int h) {
    if (d==EGL_NO_DISPLAY || !config || w<=0 || h<=0) return nullptr;
    EGLint target=0;
    if (!eglGetConfigAttrib(d,config,EGL_BIND_TO_TEXTURE_TARGET_ANGLE,&target)) return nullptr;
    auto* p=new (std::nothrow) SharedGlFrames;
    if (!p) return nullptr;
    p->display=d;
    @autoreleasepool {
        NSDictionary* props=@{(id)kIOSurfaceWidth:@(w), (id)kIOSurfaceHeight:@(h),
            (id)kIOSurfaceBytesPerElement:@4, (id)kIOSurfacePixelFormat:@(0x42475241)};
        const EGLint attrs[]={EGL_WIDTH,w,EGL_HEIGHT,h,EGL_IOSURFACE_PLANE_ANGLE,0,
            EGL_TEXTURE_TARGET,target,EGL_TEXTURE_INTERNAL_FORMAT_ANGLE,0x80E1,
            EGL_TEXTURE_FORMAT,EGL_TEXTURE_RGBA,EGL_TEXTURE_TYPE_ANGLE,GL_UNSIGNED_BYTE,EGL_NONE};
        for (auto& slot:p->slots) {
            slot.image=IOSurfaceCreate((__bridge CFDictionaryRef)props);
            if (!slot.image) {SharedGlFramesDestroy(p);return nullptr;}
            slot.surface=eglCreatePbufferFromClientBuffer(d,EGL_IOSURFACE_ANGLE,(EGLClientBuffer)slot.image,config,attrs);
            if (slot.surface==EGL_NO_SURFACE) {SharedGlFramesDestroy(p);return nullptr;}
        }
    }
    return p;
}
bool SharedGlFramesBegin(SharedGlFrames* p, uint64_t* token, EGLSurface* surface) {
    if (!p || !token || !surface) return false;
    std::lock_guard<std::mutex> lock(p->mu);
    if (p->closing) return false;
    for (const auto& s:p->slots) if (s.state==State::Drawing) return false;
    // Never reuse a token; fail closed on wraparound rather than allow stale
    // callbacks to release a different frame.
    for (auto& s:p->slots) if (s.state==State::Free) {
        uint64_t tokenValue = nextToken.load(std::memory_order_relaxed);
        do {
            if (tokenValue == std::numeric_limits<uint64_t>::max()) return false;
        } while (!nextToken.compare_exchange_weak(tokenValue, tokenValue+1,
                     std::memory_order_relaxed));
        s.state=State::Drawing;s.token=tokenValue;*token=s.token;*surface=s.surface;return true;
    }
    return false;
}
static bool transition(SharedGlFrames* p,uint64_t token,State from,State to) {
    if (!p || !token) return false;
    std::lock_guard<std::mutex> lock(p->mu);
    for (auto& s:p->slots) if (s.token==token && s.state==from) {s.state=to;return true;}
    return false;
}
bool SharedGlFramesPublish(SharedGlFrames* p,uint64_t token) {return transition(p,token,State::Drawing,State::Published);}
bool SharedGlFramesCancel(SharedGlFrames* p,uint64_t token) {return transition(p,token,State::Drawing,State::Free);}
bool SharedGlFramesPublishAndBegin(SharedGlFrames* p,uint64_t token,
                                   IOSurfaceRef* image,int* width,int* height,
                                   uint64_t* newToken,EGLSurface* nextSurface) {
    if(!p||!token||!image||!width||!height||!newToken||!nextSurface) return false;
    std::lock_guard<std::mutex> lock(p->mu);
    if(p->closing) return false;
    Slot* current=nullptr;
    Slot* next=nullptr;
    for(auto& s:p->slots) {
        if(s.token==token&&s.state==State::Drawing) current=&s;
        else if(s.state==State::Free&&!next) next=&s;
    }
    if(!current||!next) return false;
    uint64_t tokenValue=nextToken.load(std::memory_order_relaxed);
    do {
        if(tokenValue==std::numeric_limits<uint64_t>::max()) return false;
    } while(!nextToken.compare_exchange_weak(tokenValue,tokenValue+1,
                std::memory_order_relaxed));
    current->state=State::Published;
    next->state=State::Drawing;
    next->token=tokenValue;
    *image=current->image;
    *width=static_cast<int>(IOSurfaceGetWidth(current->image));
    *height=static_cast<int>(IOSurfaceGetHeight(current->image));
    *newToken=tokenValue;
    *nextSurface=next->surface;
    return true;
}
bool SharedGlFramesTake(SharedGlFrames* p,uint64_t* token,IOSurfaceRef* image) {
    if (!p || !token || !image) return false;
    std::lock_guard<std::mutex> lock(p->mu);
    if (p->closing) return false;
    Slot* oldest=nullptr;
    for (auto& s:p->slots) if (s.state==State::Published && (!oldest || s.token<oldest->token)) oldest=&s;
    if (!oldest) return false;
    oldest->state=State::Acquired;*token=oldest->token;*image=oldest->image;return true;
}
static bool copyArgb(SharedGlFrames* p,uint64_t token,uint32_t* out,size_t count,State expected) {
    if (!p || !token || !out) return false;
    std::lock_guard<std::mutex> lock(p->mu);
    if (p->closing) return false;
    Slot* slot=nullptr;
    for (auto& s:p->slots) if(s.token==token && s.state==expected) {slot=&s;break;}
    if(!slot) return false;
    IOSurfaceRef io=slot->image;
    const size_t width=IOSurfaceGetWidth(io),height=IOSurfaceGetHeight(io),stride=IOSurfaceGetBytesPerRow(io);
    if(!width || !height || width>SIZE_MAX/height || count<width*height ||
       width>SIZE_MAX/4 || stride<width*4) return false;
    if(IOSurfaceLock(io,kIOSurfaceLockReadOnly,nullptr)!=kIOReturnSuccess) return false;
    const auto* base=static_cast<const uint8_t*>(IOSurfaceGetBaseAddress(io));
    if(!base) {IOSurfaceUnlock(io,kIOSurfaceLockReadOnly,nullptr);return false;}
    for(size_t y=0;y<height;++y) {
        const auto* row=reinterpret_cast<const uint32_t*>(base+(height-1-y)*stride);
        uint32_t* dst=out+y*width;
        for(size_t x=0;x<width;++x) {
            uint32_t pixel=row[x],a=pixel>>24;
            uint32_t r=(pixel>>16)&255,g=(pixel>>8)&255,b=pixel&255;
            if(a!=255) {
                if(a==0) {r=0;g=0;b=0;}
                else {r=(r*255+(a>>1))/a;g=(g*255+(a>>1))/a;b=(b*255+(a>>1))/a;}
            }
            dst[x]=(a<<24)|(r<<16)|((g&255)<<8)|b;
        }
    }
    IOSurfaceUnlock(io,kIOSurfaceLockReadOnly,nullptr);return true;
}
bool SharedGlFramesCopyArgb(SharedGlFrames* p,uint64_t token,uint32_t* out,size_t count) {
    return copyArgb(p,token,out,count,State::Acquired);
}
bool SharedGlFramesCopyDrawingArgb(SharedGlFrames* p,uint64_t token,uint32_t* out,size_t count) {
    return copyArgb(p,token,out,count,State::Drawing);
}
bool SharedGlFramesRelease(SharedGlFrames* p,uint64_t token) {return transition(p,token,State::Acquired,State::Free);}
bool SharedGlFramesReleasePresented(SharedGlFrames* p,uint64_t token) {
    return transition(p,token,State::Published,State::Free);
}
bool SharedGlFramesRestorePublished(SharedGlFrames* p,uint64_t token) {
    return transition(p,token,State::Published,State::Drawing);
}
bool SharedGlFramesOwnsSurface(SharedGlFrames* p,EGLSurface surface) {
    if(!p||surface==EGL_NO_SURFACE) return false;
    std::lock_guard<std::mutex> lock(p->mu);
    if(p->closing) return false;
    for(const auto& s:p->slots) if(s.surface==surface) return true;
    return false;
}
bool SharedGlFramesDestroy(SharedGlFrames* p) {
    if (!p) return false;
    {
        std::lock_guard<std::mutex> lock(p->mu);
        if (p->closing) return false;
        for (const auto& s:p->slots) if (s.state!=State::Free) return false;
        // Close the pool atomically with the state check. Without this bit a
        // producer could acquire a slot after the check but before the EGL
        // surfaces below are destroyed.
        p->closing=true;
    }
    for (auto& s:p->slots) {
        if (s.surface!=EGL_NO_SURFACE) eglDestroySurface(p->display,s.surface);
        if (s.image) CFRelease(s.image);
    }
    delete p;return true;
}
}
