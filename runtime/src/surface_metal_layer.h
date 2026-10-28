#pragma once

#import <QuartzCore/CAMetalLayer.h>
#include <atomic>

struct MacApkSurfaceAlphaState {
    // Objective-C constructs C++ ivars before superclass initializers can
    // call the overridden setter. std::atomic<bool>'s C++17 default
    // constructor alone does not initialize its value.
    std::atomic<bool> requiresAlpha{false};
};

// Android's holder format belongs to the native window, not the desktop
// backend. ANGLE's Vulkan desktop path chooses OPAQUE composite alpha, and
// MoltenVK applies that to the embedder's layer before acquiring a drawable.
// Preserve a transparent holder's contract from that first allocation onward.
// Used only by the explicit per-Surface alpha experiment.
@interface MacApkSurfaceMetalLayer : CAMetalLayer {
    MacApkSurfaceAlphaState _macapkAlphaState;
}
- (void)macapkSetRequiresAlpha:(BOOL)requiresAlpha;
@end

@implementation MacApkSurfaceMetalLayer
- (void)macapkSetRequiresAlpha:(BOOL)requiresAlpha {
    _macapkAlphaState.requiresAlpha.store(requiresAlpha, std::memory_order_relaxed);
    [super setOpaque:!requiresAlpha];
}
- (void)setOpaque:(BOOL)opaque {
    [super setOpaque:_macapkAlphaState.requiresAlpha.load(std::memory_order_relaxed) ? NO : opaque];
}
- (id)initWithLayer:(id)other {
    self = [super initWithLayer:other];
    if (self && [other isKindOfClass:[MacApkSurfaceMetalLayer class]]) {
        MacApkSurfaceMetalLayer* source = (MacApkSurfaceMetalLayer*)other;
        bool requiresAlpha =
            source->_macapkAlphaState.requiresAlpha.load(std::memory_order_relaxed);
        _macapkAlphaState.requiresAlpha.store(requiresAlpha, std::memory_order_relaxed);
        // CAMetalLayer's initializer may restore its default opaque state
        // before the subclass's contract is copied. Reapply transparency for
        // presentation copies without forcing an opaque source to become YES.
        if (requiresAlpha) [super setOpaque:NO];
    }
    return self;
}
@end
