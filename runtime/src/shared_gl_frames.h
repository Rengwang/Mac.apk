#pragma once

// Experimental EGL/IOSurface pool for a single GL producer and a separate
// consumer. MacApkGlWindow uses one slot behind an explicit environment flag.
// The owner serializes destruction against ALL calls and must unbind EGL
// surfaces before destroying the pool. A borrowed IOSurface is valid until its
// matching release; release only after the consumer GPU has finished reading.
#include <EGL/egl.h>
#include <IOSurface/IOSurface.h>
#include <cstdint>
#include <cstddef>

namespace macapk {
class SharedGlFrames;
SharedGlFrames* SharedGlFramesCreate(EGLDisplay display, EGLConfig config, int width, int height);
// Non-blocking. On success returns a unique token and the drawable EGL surface.
// A producer owns at most one DRAWING slot. Returns false when the ring is full.
bool SharedGlFramesBegin(SharedGlFrames*, uint64_t* token, EGLSurface* surface);
// Before Publish the producer must finish GL commands and successfully swap.
// Cancel returns a DRAWING slot without publishing it.
bool SharedGlFramesPublish(SharedGlFrames*, uint64_t token);
bool SharedGlFramesCancel(SharedGlFrames*, uint64_t token);

// Publish the current drawing slot and acquire the next free slot in one
// serialized transition. On success `image` is borrowed until the matching
// SharedGlFramesReleasePresented call; `nextToken`/`nextSurface` identify the
// surface the producer must make current before drawing the next frame. If no
// surface is free, the current slot stays DRAWING so the producer can retry.
bool SharedGlFramesPublishAndBegin(SharedGlFrames*, uint64_t token,
                                   IOSurfaceRef* image, int* width, int* height,
                                   uint64_t* nextToken, EGLSurface* nextSurface);
// Acquires oldest published frame, preserving frame order. The surface is
// borrowed and must not be retained beyond Release without a separate retain.
bool SharedGlFramesTake(SharedGlFrames*, uint64_t* token, IOSurfaceRef* surface);
// Compatibility consumer for pixel-based Canvas. Copies an ACQUIRED frame
// into straight-alpha, top-down ARGB words; accepts padded IOSurface rows.
// Returns false without reading if token/state or output capacity is invalid.
bool SharedGlFramesCopyArgb(SharedGlFrames*, uint64_t token, uint32_t* out, size_t count);
// Synchronous producer-thread fallback only: caller must glFinish before this
// and must not draw to this slot until the copy returns.
bool SharedGlFramesCopyDrawingArgb(SharedGlFrames*, uint64_t token, uint32_t* out, size_t count);
bool SharedGlFramesRelease(SharedGlFrames*, uint64_t token);

// Release a frame published by SharedGlFramesPublishAndBegin after the
// compositor has finished accepting its layer contents.
bool SharedGlFramesReleasePresented(SharedGlFrames*, uint64_t token);

// Put a published frame back in DRAWING state when the direct compositor
// refused it before taking ownership (so the caller can use its copy path).
bool SharedGlFramesRestorePublished(SharedGlFrames*, uint64_t token);

// True when this EGLSurface belongs to the pool. The EGL window bridge uses
// this to avoid destroying a ring surface while rotating producers.
bool SharedGlFramesOwnsSurface(SharedGlFrames*, EGLSurface surface);
// Fails if ANY producer/consumer/published frame is outstanding. The caller
// explicitly drains/cancels before retiring a generation on resize/destroy.
bool SharedGlFramesDestroy(SharedGlFrames*);
}
