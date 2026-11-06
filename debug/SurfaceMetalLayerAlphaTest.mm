#import <Foundation/Foundation.h>
#import <QuartzCore/QuartzCore.h>

#include "../runtime/src/surface_metal_layer.h"
#include <cstdio>

static unsigned failures = 0;

static void expectOpaque(CAMetalLayer* layer, BOOL expected, const char* description) {
    if (layer.opaque != expected) {
        std::fprintf(stderr, "FAIL: %s: opaque=%d, expected=%d\n",
                     description, (int)layer.opaque, (int)expected);
        ++failures;
    }
}

int main() {
    @autoreleasepool {
        MacApkSurfaceMetalLayer* layer = [[MacApkSurfaceMetalLayer alloc] init];
        [layer macapkSetRequiresAlpha:YES];
        expectOpaque(layer, NO, "transparent holder starts transparent");
        [layer setOpaque:YES];
        expectOpaque(layer, NO, "backend cannot discard transparent holder alpha");
        [layer setOpaque:NO];
        expectOpaque(layer, NO, "transparent request accepts transparent setter");

        MacApkSurfaceMetalLayer* transparentCopy =
            [[MacApkSurfaceMetalLayer alloc] initWithLayer:layer];
        expectOpaque(transparentCopy, NO, "transparent copy preserves current alpha");
        [transparentCopy setOpaque:YES];
        expectOpaque(transparentCopy, NO, "transparent copy preserves alpha contract");

        [layer macapkSetRequiresAlpha:NO];
        expectOpaque(layer, YES, "opaque holder can become opaque");
        [layer setOpaque:NO];
        expectOpaque(layer, NO, "opaque holder does not force opaque setter");
        [layer setOpaque:YES];
        expectOpaque(layer, YES, "opaque holder permits backend opaque setter");

        MacApkSurfaceMetalLayer* opaqueCopy =
            [[MacApkSurfaceMetalLayer alloc] initWithLayer:layer];
        expectOpaque(opaqueCopy, YES, "opaque copy preserves current opacity");
        [opaqueCopy setOpaque:NO];
        expectOpaque(opaqueCopy, NO, "opaque copy permits transparent setter");
        [opaqueCopy setOpaque:YES];
        expectOpaque(opaqueCopy, YES, "opaque copy permits opaque setter");

        // A presentation copy is independent of subsequent model-layer changes.
        [layer macapkSetRequiresAlpha:YES];
        [opaqueCopy setOpaque:YES];
        expectOpaque(opaqueCopy, YES, "copies do not share alpha state");
        [transparentCopy setOpaque:YES];
        expectOpaque(transparentCopy, NO, "original format change does not mutate copy");

        [opaqueCopy release];
        [transparentCopy release];
        [layer release];
    }
    std::fprintf(failures ? stderr : stdout,
                 "SurfaceMetalLayerAlphaTest: %s (%u failures)\n",
                 failures ? "FAIL" : "PASS", failures);
    return failures ? 1 : 0;
}
