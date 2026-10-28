#pragma once
#include <cstdint>
#include <cstddef>
#if defined(__ARM_NEON)
#include <arm_neon.h>
#endif

namespace macapk::pixels {
inline uint32_t SwapRedBlue(uint32_t p) {
    return (p & 0xff00ff00u) | ((p & 0x00ff0000u) >> 16) | ((p & 0x000000ffu) << 16);
}
inline unsigned Mul255(unsigned c, unsigned a) {
    const unsigned product = c * a + 128;
    return (product + (product >> 8)) >> 8;
}
// Top-down straight ARGB words -> top-down GL RGBA bytes on the little-endian
// host. Unlike pbuffer readback this direction must NOT flip rows. The caller
// has validated both capacities and owns the input until this copy returns.
inline void ArgbToRgba(const uint32_t* src, uint32_t* dst, int width, int height,
                       int stride, bool premultiply) {
    for (int y = 0; y < height; ++y) {
        const uint32_t* row = src + static_cast<size_t>(y) * stride;
        uint32_t* out = dst + static_cast<size_t>(y) * width;
        int x = 0;
#if defined(__ARM_NEON)
        if (!premultiply) {
            const uint32x4_t ag = vdupq_n_u32(0xff00ff00u);
            const uint32x4_t red = vdupq_n_u32(0x00ff0000u);
            const uint32x4_t blue = vdupq_n_u32(0x000000ffu);
            for (; x + 4 <= width; x += 4) {
                const uint32x4_t p = vld1q_u32(row + x);
                vst1q_u32(out + x, vorrq_u32(vandq_u32(p, ag),
                    vorrq_u32(vshrq_n_u32(vandq_u32(p, red), 16),
                              vshlq_n_u32(vandq_u32(p, blue), 16))));
            }
        }
#endif
        for (; x < width; ++x) {
            uint32_t p = row[x];
            const unsigned a = p >> 24;
            if (premultiply && a != 255)
                p = (a << 24) | (Mul255((p >> 16) & 255, a) << 16)
                    | (Mul255((p >> 8) & 255, a) << 8) | Mul255(p & 255, a);
            out[x] = SwapRedBlue(p);
        }
    }
}
}
